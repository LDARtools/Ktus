package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.TusUploadOutcomeUnknownException.Kind
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Twelfth review. */
class Adv12Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    private fun ok(offset: Long, len: Long) = Reply.Status(200, mapOf("Upload-Offset" to "$offset", "Upload-Length" to "$len"))

    // ---------------------------------------------------------------- answered URL vs client plugins

    /** A `defaultRequest` that adds a query parameter (api-version, tenant) is not a redirect. */
    @Test
    fun defaultRequestQueryParam_isNotARedirect() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        val client = HttpClient(server.engine) {
            followRedirects = false
            defaultRequest { url { parameters.append("api-version", "2") } }
        }
        client.uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(activityHeartbeatMillis = null))
        assertContentEquals(testBytes(10), server.data)
    }

    /** A client plugin that appends a query parameter after Ktus built the request is not a redirect. */
    @Test
    fun aPluginAddedQueryParam_isNotARedirect() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        val addVersion = io.ktor.client.plugins.api.createClientPlugin("AddApiVersion") {
            onRequest { request, _ -> request.url.parameters.append("api-version", "2") }
        }
        val client = HttpClient(server.engine) {
            followRedirects = false
            install(addVersion)
        }
        client.uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(activityHeartbeatMillis = null))
        assertContentEquals(testBytes(10), server.data)
        assertTrue(server.requests.all { "api-version=2" in it.url }, "the plugin did add it: ${server.requests.map { it.url }}")
    }

    @Test
    fun defaultRequestBaseUrlOnly_isNotARedirect() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        val client = HttpClient(server.engine) {
            followRedirects = false
            defaultRequest { url("https://example.com/api/") }
        }
        client.uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(activityHeartbeatMillis = null))
        assertContentEquals(testBytes(10), server.data)
    }

    @Test
    fun aRedirectToTheSamePathWithADifferentQueryValue_isStillRejected() = runTest {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Head, Reply.Status(302, mapOf("Location" to "/files/abc?sig=other")), ok(10, 10))
        assertFailsWith<TusProtocolException> {
            server.client().uploadTus("$url?sig=mine", InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null))
        }
    }

    // ---------------------------------------------------------------- M1 last resolution HEAD

    /** Out of attempts after a 504 whose bytes were stored: one last HEAD shows the upload complete. */
    @Test
    fun finalPatch504_lastAttempt_oneHeadResolvesItAsSuccess() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Status(504, apply = true))
        val confirmed = SyncList<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
            activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1, initialDelayMillis = 10,
                maxDelayMillis = 10, jitter = false, timeSource = testScheduler.timeSource)),
            onConfirmed = { o, _ -> confirmed += o })
        assertEquals(listOf("HEAD", "PATCH", "HEAD"), server.methods())
        assertEquals(server.times(HttpMethod.Patch).single(), server.times(HttpMethod.Head).last(), "no wait before it")
        assertEquals(listOf(10L), confirmed.snapshot())
    }

    /** minDelayMillis just below the budget: the retry is impossible, but the last HEAD still resolves it. */
    @Test
    fun minDelayCloseToTheBudget_stillResolvesTheFinalPatch() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Status(504, apply = true))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
            activityHeartbeatMillis = null, retryOptions = RetryOptions(initialDelayMillis = 10, maxDelayMillis = 10,
                jitter = false, timeSource = testScheduler.timeSource, maxElapsedMillis = 1000, minDelayMillis = 999)))
        assertEquals(10, server.offset)
    }

    /** minDelayMillis equal to the budget leaves no room for any retry: rejected. */
    @Test
    fun minDelayEqualToTheBudget_isRejected() {
        assertFailsWith<IllegalArgumentException> { RetryOptions(maxElapsedMillis = 1000, minDelayMillis = 1000) }
        RetryOptions(maxElapsedMillis = 0, minDelayMillis = 0)
    }

    /** Chateau-like policy (minDelay 240 s, 10-min budget): three 504s, the last one stored. */
    @Test
    fun chateauLikeMinDelay_finalPatchGatewayTimeouts_lastOneLands_isASuccess() = runTest {
        val server = ScriptedTusServer(clock = { testScheduler.currentTime }).apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Status(504), Reply.Status(504), Reply.Status(504, apply = true))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
            activityHeartbeatMillis = null, retryOptions = RetryOptions(minDelayMillis = 240_000,
                timeSource = testScheduler.timeSource)))
        assertEquals(10, server.offset)
        assertEquals("HEAD", server.methods().last())
    }

    /** The last HEAD shows the bytes are missing: still an unknown outcome, with the original cause. */
    @Test
    fun aLastHeadBelowTheTotal_isStillOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Status(504))
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1, jitter = false,
                    timeSource = testScheduler.timeSource)))
        }
        assertEquals(504, (e.cause as TusHttpException).statusCode)
        assertEquals(listOf("HEAD", "PATCH", "HEAD"), server.methods())
    }

    /** The last HEAD itself fails (network): the reported failure is the PATCH's, unchanged. */
    @Test
    fun aFailingLastHead_doesNotChangeWhatIsReported() = runTest {
        for (headFailure in listOf<Reply>(Reply.Throw(kotlinx.io.IOException("offline")), Reply.Status(503), Reply.Status(403))) {
            val server = ScriptedTusServer().apply { uploadLength = 10 }
            server.script(HttpMethod.Patch, Reply.Throw(kotlinx.io.IOException("reset")))
            server.script(HttpMethod.Head, ok(0, 10), headFailure)
            val e = assertFailsWith<TusUploadOutcomeUnknownException>("$headFailure") {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                    activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1, jitter = false,
                        timeSource = testScheduler.timeSource)))
            }
            assertEquals(Kind.NETWORK_FAILURE, e.kind, "$headFailure")
            assertEquals("reset", e.cause?.message)
            assertEquals(2, server.count(HttpMethod.Head), "exactly one last HEAD, no retries")
        }
    }

    /** A 404 on the last HEAD means the upload is gone. */
    @Test
    fun aLastHead404_isUploadGone() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Throw(kotlinx.io.IOException("reset")))
        server.script(HttpMethod.Head, ok(0, 10), Reply.Status(404))
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1, jitter = false,
                    timeSource = testScheduler.timeSource)))
        }
        assertEquals(Kind.UPLOAD_GONE, e.kind)
    }

    /** No last HEAD when no final PATCH is unresolved. */
    @Test
    fun noLastHead_whenTheFailingPatchIsNotFinal() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 20 }
        server.script(HttpMethod.Patch, Reply.Status(504))
        assertFailsWith<TusHttpException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(20)), options = TusUploadOptions(chunkSize = 10,
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1, jitter = false,
                    timeSource = testScheduler.timeSource)))
        }
        assertEquals(listOf("HEAD", "PATCH"), server.methods())
    }

    // ---------------------------------------------------------------- L1 one budget per call

    /** OPTIONS, POST and the upload share one elapsed budget. */
    @Test
    fun oneBudgetForTheWholeCall() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        repeat(6) { server.script(HttpMethod.Options, Reply.Status(503)) }
        repeat(50) { server.script(HttpMethod.Post, Reply.Status(503)) }
        assertFailsWith<TusHttpException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1_000, initialDelayMillis = 1_000,
                    maxDelayMillis = 1_000, jitter = false, maxElapsedMillis = 10_000, timeSource = testScheduler.timeSource)))
        }
        assertTrue(currentTime <= 10_000, "one 10 s budget for OPTIONS and POST together, took $currentTime ms")
        assertTrue(server.count(HttpMethod.Post) < 10, "the POST did not get a fresh budget: ${server.count(HttpMethod.Post)}")
    }
}
