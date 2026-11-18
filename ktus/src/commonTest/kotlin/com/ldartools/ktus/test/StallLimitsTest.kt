package com.ldartools.ktus.test

import com.ldartools.ktus.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.*

/**
 * Ktus's own silence limits (sendStallMillis, replyTimeoutMillis, finalReplyTimeoutMillis) with MockEngine.
 *
 * Real time: Ktor runs the request body's writer on its own dispatcher, so the body's progress cannot follow
 * virtual time. Limits are a few hundred milliseconds; timing assertions allow the watchdog's 250 ms re-check
 * plus scheduling slack.
 */
class StallLimitsTest {
    private val url = "https://example.com/files/abc"

    /** What the server does with one PATCH attempt. */
    sealed class Patch {
        data object Normal : Patch()
        /** Never reads the body and never answers. */
        data object NoRead : Patch()
        /** Reads [chunk] bytes every [everyMillis], then answers 204. */
        data class SlowRead(val chunk: Int, val everyMillis: Long) : Patch()
        /** Reads the whole body, then never answers. */
        data object ReadThenSilent : Patch()
        /** Reads the whole body, then answers 204 after [millis]. */
        data class ReadThenAnswerAfter(val millis: Long) : Patch()
    }

    /** A minimal tus server on MockEngine that reads PATCH bodies through the real body channel. */
    class StallServer(private val total: Long, vararg patches: Patch) {
        private val start = kotlin.time.TimeSource.Monotonic.markNow()
        fun now(): Long = start.elapsedNow().inWholeMilliseconds
        private val script = ArrayDeque(patches.toList())
        var stored = 0L
        val patchTimes = SyncList<Long>()
        val headTimes = SyncList<Long>()
        val patchSizes = SyncList<Long>()

        private suspend fun read(request: HttpRequestData): ByteReadChannel =
            (request.body as OutgoingContent.ReadChannelContent).readFrom()

