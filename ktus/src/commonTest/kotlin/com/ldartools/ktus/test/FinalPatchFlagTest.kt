package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** tusdotnet-like server with hooks. */
private class Srv(val size: Long) {
    var nextId = 0
    val uploads = mutableMapOf<String, ByteArray>()
    val processed = mutableListOf<String>()
    val log = mutableListOf<String>()
    /** Called for each PATCH with (index, offset, len); return null to behave normally. */
    var patchHook: suspend (Int, Long, Int) -> String? = { _, _, _ -> null }
    var headHook: suspend (Int, String) -> Long? = { _, _ -> null }
    var finalReply: (Long) -> Pair<HttpStatusCode, List<Pair<String, List<String>>>> =
        { off -> HttpStatusCode.NoContent to listOf("Upload-Offset" to listOf(off.toString())) }
    var patches = 0
    var heads = 0
    var lastCompletedSize = 0L

    val engine = MockEngine { request ->
        val id = request.url.encodedPath.substringAfterLast('/')
        log += "${request.method.value} $id ${request.headers["Upload-Offset"] ?: ""}"
        when (request.method) {
            HttpMethod.Options -> respond("", HttpStatusCode.NoContent, headersOf("Tus-Resumable", "1.0.0"))
            HttpMethod.Post -> {
                val newId = "u${nextId++}"
                uploads[newId] = ByteArray(0)
                respond("", HttpStatusCode.Created, headersOf("Location", "/files/$newId"))
            }
            HttpMethod.Head -> {
                val idx = heads++
                headHook(idx, id)?.let { stale ->
                    return@MockEngine respond("", HttpStatusCode.OK, headersOf(
                        "Upload-Offset" to listOf(stale.toString()), "Upload-Length" to listOf(size.toString())))
                }
                val data = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                respond("", HttpStatusCode.OK, headersOf(
                    "Upload-Offset" to listOf(data.size.toString()), "Upload-Length" to listOf(size.toString())))
            }
            HttpMethod.Patch -> {
                val idx = patches++
                val body = ScriptedTusServer.readBody(request)
                val off = request.headers["Upload-Offset"]!!.toLong()
                val action = patchHook(idx, off, body.size)
                val data = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                if (off != data.size.toLong()) return@MockEngine respond("", HttpStatusCode.Conflict)
                val now = data + body
                uploads[id] = now
                if (now.size.toLong() == size) {
                    processed += id
                    uploads.remove(id)
                    if (action == "lostFinal504") return@MockEngine respond("gateway timeout", HttpStatusCode.GatewayTimeout)
                    val (st, hs) = finalReply(now.size.toLong())
                    return@MockEngine respond("", st, headersOf(*hs.toTypedArray()))
                }
                respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", now.size.toString()))
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }
    fun client() = HttpClient(engine) { expectSuccess = false }
}


/** Fourth adversarial review: the persisted flag, malformed final answers, onFinalPatch, closed client. */
class FinalPatchFlagTest {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    private fun TestScope.retry(maxRetries: Int = 3) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
        timeSource = testScheduler.timeSource)

    /**
     * The README admits a HEAD from another instance can be stale and protects against it within one call
     * (finalWasUncertain). But the stale HEAD clears the PERSISTED finalSentAt flag; if the call is then
     * cancelled before the next final PATCH re-flags it, the next call sees 404 as a plain expiry and
     * re-creates: the file is processed twice.
     */
    @Test
    fun M1_staleHead_thenCancel_nextCall_doesNotReupload() = runTest {
        val srv = Srv(1000)
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", testBytes(1000))
        val opts = TusUploadOptions(chunkSize = 500, minChunkSize = 100, activityHeartbeatMillis = null,
            retryOptions = RetryOptions(maxRetries = 5, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
                timeSource = testScheduler.timeSource))
        lateinit var job: Job
        srv.patchHook = { idx, _, _ ->
            when (idx) {
                1 -> "lostFinal504"                          // final PATCH lands; gateway answers 504
                2 -> { job.cancel(); awaitCancellation() }   // smaller (non-final) PATCH: app/watchdog cancels
                else -> null
            }
        }
        srv.headHook = { idx, _ -> if (idx == 1) 500L else null } // 2nd HEAD answered by a stale instance
        job = launch {
            srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts)
        }
        job.join()
        runCatching { srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts) }
        assertEquals(1, srv.processed.size, "file processed ${srv.processed.size} times: ${srv.processed}")
    }

    /**
     * KDoc/README: "A marked entry is never dropped or re-created". On the resume path the entry is marked by
     * beforeFinalPatch in this very call; a protocol error on the final PATCH's 2xx (here a 204 whose
     * Upload-Offset is missing) is then treated as a "permanent refusal" and the upload is re-created.
     */
    @Test
    fun M2_resumePath_protocolErrorOnFinal204_isNotRecreated() = runTest {
        val srv = Srv(1000)
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", testBytes(1000))
        val opts = TusUploadOptions(chunkSize = 500, activityHeartbeatMillis = null,
            retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
                timeSource = testScheduler.timeSource))
        // A previous call created u0 and stored 500 bytes, then the app was killed.
        srv.uploads["u0"] = testBytes(1000).copyOf(500); srv.nextId = 1
        store.set("fp", TusUpload("https://example.com/files/u0", 1000))
        var first = true
        srv.finalReply = { off ->
            if (first) { first = false; HttpStatusCode.NoContent to listOf("Upload-Offset" to listOf(" ${off}x")) }
            else HttpStatusCode.NoContent to listOf("Upload-Offset" to listOf(off.toString()))
        }
        val r = runCatching { srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts) }
        assertEquals(1, srv.processed.size, "file processed ${srv.processed.size} times: ${srv.processed}")
    }

    // ---------------------------------------------------------------- M1

    @Test
    fun aHeadBelowTheTotal_doesNotClearThePersistedFlag_butA204BelowTheTotalDoes() = runTest {
        val writes = mutableListOf<String>()
        val store = object : TusUrlStore {
            val inner = InMemoryTusUrlStore()
            override suspend fun get(fingerprint: String) = inner.get(fingerprint)
            override suspend fun set(fingerprint: String, upload: TusUpload) {
                writes += if (upload.finalSentAt == null) "clear" else "flag"; inner.set(fingerprint, upload)
            }
            override suspend fun remove(fingerprint: String) { writes += "remove"; inner.remove(fingerprint) }
        }
        store.inner.set("fp", TusUpload("https://example.com/files/old", 1000, finalSentAt = 1))
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
            options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        // HEAD said 0 (no write), the 204 at 500 cleared the flag, the final PATCH re-flagged, success removed it.
        assertEquals(listOf("clear", "flag", "remove"), writes)
    }

    // ---------------------------------------------------------------- M2

    @Test
    fun aMalformedAnswerToTheFinalPatch_isOutcomeUnknown() = runTest {
        // Final chunk is 500..1000.
        for (offset in listOf(null, "abc", " 1000x", "500", "1001", "-1", "")) {
            val server = ScriptedTusServer().apply { uploadLength = 1000; data = testBytes(500) }
            val headers = if (offset == null) emptyMap() else mapOf("Upload-Offset" to offset)
            server.script(HttpMethod.Patch, Reply.Status(204, headers, apply = true))
            val e = assertFailsWith<TusUploadOutcomeUnknownException>("Upload-Offset=$offset") {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                    options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
            }
            assertIs<TusProtocolException>(e.cause)
            assertEquals(1, server.count(HttpMethod.Patch), "no re-send")
        }
    }

    @Test
    fun aFlaggedEntry_refusedWithAPermanent4xxOnPatch_isNotRecreated() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Status(403))
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 1000, finalSentAt = 1)) }
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(403, (e.cause as TusHttpException).statusCode)
        assertEquals(0, server.count(HttpMethod.Post))
        assertNotNull(store.get("fp")?.finalSentAt)
    }

    // ---------------------------------------------------------------- M3

    @Test
    fun onFinalPatch_firesBeforeEveryFinalPatchAttempt() = runTest {
        val events = mutableListOf<String>()
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.onRequest = { r -> if (r.method == HttpMethod.Patch) events += "PATCH@${r.headers["Upload-Offset"]}" }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "500"), apply = true), Reply.Status(503))
        server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)),
            options = TusUploadOptions(chunkSize = 500, retryOptions = retry()), onFinalPatch = { events += "final" })
        assertEquals(listOf("PATCH@0", "final", "PATCH@500", "final", "PATCH@500"), events)
    }

    @Test
    fun anExceptionFromOnFinalPatch_preventsTheFinalPatch() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        val boom = RuntimeException("cannot persist")
        assertSame(boom, assertFails {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()), onFinalPatch = { throw boom })
        })
        assertEquals(listOf(0L), server.patchOffsets())
    }

    // ---------------------------------------------------------------- L2

    @Test
    fun activityHeartbeatMillis_mustBePositiveOrNull() {
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(activityHeartbeatMillis = 0) }
        assertFailsWith<IllegalArgumentException> { TusUploadOptions(activityHeartbeatMillis = -5) }
        TusUploadOptions(activityHeartbeatMillis = null)
    }

    @Test
    fun theHeartbeatInterval_alsoSlicesBackoffWaits() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }, dispatcher = StandardTestDispatcher(testScheduler))
            .apply { uploadLength = 100 }
        server.script(HttpMethod.Head, Reply.Status(503, mapOf("Retry-After" to "10")))
        val stamps = mutableListOf<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(activityHeartbeatMillis = 2_000, retryOptions = retry()),
            onActivity = { stamps += currentTime })
        assertEquals(10_000, server.times(HttpMethod.Head)[1] - server.times(HttpMethod.Head)[0])
        assertTrue(stamps.zipWithNext { a, b -> b - a }.max() <= 2_000, "$stamps")
    }

    // ---------------------------------------------------------------- L6

    @Test
    fun aClientClosedDuringTheFinalPatch_isOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 500 }
        val client = server.client()
        server.script(HttpMethod.Patch, Reply.Custom { _, _ ->
            client.close()
            throw CancellationException("client closed")
        })
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            client.uploadTus(url, InMemoryTusFile("f", testBytes(500)), options = TusUploadOptions(retryOptions = retry()))
        }
        assertIs<IllegalStateException>(e.cause)
    }

    @Test
    fun aClientClosedBeforeTheFinalPatch_isAPlainError() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        val client = server.client()
        val e = assertFailsWith<IllegalStateException> {
            client.uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()),
                onConfirmed = { _, _ -> client.close() })
        }
        assertTrue(e.message!!.contains("closed"))
        assertEquals(listOf(0L), server.patchOffsets(), "the final PATCH was never sent")
    }
}
