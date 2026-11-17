package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.RealSocketTusServer.Fault
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** The real-socket suite on the Ktor CIO engine. */
class CioRealSocketFaultTest : RealSocketFaultSuite() {
    override fun newClient(requestTimeoutMillis: Long?) = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis?.let { this.requestTimeoutMillis = it } }
    }
}

/** The real-socket suite on the Ktor OkHttp engine (Android's usual engine). */
class OkHttpRealSocketFaultTest : RealSocketFaultSuite() {
    override fun newClient(requestTimeoutMillis: Long?) = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis?.let { this.requestTimeoutMillis = it } }
    }
}

/**
 * Fault injection over real sockets, run once per HTTP engine. MockEngine reads whole request bodies before
 * answering, so it cannot show a connection dropped after the server stored a chunk, a server answering
 * while the client is still writing, a stalled server, or what cancellation leaves behind.
 */
abstract class RealSocketFaultSuite {

    abstract fun newClient(requestTimeoutMillis: Long? = null): HttpClient


    /**
     * An in-memory file that counts open "handles" (sections being read) and active background writers,
     * so a test can prove nothing outlives the request.
     */
    class TrackingFile(private val bytes: ByteArray) : ITusFile {
        val openHandles = AtomicInteger()
        val activeWriters = AtomicInteger()
        val bytesWritten = AtomicLong()

        override val size: Long get() = bytes.size.toLong()
        override val name: String get() = "tracking.bin"

        override suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) {
            openHandles.incrementAndGet()
            try {
                var position = offset
                val end = offset + length
                while (position < end) {
                    val n = minOf(64 * 1024L, end - position).toInt()
                    channel.writeFully(bytes, position.toInt(), position.toInt() + n)
                    position += n
                    bytesWritten.addAndGet(n.toLong())
                }
            } finally {
                openHandles.decrementAndGet()
            }
        }

        /** The 1.0-style read: a detached writer coroutine fills the channel. */
        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel =
            CoroutineScope(Dispatchers.IO).writer {
                openHandles.incrementAndGet()
                activeWriters.incrementAndGet()
                try {
                    channel.writeFully(bytes, offset.toInt(), (offset + length).toInt())
                    bytesWritten.addAndGet(length)
                } finally {
                    activeWriters.decrementAndGet()
                    openHandles.decrementAndGet()
                }
            }.channel

        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    /**
     * A file written for Ktus 1.0: it implements only [readSection], with a detached writer coroutine, and
     * relies on the default [ITusFile.writeSection]. Counts the same things as [TrackingFile].
     */
    class LegacyTrackingFile(private val bytes: ByteArray) : ITusFile {
        val openHandles = AtomicInteger()
        val activeWriters = AtomicInteger()
        val bytesWritten = AtomicLong()

        override val size: Long get() = bytes.size.toLong()
        override val name: String get() = "legacy.bin"

        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel =
            CoroutineScope(Dispatchers.IO).writer {
                openHandles.incrementAndGet()
                activeWriters.incrementAndGet()
                try {
                    var position = offset
                    val end = offset + length
                    while (position < end) {
                        val n = minOf(64 * 1024L, end - position).toInt()
                        channel.writeFully(bytes, position.toInt(), position.toInt() + n)
                        position += n
                        bytesWritten.addAndGet(n.toLong())
                    }
                } finally {
                    activeWriters.decrementAndGet()
                    openHandles.decrementAndGet()
                }
            }.channel

        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    private fun client(requestTimeoutMillis: Long? = null) = newClient(requestTimeoutMillis)

    private fun quickRetry(maxRetries: Int = 20, maxElapsedMillis: Long = 30_000) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 20, maxDelayMillis = 200, maxElapsedMillis = maxElapsedMillis,
    )

    private fun payload(size: Int) = ByteArray(size) { ((it * 7) xor (it ushr 11)).toByte() }

    // (1) ------------------------------------------------------------------------------------------------

