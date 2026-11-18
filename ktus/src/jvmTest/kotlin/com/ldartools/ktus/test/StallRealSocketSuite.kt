package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.RealSocketTusServer.Fault
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** The silence limits over real sockets on CIO (Chateau Mobile's Android engine). */
class CioStallRealSocketTest : StallRealSocketSuite() {
    override fun newClient(config: HttpClientConfig<*>.() -> Unit) = HttpClient(CIO) { followRedirects = false; config() }

    /**
     * Why the feature exists: CIO's socket timeout does not measure silence. With the same value, the slowly
     * but steadily moving body that Ktus's sendStallMillis lets through is cut.
     */
    @Test
    fun anEngineSocketTimeoutOfTheSameValue_cutsTheSlowlyMovingBody() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.SlowRead(SLOW_TICK_BYTES, SLOW_TICK_MILLIS) else Fault.None }
            val e = runCatching {
                withTimeout(60_000) {
                    newClient { install(HttpTimeout) { socketTimeoutMillis = SLOW_STALL } }.use { c ->
                        c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(SLOW_SIZE)),
                            options = TusUploadOptions(chunkSize = SLOW_SIZE.toLong(), activityHeartbeatMillis = null,
                                adaptiveChunkSize = false, retryOptions = RetryOptions(maxRetries = 1)))
                    }
                }
            }.exceptionOrNull()
            assertNotNull(e, "CIO's socket timeout let the slow body through; the README claim would be wrong")
            Unit
        }
    }
}

/** The same on OkHttp. */
class OkHttpStallRealSocketTest : StallRealSocketSuite() {
    override fun newClient(config: HttpClientConfig<*>.() -> Unit) = HttpClient(OkHttp) { followRedirects = false; config() }
}

abstract class StallRealSocketSuite {
    abstract fun newClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

    companion object {
        const val STALL = 1_000L
        /**
         * The send limit for the slowly-moving body: progress moves in ~1 MiB steps (Ktor's body buffer), ~0.85 s
         * apart at this rate, so the limit must stay well above that.
         */
        const val SLOW_STALL = 2_500L
        /** 64 KiB every 50 ms (~1.3 MB/s): 16 MB takes ~12.5 s, five times [SLOW_STALL]. */
        const val SLOW_TICK_BYTES = 64 * 1024
        const val SLOW_TICK_MILLIS = 50L
        const val SLOW_SIZE = 250 * 64 * 1024
        const val SLACK = 700L
    }

    private fun opts(chunk: Long, send: Long? = null, reply: Long? = null, finalReply: Long? = null, maxRetries: Int = 5) =
        TusUploadOptions(chunkSize = chunk, activityHeartbeatMillis = null, sendStallMillis = send,
            replyTimeoutMillis = reply, finalReplyTimeoutMillis = finalReply,
            retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false))

    private fun RealSocketTusServer.at(method: String, n: Int): Long = log.filter { it.method == method }[n].atMillis

    @Test
    fun aBodyThatKeepsMovingSlowly_forFiveTimesTheLimit_isNotCut() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.SlowRead(SLOW_TICK_BYTES, SLOW_TICK_MILLIS) else Fault.None }
            val started = System.nanoTime()
            withTimeout(60_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(SLOW_SIZE)),
                        options = opts(SLOW_SIZE.toLong(), send = SLOW_STALL))
                }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertEquals(1, server.log.count { it.method == "PATCH" }, "never cut: ${server.log}")
            assertEquals(SLOW_SIZE.toLong(), server.offset)
            assertTrue(elapsed >= 4 * SLOW_STALL, "the body took $elapsed ms")
        }
    }

    @Test
    fun aBodyThatStops_isCutAtSendStall_andTheUploadResumesViaHead() = runBlocking {
        RealSocketTusServer().use { server ->
            val size = 32 * 1024 * 1024 // larger than the loopback socket buffers, so the writer really blocks
            server.faults = { method, n -> if (method == "PATCH" && n == 1) Fault.StallWithoutReading else Fault.None }
            withTimeout(60_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(size)),
                        options = opts(size / 2L, send = STALL))
                }
            }
            assertEquals(size.toLong(), server.offset)
            val cut = server.at("HEAD", 1) - server.at("PATCH", 0)
            assertTrue(cut in STALL..STALL + SLACK + 2_000, "cut after $cut ms; log ${server.log}")
        }
    }

    @Test
    fun aNonFinalPatchWithNoReply_isCutAtReplyTimeout_andResumes() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, n -> if (method == "PATCH" && n == 1) Fault.Stall else Fault.None }
            withTimeout(60_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(200_000)),
                        options = opts(100_000, send = STALL, reply = STALL, finalReply = 10_000))
                }
            }
            assertEquals(200_000, server.offset)
            val cut = server.at("HEAD", 1) - server.at("PATCH", 0)
            assertTrue(cut in STALL..STALL + SLACK, "cut after $cut ms; log ${server.log}")
        }
    }

    @Test
    fun theFinalPatchWithNoReply_waitsFinalReplyTimeout() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.Stall else Fault.None }
            val e = runCatching {
                withTimeout(60_000) {
                    newClient().use { c ->
                        c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(100_000)),
                            options = opts(100_000, send = STALL, reply = 300, finalReply = 2_500, maxRetries = 1))
                    }
                }
            }.exceptionOrNull()
            val unknown = assertIs<TusUploadOutcomeUnknownException>(e, "$e")
            val stall = generateSequence(unknown.cause) { it.cause }.filterIsInstance<TusStallTimeoutException>().first()
            assertEquals(TusStallTimeoutException.Kind.FINAL_REPLY_TIMEOUT, stall.kind)
            val cut = server.log.last { it.method == "HEAD" }.atMillis - server.at("PATCH", 0)
            assertTrue(cut in 2_500..2_500 + SLACK, "cut after $cut ms; log ${server.log}")
        }
    }

    @Test
    fun aSlowFinalReply_withinFinalReplyTimeout_succeedsWithOneFinalPatch() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.AnswerAfter(1_500) else Fault.None }
            withTimeout(60_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(100_000)),
                        options = opts(100_000, send = STALL, reply = 300, finalReply = 4_000))
                }
            }
            assertEquals(1, server.log.count { it.method == "PATCH" }, "${server.log}")
            assertEquals(100_000, server.offset)
        }
    }
}
