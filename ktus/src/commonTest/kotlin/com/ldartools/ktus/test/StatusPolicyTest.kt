package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.*

/**
 * R1/R2/S2: the status policy, applied identically to OPTIONS, POST, HEAD, PATCH and DELETE, with the
 * client's `expectSuccess` both off (Chateau's default) and on.
 */
class StatusPolicyTest {

    private val methods = listOf(HttpMethod.Options, HttpMethod.Post, HttpMethod.Head, HttpMethod.Patch, HttpMethod.Delete)
    private val fileSize = 300

    private fun TestScope.fastRetry(maxRetries: Int = 5) = RetryOptions(
        maxRetries = maxRetries,
        initialDelayMillis = 10,
        maxDelayMillis = 100,
        timeSource = testScheduler.timeSource,
    )

    /** Runs the operation that sends [method] and lets the rest of the flow succeed. */
    private suspend fun runOperation(client: HttpClient, method: HttpMethod, options: TusUploadOptions, file: ITusFile) {
        when (method) {
            HttpMethod.Options, HttpMethod.Post ->
                client.createTusUpload("https://example.com/files", file, options = options)
            HttpMethod.Head, HttpMethod.Patch ->
                client.uploadTus("https://example.com/files/abc", file, options = options)
            HttpMethod.Delete ->
                client.terminateTus("https://example.com/files/abc", options = options)
            else -> error("unexpected $method")
        }
    }

    private fun server(): ScriptedTusServer = ScriptedTusServer().apply { uploadLength = fileSize.toLong() }

    // ---------------------------------------------------------------- retryable statuses

    @Test
    fun retryableStatuses_areRetried_onEveryMethod_inBothExpectSuccessModes() = runTest {
        for (expectSuccess in listOf(false, true)) {
            for (method in methods) {
                for (status in listOf(408, 423, 429, 500, 502, 503, 504)) {
                    val server = server()
                    server.script(method, Reply.Status(status))
                    val file = InMemoryTusFile("f.bin", testBytes(fileSize))
                    val client = server.client(expectSuccess)

                    runOperation(client, method, TusUploadOptions(retryOptions = fastRetry()), file)

                    assertEquals(2, server.count(method), "$method $status expectSuccess=$expectSuccess should be retried once")
                    if (method == HttpMethod.Patch) {
                        assertContentEquals(file.readAll(), server.data, "$status: bytes must be complete")
                    }
                    client.close()
                }
            }
        }
    }

    @Test
    fun unauthorized_isRetried_onEveryMethod_soTheBlockCanRefreshTheToken() = runTest {
        for (expectSuccess in listOf(false, true)) {
            for (method in methods) {
                val server = server()
                server.script(method, Reply.Status(401))
                val tokens = mutableListOf<String>()
                var token = 0
                server.onRequest = { request ->
                    if (request.method == method) tokens += request.headers["Authorization"] ?: "none"
                }
                val file = InMemoryTusFile("f.bin", testBytes(fileSize))
                val client = server.client(expectSuccess)
                val options = TusUploadOptions(retryOptions = fastRetry())
                when (method) {
                    HttpMethod.Options, HttpMethod.Post -> client.createTusUpload("https://example.com/files", file, options = options) {
                        headers.append("Authorization", "Bearer ${token++}")
                    }
                    HttpMethod.Head, HttpMethod.Patch -> client.uploadTus("https://example.com/files/abc", file, options = options) {
                        headers.append("Authorization", "Bearer ${token++}")
                    }
                    else -> client.terminateTus("https://example.com/files/abc", options = options) {
                        headers.append("Authorization", "Bearer ${token++}")
                    }
                }
                assertEquals(2, tokens.size, "$method should be retried after 401 (expectSuccess=$expectSuccess)")
                assertNotEquals(tokens[0], tokens[1], "the block must run again so it can supply a new token")
                client.close()
            }
        }
    }