    @Test
    fun serverStoresTheChunkThenDropsTheConnection_clientHeadsAndResumes() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, n -> if (method == "PATCH" && n == 2) Fault.ApplyThenDrop else Fault.None }
            val bytes = payload(1024 * 1024)
            val file = TrackingFile(bytes)
            val confirmed = mutableListOf<Long>()

            withTimeout(30_000) {
                client().use { client ->
                    client.createAndUploadTus("${server.baseUrl}/files", file,
                        options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry()),
                        onConfirmed = { o, _ -> confirmed += o })
                }
            }

            assertContentEquals(bytes, server.data, "the server must end with exactly the file's bytes")
            val patches = server.log.filter { it.method == "PATCH" }
            assertEquals(listOf(0L, 262_144L, 524_288L, 786_432L), patches.map { it.uploadOffset },
                "after the drop the client must HEAD and continue at 512 KiB, never re-send 256 KiB: ${server.log}")
            val dropIndex = server.log.indexOfFirst { it.result == "applied+dropped" }
            assertEquals("HEAD", server.log[dropIndex + 1].method, "the request after the drop is a HEAD")
            assertEquals(listOf(262_144L, 524_288L, 786_432L, 1_048_576L), confirmed)
            assertEquals(0, file.openHandles.get())
        }
    }

    // (2) ------------------------------------------------------------------------------------------------

    @Test
    fun serverAnswers409WhileTheClientIsStillWritingALargeBody_clientRecovers() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, n -> if (method == "PATCH" && n == 1) Fault.EarlyConflict else Fault.None }
            val bytes = payload(16 * 1024 * 1024)
            val file = TrackingFile(bytes)

            withTimeout(60_000) {
                client().use { client ->
                    client.createAndUploadTus("${server.baseUrl}/files", file,
                        options = TusUploadOptions(chunkSize = 16L * 1024 * 1024, retryOptions = quickRetry()))
                }
            }

            assertContentEquals(bytes, server.data)
            assertEquals("409-early", server.log.first { it.method == "PATCH" }.result)
            val firstPatch = server.log.indexOfFirst { it.method == "PATCH" }
            assertEquals("HEAD", server.log[firstPatch + 1].method, "recovery goes through HEAD: ${server.log}")
            // The abandoned write is cancelled asynchronously; allow it a moment (in 1.1.0-dev without
            // abandon() it stayed open for good, and 10 s later was still blocked on the socket).
            awaitZero(file.openHandles, 2_000)
            assertEquals(0, file.openHandles.get(), "the abandoned body must not keep the file open")
        }
    }

    // (3) ------------------------------------------------------------------------------------------------

    @Test
    fun locked423ForAWhile_thenTheUploadCompletes() = runBlocking {
        RealSocketTusServer().use { server ->
            val bytes = payload(300 * 1024)
            server.faults = { method, n ->
                // Lock the file right after the first PATCH arrives, for 1.5 s.
                if (method == "PATCH" && n == 1) server.lockedUntilMillis = server.nowMillis() + 1_500
                Fault.None
            }
            val started = System.nanoTime()
            withTimeout(30_000) {
                client().use { client ->
                    client.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                        options = TusUploadOptions(chunkSize = 100 * 1024, retryOptions = quickRetry(maxRetries = 100)))
                }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertContentEquals(bytes, server.data)
            assertTrue(server.log.count { it.result == "423" } >= 2, "${server.log}")
            assertTrue(elapsed >= 1_500, "the client kept retrying through the lock ($elapsed ms)")
        }
    }

    // (4) ------------------------------------------------------------------------------------------------

    @Test
    fun serviceUnavailableWithRetryAfter_isHonoured() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, n ->
                if (method == "PATCH" && n == 1) Fault.Status(503, mapOf("Retry-After" to "1")) else Fault.None
            }
            val bytes = payload(64 * 1024)
            withTimeout(30_000) {
                client().use { client ->
                    client.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                        options = TusUploadOptions(retryOptions = quickRetry()))
                }
            }
            assertContentEquals(bytes, server.data)
            val i = server.log.indexOfFirst { it.result == "503" }
            val gap = server.log[i + 1].atMillis - server.log[i].atMillis
            assertTrue(gap >= 950, "Retry-After: 1 must delay the next request by ~1 s, was $gap ms")
            assertEquals("HEAD", server.log[i + 1].method)
        }
    }

    // (5) ------------------------------------------------------------------------------------------------

    @Test
    fun aSilentlyStallingServer_isRetriedWithinTheBudget_thenFails() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.Stall else Fault.None }
            val budget = 3_000L
            val timeout = 400L
            val started = System.nanoTime()
            val error = assertFailsWith<Exception> {
                withTimeout(30_000) {
                    client(requestTimeoutMillis = timeout).use { client ->
                        client.createAndUploadTus("${server.baseUrl}/files", TrackingFile(payload(1024 * 1024)),
                            options = TusUploadOptions(chunkSize = 1024 * 1024, minChunkSize = 128 * 1024,
                                retryOptions = quickRetry(maxRetries = 1_000, maxElapsedMillis = budget)))
                    }
                }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            // The first PATCH carried the whole file and never got an answer, so the outcome is unknown; the
            // cause is the timeout, a network error.
            assertIs<TusUploadOutcomeUnknownException>(error, "was $error")
            assertTrue(error.cause is kotlinx.io.IOException, "cause ${error.cause}")
            val patches = server.log.filter { it.method == "PATCH" }
            assertTrue(patches.size in 2..20, "bounded retries: ${patches.size}")
            // Wall-clock bound kept generous for loaded CI machines; the attempt count above is the tight check.
            assertTrue(elapsed < budget + 10_000, "must stop near the $budget ms budget, took $elapsed ms")
            assertEquals(listOf(1_048_576L, 524_288L, 262_144L, 131_072L), patches.take(4).map { it.contentLength },
                "each timeout halves the chunk down to the floor")
        }
    }

    // (7) ------------------------------------------------------------------------------------------------

    @Test
    fun theFinalResponseIsLost_andTheServerRemovesTheUpload_isOutcomeUnknown() = runBlocking {
        RealSocketTusServer().use { server ->
            server.removeWhenComplete = true
            server.faults = { method, n -> if (method == "PATCH" && n == 2) Fault.ApplyThenDrop else Fault.None }
            val bytes = payload(512 * 1024)
            val error = assertFailsWith<TusUploadOutcomeUnknownException> {
                withTimeout(30_000) {
                    client().use { client ->
                        client.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                            options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = quickRetry()))
                    }
                }
            }
            assertIs<TusUploadExpiredException>(error.cause)
            assertContentEquals(bytes, server.data)
            assertEquals(1, server.completions.get())
            assertEquals(2, server.log.count { it.method == "PATCH" }, "the final chunk is never re-sent: ${server.log}")
        }
    }

    // (8) ------------------------------------------------------------------------------------------------

    @Test
    fun onActivity_isNeverInvokedConcurrently() = runBlocking {
        RealSocketTusServer().use { server ->
            val inside = AtomicInteger()
            val overlaps = AtomicInteger()
            val calls = AtomicInteger()
            val bytes = payload(8 * 1024 * 1024)
            withTimeout(60_000) {
                client().use { client ->
                    client.createAndUploadTus("${server.baseUrl}/files", TrackingFile(bytes),
                        // A 1 ms heartbeat on the caller's dispatcher races the body-write progress on the
                        // engine's thread.
                        options = TusUploadOptions(chunkSize = 8L * 1024 * 1024, activityHeartbeatMillis = 1,
                            retryOptions = quickRetry()),
                        onActivity = {
                            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
                            calls.incrementAndGet()
                            Thread.sleep(1)
                            inside.decrementAndGet()
                        })
                }
            }
            assertContentEquals(bytes, server.data)
            assertTrue(calls.get() > 10, "precondition: many activity calls (${calls.get()})")
            assertEquals(0, overlaps.get(), "onActivity ran concurrently ${overlaps.get()} times")
        }
    }

    // (6) ------------------------------------------------------------------------------------------------

    @Test
    fun cancellingMidPatch_leavesNoOpenFile() = runBlocking {
        val file = TrackingFile(payload(64 * 1024 * 1024))
        cancelMidPatch(file, file.openHandles, file.activeWriters, file.bytesWritten)
    }

    @Test
    fun cancellingMidPatch_withALegacyReadSectionOnlyFile_leavesNoRunningWriter() = runBlocking {
        // Here the bytes come from a detached writer the file itself launched (the 1.0 OkioTusFile shape), so
        // only the default writeSection's cleanup can stop it.
        val file = LegacyTrackingFile(payload(64 * 1024 * 1024))
        cancelMidPatch(file, file.openHandles, file.activeWriters, file.bytesWritten)
    }

    private suspend fun cancelMidPatch(file: ITusFile, openHandles: AtomicInteger, activeWriters: AtomicInteger, bytesWritten: AtomicLong) = coroutineScope {
        RealSocketTusServer().use { server ->
            server.faults = { method, _ -> if (method == "PATCH") Fault.StallWithoutReading else Fault.None }
            val client = client()
            val job: Job = launch(Dispatchers.Default) {
                client.createAndUploadTus("${server.baseUrl}/files", file,
                    options = TusUploadOptions(chunkSize = 64L * 1024 * 1024, retryOptions = quickRetry()))
            }
            // Wait until the body writer is blocked on a full socket.
            withTimeout(20_000) {
                var last = -1L
                while (true) {
                    delay(200)
                    val now = bytesWritten.get()
                    if (now > 0 && now == last && openHandles.get() > 0) break
                    last = now
                }
            }
            assertEquals(1, openHandles.get(), "precondition: the PATCH body is being written")

            withTimeout(10_000) { job.cancelAndJoin() }

            awaitZero(openHandles, 2_000)
            awaitZero(activeWriters, 2_000)
            assertEquals(0, openHandles.get(), "the file section must be closed after cancellation")
            assertEquals(0, activeWriters.get(), "no writer may outlive the request")
            assertTrue(job.isCancelled)
            client.close()
        }
    }

    private fun awaitZero(counter: AtomicInteger, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (counter.get() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
    }
}
