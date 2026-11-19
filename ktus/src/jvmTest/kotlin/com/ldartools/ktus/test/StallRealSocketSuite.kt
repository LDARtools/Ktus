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
    // CIO's own requestTimeout defaults to 15 s for every request; off here, so only the limits under test cut.
    override fun newClient(config: HttpClientConfig<*>.() -> Unit) = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        followRedirects = false
        config()
    }

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
            val timeout = generateSequence(e) { it.cause }.firstOrNull { it is java.net.SocketTimeoutException }
            assertNotNull(timeout, "cut by the socket timeout: $e")
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
        /** Allowed shortfall of the measured silence before a cut (clock and callback granularity). */
        const val CUT_TOLERANCE = 100L
    }

    private fun opts(chunk: Long, send: Long? = null, reply: Long? = null, finalReply: Long? = null, maxRetries: Int = 5) =
        TusUploadOptions(chunkSize = chunk, activityHeartbeatMillis = null, sendStallMillis = send,
            replyTimeoutMillis = reply, finalReplyTimeoutMillis = finalReply,
            retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false))

    private fun RealSocketTusServer.at(method: String, n: Int): Long = log.filter { it.method == method }[n].atMillis

    /**
     * A body that keeps moving is not cut by the send limit. Body progress comes in ~1 MiB steps (Ktor's buffer),
     * so the gap between progress events is ~1 MiB ÷ the server's read speed: ~0.85 s on a Mac, but a slow CI
     * runner (oversleeping the fixture's 50 ms pauses) can genuinely exceed the limit. What is pinned is the rule,
     * not a speed: either the upload went through in one PATCH, or a real progress gap of at least the limit
     * preceded the cut.
     */
    @Test
    fun aBodyThatKeepsMovingSlowly_isOnlyCutAfterARealGap() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.SlowRead(SLOW_TICK_BYTES, SLOW_TICK_MILLIS) else Fault.None }
            val started = System.nanoTime()
            fun now() = (System.nanoTime() - started) / 1_000_000
            // How long the first cut attempt had been silent when it was cut (its last progress to its end),
            // excluding the recovery after it.
            val probe = CutGapProbe()
            withTimeout(90_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(SLOW_SIZE)),
                        options = opts(SLOW_SIZE.toLong(), send = SLOW_STALL),
                        onProgress = { sent, _ -> probe.progress(now(), sent) },
                        onActivity = { probe.activity(now()) },
                        onConfirmed = { _, _ -> probe.confirmed(now()) })
                }
            }
            val patches = server.arrivals.count { it.first == "PATCH" }
            val silence = probe.silenceBeforeFirstCut()
            val measured = "patches=$patches silenceBeforeFirstCut=$silence ms elapsed=${now()} ms " +
                "stored=${server.offset}/$SLOW_SIZE log=${server.log}"
            assertEquals(SLOW_SIZE.toLong(), server.offset, measured)
            // A cut only after at least the limit of silence (less a little for clock granularity).
            assertTrue(patches == 1 || (silence != null && silence >= SLOW_STALL - CUT_TOLERANCE),
                "a send stall without $SLOW_STALL ms of silence: $measured")
            assertTrue(now() >= 4 * SLOW_STALL, "the body moved for at least 4x the limit: $measured")
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

    // ---- Adaptive chunk sizing (targetChunkMillis) with the silence limits, scaled down from the recommended
    // 10 s target / 30 s limits to 1 s / 3 s.

    /** ~16 KiB/s: the server reads 4 KiB every 250 ms. */
    private val slowLink: (String, Int) -> Fault = { m, _ -> if (m == "PATCH") Fault.SlowRead(4 * 1024, 250) else Fault.None }

    private fun adaptive(target: Long?, maxRetries: Int = 5) = TusUploadOptions(chunkSize = 2L * 1024 * 1024,
        minChunkSize = 16L * 1024, targetChunkMillis = target, activityHeartbeatMillis = null,
        sendStallMillis = 3_000, replyTimeoutMillis = 3_000, finalReplyTimeoutMillis = 10_000,
        retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false))

    /**
     * A slow but steady link (~16 KiB/s) with the silence limits set: adaptive sizing keeps each PATCH near
     * 1 s of data, so nothing is cut. (With sizing off, the same limits cut it: see the next test.)
     */
    @Test
    fun aSlowSteadyLink_withAdaptiveSizing_completesWithoutACut() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = slowLink
            withTimeout(90_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(160 * 1024)),
                        options = adaptive(target = 1_000))
                }
            }
            val measured = "heads=${server.arrivals.count { it.first == "HEAD" }} patches=${server.arrivals.count { it.first == "PATCH" }} " +
                "stored=${server.offset} log=${server.log}"
            assertEquals(160L * 1024, server.offset, measured)
            // Strict on purpose: the fixture's speed is set by its 250 ms pauses, and adaptive sizing measures
            // whatever speed the runner really delivers, so a cut here is a real failure on any machine.
            assertEquals(1, server.arrivals.count { it.first == "HEAD" }, "no recovery HEAD, so no cut: $measured")
        }
    }

    /** The same link and limits without adaptive sizing: the whole body is buffered at once and the reply limit cuts it. */
    @Test
    fun aSlowSteadyLink_withoutAdaptiveSizing_isCut() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = slowLink
            runCatching {
                withTimeout(90_000) {
                    newClient().use { c ->
                        c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(160 * 1024)),
                            options = adaptive(target = null, maxRetries = 2))
                    }
                }
            }
            assertTrue(server.arrivals.count { it.first == "HEAD" } > 1,
                "cut: heads=${server.arrivals.count { it.first == "HEAD" }} stored=${server.offset} log=${server.log}")
        }
    }

    /** With adaptive sizing on, a server that stops reading is still cut, and the upload resumes. */
    @Test
    fun withAdaptiveSizing_aServerThatStopsReading_isStillCut_andResumes() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { m, n -> if (m == "PATCH" && n == 3) Fault.StallWithoutReading else Fault.None }
            withTimeout(60_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", testBytes(512 * 1024)),
                        options = adaptive(target = 1_000))
                }
            }
            val measured = "heads=${server.arrivals.count { it.first == "HEAD" }} patches=${server.arrivals.count { it.first == "PATCH" }} " +
                "stored=${server.offset} log=${server.log}"
            assertEquals(512L * 1024, server.offset, measured)
            assertEquals(2, server.arrivals.count { it.first == "HEAD" }, "one cut, one recovery HEAD: $measured")
        }
    }
}
