package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

/**
 * A tusdotnet-like server: it removes an upload once it is complete, so a HEAD afterwards answers 404.
 * [loseFinalResponses] drops the connection after the last chunk has been stored.
 */
private class CompletingServer(val size: Long) {
    var nextId = 0
    val uploads = mutableMapOf<String, ByteArray>()
    val processed = mutableListOf<ByteArray>()
    var loseFinalResponses = 1
    val methods = mutableListOf<String>()

    val engine = MockEngine { request ->
        methods += request.method.value
        val id = request.url.encodedPath.substringAfterLast('/')
        when (request.method) {
            HttpMethod.Options -> respond("", HttpStatusCode.NoContent, headersOf("Tus-Resumable", "1.0.0"))
            HttpMethod.Post -> {
                val newId = "u${nextId++}"
                uploads[newId] = ByteArray(0)
                respond("", HttpStatusCode.Created, headersOf("Location", "/files/$newId"))
            }
            HttpMethod.Head -> {
                val data = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                respond("", HttpStatusCode.OK, headersOf(
                    "Upload-Offset" to listOf(data.size.toString()), "Upload-Length" to listOf(size.toString())))
            }
            HttpMethod.Patch -> {
                val data = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                val body = ScriptedTusServer.readBody(request)
                if (request.headers["Upload-Offset"]?.toLong() != data.size.toLong()) {
                    return@MockEngine respond("", HttpStatusCode.Conflict)
                }
                val now = data + body
                uploads[id] = now
                if (now.size.toLong() == size) {
                    processed += now
                    uploads.remove(id)
                    if (loseFinalResponses-- > 0) throw IOException("connection reset after the final chunk was stored")
                }
                respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", now.size.toString()))
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    fun client() = HttpClient(engine) { expectSuccess = false }
}

/** Findings of the second adversarial review: production failure modes behind Azure Front Door + tusdotnet. */
class ProductionHardeningTest {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    private fun TestScope.retry(maxRetries: Int = 10) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 100, maxDelayMillis = 100, jitter = false,
        timeSource = testScheduler.timeSource)

    // ---------------------------------------------------------------- F1/F2 lost final 204

    @Test
    fun aLostFinal204_thenHead404_isPossiblyComplete_notExpired() = runTest {
        val bytes = testBytes(1000)
        val server = CompletingServer(1000)
        val error = assertFailsWith<TusUploadPossiblyCompleteException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", bytes),
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        }
        assertFalse((error as Throwable) is TusUploadExpiredException)
        assertEquals("https://example.com/files/u0", error.uploadUrl)
        assertEquals(listOf("OPTIONS", "POST", "HEAD", "PATCH", "PATCH", "HEAD"), server.methods, "no extra POST or PATCH")
        assertContentEquals(bytes, server.processed.single())
    }

    @Test
    fun resumeOrCreate_afterALostFinal204_doesNotUploadTheFileAgain() = runTest {
        val bytes = testBytes(1000)
        val server = CompletingServer(1000)
        val store = InMemoryTusUrlStore()
        assertFailsWith<TusUploadPossiblyCompleteException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", bytes), "fp", store,
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        }
        assertEquals(1, server.processed.size)
        assertEquals(1, server.methods.count { it == "POST" })
        assertEquals(2, server.methods.count { it == "PATCH" })
        assertTrue(store.snapshot().isEmpty(), "the entry is removed; the caller verifies on the server")
    }

