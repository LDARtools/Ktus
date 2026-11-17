package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.TusUploadOutcomeUnknownException.Kind
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Eleventh review. */
class Adv11Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"
    private fun kotlinx.coroutines.test.TestScope.fast(maxRetries: Int = 5) = RetryOptions(maxRetries = maxRetries,
        initialDelayMillis = 10, maxDelayMillis = 10, jitter = false, timeSource = testScheduler.timeSource)

    private fun ok(offset: Long, len: Long) = Reply.Status(200, mapOf("Upload-Offset" to "$offset", "Upload-Length" to "$len"))

    /** M1: a stale HEAD after an uncertain final PATCH is "call again later", and calling again completes it. */
    @Test
    fun staleHeadAfterUncertainFinal_isCallAgainLater_andRetryingFixesIt() = runTest {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Head, ok(0, 20), ok(0, 20), ok(0, 20), ok(0, 20), ok(0, 20))
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "10"), apply = true),
            Reply.Throw(kotlinx.io.IOException("reset")))
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", testBytes(20))
        val opts = TusUploadOptions(checkServerCapabilities = false, chunkSize = 10, activityHeartbeatMillis = null,
            retryOptions = fast())
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts)
        }
        assertIs<TusOffsetMismatchException>(e.cause)
        assertEquals(Kind.UNKNOWN, e.kind)
        assertFalse(e.isServerRefusal); assertFalse(e.isUploadGone); assertFalse(e.isNetworkFailure)
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts)
        assertContentEquals(testBytes(20), server.data)
        assertEquals(1, server.count(HttpMethod.Post))
    }

    /** L3: with expectSuccess = true a non-retried 4xx is still a server refusal. */
    @Test
    fun expectSuccessTrue_403AfterUncertainFinal_isAServerRefusal() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Throw(kotlinx.io.IOException("reset")), Reply.Status(403))
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client(expectSuccess = true).uploadTus(url, InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = fast()))
        }
        assertIs<io.ktor.client.plugins.ClientRequestException>(e.cause)
        assertEquals(Kind.SERVER_REFUSAL, e.kind)
    }

    /** L3: the classification follows the retry policy in use: a 4xx the app retries is not a refusal. */
    @Test
    fun theClassificationFollowsTheRetryPolicy() = runTest {
        for ((retryable, expected) in listOf(setOf(503) to Kind.SERVER_REFUSAL, setOf(503, 418) to Kind.UNKNOWN)) {
            val server = ScriptedTusServer().apply { uploadLength = 10 }
            server.script(HttpMethod.Patch, Reply.Throw(kotlinx.io.IOException("reset")))
            repeat(10) { server.script(HttpMethod.Patch, Reply.Status(418)) }
            val e = assertFailsWith<TusUploadOutcomeUnknownException> {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                    activityHeartbeatMillis = null, retryOptions = fast(3).copy(retryableStatusCodes = retryable)))
            }
            assertEquals(expected, e.kind, "retryable=$retryable cause=${e.cause}")
        }
    }

    /** L3: every cause maps to exactly one kind. */
    @Test
    fun everyCauseHasExactlyOneKind() {
        val policy = RetryOptions()
        val cases = mapOf<Throwable?, Kind>(
            null to Kind.UPLOAD_GONE,
            TusUploadExpiredException() to Kind.UPLOAD_GONE,
            TusHttpException(404, "PATCH") to Kind.UPLOAD_GONE,
            TusHttpException(410, "HEAD") to Kind.UPLOAD_GONE,
            TusHttpException(403, "PATCH") to Kind.SERVER_REFUSAL,
            TusHttpException(400, "PATCH") to Kind.SERVER_REFUSAL,
            TusHttpException(401, "PATCH") to Kind.UNKNOWN,
            TusHttpException(409, "PATCH") to Kind.UNKNOWN,
            TusHttpException(423, "PATCH") to Kind.UNKNOWN,
            TusHttpException(429, "PATCH") to Kind.UNKNOWN,
            TusHttpException(500, "PATCH") to Kind.UNKNOWN,
            TusHttpException(503, "PATCH") to Kind.UNKNOWN,
            TusUploadLengthMismatchException(1, 2) to Kind.SERVER_REFUSAL,
            TusOffsetMismatchException("stale") to Kind.UNKNOWN,
            TusProtocolException("malformed") to Kind.UNKNOWN,
            TusLocalFileException("short") to Kind.UNKNOWN,
            kotlinx.io.IOException("offline") to Kind.NETWORK_FAILURE,
            IllegalStateException("closed client") to Kind.UNKNOWN,
        )
        for ((cause, expected) in cases) {
            val e = TusUploadOutcomeUnknownException("u", cause, classifyOutcomeUnknown(cause, policy))
            assertEquals(expected, e.kind, "$cause")
            assertEquals(1, listOf(e.isUploadGone, e.isNetworkFailure, e.isServerRefusal, e.kind == Kind.UNKNOWN).count { it })
        }
        // retryOnUnauthorized = false makes a 401 a refusal.
        assertEquals(Kind.SERVER_REFUSAL, classifyOutcomeUnknown(TusHttpException(401, "PATCH"), RetryOptions(retryOnUnauthorized = false)))
    }

    /** L1: a HEAD redirected within the same origin is not trusted. */
    @Test
    fun aSameOriginRedirectOnHead_isRejected() = runTest {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Head, Reply.Status(302, mapOf("Location" to "/files/other")), ok(10, 10))
        val e = assertFailsWith<TusProtocolException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = fast()))
        }
        assertTrue(e.message!!.contains("answered by"), e.message)
        assertEquals(0, server.data.size)
        assertEquals(0, server.count(HttpMethod.Patch))
    }

    /** L1: a final PATCH redirected within the same origin is an unknown outcome. */
    @Test
    fun aSameOriginRedirectOnTheFinalPatch_isOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 10 }
        server.script(HttpMethod.Patch, Reply.Status(307, mapOf("Location" to "/files/other")),
            Reply.Status(204, mapOf("Upload-Offset" to "10")))
        val client = io.ktor.client.HttpClient(server.engine) {
            install(io.ktor.client.plugins.HttpRedirect) { checkHttpMethod = false }
        }
        assertFailsWith<TusUploadOutcomeUnknownException> {
            client.uploadTus(url, InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = fast()))
        }
    }

    /** L2: once the budget is spent, no further full request is sent: budget + one request timeout at most. */
    @Test
    fun theCallGivesUpWithinTheBudgetPlusOneRequest() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime }, dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(sched)).apply { uploadLength = 10 }
        server.script(HttpMethod.Head, ok(0, 10))
        var firstFailure = -1L
        repeat(500) {
            server.script(HttpMethod.Patch, Reply.Custom { _, _ ->
                if (firstFailure < 0) firstFailure = sched.currentTime
                if (sched.currentTime - firstFailure >= 590_000) delay(300_000)
                throw kotlinx.io.IOException("timeout") })
            server.script(HttpMethod.Head, Reply.Custom { _, _ ->
                if (firstFailure >= 0 && sched.currentTime - firstFailure >= 590_000) delay(300_000)
                respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("0"), "Upload-Length" to listOf("10"))) })
        }
        runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 10_000,
                    jitter = false, timeSource = sched.timeSource)))
        }
        val gaveUpAfter = sched.currentTime - firstFailure
        assertTrue(gaveUpAfter <= 600_000 + 300_000 + 10_000, "budget + one request: gave up after $gaveUpAfter ms")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.gaveUpAfter(headSlowFrom: Long, patchSlowFrom: Long): Long {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime },
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(sched)).apply { uploadLength = 10 }
        server.script(HttpMethod.Head, ok(0, 10))
        repeat(200) {
            server.script(HttpMethod.Patch, Reply.Custom { _, _ ->
                if (sched.currentTime >= patchSlowFrom) delay(300_000)
                throw kotlinx.io.IOException("fails") })
            server.script(HttpMethod.Head, Reply.Custom { _, _ ->
                if (sched.currentTime >= headSlowFrom) delay(300_000)
                respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("0"), "Upload-Length" to listOf("10"))) })
        }
        val start = sched.currentTime
        runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 100_000, initialDelayMillis = 10_000,
                    maxDelayMillis = 10_000, jitter = false, timeSource = sched.timeSource)))
        }
        return sched.currentTime - start
    }

    /** L2: the backoff wait ends exactly at the end of the budget: the recovery HEAD is not sent. */
    @Test
    fun noHeadIsSentOnceTheWaitUsedUpTheBudget() = runTest {
        val t = gaveUpAfter(headSlowFrom = 600_000, patchSlowFrom = 600_000)
        assertTrue(t <= 601_000, "gave up after $t ms; a HEAD after the budget would add 300 s")
    }

    /** L2: a recovery HEAD that runs past the end of the budget is not followed by another PATCH. */
    @Test
    fun noPatchIsSentAfterAHeadThatOutlivedTheBudget() = runTest {
        val t = gaveUpAfter(headSlowFrom = 590_000, patchSlowFrom = 600_000)
        assertTrue(t <= 600_000 + 300_000 + 1_000, "gave up after $t ms; a PATCH after the budget would add 300 s")
    }
}
