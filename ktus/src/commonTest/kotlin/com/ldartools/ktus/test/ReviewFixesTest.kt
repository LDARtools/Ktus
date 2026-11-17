package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.request.post
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.*

/** Fixes from the adversarial review of 1.1.0. */
class ReviewFixesTest {

    private val url = "https://example.com/files/abc"

    private fun TestScope.retry(maxRetries: Int = 6) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
        timeSource = testScheduler.timeSource,
    )

    private fun headAt(offset: Long, length: Long) =
        Reply.Status(200, mapOf("Upload-Offset" to offset.toString(), "Upload-Length" to length.toString()))

    // ---------------------------------------------------------------- 1. HEAD going backwards

    @Test
    fun aHeadOffsetBelowTheConfirmedOffset_isAnOffsetMismatch_andNothingIsResent() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 600 }
        // Chunk 1 is accepted (confirmed 200), chunk 2 fails, then HEAD claims only 100 bytes exist.
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "200"), apply = true), Reply.Status(500))
        // The stale answer persists through every re-HEAD.
        server.script(HttpMethod.Head, headAt(0, 600), headAt(100, 600), headAt(100, 600), headAt(100, 600), headAt(100, 600))
        val error = assertFailsWith<TusOffsetMismatchException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(600)),
                options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
        }
        assertTrue(error.message!!.contains("already confirmed"), error.message)
        assertEquals(listOf(0L, 200L), server.patchOffsets(), "no PATCH below the confirmed offset")
    }

    @Test
    fun aFlappingHeadOffset_withEveryPatchFailing_terminates() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000; data = testBytes(100) }
        repeat(500) { server.script(HttpMethod.Patch, Reply.Status(500)) }
        server.script(HttpMethod.Head, headAt(100, 1000))
        repeat(250) { server.script(HttpMethod.Head, headAt(50, 1000), headAt(100, 1000)) }
        server.onRequest = { check(server.requests.size < 200) { "runaway loop: ${server.requests.size} requests" } }
        // A stale HEAD is asked again (and the next answer is 100 again), so the call ends when the PATCH
        // failures run out; it must not loop.
        assertFails {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 100, retryOptions = retry(maxRetries = 3)))
        }
        assertTrue(server.requests.size < 20, "terminated: ${server.methods()}")
    }

    // ---------------------------------------------------------------- 2. Retry-After limits

    private suspend fun TestScope.gapAfter(retryAfter: Map<String, String>, maxDelayMillis: Long = 10): Long {
        val server = ScriptedTusServer(clock = { currentTime })
        server.script(HttpMethod.Post, Reply.Status(503, retryAfter))
        server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(checkServerCapabilities = false, retryOptions = RetryOptions(
                initialDelayMillis = 10, maxDelayMillis = maxDelayMillis, jitter = false,
                timeSource = testScheduler.timeSource)))
        val times = server.times(HttpMethod.Post)
        assertEquals(2, times.size)
        return times[1] - times[0]
    }

    @Test
    fun aHugeRetryAfter_isCappedAtOneMinute() = runTest {
        assertEquals(60_000, gapAfter(mapOf("Retry-After" to "99999")))
    }

    @Test
    fun aRetryAfterThatWouldOverflow_saturatesInsteadOfWrapping() = runTest {
        // 9223372036854776 s * 1000 overflows a Long; the second value does not even fit in a Long.
        assertEquals(60_000, gapAfter(mapOf("Retry-After" to "9223372036854776")))
        assertEquals(60_000, gapAfter(mapOf("Retry-After" to "99999999999999999999999")))
        assertEquals(Long.MAX_VALUE, parseRetryAfterForTest("9223372036854776"))
    }

    @Test
    fun anHttpDateRetryAfter_withoutADateHeader_usesTheLocalClock_andIsCapped() = runTest {
        assertEquals(60_000, gapAfter(mapOf("Retry-After" to "Fri, 31 Dec 2099 23:59:59 GMT")))
    }

    @Test
    fun aLargerMaxDelay_raisesTheRetryAfterCap() = runTest {
        assertEquals(90_000, gapAfter(mapOf("Retry-After" to "90"), maxDelayMillis = 120_000))
        assertEquals(120_000, gapAfter(mapOf("Retry-After" to "99999"), maxDelayMillis = 120_000))
    }

    private suspend fun parseRetryAfterForTest(value: String): Long? {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Post, Reply.Status(503, mapOf("Retry-After" to value)))
        val response = server.client().post("https://example.com/files")
        return parseRetryAfterMillis(response)
    }

    // ---------------------------------------------------------------- 3. CancellationException from the engine

    @Test
    fun aCancellationExceptionFromTheEngine_whileTheCallerIsActive_isRetried() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        server.script(HttpMethod.Head, Reply.Throw(CancellationException("engine tore the call down")))
        server.script(HttpMethod.Patch, Reply.Throw(CancellationException("engine tore the call down")))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
        assertEquals(listOf("HEAD", "HEAD", "PATCH", "HEAD", "PATCH"), server.methods())
        assertEquals(100, server.offset)
    }

    @Test
    fun anExhaustedEngineCancellation_surfacesAsANetworkError_notAsCancellation() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        repeat(5) { server.script(HttpMethod.Head, Reply.Throw(CancellationException("engine"))) }
        val error = assertFailsWith<IOException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry(maxRetries = 3)))
        }
        assertIs<CancellationException>(error.cause)
    }

    @Test
    fun cancellingTheCaller_stillStopsImmediately() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        val started = CompletableDeferred<Unit>()
        server.script(HttpMethod.Head, Reply.Custom { _, _ -> started.complete(Unit); awaitCancellation() })
        val job = launch(Dispatchers.Default) {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
        }
        withContext(Dispatchers.Default) { withTimeout(5_000) { started.await(); job.cancelAndJoin() } }
        assertTrue(job.isCancelled)
        assertEquals(1, server.count(HttpMethod.Head), "a cancelled caller must not retry")
    }

    // ---------------------------------------------------------------- 4. onProgress failures

    class ProgressSinkFailed : IOException("progress sink is full")

    @Test
    fun anExceptionFromOnProgress_isRethrownAsIs_andNotRetried() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        var patches = 0
        server.onRequest = { if (it.method == HttpMethod.Patch) patches++ }
        assertFailsWith<ProgressSinkFailed> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(retryOptions = retry()),
                onProgress = { _, _ -> throw ProgressSinkFailed() })
        }
        assertEquals(1, patches, "the caller's own failure is not a network error to retry")
    }

    // ---------------------------------------------------------------- 5. Location edge cases

    private suspend fun create(location: String, createUrl: String = "https://example.com/files"): String =
        ScriptedTusServer(location = location).client().createTus(createUrl, InMemoryTusFile("f", testBytes(10)))

    @Test
    fun userInformationInTheLocation_isRejected() = runTest {
        for (location in listOf("https://example.com@evil.net/u", "https://user:pw@example.com/u", "//example.com@evil.net/u")) {
            assertFailsWith<TusProtocolException>(location) { create(location) }
        }
    }

    @Test
    fun ipv6Hosts_areComparedAsHosts() = runTest {
        val createUrl = "https://[2001:db8::1]:8443/files"
        assertEquals("https://[2001:db8::1]:8443/u", create("/u", createUrl))
        assertEquals("https://[2001:db8::1]:8443/u", create("https://[2001:db8::1]:8443/u", createUrl))
        assertFailsWith<TusProtocolException> { create("https://[2001:db8::2]:8443/u", createUrl) }
    }

    @Test
    fun aTrailingDotHost_isTreatedAsADifferentHost() = runTest {
        assertFailsWith<TusProtocolException> { create("https://example.com./u") }
    }

    @Test
    fun anExplicitDefaultPort_isTheSameOrigin() = runTest {
        assertEquals("https://example.com:443/u", create("https://example.com:443/u"))
        assertEquals("http://example.com:80/u", create("http://example.com:80/u", "http://example.com/files"))
    }

    // ---------------------------------------------------------------- 8. timeout detection

    class PlatformReadTimeoutException : IOException("read timed out")
    class TimeoutConfigurationIOException : IOException("bad timeout configuration")

    @Test
    fun anUnknownIoTimeoutException_shrinksTheChunk_butOtherTimeoutNamedErrorsDoNot() = runTest {
        val shrinking = ScriptedTusServer().apply { uploadLength = 1024 }
        shrinking.script(HttpMethod.Patch, Reply.Throw(PlatformReadTimeoutException()))
        shrinking.client().uploadTus(url, InMemoryTusFile("f", testBytes(1024)),
            options = TusUploadOptions(chunkSize = 1024, minChunkSize = 256, retryOptions = retry()))
        assertEquals(listOf(1024, 512, 512), shrinking.patchSizes())

        val notTimeout = ScriptedTusServer().apply { uploadLength = 1024 }
        notTimeout.script(HttpMethod.Patch, Reply.Throw(TimeoutConfigurationIOException()))
        notTimeout.client().uploadTus(url, InMemoryTusFile("f", testBytes(1024)),
            options = TusUploadOptions(chunkSize = 1024, minChunkSize = 256, retryOptions = retry()))
        assertEquals(listOf(1024, 1024), notTimeout.patchSizes())
    }
}
