package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.PosixTusServer.Fault
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Engine behaviour Ktus documents (README "Weak links"), not Ktus behaviour: what Darwin's
 * `HttpTimeout.socketTimeoutMillis` does while a large PATCH body is sent slowly, with Ktus's own silence limits
 * off. Ktor 3.2.2 maps it to `NSMutableURLRequest.timeoutInterval` (`setupSocketTimeout` in
 * ktor-client-darwin), NSURLSession's idle timeout.
 *
 * Measured: like OkHttp, a body read steadily for ~13 s was cut by a 2.5 s timeout before the server had read it
 * all, once the last bytes were in the kernel's buffers and only the server's reading of them was left (after
 * 7.5-10 s on a Mac; earlier on CI runners).
 */
class DarwinSocketTimeoutBehaviourTest {
    private val chunk = 2L * 1024 * 1024

    private class Run(val error: Throwable?, val arrivals: List<Pair<String, Long>>, val stored: Int) {
        val patchStarts get() = arrivals.filter { it.first == "PATCH" }.map { it.second }
        val heads get() = arrivals.filter { it.first == "HEAD" }.map { it.second }
        val cuts get() = heads.size - 1
        val firstCutAfter get() = heads.getOrNull(1)?.minus(patchStarts.first())
        override fun toString() = "cuts=$cuts firstCutAfter=$firstCutAfter patches=${patchStarts.size} stored=$stored error=$error"
    }

    private fun run(
        socketTimeout: Long, size: Long, maxRetries: Int = 3, inspect: (PosixTusServer) -> Unit = {},
        setup: PosixTusServer.() -> Unit,
    ): Run = runBlocking {
        PosixTusServer().use { server ->
            server.setup()
            val error = runCatching {
                withTimeout(120_000) {
                    HttpClient(Darwin) { followRedirects = false; install(HttpTimeout) }.use { c ->
                        c.createAndUploadTus("${server.baseUrl}/files",
                            DarwinRealSocketTest.TrackingFile(ByteArray(size.toInt()) { it.toByte() }),
                            options = TusUploadOptions(chunkSize = chunk, activityHeartbeatMillis = null, adaptiveChunkSize = false,
                                retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false))) {
                            timeout { socketTimeoutMillis = socketTimeout }
                        }
                    }
                }
            }.exceptionOrNull()
            inspect(server)
            Run(error, server.arrivals, server.data.size)
        }
    }

    /**
     * 2 MiB read steadily (16 KiB per 100 ms, ~13 s): cut by a 2.5 s timeout while the body is still moving. The
     * time of the cut depends on the OS socket buffers, so the test pins the order instead: the client gave up
     * before the server had finished reading the body (or the server never got all of it).
     */
    @Test
    fun aSlowSteadyRead_isCut_whileTheBodyIsStillMoving() {
        var ends: List<PosixTusServer.SlowReadEnd> = emptyList()
        val r = run(2_500, chunk, maxRetries = 1, inspect = { server ->
            val deadline = kotlin.time.TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("30s")
            while (server.slowReadEnds.isEmpty() && deadline.hasNotPassedNow()) platform.posix.usleep(20_000u)
            ends = server.slowReadEnds
        }) {
            faults = { m, _ -> if (m == "PATCH") Fault.SlowRead else Fault.None }
            slowReadBytes = 16 * 1024; slowReadPauseMicros = 100_000u
        }
        assertTrue(r.cuts >= 1, "$r")
        val end = ends.firstOrNull() ?: fail("the server's body read never ended: $r")
        assertEquals(chunk, end.declared)
        assertTrue(end.read > 0, "$end $r")
        // The body was still moving when the client gave up: either the server never got all of it, or it was
        // still reading the buffered rest when the client's recovery HEAD arrived.
        val cutAt = r.heads[1]
        assertTrue(end.read < end.declared || cutAt < end.atMillis,
            "cut at $cutAt ms while the body was still moving: $end $r")
    }

    @Test
    fun aServerThatStopsReading_isCutAtTheLimit_andTheUploadResumes() {
        val r = run(2_500, 2 * chunk) { faults = { m, n -> if (m == "PATCH" && n == 1) Fault.StallWithoutReading else Fault.None } }
        assertNull(r.error, "$r")
        assertEquals((2 * chunk).toInt(), r.stored)
        assertEquals(1, r.cuts, "$r")
        assertTrue(r.firstCutAfter!! in 2_500..8_000, "$r")
    }

    @Test
    fun aSlowAnswer_followsTheLimit() {
        val waits = run(10_000, chunk) { faults = { m, _ -> if (m == "PATCH") Fault.AnswerAfter else Fault.None }; answerAfterMillis = 6_000 }
        assertNull(waits.error, "$waits")
        assertEquals(0, waits.cuts, "$waits")
        val cut = run(2_500, chunk, maxRetries = 1) { faults = { m, _ -> if (m == "PATCH") Fault.AnswerAfter else Fault.None }; answerAfterMillis = 6_000 }
        assertTrue(cut.cuts >= 1, "$cut")
        assertTrue(cut.firstCutAfter!! in 2_500..5_900, "$cut")
    }
}
