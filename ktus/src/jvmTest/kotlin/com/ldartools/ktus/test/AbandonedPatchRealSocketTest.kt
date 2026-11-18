package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.RealSocketTusServer.Fault
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** On CIO. */
class CioAbandonedPatchRealSocketTest : AbandonedPatchRealSocketSuite() {
    override fun newClient() = HttpClient(CIO) { followRedirects = false }
}

/** On OkHttp. */
class OkHttpAbandonedPatchRealSocketTest : AbandonedPatchRealSocketSuite() {
    override fun newClient() = HttpClient(OkHttp) { followRedirects = false }
}

/**
 * A request Ktus abandons is abandoned only on the client: bytes already handed to the operating system can still
 * reach the server, which may apply the abandoned PATCH after all. The offsets keep the data correct.
 *
 * PATCH 1 (64 KiB, fits the socket buffers) is read slowly over ~5 s; the 4 s reply limit cuts it while its body
 * is still arriving. The retry (sent at the offset the HEAD reported) is read at once but answered only after
 * 2.5 s, by when the abandoned PATCH 1 has landed: the server answers the retry 409, Ktus asks with HEAD and
 * continues from the new offset. Every byte arrives exactly once and the upload completes.
 */
abstract class AbandonedPatchRealSocketSuite {
    abstract fun newClient(): HttpClient

    @Test
    fun anAbandonedPatchTheServerAppliesLater_leadsTo409_thenHead_andACorrectUpload() = runBlocking {
        RealSocketTusServer().use { server ->
            server.faults = { m, n ->
                when {
                    m == "PATCH" && n == 1 -> Fault.SlowRead(4 * 1024, 312) // 16 reads: ~5 s
                    m == "PATCH" && n == 2 -> Fault.AnswerAfter(2_500)
                    else -> Fault.None
                }
            }
            val bytes = testBytes(3 * 64 * 1024)
            withTimeout(60_000) {
                newClient().use { c ->
                    c.createAndUploadTus("${server.baseUrl}/files", InMemoryTusFile("f", bytes),
                        options = TusUploadOptions(chunkSize = 64L * 1024, minChunkSize = 64L * 1024,
                            activityHeartbeatMillis = null, replyTimeoutMillis = 4_000, finalReplyTimeoutMillis = 30_000,
                            retryOptions = RetryOptions(initialDelayMillis = 10, maxDelayMillis = 10, jitter = false)))
                }
            }
            val log = server.log.map { "${it.method}@${it.uploadOffset}:${it.result}" }
            assertContentEquals(bytes, server.data, "every byte exactly once: $log")
            val first = server.slowReadEnds.single()
            assertEquals(first.declared, first.read, "the abandoned PATCH 1 still arrived whole: $log")
            assertTrue(log.any { it.startsWith("PATCH@0:204") }, "the server applied the abandoned PATCH 1: $log")
            assertTrue(log.any { it.startsWith("PATCH@0:409") }, "the retry at the old offset got 409: $log")
            assertTrue(server.arrivals.count { it.first == "HEAD" } >= 3, "a HEAD after the cut and after the 409: $log")
        }
    }
}