    @Test
    fun unauthorized_isFatal_whenRetryOnUnauthorizedIsOff() = runTest {
        val server = server()
        server.script(HttpMethod.Head, Reply.Status(401))
        val error = assertFailsWith<TusHttpException> {
            server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(fileSize)),
                options = TusUploadOptions(retryOptions = fastRetry().copy(retryOnUnauthorized = false)))
        }
        assertEquals(401, error.statusCode)
        assertEquals(1, server.count(HttpMethod.Head))
    }

    // ---------------------------------------------------------------- 404 / 410

    @Test
    fun notFoundAndGone_onHeadAndPatch_meanTheUploadExpired() = runTest {
        for (expectSuccess in listOf(false, true)) {
            for (method in listOf(HttpMethod.Head, HttpMethod.Patch)) {
                for (status in listOf(404, 410)) {
                    val server = server()
                    server.script(method, Reply.Status(status))
                    val client = server.client(expectSuccess)
                    assertFailsWith<TusUploadExpiredException>("$method $status expectSuccess=$expectSuccess") {
                        client.uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(fileSize)),
                            options = TusUploadOptions(retryOptions = fastRetry()))
                    }
                    assertEquals(1, server.count(method), "$method $status must not be retried")
                }
            }
        }
    }

    @Test
    fun notFoundAndGone_onPostAndOptions_areAnHttpError_notAnExpiredUpload() = runTest {
        for (expectSuccess in listOf(false, true)) {
            for (method in listOf(HttpMethod.Options, HttpMethod.Post)) {
                for (status in listOf(404, 410)) {
                    val server = server()
                    server.script(method, Reply.Status(status))
                    val client = server.client(expectSuccess)
                    val error = assertFailsWith<TusProtocolException>("$method $status expectSuccess=$expectSuccess") {
                        client.createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(fileSize)),
                            options = TusUploadOptions(retryOptions = fastRetry()))
                    }
                    assertFalse(error is TusUploadExpiredException, "$method $status must not claim an upload expired")
                    val http = assertIs<TusHttpException>(error)
                    assertEquals(status, http.statusCode)
                    assertEquals(method.value, http.method)
                    assertEquals(1, server.count(method))
                }
            }
        }
    }

    @Test
    fun notFoundAndGone_onDelete_countAsTerminated() = runTest {
        for (expectSuccess in listOf(false, true)) {
            for (status in listOf(204, 404, 410)) {
                val server = server()
                server.script(HttpMethod.Delete, Reply.Status(status))
                server.client(expectSuccess).terminateTus("https://example.com/files/abc",
                    options = TusUploadOptions(retryOptions = fastRetry()))
                assertEquals(1, server.count(HttpMethod.Delete))
                assertEquals("1.0.0", server.requests.single().headers["Tus-Resumable"])
            }
        }
    }

    // ---------------------------------------------------------------- fatal statuses

    @Test
    fun otherClientErrors_areFatal_withTheStatusInTheException() = runTest {
        for (method in methods) {
            for (status in listOf(400, 403, 405, 413, 415, 501, 505)) {
                val server = server()
                server.script(method, Reply.Status(status, body = "nope"))
                val error = assertFailsWith<TusHttpException>("$method $status") {
                    runOperation(server.client(expectSuccess = false), method,
                        TusUploadOptions(retryOptions = fastRetry()), InMemoryTusFile("f", testBytes(fileSize)))
                }
                assertEquals(status, error.statusCode, "$method $status")
                assertEquals(method.value, error.method)
                if (method != HttpMethod.Head) assertEquals("nope", error.body)
                assertEquals(1, server.count(method), "$method $status must not be retried")
            }
        }
    }

    @Test
    fun otherClientErrors_withExpectSuccess_keepKtorsException_forCompatibility() = runTest {
        for (method in methods) {
            val server = server()
            server.script(method, Reply.Status(403))
            val error = assertFailsWith<ResponseException>("$method") {
                runOperation(server.client(expectSuccess = true), method,
                    TusUploadOptions(retryOptions = fastRetry()), InMemoryTusFile("f", testBytes(fileSize)))
            }
            assertIs<ClientRequestException>(error)
            assertEquals(403, error.response.status.value)
            assertEquals(1, server.count(method))
        }
    }

    @Test
    fun conflictOutsidePatch_isFatal() = runTest {
        val server = server()
        server.script(HttpMethod.Post, Reply.Status(409))
        val error = assertFailsWith<TusHttpException> {
            server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(fileSize)),
                options = TusUploadOptions(retryOptions = fastRetry()))
        }
        assertEquals(409, error.statusCode)
    }

    // ---------------------------------------------------------------- network errors

    @Test
    fun networkErrorsAndTimeouts_areRetried_onEveryMethod() = runTest {
        for (method in methods) {
            for (error in listOf<Throwable>(IOException("reset"), HttpRequestTimeoutException("u", 1, null))) {
                val server = server()
                server.script(method, Reply.Throw(error))
                runOperation(server.client(), method, TusUploadOptions(retryOptions = fastRetry()),
                    InMemoryTusFile("f", testBytes(fileSize)))
                assertEquals(2, server.count(method), "$method after ${error::class.simpleName}")
            }
        }
    }

    @Test
    fun nonNetworkExceptions_areNotRetried() = runTest {
        val server = server()
        server.script(HttpMethod.Head, Reply.Throw(IllegalStateException("bug")))
        assertFailsWith<IllegalStateException> {
            server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(fileSize)),
                options = TusUploadOptions(retryOptions = fastRetry()))
        }
        assertEquals(1, server.count(HttpMethod.Head))
    }

    @Test
    fun exhaustedNetworkRetries_rethrowTheNetworkError() = runTest {
        val server = server()
        repeat(10) { server.script(HttpMethod.Patch, Reply.Throw(IOException("offline $it"))) }
        val error = assertFailsWith<IOException> {
            server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(fileSize)),
                options = TusUploadOptions(retryOptions = fastRetry(maxRetries = 3)))
        }
        assertFalse(error is TusProtocolException, "an exhausted network failure must stay a network error, was $error")
        assertEquals(3, server.count(HttpMethod.Patch))
    }

    @Test
    fun exhaustedServerErrors_withExpectSuccess_areTusHttpException_withKtorsExceptionAsCause() = runTest {
        val server = server()
        repeat(10) { server.script(HttpMethod.Post, Reply.Status(503)) }
        val error = assertFailsWith<TusHttpException> {
            server.client(expectSuccess = true).createTusUpload("https://example.com/files",
                InMemoryTusFile("f", testBytes(fileSize)), options = TusUploadOptions(retryOptions = fastRetry(maxRetries = 3)))
        }
        assertIs<ServerResponseException>(error.cause)
        assertEquals(3, server.count(HttpMethod.Post))
    }

    @Test
    fun maxRetries_isTheNumberOfAttemptsPerOperation() = runTest {
        for (method in methods) {
            val server = server()
            repeat(20) { server.script(method, Reply.Status(503)) }
            val error = assertFailsWith<TusHttpException>("$method") {
                runOperation(server.client(), method, TusUploadOptions(retryOptions = fastRetry(maxRetries = 4)),
                    InMemoryTusFile("f", testBytes(fileSize)))
            }
            assertEquals(503, error.statusCode)
            assertEquals(4, server.count(method), "$method: maxRetries = 4 means 4 attempts")
        }
    }

    // ---------------------------------------------------------------- redirects (S2)

    @Test
    fun redirects_areRejected_andNotFollowed() = runTest {
        for (expectSuccess in listOf(false, true)) {
            for (method in methods) {
                for (status in listOf(301, 302, 307, 308)) {
                    val server = server()
                    server.script(method, Reply.Status(status, mapOf("Location" to "https://evil.example.net/x")))
                    val error = assertFailsWith<TusProtocolException>("$method $status expectSuccess=$expectSuccess") {
                        runOperation(server.client(expectSuccess, followRedirects = false), method,
                            TusUploadOptions(retryOptions = fastRetry()), InMemoryTusFile("f", testBytes(fileSize)))
                    }
                    assertTrue(error.message!!.contains("redirect"), error.message)
                    assertFalse(server.requests.any { it.url.contains("evil") })
                }
            }
        }
    }

    @Test
    fun aRedirectTheClientFollowedToAnotherHost_isRejected() = runTest {
        // Ktor follows 3xx for HEAD when followRedirects = true (its default). The answer then comes from
        // another origin; Ktus must not trust its Upload-Offset.
        val server = server()
        server.script(HttpMethod.Head, Reply.Status(302, mapOf("Location" to "https://evil.example.net/files/abc")))
        val error = assertFailsWith<TusProtocolException> {
            server.client(followRedirects = true).uploadTus("https://example.com/files/abc",
                InMemoryTusFile("f", testBytes(fileSize)), options = TusUploadOptions(retryOptions = fastRetry()))
        }
        assertTrue(error.message!!.contains("followRedirects"), error.message)
        assertEquals(0, server.count(HttpMethod.Patch))
    }

    // ---------------------------------------------------------------- Retry-After

    @Test
    fun retryAfterSeconds_isHonoured_on503And429() = runTest {
        for (status in listOf(503, 429)) {
            val server = ScriptedTusServer(clock = { currentTime })
            server.script(HttpMethod.Post, Reply.Status(status, mapOf("Retry-After" to "7")))
            server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(checkServerCapabilities = false,
                    retryOptions = fastRetry().copy(jitter = false)))
            val times = server.times(HttpMethod.Post)
            assertEquals(2, times.size)
            assertEquals(7_000, times[1] - times[0], "$status Retry-After: 7 must delay the retry by 7 s")
        }
    }

    @Test
    fun retryAfterHttpDate_isHonoured_relativeToTheResponseDate() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        server.script(HttpMethod.Head, Reply.Status(503, mapOf(
            "Date" to "Wed, 21 Oct 2026 07:28:00 GMT",
            "Retry-After" to "Wed, 21 Oct 2026 07:28:12 GMT",
        )))
        server.uploadLength = 10
        server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(retryOptions = fastRetry().copy(jitter = false)))
        val times = server.times(HttpMethod.Head)
        assertEquals(12_000, times[1] - times[0])
    }

    @Test
    fun retryAfterHttpDate_inThePast_meansNoExtraWait() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        server.script(HttpMethod.Post, Reply.Status(503, mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")))
        server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(checkServerCapabilities = false, retryOptions = fastRetry().copy(jitter = false)))
        val times = server.times(HttpMethod.Post)
        assertEquals(10, times[1] - times[0], "only the normal backoff (10 ms) applies")
    }

    @Test
    fun retryAfter_isIgnored_whenHonorRetryAfterIsOff() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        server.script(HttpMethod.Post, Reply.Status(503, mapOf("Retry-After" to "7")))
        server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(checkServerCapabilities = false,
                retryOptions = fastRetry().copy(jitter = false, honorRetryAfter = false)))
        val times = server.times(HttpMethod.Post)
        assertEquals(10, times[1] - times[0])
    }

    @Test
    fun retryAfter_isCappedByTheRemainingBudget() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        repeat(5) { server.script(HttpMethod.Post, Reply.Status(503, mapOf("Retry-After" to "3600"))) }
        val start = currentTime
        assertFailsWith<TusHttpException> {
            server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(checkServerCapabilities = false,
                    retryOptions = fastRetry(maxRetries = 100).copy(maxElapsedMillis = 5_000)))
        }
        assertEquals(5_000, currentTime - start, "the hour-long Retry-After is cut to the 5 s budget")
        assertEquals(2, server.count(HttpMethod.Post))
    }

    // ---------------------------------------------------------------- budget and backoff

    @Test
    fun theElapsedBudget_stopsRetriesBeforeMaxRetries() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        repeat(1000) { server.script(HttpMethod.Post, Reply.Status(503)) }
        val start = currentTime
        val error = assertFailsWith<TusHttpException> {
            server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(checkServerCapabilities = false, retryOptions = RetryOptions(
                    maxRetries = 1000, initialDelayMillis = 100, maxDelayMillis = 1_000,
                    maxElapsedMillis = 20_000, timeSource = testScheduler.timeSource)))
        }
        assertEquals(503, error.statusCode)
        val elapsed = currentTime - start
        assertTrue(elapsed <= 20_000, "must stop within the 20 s budget, took $elapsed ms")
        assertTrue(server.count(HttpMethod.Post) in 3..999, "budget, not maxRetries, ended it: ${server.count(HttpMethod.Post)}")
    }

    @Test
    fun theBudget_spansHeadAndPatch_andResetsOnProgress() = runTest {
        // Every PATCH stores its bytes and then fails, so each HEAD shows progress: the budget must keep
        // restarting and the upload must complete even though it takes longer than the budget overall.
        val server = ScriptedTusServer(clock = { currentTime })
        server.uploadLength = 1000
        repeat(10) { server.script(HttpMethod.Patch, Reply.Status(503, apply = true)) }
        val file = InMemoryTusFile("f", testBytes(1000))
        server.client().uploadTus("https://example.com/files/abc", file, options = TusUploadOptions(
            chunkSize = 100, retryOptions = RetryOptions(maxRetries = 2, initialDelayMillis = 400, maxDelayMillis = 400,
                maxElapsedMillis = 1_000, jitter = false, timeSource = testScheduler.timeSource)))
        assertContentEquals(file.readAll(), server.data)
        assertTrue(currentTime >= 4_000, "took ${currentTime} ms of virtual time, more than the 1 s budget")
    }

    @Test
    fun fullJitter_delaysStayWithinTheirBounds() {
        val options = RetryOptions(initialDelayMillis = 500, maxDelayMillis = 30_000, factor = 2.0, random = Random(1234))
        val backoff = Backoff(options)
        for (failure in 1..12) {
            val bound = minOf(30_000L, 500L shl (failure - 1))
            assertEquals(bound, delayBoundMillis(options, failure))
            val draws = List(2_000) { backoff.nextDelayMillis(failure) }
            assertTrue(draws.all { it in 0..bound }, "failure $failure: draws outside [0, $bound]")
            assertTrue(draws.min() < bound / 10, "failure $failure: full jitter must reach near 0, min ${draws.min()}")
            assertTrue(draws.max() > bound * 9 / 10, "failure $failure: full jitter must reach near the bound, max ${draws.max()}")
        }
    }

    @Test
    fun theDelaysBetweenAttempts_areTheJitteredDelays() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        repeat(5) { server.script(HttpMethod.Post, Reply.Status(500)) }
        server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(checkServerCapabilities = false, retryOptions = RetryOptions(
                maxRetries = 10, initialDelayMillis = 1_000, maxDelayMillis = 6_000, random = Random(99),
                timeSource = testScheduler.timeSource)))
        val times = server.times(HttpMethod.Post)
        val gaps = times.zipWithNext { a, b -> b - a }
        val expectedRandom = Random(99)
        val expected = (1..5).map { failure ->
            expectedRandom.nextLong(0, minOf(6_000L, 1_000L shl (failure - 1)) + 1)
        }
        assertEquals(expected, gaps)
    }

    @Test
    fun withoutJitter_delaysGrowExponentiallyToTheCap() = runTest {
        val server = ScriptedTusServer(clock = { currentTime })
        repeat(6) { server.script(HttpMethod.Post, Reply.Status(502)) }
        server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(checkServerCapabilities = false, retryOptions = RetryOptions(
                maxRetries = 10, initialDelayMillis = 100, maxDelayMillis = 1_000, jitter = false,
                timeSource = testScheduler.timeSource)))
        val gaps = server.times(HttpMethod.Post).zipWithNext { a, b -> b - a }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_000L, 1_000L), gaps)
    }
}

suspend fun ITusFile.readAll(): ByteArray = readSection(0, size).readRemaining().readByteArray()
