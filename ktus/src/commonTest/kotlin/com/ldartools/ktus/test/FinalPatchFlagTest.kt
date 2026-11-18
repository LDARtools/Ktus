package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.onUpload
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
    val uploads = SyncMap<String, ByteArray>()
    val processed = SyncList<String>()
    val log = SyncList<String>()
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
    fun aMarkFromAnEarlierCall_isNeverClearedByA204_onlyByCompletion() = runTest {
        val writes = SyncList<String>()
        val store = recordingStore(writes)
        store.inner.set("fp", TusUpload("https://example.com/files/old", 1000, finalSentAt = 1))
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
            options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        // The earlier final PATCH's outcome was unknown: the 204 at 500 does not clear the mark; completion does.
        assertEquals(listOf("remove"), writes)
    }

    @Test
    fun aMarkSetInThisCall_isClearedByA204BelowTheTotal_whenNothingWasUncertain() = runTest {
        val writes = SyncList<String>()
        val store = recordingStore(writes)
        val server = ScriptedTusServer(location = "/files/new")
        // The single (final) PATCH is answered 204 with only 500 of 1000 stored: a definitive, partial answer.
        server.script(HttpMethod.Patch, Reply.Custom { _, body ->
            server.data += body.copyOf(500)
            respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "500"))
        })
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
            options = TusUploadOptions(retryOptions = retry()))
        assertEquals(listOf("clear", "flag", "clear", "flag", "remove"), writes)
    }

    private class RecordingStore(val writes: SyncList<String>) : TusUrlStore {
        val inner = InMemoryTusUrlStore()
        override suspend fun get(fingerprint: String) = inner.get(fingerprint)
        override suspend fun set(fingerprint: String, upload: TusUpload) {
            writes += if (upload.finalSentAt == null) "clear" else "flag"; inner.set(fingerprint, upload)
        }
        override suspend fun remove(fingerprint: String) { writes += "remove"; inner.remove(fingerprint) }
    }

    private fun recordingStore(writes: SyncList<String>) = RecordingStore(writes)

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
        val events = SyncList<String>()
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
        val stamps = SyncList<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(activityHeartbeatMillis = 2_000, retryOptions = retry()),
            onActivity = { stamps += currentTime })
        val (t0, t1) = server.times(HttpMethod.Head).let { it[0] to it[1] }
        assertEquals(10_000, t1 - t0)
        // Only the backoff window is asserted: after it, the in-flight ticker runs while the PATCH is handled on
        // an engine thread, and runTest's virtual clock can jump ahead there (most visibly on Kotlin/Native).
        val window = stamps.filter { it in t0..t1 }
        assertTrue(window.zipWithNext { a, b -> b - a }.max() <= 2_000, "$window")
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

    // ---------------------------------------------------------------- round 5

    private fun TestScope.adv5Options() = TusUploadOptions(chunkSize = 500, minChunkSize = 100, activityHeartbeatMillis = null,
        retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
            timeSource = testScheduler.timeSource))

    private fun Srv.staleHeadThenOffline() {
        var offline = false
        patchHook = { idx, _, _ ->
            when (idx) {
                1 -> "lostFinal504"
                2 -> { offline = true; throw kotlinx.io.IOException("connection reset") }
                else -> if (offline) throw kotlinx.io.IOException("offline") else null
            }
        }
        headHook = { idx, _ ->
            when {
                idx == 1 -> 500L // stale instance
                offline -> throw kotlinx.io.IOException("offline")
                else -> null
            }
        }
    }

    /**
     * H1, no store (Chateau's call shape): the final PATCH lands but the gateway answers 504, a stale HEAD says
     * 500, the next (smaller, non-final) PATCH and every HEAD then fail. That used to be a plain IOException,
     * which a "retry later" handler answered by uploading the file again.
     */
    @Test
    fun H1_noStore_staleHeadThenNetworkLoss_isOutcomeUnknown_soTheAppDoesNotReupload() = runTest {
        val srv = Srv(1000).apply { staleHeadThenOffline() }
        val file = InMemoryTusFile("f", testBytes(1000))
        val first = runCatching { srv.client().createAndUploadTus(createUrl, file, options = adv5Options()) }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(first)
        assertIs<kotlinx.io.IOException>(first.cause)
        // The app's handler: retry only on a network error.
        srv.patchHook = { _, _, _ -> null }; srv.headHook = { _, _ -> null }
        if (first is kotlinx.io.IOException) runCatching { srv.client().createAndUploadTus(createUrl, file, options = adv5Options()) }
        assertEquals(1, srv.processed.size, "file processed ${srv.processed.size} times: ${srv.processed}")
    }

    @Test
    fun H1_store_staleHeadThenNetworkLoss_isOutcomeUnknown() = runTest {
        val srv = Srv(1000).apply { staleHeadThenOffline() }
        val store = InMemoryTusUrlStore()
        val first = runCatching {
            srv.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store, options = adv5Options())
        }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(first)
        assertNotNull(store.get("fp")?.finalSentAt)
    }

    /**
     * A 404 answered to the first-ever final PATCH is NOT definitive: tusdotnet answers it too once another request
     * completed the upload and removed it. The outcome is unknown (UPLOAD_GONE), the mark stays, nothing is
     * re-created, and the next call reports the same.
     */
    @Test
    fun a404AnsweredToTheFirstFinalPatch_isUploadGone_andKeepsTheMark() = runTest {
        val srv = Srv(500)
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", testBytes(500))
        val opts = TusUploadOptions(chunkSize = 500, activityHeartbeatMillis = null, retryOptions = retry())
        srv.patchHook = { _, _, _ -> srv.uploads.clear(); null } // the server lost the upload before the PATCH
        val e1 = runCatching { srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts) }.exceptionOrNull()
        val unknown = assertIs<TusUploadOutcomeUnknownException>(e1)
        assertEquals(TusUploadOutcomeUnknownException.Kind.UPLOAD_GONE, unknown.kind)
        assertNotNull(store.get("fp")?.finalSentAt, "the mark stays")
        srv.patchHook = { _, _, _ -> null }
        val e2 = runCatching { srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts) }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e2, "a marked entry is never re-created")
        assertEquals(0, srv.processed.size)
        assertNotNull(store.get("fp")?.finalSentAt)
    }

    /** A direct 403 to the first final PATCH clears the mark it just set (the bytes did not land). */
    @Test
    fun aDirectRefusalOfTheFirstFinalPatch_clearsTheMarkItSet() = runTest {
        val server = ScriptedTusServer(location = "/files/new")
        server.script(HttpMethod.Patch, Reply.Status(403))
        val store = InMemoryTusUrlStore()
        assertFailsWith<TusHttpException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
                options = TusUploadOptions(retryOptions = retry()))
        }
        assertNull(store.get("fp")?.finalSentAt)
    }

    /** L1: a client closed before the final PATCH leaves no mark and does not call onFinalPatch. */
    @Test
    fun aClientClosedBeforeTheFinalPatch_leavesNoMark() = runTest {
        val server = ScriptedTusServer(location = "/files/new")
        val client = server.client()
        val store = InMemoryTusUrlStore()
        var finalCalls = 0
        assertFailsWith<IllegalStateException> {
            client.resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()),
                onConfirmed = { _, _ -> client.close() }, onFinalPatch = { finalCalls++ })
        }
        assertEquals(0, finalCalls)
        assertNull(store.get("fp")?.finalSentAt, "a PATCH that never left must not leave a mark")
    }

    /** L3: if the store cannot forget a completed upload, the call still succeeds. */
    @Test
    fun aStoreThatFailsToRemoveAfterCompletion_stillReportsSuccess() = runTest {
        val inner = InMemoryTusUrlStore()
        val store = object : TusUrlStore by inner {
            override suspend fun remove(fingerprint: String) = throw kotlinx.io.IOException("disk full")
        }
        val server = ScriptedTusServer(location = "/files/new")
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
            options = TusUploadOptions(retryOptions = retry()))
        assertEquals(100, server.offset)
        assertNotNull(inner.get("fp")?.finalSentAt, "the entry stays marked")
    }
}

