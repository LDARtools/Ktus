package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** TusUploadOptions.targetChunkMillis: each PATCH sized from the measured goodput (virtual time). */
class AdaptiveChunkSizeTest {
    private val url = "https://example.com/files/abc"
    private val k = 1024

    private fun TestScope.server(total: Int, bytesPerSecond: (patchIndex: Int) -> Long): ScriptedTusServer {
        val server = ScriptedTusServer(clock = { testScheduler.currentTime }, dispatcher = StandardTestDispatcher(testScheduler))
        server.uploadLength = total.toLong()
        var n = 0
        server.patchMillis = { bytes -> val rate = bytesPerSecond(n++); if (rate <= 0) 0 else bytes * 1000L / rate }
        return server
    }

    private fun TestScope.opts(target: Long? = 10_000, min: Long = 32L * 1024, cap: Long = 2L * 1024 * 1024) = TusUploadOptions(
        chunkSize = cap, minChunkSize = min, targetChunkMillis = target, activityHeartbeatMillis = null,
        retryOptions = RetryOptions(initialDelayMillis = 10, maxDelayMillis = 10, jitter = false, timeSource = testScheduler.timeSource))

    private suspend fun TestScope.upload(server: ScriptedTusServer, total: Int, options: TusUploadOptions) {
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(total)), options = options)
        assertEquals(total.toLong(), server.offset)
    }

    @Test
    fun aFastLink_doublesEachStep_upToTheCap() = runTest {
        val sizes = listOf(32, 64, 128, 256, 512, 1024, 2048, 2048).map { it * k }
        val total = sizes.sum()
        val server = server(total) { 0 } // answers at once: an "infinite" goodput
        upload(server, total, opts())
        assertEquals(sizes, server.patchSizes())
    }

    @Test
    fun aSlowLink_settlesAtGoodputTimesTarget() = runTest {
        // 16 KiB/s x 10 s = 160 KiB per PATCH; the file ends with whatever remains (136 KiB).
        val total = 1000 * k
        val server = server(total) { 16L * k }
        upload(server, total, opts())
        assertEquals(listOf(32, 64, 128, 160, 160, 160, 160, 136).map { it * k }, server.patchSizes())
    }

    @Test
    fun theFloorIsRespected_onAVerySlowLink() = runTest {
        val total = 200 * k
        val server = server(total) { 1L * k } // 1 KiB/s x 10 s = 10 KiB, below the 32 KiB floor
        upload(server, total, opts())
        assertTrue(server.patchSizes().dropLast(1).all { it == 32 * k }, "${server.patchSizes()}")
    }

    @Test
    fun aLinkThatSlowsDown_shrinksTheChunk() = runTest {
        val total = 8 * 1024 * k
        val server = server(total) { i -> if (i < 7) 160L * k else 16L * k }
        upload(server, total, opts())
        val sizes = server.patchSizes()
        assertEquals(listOf(32, 64, 128, 256, 512, 1024, 1600).map { it * k }, sizes.take(7), "fast phase: $sizes")
        val slow = sizes.drop(7).dropLast(1)
        assertTrue(slow.zipWithNext().all { (a, b) -> b < a }, "keeps shrinking while slow: $slow")
        assertTrue(slow.last() < 200 * k, "approaches 160 KiB: $slow")
    }

    @Test
    fun aResumeWithinTheCall_keepsTheEstimate() = runTest {
        val total = 1000 * k
        val server = server(total) { 16L * k }
        var offset = 0
        for (size in listOf(32, 64, 128, 160)) {
            offset += size * k
            server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "$offset"), apply = true))
        }
        server.script(HttpMethod.Patch, Reply.Status(503))
        upload(server, total, opts())
        val sizes = server.patchSizes()
        assertEquals(listOf(32, 64, 128, 160, 160, 160).map { it * k }, sizes.take(6), "after the 503 and HEAD: $sizes")
        assertEquals(2, server.count(HttpMethod.Head), "the first HEAD and the recovery HEAD")
    }

    @Test
    fun aTimeout_halvesTheChunk_andLowersTheEstimate() = runTest {
        val total = 1000 * k
        val server = server(total) { 16L * k }
        var offset = 0
        for (size in listOf(32, 64, 128, 160)) {
            offset += size * k
            server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "$offset"), apply = true))
        }
        server.script(HttpMethod.Patch, Reply.Throw(io.ktor.client.network.sockets.SocketTimeoutException("read timed out")))
        upload(server, total, opts())
        val sizes = server.patchSizes()
        assertEquals(80 * k, sizes[5], "halved after the timeout: $sizes")
        assertTrue(sizes[6] in (80 * k + 1) until 160 * k, "grows back from the lowered estimate: $sizes")
    }

    @Test
    fun off_everyPatchIsChunkSize_asIn11() = runTest {
        val total = 350 * k
        val server = server(total) { 16L * k }
        upload(server, total, opts(target = null, cap = 100L * k))
        assertEquals(listOf(100, 100, 100, 50).map { it * k }, server.patchSizes())
    }

    @Test
    fun validation() {
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(targetChunkMillis = 0) }
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(targetChunkMillis = 10_000, minChunkSize = 8L * 1024) }
        TusUploadOptions(targetChunkMillis = 10_000, minChunkSize = 16L * 1024)
        TusUploadOptions(minChunkSize = 8L * 1024) // unchanged without a target
        assertNull(TusUploadOptions().targetChunkMillis)
    }
}
