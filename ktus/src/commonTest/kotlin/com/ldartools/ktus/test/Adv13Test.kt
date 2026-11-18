package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Adv13Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    /** One transient OPTIONS failure, then a healthy but slow first PATCH (11 min) that fails at the end. */
    private suspend fun TestScope.slowFirstPatch(optionsBlip: Boolean): Result<Unit> {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime }, dispatcher = StandardTestDispatcher(sched))
        if (optionsBlip) server.script(HttpMethod.Options, Reply.Status(503))
        server.script(HttpMethod.Patch, Reply.Custom { _, _ ->
            delay(11 * 60_000L)
            throw kotlinx.io.IOException("connection reset after 11 minutes of healthy sending")
        })
        val r = runCatching {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(20)), options = TusUploadOptions(
                chunkSize = 10, activityHeartbeatMillis = null,
                retryOptions = RetryOptions(jitter = false, timeSource = sched.timeSource)))
        }
        return r
    }

    @Test
    fun control_noOptionsBlip_slowPatchFailureIsRetried() = runTest {
        assertTrue(slowFirstPatch(optionsBlip = false).isSuccess)
    }

    /** A single 503 on OPTIONS must not leave a budget running that starves a PATCH failure 11 minutes later. */
    @Test
    fun optionsBlip_starvesTheUploadOfAnyRetry() = runTest {
        assertTrue(slowFirstPatch(optionsBlip = true).isSuccess, "a 503 on OPTIONS 11 minutes earlier must not cancel the PATCH retry")
    }

    /** Same within uploadTus: one transient failure of the first HEAD starts the clock. */
    @Test
    fun headBlip_starvesTheUploadOfAnyRetry() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime }, dispatcher = StandardTestDispatcher(sched)).apply { uploadLength = 20 }
        server.script(HttpMethod.Head, Reply.Status(503))
        server.script(HttpMethod.Patch, Reply.Custom { _, _ ->
            delay(11 * 60_000L)
            throw kotlinx.io.IOException("reset")
        })
        val r = runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(20)), options = TusUploadOptions(
                chunkSize = 10, activityHeartbeatMillis = null,
                retryOptions = RetryOptions(jitter = false, timeSource = sched.timeSource)))
        }
        assertTrue(r.isSuccess)
    }

    /** Doc claim: "once it is spent Ktus sends no further request" (except one last HEAD on a final PATCH). */
    @Test
    fun aHeadRetryIsSentAfterTheBudgetIsSpent() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime })
        repeat(100) { server.script(HttpMethod.Head, Reply.Status(503)) }
        runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1000, initialDelayMillis = 7_000,
                    maxDelayMillis = 7_000, jitter = false, maxElapsedMillis = 10_000, timeSource = sched.timeSource)))
        }
        assertTrue(server.times(HttpMethod.Head).none { it >= 10_000 }, "HEAD sent after the budget: ${server.times(HttpMethod.Head)}")
    }

    /**
     * Documented trade-off: a followed redirect to the same path that only adds a query parameter is treated as
     * the same tus resource (client plugins add parameters the same way), so its answer is accepted.
     */
    @Test
    fun redirectToSamePathWithExtraParam_isAccepted_asDocumented() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        server.script(HttpMethod.Head, Reply.Status(302, mapOf("Location" to "/files/abc?upload=someone-else")),
            Reply.Status(200, mapOf("Upload-Offset" to "10", "Upload-Length" to "10")))
        val r = runCatching {
            server.client(followRedirects = true).uploadTus(url, InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null))
        }
        assertTrue(r.isSuccess, "$r")
    }

    /** (c) Continuous failures with no success give up within the budget. */
    @Test
    fun continuousOptionsFailures_giveUpWithinTheBudget() = runTest {
        val server = ScriptedTusServer(clock = { testScheduler.currentTime })
        repeat(100) { server.script(HttpMethod.Options, Reply.Status(503)) }
        assertFailsWith<TusHttpException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1_000, initialDelayMillis = 1_000,
                    maxDelayMillis = 1_000, jitter = false, maxElapsedMillis = 10_000, timeSource = testScheduler.timeSource)))
        }
        assertTrue(testScheduler.currentTime <= 10_000, "${testScheduler.currentTime}")
        assertEquals(0, server.count(HttpMethod.Post))
    }

    /** A success between phases (OPTIONS answered) ends the run: the POST's failures get a fresh budget. */
    @Test
    fun aSuccessBetweenPhases_restartsTheBudget() = runTest {
        val server = ScriptedTusServer(clock = { testScheduler.currentTime })
        repeat(6) { server.script(HttpMethod.Options, Reply.Status(503)) }
        repeat(100) { server.script(HttpMethod.Post, Reply.Status(503)) }
        assertFailsWith<TusHttpException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1_000, initialDelayMillis = 1_000,
                    maxDelayMillis = 1_000, jitter = false, maxElapsedMillis = 10_000, timeSource = testScheduler.timeSource)))
        }
        val firstPost = server.times(HttpMethod.Post).first()
        assertEquals(6_000, firstPost)
        assertEquals(10_000, testScheduler.currentTime - firstPost, "a full budget from the POST's first failure")
    }

    /** (d) A recovery HEAD that keeps answering without progress does not reset the run: it still ends. */
    @Test
    fun aRecoveryHeadWithoutProgress_doesNotResetTheBudget() = runTest {
        val server = ScriptedTusServer(clock = { testScheduler.currentTime }).apply { uploadLength = 20 }
        repeat(1_000) { server.script(HttpMethod.Patch, Reply.Status(503)) }
        assertFailsWith<TusHttpException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(20)), options = TusUploadOptions(chunkSize = 10,
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 1_000, initialDelayMillis = 1_000,
                    maxDelayMillis = 1_000, jitter = false, maxElapsedMillis = 10_000, timeSource = testScheduler.timeSource)))
        }
        assertTrue(testScheduler.currentTime <= 10_000, "${testScheduler.currentTime}")
        assertTrue(server.count(HttpMethod.Head) > 3, "the HEADs answered, yet the run was not reset")
    }

    /** L3: a plugin that appends a second value to a parameter the target already has is not a redirect. */
    @Test
    fun aPluginAppendingAValueToAnExistingParameter_isNotARedirect() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        val addValue = io.ktor.client.plugins.api.createClientPlugin("AddScope") {
            onRequest { request, _ -> request.url.parameters.append("scope", "extra") }
        }
        val client = io.ktor.client.HttpClient(server.engine) { followRedirects = false; install(addValue) }
        client.uploadTus("$url?scope=upload", InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(activityHeartbeatMillis = null))
        assertEquals(10, server.offset)
    }
}