    @Test
    fun resumeOrCreate_resumingAStoredUpload_whoseFinal204IsLost_isPossiblyComplete() = runTest {
        val bytes = testBytes(1000)
        val server = CompletingServer(1000)
        server.uploads["old"] = bytes.copyOfRange(0, 500)
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 1000, null)) }
        assertFailsWith<TusUploadPossiblyCompleteException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", bytes), "fp", store,
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        }
        assertEquals(listOf("HEAD", "PATCH", "HEAD"), server.methods, "no re-creation")
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun a404AfterANonFinalUnknownPatch_isStillExpired() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Throw(IOException("reset")))
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000")),
            Reply.Status(404))
        assertFailsWith<TusUploadExpiredException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        }
    }

    // ---------------------------------------------------------------- F5 onActivity

    @Test
    fun onActivity_keepsTicking_duringALongRecovery_whileOnProgressIsSilent() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Throw(IOException("reset")))
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000")))
        repeat(6) { server.script(HttpMethod.Head, Reply.Status(503, mapOf("Retry-After" to "60"))) }
        val activity = mutableListOf<Long>()
        val progress = mutableListOf<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
            options = TusUploadOptions(retryOptions = RetryOptions(jitter = false, timeSource = testScheduler.timeSource)),
            onProgress = { _, _ -> progress += currentTime },
            onActivity = { activity += currentTime })
        assertEquals(1000, server.offset)
        val progressGap = progress.zipWithNext { a, b -> b - a }.max()
        val activityGap = activity.zipWithNext { a, b -> b - a }.max()
        assertTrue(progressGap >= 360_000, "precondition: no body bytes for 6 minutes ($progressGap ms)")
        assertTrue(activityGap <= 5_000, "onActivity silent for $activityGap ms")
        assertTrue(activity.first() == 0L && activity.last() == currentTime)
    }

    // ---------------------------------------------------------------- F6 closed client

    @Test
    fun aClosedClient_failsAtOnce_withoutRetrying() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        val client = server.client()
        client.close()
        val start = currentTime
        val error = assertFailsWith<IllegalStateException> {
            client.uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(retryOptions = RetryOptions(jitter = false, timeSource = testScheduler.timeSource)))
        }
        assertTrue(error.message!!.contains("closed"), error.message)
        assertEquals(0, currentTime - start, "no backoff: a closed client cannot recover")
    }

    // ---------------------------------------------------------------- F7 dead stored URL

    @Test
    fun aStoredUrlThatIsPermanentlyRefused_isReplaced_once() = runTest {
        for (status in listOf(403, 400)) {
            val server = ScriptedTusServer(location = "/files/new").apply { uploadLength = 100 }
            server.script(HttpMethod.Head, Reply.Status(status))
            val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
                options = TusUploadOptions(retryOptions = retry()))
            assertEquals(listOf("HEAD", "OPTIONS", "POST", "HEAD", "PATCH"), server.methods(), "$status")
            assertTrue(store.snapshot().isEmpty())
        }
    }

    @Test
    fun aStoredUrlWithAProtocolError_isReplaced() = runTest {
        val server = ScriptedTusServer(location = "/files/new").apply { uploadLength = 100 }
        server.script(HttpMethod.Head, Reply.Status(200)) // no Upload-Offset
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
            options = TusUploadOptions(retryOptions = retry()))
        assertEquals(1, server.count(HttpMethod.Post))
    }

    @Test
    fun aReplacementThatIsAlsoRefused_failsInsteadOfLooping() = runTest {
        val server = ScriptedTusServer(location = "/files/new").apply { uploadLength = 100 }
        server.script(HttpMethod.Head, Reply.Status(403), Reply.Status(403))
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
        val error = assertFailsWith<TusHttpException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
                options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(403, error.statusCode)
        assertEquals(1, server.count(HttpMethod.Post), "re-created once, not in a loop")
    }

    @Test
    fun aStoredUrlFailingTransiently_keepsTheEntry() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        repeat(5) { server.script(HttpMethod.Head, Reply.Status(503)) }
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
        assertFailsWith<TusHttpException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
                options = TusUploadOptions(retryOptions = retry(maxRetries = 3)))
        }
        assertEquals(0, server.count(HttpMethod.Post))
        assertNotNull(store.get("fp"))
    }

    // ---------------------------------------------------------------- F8 huge maxDelay

    @Test
    fun aHugeMaxDelay_withJitter_doesNotOverflow() {
        val backoff = Backoff(RetryOptions(maxDelayMillis = Long.MAX_VALUE))
        for (failure in listOf(1, 64, 200)) assertTrue(backoff.nextDelayMillis(failure) >= 0)
    }

    // ---------------------------------------------------------------- F10 consistent exhausted errors

    @Test
    fun exhaustedRetryableStatuses_areTusHttpException_onEveryMethod_evenWithExpectSuccess() = runTest {
        for (method in listOf(HttpMethod.Options, HttpMethod.Post, HttpMethod.Head, HttpMethod.Patch, HttpMethod.Delete)) {
            val server = ScriptedTusServer().apply { uploadLength = 100 }
            repeat(10) { server.script(method, Reply.Status(503)) }
            val client = server.client(expectSuccess = true)
            val options = TusUploadOptions(retryOptions = retry(maxRetries = 3))
            val error = assertFailsWith<TusHttpException>("$method") {
                when (method) {
                    HttpMethod.Options, HttpMethod.Post -> client.createTusUpload(createUrl, InMemoryTusFile("f", testBytes(100)), options = options)
                    HttpMethod.Delete -> client.terminateTus(url, options = options)
                    else -> client.uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = options)
                }
            }
            assertEquals(503, error.statusCode, "$method")
            assertIs<ServerResponseException>(error.cause, "$method keeps Ktor's exception as the cause")
        }
    }

    // ---------------------------------------------------------------- F11 size change after completion

    @Test
    fun aSizeChangeAfterTheServerHasEveryByte_doesNotFailTheUpload() = runTest {
        val file = MutableTusFile("f", testBytes(400))
        val server = ScriptedTusServer().apply { uploadLength = 400 }
        server.onRequest = { request ->
            if (request.method == HttpMethod.Patch && server.count(HttpMethod.Patch) == 1) file.sizeOverride = 999
        }
        server.client().uploadTus(url, file, options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
        assertEquals(400, server.offset)
    }

    // ---------------------------------------------------------------- F12 423 Locked

    @Test
    fun aFiveMinuteLock_isWaitedOut_regardlessOfMaxRetries() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 100; lockedUntil = 300_000 }
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(retryOptions = RetryOptions(maxRetries = 3, timeSource = testScheduler.timeSource)))
        assertEquals(100, server.offset)
        assertTrue(server.requests.count { it.method == "HEAD" } > 3, "more 423s than maxRetries were absorbed")
    }

    @Test
    fun repeated423OnPatch_isNotCountedAgainstMaxRetries() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        repeat(6) { server.script(HttpMethod.Patch, Reply.Status(423)) }
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(retryOptions = retry(maxRetries = 3)))
        assertEquals(100, server.offset)
        assertEquals(7, server.count(HttpMethod.Patch))
    }

    @Test
    fun aLockOutlastingTheBudget_fails() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 100; lockedUntil = Long.MAX_VALUE }
        val error = assertFailsWith<TusHttpException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(retryOptions = RetryOptions(maxRetries = 3, timeSource = testScheduler.timeSource)))
        }
        assertEquals(423, error.statusCode)
        assertTrue(currentTime <= 10 * 60 * 1000L, "bounded by the 10-minute budget: $currentTime")
    }

    // ---------------------------------------------------------------- redirect credential leak (guard)

    @Test
    fun aCrossOriginRedirect_neverReceivesTheAuthorizationHeader() = runTest {
        val seenAtEvil = mutableListOf<String?>()
        val engine = MockEngine { request ->
            if (request.url.host == "evil.example") {
                seenAtEvil += request.headers["Authorization"]
                respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("0"), "Upload-Length" to listOf("100")))
            } else {
                respond("", HttpStatusCode.Found, headersOf("Location", "https://evil.example/steal"))
            }
        }
        val client = HttpClient(engine) { expectSuccess = false } // followRedirects left at Ktor's default
        assertFailsWith<TusProtocolException> {
            client.uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry())) {
                header("Authorization", "Bearer test-token")
            }
        }
        assertTrue(seenAtEvil.none { it != null }, "token reached the redirect target: $seenAtEvil")
    }
}