        private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            val now = now()
            return when (request.method) {
                HttpMethod.Head -> {
                    headTimes += now
                    respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("$stored"),
                        "Upload-Length" to listOf("$total"), "Tus-Resumable" to listOf("1.0.0")))
                }
                HttpMethod.Patch -> {
                    patchTimes += now
                    patchSizes += request.body.contentLength ?: -1
                    val offset = request.headers["Upload-Offset"]!!.toLong()
                    val mode = script.removeFirstOrNull() ?: Patch.Normal
                    val channel = read(request)
                    var got = 0L
                    when (mode) {
                        Patch.NoRead -> awaitCancellation()
                        is Patch.SlowRead -> {
                            val buffer = ByteArray(mode.chunk)
                            while (true) {
                                val n = channel.readAvailable(buffer, 0, buffer.size)
                                if (n < 0) break
                                got += n
                                delay(mode.everyMillis)
                            }
                        }
                        else -> got = channel.readRemaining().readByteArray().size.toLong()
                    }
                    if (mode == Patch.ReadThenSilent) awaitCancellation()
                    if (mode is Patch.ReadThenAnswerAfter) delay(mode.millis)
                    if (offset != stored) return respond("", HttpStatusCode.Conflict)
                    stored += got
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "$stored"))
                }
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        }

        fun client(): HttpClient = HttpClient(MockEngine(MockEngineConfig().apply {
            addHandler { handle(it) }
        })) { followRedirects = false }
    }

    private fun real(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runTest(timeout = kotlin.time.Duration.parse("2m")) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() }
    }

    private fun opts(
        chunk: Long,
        send: Long? = null, reply: Long? = null, finalReply: Long? = null,
        maxRetries: Int = 5,
    ) = TusUploadOptions(chunkSize = chunk, minChunkSize = chunk, activityHeartbeatMillis = null,
        sendStallMillis = send, replyTimeoutMillis = reply, finalReplyTimeoutMillis = finalReply,
        retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 100, maxDelayMillis = 100,
            jitter = false))

    /**
     * A file whose first [stalls] body writes hand over [before] bytes and then stop, like a socket that no
     * longer accepts data. (MockEngine buffers a whole body, so a server that stops reading cannot stall it.)
     */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    class StallingFile(private val bytes: ByteArray, private val stalls: Int, private val before: Int = 1_000) : ITusFile {
        private val attempts = kotlin.concurrent.atomics.AtomicInt(0)
        override val size: Long get() = bytes.size.toLong()
        override val name: String get() = "stalling.bin"
        override suspend fun writeSection(offset: Long, length: Long, channel: io.ktor.utils.io.ByteWriteChannel) {
            if (attempts.fetchAndAdd(1) < stalls) {
                channel.writeFully(bytes, offset.toInt(), (offset + minOf(before.toLong(), length)).toInt())
                channel.flush()
                awaitCancellation()
            }
            channel.writeFully(bytes, offset.toInt(), (offset + length).toInt())
        }
        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel = error("unused")
        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    private fun Throwable?.stall(): TusStallTimeoutException? =
        generateSequence(this) { it.cause }.filterIsInstance<TusStallTimeoutException>().firstOrNull()

    /** Generous: a 2-core CI runner can be slow to schedule the watchdog and the retry. */
    private val slack = 2_000L

    @Test
    fun aBodyThatStops_isCutAtSendStall_andTheUploadResumesViaHead() = real {
        val server = StallServer(200_000)
        server.client().uploadTus(url, StallingFile(testBytes(200_000), stalls = 1), options = opts(100_000, send = 800))
        assertEquals(200_000, server.stored)
        assertEquals(3, server.patchTimes.size, "stalled PATCH, its retry, the last chunk")
        assertEquals(2, server.headTimes.size, "first HEAD, then the recovery HEAD")
        val cut = server.headTimes[1] - server.patchTimes[0]
        assertTrue(cut in 800..800 + 100 + slack, "cut at ~sendStallMillis: $cut")
    }

    @Test
    fun aBodyThatKeepsMovingSlowly_isNotCut() = real {
        // 20 KB read 1 KB every 200 ms: ~4 s of sending with a 800 ms stall limit, ~5x the limit.
        val server = StallServer(20_000, Patch.SlowRead(1_000, 200))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(20_000)), options = opts(20_000, send = 800))
        assertEquals(1, server.patchTimes.size, "never cut")
        assertEquals(20_000, server.stored)
        assertTrue(server.now() >= 3_500, "${server.now()}")
    }

    @Test
    fun aSendStall_isATimedOutNetworkFailure_withItsKind() = real {
        val server = StallServer(200_000)
        val e = runCatching {
            server.client().uploadTus(url, StallingFile(testBytes(200_000), stalls = 1),
                options = opts(100_000, send = 500, maxRetries = 1))
        }.exceptionOrNull()
        val stall = assertNotNull(e.stall(), "$e")
        assertEquals(TusStallTimeoutException.Kind.SEND_STALL, stall.kind)
        assertEquals(500, stall.limitMillis)
        assertIs<kotlinx.io.IOException>(e, "a network error stays a network error: $e")
    }

    /** A stall is a timeout like an engine's: the next PATCH is halved by adaptive chunk sizing. */
    @Test
    fun aSendStall_halvesTheChunk_likeAnEngineTimeout() = real {
        val server = StallServer(200_000)
        server.client().uploadTus(url, StallingFile(testBytes(200_000), stalls = 1), options = TusUploadOptions(
            chunkSize = 200_000, minChunkSize = 50_000, activityHeartbeatMillis = null, sendStallMillis = 500,
            retryOptions = RetryOptions(initialDelayMillis = 10, maxDelayMillis = 10, jitter = false)))
        assertEquals(200_000, server.stored)
        assertEquals(listOf(200_000L, 100_000L, 100_000L), server.patchSizes.snapshot())
    }

    @Test
    fun aNonFinalPatchWithNoReply_isCutAtReplyTimeout_andResumes() = real {
        val server = StallServer(200_000, Patch.ReadThenSilent)
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(200_000)),
            options = opts(100_000, reply = 800, finalReply = 30_000))
        assertEquals(200_000, server.stored)
        val cut = server.headTimes[1] - server.patchTimes[0]
        assertTrue(cut in 800..800 + 100 + slack, "cut at ~replyTimeoutMillis: $cut")
    }

    @Test
    fun theFinalPatch_waitsFinalReplyTimeout_notReplyTimeout() = real {
        val server = StallServer(100_000, Patch.ReadThenSilent)
        val e = runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100_000)),
                options = opts(100_000, reply = 300, finalReply = 2_000, maxRetries = 1))
        }.exceptionOrNull()
        val unknown = assertIs<TusUploadOutcomeUnknownException>(e, "$e")
        val stall = assertNotNull(unknown.cause.stall(), "${unknown.cause}")
        assertEquals(TusStallTimeoutException.Kind.FINAL_REPLY_TIMEOUT, stall.kind)
        assertEquals(TusUploadOutcomeUnknownException.Kind.NETWORK_FAILURE, unknown.kind)
        // Cut at ~2 s, not 300 ms; then one last resolution HEAD at once.
        val cut = server.headTimes.last() - server.patchTimes[0]
        assertTrue(cut in 2_000..2_000 + slack, "${server.headTimes} ${server.patchTimes}")
    }

    @Test
    fun aSlowFinalReply_withinFinalReplyTimeout_succeedsWithOneFinalPatch() = real {
        val server = StallServer(100_000, Patch.ReadThenAnswerAfter(1_500))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100_000)),
            options = opts(100_000, send = 500, reply = 300, finalReply = 3_000))
        assertEquals(1, server.patchTimes.size)
        assertEquals(100_000, server.stored)
    }

    @Test
    fun replyTimeout_appliesToHead() = real {
        var heads = 0
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        val engine = MockEngine(MockEngineConfig().apply {
            addHandler { request ->
                if (request.method == HttpMethod.Head && heads++ == 0) awaitCancellation()
                respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("10"), "Upload-Length" to listOf("10")))
            }
        })
        HttpClient(engine).uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = opts(10, send = 100, reply = 800))
        assertEquals(2, heads)
        val t = start.elapsedNow().inWholeMilliseconds
        assertTrue(t in 800..800 + 100 + slack, "$t")
    }

    /** An abandoned final PATCH follows exactly the latch of an engine timeout. */
    @Test
    fun anAbandonedFinalPatch_isLikeAnEngineTimeout() = real {
        suspend fun run(stalled: Boolean): Pair<Throwable?, List<String>> {
            val server = ScriptedTusServer()
            server.uploadLength = 10
            server.script(HttpMethod.Patch, if (stalled) ScriptedTusServer.Reply.Custom { _, _ -> awaitCancellation() }
                else ScriptedTusServer.Reply.Throw(io.ktor.client.network.sockets.SocketTimeoutException("read timed out")))
            repeat(5) { server.script(HttpMethod.Patch, ScriptedTusServer.Reply.Status(503)) }
            val store = InMemoryTusUrlStore()
            store.set("fp", TusUpload(url, 10))
            val e = runCatching {
                server.client().resumeOrCreateAndUploadTus("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
                    "fp", store, options = opts(10, finalReply = 300, maxRetries = 2))
            }.exceptionOrNull()
            assertNotNull(store.get("fp")?.finalSentAt, "the mark stays (stalled=$stalled)")
            return e to server.methods()
        }
        val (stall, stallMethods) = run(stalled = true)
        val (timeout, timeoutMethods) = run(stalled = false)
        assertIs<TusUploadOutcomeUnknownException>(stall)
        assertIs<TusUploadOutcomeUnknownException>(timeout)
        assertEquals(timeout.kind, stall.kind)
        assertEquals(timeoutMethods, stallMethods, "same request sequence")
    }

    @Test
    fun callerCancellation_isStillCancellation() = real {
        val server = StallServer(100_000, Patch.NoRead)
        var error: Throwable? = null
        val job = launch {
            try {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100_000)),
                    options = opts(100_000, send = 2_000, reply = 2_000))
            } catch (e: Throwable) {
                error = e
                throw e
            }
        }
        delay(500)
        job.cancel()
        job.join()
        assertIs<CancellationException>(error, "$error")
        assertNull(error.stall())
        assertEquals(1, server.patchTimes.size, "not retried")
    }

    @Test
    fun aStallCountsAgainstTheRunBudget() = real {
        val server = StallServer(200_000)
        val e = runCatching {
            server.client().uploadTus(url, StallingFile(testBytes(200_000), stalls = 100), options = TusUploadOptions(
                chunkSize = 100_000, minChunkSize = 100_000, activityHeartbeatMillis = null, sendStallMillis = 500,
                retryOptions = RetryOptions(initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
                    maxElapsedMillis = 1_200)))
        }.exceptionOrNull()
        assertNotNull(e.stall(), "$e")
        assertTrue(server.patchTimes.size in 2..4, "the budget ended the run: ${server.patchTimes}")
    }

    @Test
    fun limitsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(sendStallMillis = 0) }
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(replyTimeoutMillis = -1) }
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(finalReplyTimeoutMillis = 0) }
        val o = TusUploadOptions()
        assertNull(o.sendStallMillis); assertNull(o.replyTimeoutMillis); assertNull(o.finalReplyTimeoutMillis)
    }
}
