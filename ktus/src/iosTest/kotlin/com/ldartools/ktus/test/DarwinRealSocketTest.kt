package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.PosixTusServer.Fault
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import com.ldartools.ktus.copyExactly
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.test.*
import kotlin.time.TimeSource

/**
 * Real-socket tests with the Ktor Darwin (NSURLSession) engine on the iOS simulator, against [PosixTusServer].
 */
class DarwinRealSocketTest {

    class TrackingFile(private val bytes: ByteArray) : ITusFile {
        val openHandles = AtomicInt(0)
        val bytesWritten = AtomicLong(0)
        override val size: Long get() = bytes.size.toLong()
        override val name: String get() = "tracking.bin"
        override suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) {
            openHandles.incrementAndGet()
            try {
                var p = offset
                while (p < offset + length) {
                    val n = minOf(64 * 1024L, offset + length - p).toInt()
                    channel.writeFully(bytes, p.toInt(), p.toInt() + n)
                    p += n
                    bytesWritten.addAndGet(n.toLong())
                }
            } finally {
                openHandles.decrementAndGet()
            }
        }
        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel =
            ByteReadChannel(bytes.copyOfRange(offset.toInt(), (offset + length).toInt()))
        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    private fun client(requestTimeoutMillis: Long? = null) = HttpClient(Darwin) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis?.let { this.requestTimeoutMillis = it } }
    }

    private fun quickRetry(maxRetries: Int = 20, maxElapsedMillis: Long = 30_000) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 20, maxDelayMillis = 200, maxElapsedMillis = maxElapsedMillis)

    private fun payload(size: Int) = ByteArray(size) { ((it * 7) xor (it ushr 11)).toByte() }

    @Test
    fun aNormalUpload_isByteIdentical() = runBlocking {
        PosixTusServer().use { server ->
            val bytes = payload(700 * 1024)
            withTimeout(30_000) {
                client().use { it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                    options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry())) }
            }
            assertContentEquals(bytes, server.data)
        }
    }

    @Test
    fun serverStoresTheChunkThenDropsTheConnection_clientHeadsAndResumes() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, n -> if (m == "PATCH" && n == 2) Fault.ApplyThenDrop else Fault.None }
            val bytes = payload(1024 * 1024)
            withTimeout(30_000) {
                client().use { it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                    options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry())) }
            }
            assertContentEquals(bytes, server.data)
            val patches = server.log.filter { it.method == "PATCH" }
            assertEquals(listOf(0L, 262_144L, 524_288L, 786_432L), patches.map { it.uploadOffset }, "${server.log}")
        }
    }

    @Test
    fun serviceUnavailableWithRetryAfter_isHonoured() = runBlocking {
        PosixTusServer().use { server ->
            server.statuses = { m, n -> if (m == "PATCH" && n == 1) 503 to mapOf("Retry-After" to "1") else null }
            val bytes = payload(64 * 1024)
            withTimeout(30_000) {
                client().use { it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                    options = TusUploadOptions(retryOptions = quickRetry())) }
            }
            assertContentEquals(bytes, server.data)
            val log = server.log
            val i = log.indexOfFirst { it.result == "503" }
            assertTrue(log[i + 1].atMillis - log[i].atMillis >= 950, "$log")
        }
    }

    @Test
    fun theFinalResponseIsLost_andTheServerRemovesTheUpload_isOutcomeUnknown() = runBlocking {
        PosixTusServer().use { server ->
            server.removeWhenComplete = true
            server.faults = { m, n -> if (m == "PATCH" && n == 2) Fault.ApplyThenDrop else Fault.None }
            val bytes = payload(512 * 1024)
            val error = assertFailsWith<TusUploadOutcomeUnknownException> {
                withTimeout(30_000) {
                    client().use { it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                        options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry())) }
                }
            }
            assertIs<TusUploadExpiredException>(error.cause)
            assertContentEquals(bytes, server.data)
            assertEquals(2, server.log.count { it.method == "PATCH" })
        }
    }

    @Test
    fun aStallingServer_timesOut_isRetried_andShrinksTheChunk() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, _ -> if (m == "PATCH") Fault.Stall else Fault.None }
            val started = TimeSource.Monotonic.markNow()
            val error = assertFailsWith<Exception> {
                withTimeout(60_000) {
                    client(requestTimeoutMillis = 500).use {
                        it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(1024 * 1024)),
                            options = TusUploadOptions(chunkSize = 1024 * 1024, minChunkSize = 128 * 1024,
                                retryOptions = quickRetry(maxRetries = 1_000, maxElapsedMillis = 3_000)))
                    }
                }
            }
            // The first PATCH carried the whole file and never got an answer, so the outcome is unknown; the
            // cause is the timeout, a network error.
            assertIs<TusUploadOutcomeUnknownException>(error, "was $error")
            assertTrue(error.cause is kotlinx.io.IOException, "cause ${error.cause}")
            val patches = server.log.filter { it.method == "PATCH" }
            assertTrue(patches.size in 2..30, "bounded retries: ${patches.size}")
            assertTrue(started.elapsedNow().inWholeMilliseconds < 3_000 + 10_000)
            assertEquals(listOf(1_048_576L, 524_288L, 262_144L, 131_072L), patches.take(4).map { it.contentLength })
        }
    }

    @Test
    fun anNSURLSessionTimeout_isRetried_asATimeout_andShrinksTheChunk() = runBlocking {
        // No HttpTimeout plugin: NSURLSession's own request timeout fires (NSURLErrorTimedOut, -1001) and Ktor
        // reports it as DarwinHttpRequestException.
        PosixTusServer().use { server ->
            server.faults = { m, n -> if (m == "PATCH" && n <= 2) Fault.Stall else Fault.None }
            val nativeTimeoutClient = HttpClient(Darwin) {
                followRedirects = false
                engine { configureSession { setTimeoutIntervalForRequest(1.0) } }
            }
            val bytes = payload(1024 * 1024)
            withTimeout(60_000) {
                nativeTimeoutClient.use {
                    it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                        options = TusUploadOptions(chunkSize = 1024 * 1024, minChunkSize = 128 * 1024,
                            retryOptions = quickRetry()))
                }
            }
            assertContentEquals(bytes, server.data)
            val sizes = server.log.filter { it.method == "PATCH" }.map { it.contentLength }
            assertEquals(listOf(1_048_576L, 524_288L, 262_144L), sizes.take(3), "${server.log}")
        }
    }

    /** A file that is shorter than its size mid-PATCH: TusLocalFileException, not retried, nothing truncated stored. */
    /**
     * Claims [claimed] bytes but holds fewer. With [pauseBeforeFailingMillis] > 0 it first sends what it has and
     * waits, so the partial body certainly reaches the server before the short read is detected.
     */
    class ShortFile(
        private val bytes: ByteArray,
        private val claimed: Long,
        private val pauseBeforeFailingMillis: Long = 0,
    ) : ITusFile {
        override val size: Long get() = claimed
        override val name: String get() = "short.bin"
        override suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) {
            if (pauseBeforeFailingMillis == 0L) return copyExactly(readSection(offset, length), channel, offset, length)
            val available = (bytes.size - offset).coerceIn(0, length).toInt()
            channel.writeFully(bytes, offset.toInt(), offset.toInt() + available)
            channel.flush()
            if (available < length) {
                delay(pauseBeforeFailingMillis)
                throw TusLocalFileException("short read at ${offset + available}")
            }
        }
        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel =
            ByteReadChannel(bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt()))
        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    @Test
    fun aShortRead_isALocalFileError_andIsNotRetried() = runBlocking {
        for (pause in listOf(0L, 500L)) shortRead(pause)
    }

    private suspend fun shortRead(pauseBeforeFailingMillis: Long) {
        PosixTusServer().use { server ->
            val file = ShortFile(payload(300 * 1024), claimed = 512 * 1024, pauseBeforeFailingMillis)
            val error = assertFails {
                withTimeout(30_000) {
                    client().use { it.createAndUploadTus("${server.baseUrl}/files", file,
                        options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry())) }
                }
            }
            assertIs<TusLocalFileException>(error, "was $error")
            // Let the server finish with the aborted request before looking at what it stored.
            withTimeoutOrNull(2_000) { while (server.log.count { it.method == "PATCH" } < 2) delay(20) }
            delay(200)
            val patches = server.log.filter { it.method == "PATCH" }
            // NSURLSession may abort the second request before the server even logs it; it is never retried.
            assertTrue(patches.size in 1..2, "${server.log}")
            // The second request never completes: the client aborts it, so the server sees at most a body shorter
            // than its Content-Length (logged "aborted"), never a finished request with fewer bytes.
            assertTrue(patches.drop(1).all { it.result == "aborted" }, "${server.log}")
            assertEquals(256 * 1024, server.data.size, "only the first chunk is stored: ${server.log}")
        }
    }

    /**
     * 307/308 on HEAD and on PATCH, with followRedirects on (Ktor's default) and off. Observed: NSURLSession does
     * not follow on its own; with followRedirects on, Ktor's redirect plugin follows the HEAD (and the answered-URL
     * check rejects it) but not the PATCH (rejected as a 3xx); with it off every 3xx is rejected.
     */
    @Test
    fun redirectsOnHeadAndPatch_areRejected_onDarwin() = runBlocking {
        for (follow in listOf(true, false)) {
            for (method in listOf("HEAD", "PATCH")) {
                for (status in listOf(307, 308)) {
                    PosixTusServer().use { server ->
                        server.statuses = { m, n -> if (m == method && n == 1) status to mapOf("Location" to "/files/other") else null }
                        val error = runCatching {
                            withTimeout(30_000) {
                                HttpClient(Darwin) { followRedirects = follow }.use {
                                    it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(64 * 1024)),
                                        options = TusUploadOptions(retryOptions = quickRetry(maxRetries = 2)))
                                }
                            }
                        }.exceptionOrNull()
                        val label = "follow=$follow $method $status"
                        assertTrue(error != null, "$label: a redirected tus request must not succeed; log=${server.log}")
                        val rejected = error is TusProtocolException ||
                            (error is TusUploadOutcomeUnknownException && error.cause is TusProtocolException)
                        assertTrue(rejected, "$label: rejected as a protocol error, was $error; log=${server.log}")
                    }
                }
            }
        }
    }

    /** A block that adds a query parameter (an auth token, say) is not mistaken for a followed redirect. */
    @Test
    fun aQueryParameterAddedByTheBlock_isNotARedirect() = runBlocking {
        PosixTusServer().use { server ->
            val bytes = payload(300 * 1024)
            withTimeout(30_000) {
                client().use { it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                    options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry())) {
                    url { parameters.append("token", "a b/c") }
                } }
            }
            assertContentEquals(bytes, server.data)
        }
    }

    /** The Darwin engine reports upload progress before connecting; the floor must not depend on it. */
    @Test
    fun aRefusedConnection_doesNotGetTheMinDelayFloor() = runBlocking {
        val server = PosixTusServer()
        val started = TimeSource.Monotonic.markNow()
        val progress = AtomicLong(0)
        val error = runCatching {
            withTimeout(30_000) {
                client().use {
                    it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(512 * 1024)),
                        options = TusUploadOptions(retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 20,
                            maxDelayMillis = 50, minDelayMillis = 10_000, maxElapsedMillis = 60_000)),
                        onProgress = { sent, _ -> if (sent > progress.value) progress.value = sent },
                        onFinalPatch = { server.close() }) // every later connection is refused
                }
            }
        }.exceptionOrNull()
        val elapsed = started.elapsedNow().inWholeMilliseconds
        assertTrue(error != null, "the call must fail")
        assertTrue(elapsed < 5_000, "connection refused (-1004) proves nothing was sent; no 10 s floor ($elapsed ms, $error)")
    }

    @Test
    fun aConnectionDroppedAfterTheBody_getsTheMinDelayFloor() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, n -> if (m == "PATCH" && n == 1) Fault.ApplyThenDrop else Fault.None }
            val bytes = payload(512 * 1024)
            withTimeout(30_000) {
                client().use { it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                    options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = RetryOptions(
                        initialDelayMillis = 20, maxDelayMillis = 50, minDelayMillis = 1_500, maxElapsedMillis = 60_000))) }
            }
            assertContentEquals(bytes, server.data)
            val log = server.log
            val i = log.indexOfFirst { it.result == "applied+dropped" }
            assertTrue(log[i + 1].atMillis - log[i].atMillis >= 1_400, "$log")
        }
    }

    @Test
    fun cancellingMidPatch_leavesNoOpenFile() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, _ -> if (m == "PATCH") Fault.StallWithoutReading else Fault.None }
            val file = TrackingFile(payload(32 * 1024 * 1024))
            val client = client()
            val job = launch(Dispatchers.Default) {
                client.createAndUploadTus("${server.baseUrl}/files", file,
                    options = TusUploadOptions(chunkSize = 32L * 1024 * 1024, retryOptions = quickRetry()))
            }
            withTimeout(20_000) {
                var last = -1L
                while (true) {
                    delay(200)
                    val now = file.bytesWritten.value
                    if (now > 0 && now == last && file.openHandles.value > 0) break
                    last = now
                }
            }
            withTimeout(10_000) { job.cancelAndJoin() }
            withTimeout(2_000) { while (file.openHandles.value != 0) delay(10) }
            assertEquals(0, file.openHandles.value)
            client.close()
        }
    }

    /** Ktus's reply limit on Darwin: a non-final PATCH whose answer never comes is abandoned, and the upload resumes. */
    @Test
    fun replyTimeout_firesOnDarwin_andTheUploadResumes() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, n -> if (m == "PATCH" && n == 1) Fault.Stall else Fault.None }
            withTimeout(60_000) {
                client().use {
                    it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(200_000)),
                        options = TusUploadOptions(chunkSize = 100_000, activityHeartbeatMillis = null,
                            sendStallMillis = 1_000, replyTimeoutMillis = 1_000, finalReplyTimeoutMillis = 10_000,
                            retryOptions = quickRetry()))
                }
            }
            assertEquals(200_000, server.data.size)
            val firstPatch = server.log.first { it.method == "PATCH" }.atMillis
            val recoveryHead = server.log.filter { it.method == "HEAD" }[1].atMillis
            val cut = recoveryHead - firstPatch
            assertTrue(cut in 1_000..1_000 + 1_500, "cut after $cut ms: ${server.log}")
        }
    }

    /**
     * Body progress comes in ~1 MiB steps (Ktor buffers about 1 MiB of the body; the same on CIO and OkHttp), so
     * the gap between progress events is ~1 MiB ÷ the link speed: ~0.3-0.8 s on a Mac at ~1.3 MB/s, longer on a
     * slower CI runner. What is pinned is the rule, not a speed: a send stall is never reported while progress
     * keeps arriving within the limit. Either the upload went through in one PATCH, or a real gap of at least
     * the limit preceded the cut.
     */
    @Test
    fun noFalseSendStall_onDarwin() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, _ -> if (m == "PATCH") Fault.SlowRead else Fault.None }
            val size = 96 * 64 * 1024 // ~5 s at 64 KiB per 50 ms, so a wrong cut at the limit would show
            val limit = 2_000L
            val clock = TimeSource.Monotonic.markNow()
            var last = 0L
            var maxGap = 0L
            withTimeout(60_000) {
                client().use {
                    it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(size)),
                        options = TusUploadOptions(chunkSize = size.toLong(), activityHeartbeatMillis = null,
                            sendStallMillis = limit, replyTimeoutMillis = 30_000, finalReplyTimeoutMillis = 30_000,
                            retryOptions = quickRetry()),
                        onProgress = { _, _ ->
                            val now = clock.elapsedNow().inWholeMilliseconds
                            maxGap = maxOf(maxGap, now - last)
                            last = now
                        })
                }
            }
            val patches = server.arrivals.count { it.first == "PATCH" }
            val measured = "patches=$patches maxProgressGap=$maxGap ms elapsed=${clock.elapsedNow().inWholeMilliseconds} ms " +
                "stored=${server.data.size}/$size log=${server.log}"
            assertEquals(size, server.data.size, measured)
            assertTrue(patches == 1 || maxGap >= limit, "a send stall without a progress gap of $limit ms: $measured")
        }
    }

    private fun adaptive(target: Long?, maxRetries: Int = 5) = TusUploadOptions(chunkSize = 2L * 1024 * 1024,
        minChunkSize = 16L * 1024, targetChunkMillis = target, activityHeartbeatMillis = null,
        sendStallMillis = 3_000, replyTimeoutMillis = 3_000, finalReplyTimeoutMillis = 10_000,
        retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false))

    /** ~16 KiB/s with the silence limits (scaled 1 s target / 3 s limits): adaptive sizing completes without a cut. */
    @Test
    fun aSlowSteadyLink_withAdaptiveSizing_completesWithoutACut() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, _ -> if (m == "PATCH") Fault.SlowRead else Fault.None }
            server.slowReadBytes = 4 * 1024; server.slowReadPauseMicros = 250_000u
            withTimeout(90_000) {
                client().use {
                    it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(160 * 1024)), options = adaptive(1_000))
                }
            }
            assertEquals(160 * 1024, server.data.size)
            assertEquals(1, server.arrivals.count { it.first == "HEAD" }, "no recovery HEAD, so no cut: ${server.log}")
        }
    }

    /** The same without adaptive sizing: cut by the reply limit. */
    @Test
    fun aSlowSteadyLink_withoutAdaptiveSizing_isCut() = runBlocking {
        PosixTusServer().use { server ->
            server.faults = { m, _ -> if (m == "PATCH") Fault.SlowRead else Fault.None }
            server.slowReadBytes = 4 * 1024; server.slowReadPauseMicros = 250_000u
            runCatching {
                withTimeout(90_000) {
                    client().use {
                        it.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(160 * 1024)),
                            options = adaptive(null, maxRetries = 2))
                    }
                }
            }
            assertTrue(server.arrivals.count { it.first == "HEAD" } > 1, "cut: ${server.log}")
        }
    }
}
