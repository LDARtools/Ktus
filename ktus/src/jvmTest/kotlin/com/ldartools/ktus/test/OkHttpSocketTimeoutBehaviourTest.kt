package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.RealSocketTusServer.Fault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Engine behaviour Ktus documents (README "Weak links"), not Ktus behaviour: what OkHttp's
 * `HttpTimeout.socketTimeoutMillis` does while a large PATCH body is sent slowly, with Ktus's own silence limits
 * off. Ktor 3.2.2 maps it to OkHttp's `readTimeout` and `writeTimeout` (`OkHttpEngineKt.setupTimeoutAttributes`),
 * both per socket operation. If a Ktor or OkHttp upgrade changes this, these tests say so and the README needs
 * updating.
 *
 * Measured: the write timeout never fires while the server keeps reading, but the read timeout starts once
 * OkHttp has handed the last byte to the kernel, so the time the server needs to read what is still buffered
 * counts against it: a body read steadily for ~13 s was cut by a 2.5 s timeout before the server had read it
 * all (after 8-13 s on a Mac; earlier on CI runners, whose socket buffers hold more).
 */
class OkHttpSocketTimeoutBehaviourTest {
    private val chunk = 2L * 1024 * 1024

    private class Run(val error: Throwable?, val arrivals: List<Pair<String, Long>>, val stored: Long) {
        val patchStarts get() = arrivals.filter { it.first == "PATCH" }.map { it.second }
        val heads get() = arrivals.filter { it.first == "HEAD" }.map { it.second }
        /** Recovery HEADs: each one follows an abandoned PATCH. */
        val cuts get() = heads.size - 1
        val firstCutAfter get() = heads.getOrNull(1)?.minus(patchStarts.first())
        override fun toString() = "cuts=$cuts firstCutAfter=$firstCutAfter patches=${patchStarts.size} stored=$stored error=$error"
    }

    private fun run(
        socketTimeout: Long, size: Long, maxRetries: Int = 3, inspect: (RealSocketTusServer) -> Unit = {},
        fault: (String, Int) -> Fault,
    ): Run = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = fault
            val error = runCatching {
                withTimeout(120_000) {
                    HttpClient(OkHttp) { followRedirects = false; install(HttpTimeout) }.use { c ->
                        c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(size.toInt())),
                            options = TusUploadOptions(chunkSize = chunk, activityHeartbeatMillis = null, adaptiveChunkSize = false,
                                retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false))) {
                            timeout { socketTimeoutMillis = socketTimeout }
                        }
                    }
                }
            }.exceptionOrNull()
            inspect(server)
            Run(error, server.arrivals.toList(), server.offset)
        }
    }

    /**
     * 2 MiB read steadily (16 KiB per 100 ms, ~13 s): cut by a 2.5 s socket timeout while the body is still
     * moving. When the cut lands depends on how much the OS socket buffers absorb (later on a Mac, earlier on CI
     * runners), so the test pins the order instead: the client gave up before the server had finished reading the
     * body (or the server never got all of it).
     */
    @Test
    fun aSlowSteadyRead_isCut_whileTheBodyIsStillMoving() {
        var ends: List<RealSocketTusServer.SlowReadEnd> = emptyList()
        val r = run(2_500, chunk, maxRetries = 1, inspect = { server ->
            // The server reads what is still buffered until the EOF or reset reaches it.
            val deadline = System.nanoTime() + 30_000_000_000L
            while (server.slowReadEnds.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            ends = server.slowReadEnds.toList()
        }) { m, _ -> if (m == "PATCH") Fault.SlowRead(16 * 1024, 100) else Fault.None }
        assertTrue(r.cuts >= 1, "$r")
        val end = ends.firstOrNull() ?: fail("the server's body read never ended: $r")
        assertEquals(chunk, end.declared)
        assertTrue(end.read > 0, "$end $r")
        // The body was still moving when the client gave up: either the server never got all of it, or it was
        // still reading the buffered rest when the client's recovery HEAD arrived.
        val cutAt = r.heads[1]
        assertTrue(end.read < end.declared || cutAt < end.atMillis,
            "cut at $cutAt ms while the body was still moving: $end $r")
        val timeout = generateSequence(r.error) { it.cause }.firstOrNull { it is java.net.SocketTimeoutException }
        assertNotNull(timeout, "a socket timeout: $r")
    }

    /** The server stops reading mid-body: cut at ~2.5 s, and the upload resumes and completes. */
    @Test
    fun aServerThatStopsReading_isCutAtTheLimit_andTheUploadResumes() {
        val r = run(2_500, 2 * chunk) { m, n -> if (m == "PATCH" && n == 1) Fault.StallWithoutReading else Fault.None }
        assertNull(r.error, "$r")
        assertEquals(2 * chunk, r.stored)
        assertEquals(1, r.cuts, "$r")
        assertTrue(r.firstCutAfter!! in 2_500..8_000, "$r")
    }

    /** The server reads the body and answers after 6 s: a 10 s socket timeout waits for it, a 2.5 s one does not. */
    @Test
    fun aSlowAnswer_followsTheLimit() {
        val waits = run(10_000, chunk) { m, _ -> if (m == "PATCH") Fault.AnswerAfter(6_000) else Fault.None }
        assertNull(waits.error, "$waits")
        assertEquals(0, waits.cuts, "$waits")
        val cut = run(2_500, chunk, maxRetries = 1) { m, _ -> if (m == "PATCH") Fault.AnswerAfter(6_000) else Fault.None }
        assertTrue(cut.cuts >= 1, "$cut")
        assertTrue(cut.firstCutAfter!! in 2_500..5_900, "$cut")
    }
}
