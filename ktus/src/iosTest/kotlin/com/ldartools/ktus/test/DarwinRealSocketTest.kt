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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    class ShortFile(private val bytes: ByteArray, private val claimed: Long) : ITusFile {
        override val size: Long get() = claimed
        override val name: String get() = "short.bin"
        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel =
            ByteReadChannel(bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt()))
        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    @Test
    fun aShortRead_isALocalFileError_andIsNotRetried() = runBlocking {
        PosixTusServer().use { server ->
            val file = ShortFile(payload(300 * 1024), claimed = 512 * 1024)
            val error = assertFails {
                withTimeout(30_000) {
                    client().use { it.createAndUploadTus("${server.baseUrl}/files", file,
                        options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry())) }
                }
            }
            assertIs<TusLocalFileException>(error, "was $error")
            assertEquals(2, server.log.count { it.method == "PATCH" }, "${server.log}")
            assertEquals(256 * 1024, server.data.size, "the truncated second chunk is not stored")
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
}