/** Sixth review: a 5xx answered to the final PATCH is never definitive. */
class Adv6DefinitiveRefusalTest {
    private val createUrl = "https://example.com/files"

    private fun TestScope.opts(retryable: Set<Int> = RetryOptions.DEFAULT_RETRYABLE_STATUS_CODES) =
        TusUploadOptions(chunkSize = 500, activityHeartbeatMillis = null,
            retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
                retryableStatusCodes = retryable, timeSource = testScheduler.timeSource))

    /** Server stores + processes the file, then answers 507 (a non-retried 5xx). */
    @Test
    fun adv6_non_retried_5xx_after_final_landed_is_outcome_unknown() = runTest {
        val srv = Srv(1000)
        srv.finalReply = { _ -> HttpStatusCode.InsufficientStorage to emptyList() }
        val e = runCatching {
            srv.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), options = opts())
        }.exceptionOrNull()
        assertEquals(1, srv.processed.size)
        assertIs<TusUploadOutcomeUnknownException>(e, "got $e although the server processed the file")
    }

    /** Chateau's server answers 500 when the body was stored but the import could not start. */
    @Test
    fun adv6_500_with_narrowed_retryable_codes_after_final_landed_is_outcome_unknown() = runTest {
        val srv = Srv(1000)
        srv.finalReply = { _ -> HttpStatusCode.InternalServerError to emptyList() }
        val e = runCatching {
            srv.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)),
                options = opts(retryable = setOf(503)))
        }.exceptionOrNull()
        assertEquals(1, srv.processed.size)
        assertIs<TusUploadOutcomeUnknownException>(e, "got $e although the server processed the file")
    }

    /** With a store: the 5xx clears the mark, and the next call re-creates and uploads the file again. */
    @Test
    fun adv6_store_non_retried_5xx_after_final_landed_then_next_call_reuploads() = runTest {
        val srv = Srv(1000)
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", testBytes(1000))
        var first = true
        srv.finalReply = { off ->
            if (first) { first = false; HttpStatusCode.InsufficientStorage to emptyList() }
            else HttpStatusCode.NoContent to listOf("Upload-Offset" to listOf(off.toString()))
        }
        val e1 = runCatching { srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts()) }
            .exceptionOrNull()
        val markAfterFirst = store.get("fp")?.finalSentAt
        runCatching { srv.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts()) }
        assertEquals(1, srv.processed.size,
            "processed ${srv.processed.size} times; first error=$e1, mark after first call=$markAfterFirst")
    }

    /** Every non-retried 5xx answered to the final PATCH is an unknown outcome. */
    @Test
    fun everyNonRetried5xx_onTheFinalPatch_isOutcomeUnknown() = runTest {
        for (status in listOf(500, 501, 505, 507, 508, 511)) {
            val server = ScriptedTusServer().apply { uploadLength = 500 }
            server.script(HttpMethod.Patch, Reply.Status(status))
            val e = assertFailsWith<TusUploadOutcomeUnknownException>("$status") {
                server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(500)),
                    options = opts(retryable = setOf(503)))
            }
            assertEquals(status, (e.cause as TusHttpException).statusCode)
        }
    }

    /** L1: a final PATCH that once ended unknown taints every later refusal in the call, even after a 204. */
    @Test
    fun aRefusalAfterAnEarlierUnknownFinalPatch_isOutcomeUnknown_evenAfterA204() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        // 0..500 ok; final 500..1000 times out (504, halves); HEAD 500; 500..750 -> 204 750 (resolves);
        // final 750..1000 -> 404.
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "500"), apply = true),
            Reply.Status(504), Reply.Status(204, mapOf("Upload-Offset" to "750"), apply = true), Reply.Status(404))
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 500, minChunkSize = 100, activityHeartbeatMillis = null,
                    retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10,
                        jitter = false, timeSource = testScheduler.timeSource)))
        }
        assertIs<TusUploadExpiredException>(e.cause)
        assertEquals(listOf(0L, 500L, 500L, 750L), server.patchOffsets())
    }

    /** L2: minDelayMillis is a floor after jitter. */
    private suspend fun TestScope.gapsBetweenPatches(minDelay: Long, jitter: Boolean, failures: List<Reply>): List<Long> {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 200 }
        server.script(HttpMethod.Patch, *failures.toTypedArray())
        server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(200)),
            options = TusUploadOptions(chunkSize = 100, activityHeartbeatMillis = null, retryOptions = RetryOptions(
                initialDelayMillis = 1_000, maxDelayMillis = 10_000, minDelayMillis = minDelay, jitter = jitter,
                random = kotlin.random.Random(3), timeSource = testScheduler.timeSource)))
        return server.times(HttpMethod.Patch).zipWithNext { a, b -> b - a }.take(failures.size)
    }

    /** M3: after a PATCH the origin may still be processing (503 here), every retry waits at least minDelayMillis. */
    @Test
    fun minDelayMillis_isAFloorAfterJitter_forPatchesTheOriginMayStillBeProcessing() = runTest {
        val jittered = gapsBetweenPatches(4_000, jitter = true, failures = List(5) { Reply.Status(503) })
        assertTrue(jittered.size == 5 && jittered.all { it >= 4_000 }, "$jittered")
        // Without jitter the floor only lifts the short early delays.
        assertEquals(listOf(4_000L, 4_000L, 4_000L, 8_000L, 10_000L),
            gapsBetweenPatches(4_000, jitter = false, failures = List(5) { Reply.Status(503) }))
        // Also after a timeout, 423, or a connection lost after body bytes were sent.
        for (failure in listOf<Reply>(Reply.Status(504), Reply.Status(502), Reply.Status(423),
                Reply.Throw(kotlinx.io.IOException("reset after the body")))) {
            assertEquals(listOf(4_000L), gapsBetweenPatches(4_000, jitter = false, failures = listOf(failure)), "$failure")
        }
    }

    /** M3: not after a fast failure where nothing was sent, nor for HEAD/OPTIONS/POST retries. */
    @Test
    fun minDelayMillis_doesNotApplyToFastFailures_orToNonPatchRequests() = runTest {
        assertEquals(listOf(1_000L),
            gapsBetweenPatches(4_000, jitter = false, failures = listOf(Reply.Throw(io.ktor.client.network.sockets.ConnectTimeoutException("connect timed out"), beforeBody = true))))
        // Darwin: a refused connection (NSURLErrorDomain -1004) also proves nothing reached the origin.
        assertEquals(listOf(1_000L), gapsBetweenPatches(4_000, jitter = false, failures = listOf(Reply.Throw(
            kotlinx.io.IOException("Exception in http request: Error Domain=NSURLErrorDomain Code=-1004 \"Could not connect to the server.\""),
            beforeBody = true))))
        // Upload progress is no longer the signal: a plain network error after progress still gets the floor (see
        // the floor test), and one before any progress does too unless it proves nothing was sent.
        assertEquals(listOf(4_000L), gapsBetweenPatches(4_000, jitter = false, failures = listOf(Reply.Throw(
            kotlinx.io.IOException("reset"), beforeBody = true))))
        assertEquals(listOf(1_000L), gapsBetweenPatches(4_000, jitter = false, failures = listOf(Reply.Status(500))))

        val server = ScriptedTusServer(clock = { currentTime })
        repeat(2) { server.script(HttpMethod.Post, Reply.Status(503)) }
        server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)),
            options = TusUploadOptions(checkServerCapabilities = false, retryOptions = RetryOptions(
                initialDelayMillis = 1_000, maxDelayMillis = 10_000, minDelayMillis = 4_000, jitter = false,
                timeSource = testScheduler.timeSource)))
        assertEquals(listOf(1_000L, 2_000L), server.times(HttpMethod.Post).zipWithNext { a, b -> b - a })
    }

    /** L-c: contradictory retry options are rejected. */
    @Test
    fun aMinDelayLongerThanTheBudget_isRejected() {
        val e = assertFailsWith<IllegalArgumentException> { RetryOptions(minDelayMillis = 300_000, maxElapsedMillis = 240_000) }
        assertTrue(e.message!!.contains("minDelayMillis"), e.message)
        RetryOptions(minDelayMillis = 239_999, maxElapsedMillis = 240_000)
        for (bad in listOf<() -> Unit>({ RetryOptions(initialDelayMillis = -1) }, { RetryOptions(maxDelayMillis = -1) },
                { RetryOptions(maxElapsedMillis = -1) }, { RetryOptions(minDelayMillis = -1) }, { RetryOptions(factor = -1.0) },
                { RetryOptions(factor = Double.NaN) })) {
            assertFailsWith<IllegalArgumentException> { bad() }
        }
    }

    /** L1: values 1.0.3 accepted keep working the same way. */
    @Test
    fun retryOptionsThat103Accepted_keepTheirMeaning() = runTest {
        // initialDelayMillis above maxDelayMillis is capped.
        val capped = RetryOptions(initialDelayMillis = 5_000, maxDelayMillis = 200)
        assertEquals(200, delayBoundMillis(capped, 1))
        // A factor below 1 shrinks the delays.
        assertEquals(250, delayBoundMillis(RetryOptions(initialDelayMillis = 1_000, factor = 0.5), 3))
        // maxRetries = 0 (or less) means one attempt.
        for (maxRetries in listOf(0, -3)) {
            val server = ScriptedTusServer()
            repeat(5) { server.script(HttpMethod.Post, Reply.Status(503)) }
            assertFailsWith<TusHttpException> {
                server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)), options = TusUploadOptions(
                    checkServerCapabilities = false, retryOptions = RetryOptions(maxRetries = maxRetries,
                        timeSource = testScheduler.timeSource)))
            }
            assertEquals(1, server.count(HttpMethod.Post), "maxRetries = $maxRetries")
        }
    }

    /** M1: Darwin NSURLErrorDomain codes that prove the request was never sent. */
    @Test
    fun darwinPreSendCodes_areRecognisedFromTheErrorText() {
        fun darwin(code: Int) = kotlinx.io.IOException("Exception in http request: Error Domain=NSURLErrorDomain Code=$code \"x\"")
        for (code in listOf(-1003, -1004, -1006, -1022, -1201, -1202, -1206)) assertTrue(isPreSendFailure(darwin(code)), "$code")
        // Codes that can also occur after bytes were sent get the floor (conservative).
        for (code in listOf(-1001, -1005, -1009, -1017, -1018, -1019, -1020, -1200)) assertFalse(isPreSendFailure(darwin(code)), "$code")
        assertTrue(isPreSendFailure(RuntimeException("wrapped", darwin(-1004))))
        assertFalse(isPreSendFailure(kotlinx.io.IOException("connection reset")))
    }

    /** M2: the helpers tell "check the server" from "call again later". */
    @Test
    fun outcomeUnknownHelpers_classifyTheCause() {
        fun e(cause: Throwable?) = TusUploadOutcomeUnknownException("u", cause)
        assertTrue(e(null).isUploadGone)
        assertTrue(e(TusUploadExpiredException()).isUploadGone)
        assertTrue(e(TusHttpException(404, "PATCH")).isUploadGone)
        assertTrue(e(TusHttpException(410, "HEAD")).isUploadGone)
        assertFalse(e(TusHttpException(503, "PATCH")).isUploadGone)
        assertFalse(e(kotlinx.io.IOException("offline")).isUploadGone)

        assertTrue(e(kotlinx.io.IOException("offline")).isNetworkFailure)
        assertFalse(e(TusUploadExpiredException()).isNetworkFailure, "a TusProtocolException is an IOException, not a network failure")
        assertFalse(e(TusHttpException(503, "PATCH")).isNetworkFailure)
        assertFalse(e(null).isNetworkFailure)

        for (status in listOf(400, 403, 413)) assertTrue(e(TusHttpException(status, "PATCH")).isServerRefusal, "$status")
        for (status in listOf(401, 404, 409, 410, 423, 429, 500, 503)) assertFalse(e(TusHttpException(status, "PATCH")).isServerRefusal, "$status")
        assertTrue(e(TusUploadLengthMismatchException(1, 2)).isServerRefusal)
        // An offset mismatch can come from a stale instance: call again later, not a refusal.
        assertFalse(e(TusOffsetMismatchException("x")).isServerRefusal)
        assertFalse(e(kotlinx.io.IOException("offline")).isServerRefusal)
        assertFalse(e(null).isServerRefusal)
    }

    /** L2: an onUpload set in the request block does not silence onProgress. */
    @Test
    fun anOnUploadInTheBlock_doesNotReplaceKtusProgress() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        val progress = SyncList<Long>()
        server.client().uploadTus("https://example.com/files/abc", InMemoryTusFile("f", testBytes(1000)),
            options = opts(), onProgress = { sent, _ -> progress += sent }) {
            onUpload { _, _ -> }
        }
        assertTrue(progress.isNotEmpty() && progress.last() == 1000L, "$progress")
    }

    /** L3: an offset mismatch (for example a stale HEAD) keeps the stored entry. */
    @Test
    fun anOffsetMismatchOnAStoredUpload_keepsTheEntry() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "300"), apply = true), Reply.Status(503))
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000")))
        repeat(4) { server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "100", "Upload-Length" to "1000"))) }
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 1000, null)) }
        assertFailsWith<TusOffsetMismatchException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = opts())
        }
        assertEquals(0, server.count(HttpMethod.Post))
        assertNotNull(store.get("fp"))
    }
}
