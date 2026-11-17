package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.*

internal class TusdotnetLike(val size: Long) {
    var nextId = 0
    val uploads = mutableMapOf<String, ByteArray>()
    val processed = mutableListOf<ByteArray>()
    var loseFinal = 0
    var headFailures = 0
    var staleHeads = 0
    var patchConflicts = 0
    /** Runs after the final chunk is stored (and the upload removed), before the response. */
    var afterFinalStored: suspend () -> Unit = {}
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
                if (processed.isNotEmpty() && headFailures > 0) { headFailures--; throw IOException("dead link") }
                if (processed.isNotEmpty() && staleHeads > 0) {
                    staleHeads--
                    return@MockEngine respond("", HttpStatusCode.OK, headersOf(
                        "Upload-Offset" to listOf("0"), "Upload-Length" to listOf(size.toString())))
                }
                val data = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                respond("", HttpStatusCode.OK, headersOf(
                    "Upload-Offset" to listOf(data.size.toString()), "Upload-Length" to listOf(size.toString())))
            }
            HttpMethod.Patch -> {
                val body = ScriptedTusServer.readBody(request)
                if (patchConflicts > 0) { patchConflicts--; return@MockEngine respond("", HttpStatusCode.Conflict) }
                val data = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                if (request.headers["Upload-Offset"]?.toLong() != data.size.toLong()) {
                    return@MockEngine respond("", HttpStatusCode.Conflict)
                }
                val now = data + body
                uploads[id] = now
                if (now.size.toLong() == size) {
                    processed += now
                    uploads.remove(id)
                    afterFinalStored()
                    if (loseFinal-- > 0) throw IOException("response to the final PATCH lost")
                }
                respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", now.size.toString()))
            }
            else -> respond("", HttpStatusCode.MethodNotAllowed)
        }
    }

    fun client() = HttpClient(engine) { expectSuccess = false }
}


/** A store that records every write, to observe the finalSentAt flag over time. */
private class RecordingStore : TusUrlStore {
    val inner = InMemoryTusUrlStore()
    val writes = mutableListOf<String>()
    override suspend fun get(fingerprint: String) = inner.get(fingerprint)
    override suspend fun set(fingerprint: String, upload: TusUpload) {
        writes += if (upload.finalSentAt == null) "set" else "set-final"
        inner.set(fingerprint, upload)
    }
    override suspend fun remove(fingerprint: String) {
        writes += "remove"
        inner.remove(fingerprint)
    }
}

/**
 * D1/D2/D3: the outcome-unknown rule, plus the third adversarial review's regression tests (A1-A5) and fixes
 * F5-F10.
 */
class OutcomeUnknownTest {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    private fun TestScope.retry(maxRetries: Int = 3) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 100, maxDelayMillis = 100, jitter = false,
        timeSource = testScheduler.timeSource)

    // ---------------------------------------------------------------- reviewer's regression tests

    @Test
    fun A1_resumeOrCreate_lostFinal_thenDeadLink_neverProcessesTheFileTwice() = runTest {
        val bytes = testBytes(1000)
        val server = TusdotnetLike(1000).apply { loseFinal = 1; headFailures = 3 }
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", bytes)
        val opts = TusUploadOptions(chunkSize = 500, retryOptions = retry(maxRetries = 3))
        val first = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts)
        }
        assertIs<IOException>(first.cause, "the dead link is the cause")
        assertNotNull(store.get("fp")?.finalSentAt, "the entry stays, flagged")
        assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts)
        }
        assertEquals(1, server.processed.size, "file processed ${server.processed.size} times")
        assertEquals(1, server.methods.count { it == "POST" })
    }

    @Test
    fun A2_lostFinal_thenHeadRetriesExhausted_isOutcomeUnknown() = runTest {
        val server = TusdotnetLike(1000).apply { loseFinal = 1; headFailures = 100 }
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry(maxRetries = 3)))
        }
        assertIs<IOException>(e.cause)
    }

    @Test
    fun A3_lostFinal_staleHead_thenPatch404_isOutcomeUnknown() = runTest {
        val server = TusdotnetLike(1000).apply { loseFinal = 1; staleHeads = 1 }
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 1000, retryOptions = retry()))
        }
        assertIs<TusUploadExpiredException>(e.cause)
        assertEquals(1, server.processed.size)
    }

    @Test
    fun A3b_resumeOrCreate_lostFinal_staleHead_patch404_doesNotReupload() = runTest {
        val server = TusdotnetLike(1000).apply { loseFinal = 1; staleHeads = 1 }
        val store = InMemoryTusUrlStore()
        server.uploads["old"] = ByteArray(0)
        store.set("fp", TusUpload("https://example.com/files/old", 1000, null))
        assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(chunkSize = 1000, retryOptions = retry()))
        }
        assertEquals(1, server.processed.size, "file processed ${server.processed.size} times")
        assertEquals(0, server.methods.count { it == "POST" })
        assertNotNull(store.get("fp")?.finalSentAt, "re-flagged even though a stale HEAD had cleared it")
    }

    @Test
    fun A4_resumeOrCreate_409Exhaustion_keepsTheStoredUrl() = runTest {
        val server = TusdotnetLike(1000).apply { patchConflicts = 3 }
        server.uploads["old"] = ByteArray(0)
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 1000, null)) }
        runCatching {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(chunkSize = 1000, retryOptions = retry(maxRetries = 3)))
        }
        assertEquals(0, server.methods.count { it == "POST" }, "a 409 run re-created the upload")
        assertNotNull(store.get("fp"))
    }

    @Test
    fun A5_cancellationFromOnActivity_isRethrownAsIs() = runTest {
        val server = TusdotnetLike(1000)
        var calls = 0
        val e = assertFailsWith<CancellationException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 1000, retryOptions = retry()),
                onActivity = { calls++; if (calls == 2) throw CancellationException("watchdog says stop") })
        }
        assertEquals("watchdog says stop", e.message)
        assertEquals(listOf("OPTIONS"), server.methods, "nothing after the callback threw")
    }

    // ---------------------------------------------------------------- D1

    @Test
    fun aLostFinalResponse_whereHeadProvesCompletion_succeeds() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 500 }
        server.script(HttpMethod.Patch, Reply.Throw(IOException("response lost"), apply = true))
        val confirmed = mutableListOf<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(500)),
            options = TusUploadOptions(retryOptions = retry()), onConfirmed = { o, _ -> confirmed += o })
        assertEquals(listOf("HEAD", "PATCH", "HEAD"), server.methods())
        assertEquals(listOf(500L), confirmed)
    }

    @Test
    fun aFinalPatchThatDidNotLand_isUnlatchedByHead_andFailuresAfterThatAreNormal() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 500 }
        // Final PATCH fails without storing; HEAD shows 0 (< total), so the latch is released; the re-sent
        // PATCH is then refused with 403, an ordinary error.
        server.script(HttpMethod.Patch, Reply.Status(503), Reply.Status(403))
        val e = assertFailsWith<TusHttpException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(500)), options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(403, e.statusCode)
    }

    @Test
    fun whileLatched_everyTerminalFailure_isOutcomeUnknown_withTheOriginalCause() = runTest {
        val cases = listOf<Pair<List<Reply>, (Throwable?) -> Boolean>>(
            listOf<Reply>(Reply.Status(403)) to { c -> c is TusHttpException && c.statusCode == 403 },
            List(10) { Reply.Status(503) } to { c -> c is TusHttpException && c.statusCode == 503 },
            List(10) { Reply.Throw(IOException("down")) } to { c -> c is IOException },
            listOf<Reply>(Reply.Status(410)) to { c -> c is TusUploadExpiredException },
            listOf<Reply>(Reply.Status(200)) to { c -> c is TusProtocolException },
        )
        for ((heads, isCause) in cases) {
            val server = ScriptedTusServer().apply { uploadLength = 500 }
            server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "500")), *heads.toTypedArray())
            server.script(HttpMethod.Patch, Reply.Throw(IOException("response lost")))
            val e = assertFailsWith<TusUploadOutcomeUnknownException>("$heads") {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(500)), options = TusUploadOptions(retryOptions = retry()))
            }
            assertTrue(isCause(e.cause), "cause was ${e.cause}")
        }
    }

    @Test
    fun anExhaustedFinalPatch_isOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 500 }
        repeat(10) { server.script(HttpMethod.Patch, Reply.Status(502)) }
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(500)), options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(502, (e.cause as TusHttpException).statusCode)
    }

    @Test
    fun outcomeUnknown_isNotAnIOException_norAProtocolException() {
        val e: Throwable = TusUploadOutcomeUnknownException("u")
        assertFalse(e is IOException)
        assertFalse(e is TusProtocolException)
    }

    // ---------------------------------------------------------------- D2

    @Test
    fun theStoreIsFlaggedBeforeTheFinalPatch_clearedWhenHeadShowsItDidNotLand_andRemovedOnSuccess() = runTest {
        val server = ScriptedTusServer(location = "/files/new").apply { uploadLength = 1000 }
        val store = RecordingStore()
        val flagAtFinalPatch = mutableListOf<Long?>()
        server.onRequest = { r ->
            if (r.method == HttpMethod.Patch && r.headers["Upload-Offset"] == "500") flagAtFinalPatch += store.get("fp")?.finalSentAt
        }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "500"), apply = true), Reply.Status(503))
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
            options = TusUploadOptions(chunkSize = 500, retryOptions = retry()), nowMillis = { 42 })
        assertEquals(listOf<Long?>(42, 42), flagAtFinalPatch, "flag persisted before each final PATCH")
        assertEquals(listOf("set", "set-final", "set", "set-final", "remove"), store.writes)
    }

    @Test
    fun cancellationDuringTheFinalPatch_leavesTheEntryFlagged_soTheNextCallIsOutcomeUnknown() = runTest {
        val server = TusdotnetLike(1000)
        val stored = CompletableDeferred<Unit>()
        server.afterFinalStored = { stored.complete(Unit); awaitCancellation() }
        val store = InMemoryTusUrlStore()
        val job = launch(Dispatchers.Default) {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        }
        withContext(Dispatchers.Default) { withTimeout(5_000) { stored.await(); job.cancelAndJoin() } }
        assertNotNull(store.get("fp")?.finalSentAt)
        server.afterFinalStored = {}
        assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        }
        assertEquals(1, server.processed.size)
        assertEquals(1, server.methods.count { it == "POST" })
    }

    @Test
    fun aFlaggedEntry_isNeverDroppedOrRecreated() = runTest {
        // Expired by its Upload-Expires: not even contacted.
        val server = ScriptedTusServer()
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, expiresAt = 1, finalSentAt = 1)) }
        assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store, nowMillis = { 10 })
        }
        assertTrue(server.requests.isEmpty())

        // Refused permanently: still not re-created.
        val refusing = ScriptedTusServer()
        refusing.script(HttpMethod.Head, Reply.Status(403))
        val store2 = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, finalSentAt = 1)) }
        assertFailsWith<TusUploadOutcomeUnknownException> {
            refusing.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store2,
                options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(0, refusing.count(HttpMethod.Post))
        assertNotNull(store2.get("fp"))
    }

    @Test
    fun aFlaggedEntry_whoseHeadShowsMissingBytes_isResumedNormally() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, finalSentAt = 1)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)), "fp", store,
            options = TusUploadOptions(retryOptions = retry()))
        assertEquals(100, server.offset)
        assertNull(store.get("fp"))
    }

    // ---------------------------------------------------------------- F6

    @Test
    fun conflictsAndNon204Successes_onAStoredUpload_areTransient() = runTest {
        for (status in listOf(409, 200)) {
            val server = ScriptedTusServer().apply { uploadLength = 1000 }
            repeat(10) { server.script(HttpMethod.Patch, Reply.Status(status)) }
            val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 1000, null)) }
            val e = assertFailsWith<TusHttpException>("$status") {
                server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                    options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
            }
            assertEquals(status, e.statusCode)
            assertEquals(0, server.count(HttpMethod.Post), "$status must not re-create")
            assertNotNull(store.get("fp"))
        }
    }

    // ---------------------------------------------------------------- F7

    class WatchdogIo : IOException("watchdog io")
    class WatchdogBug : RuntimeException("watchdog bug")

    @Test
    fun exceptionsFromOnActivity_surfaceAsIs_atEveryStage() = runTest {
        val errors = listOf<() -> Throwable>({ CancellationException("stop") }, { WatchdogIo() }, { WatchdogBug() })
        for (make in errors) {
            for (throwOnCall in 1..4) {
                val server = ScriptedTusServer().apply { uploadLength = 100 }
                var calls = 0
                val expected = make()
                val thrown = assertFails("${expected::class.simpleName} on call $throwOnCall") {
                    server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                        options = TusUploadOptions(retryOptions = retry()),
                        onActivity = { if (++calls == throwOnCall) throw expected })
                }
                assertSame(expected, thrown, "call $throwOnCall")
                assertTrue(server.requests.size <= 2, "no retries after the callback threw: ${server.methods()}")
            }
        }
    }

    @Test
    fun anExceptionFromOnConfirmed_surfacesAsIs() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        val boom = WatchdogIo()
        val thrown = assertFails {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(retryOptions = retry()), onConfirmed = { _, _ -> throw boom })
        }
        assertSame(boom, thrown)
        assertEquals(1, server.count(HttpMethod.Patch))
    }

    // ---------------------------------------------------------------- F8

    @Test
    fun aLocalFileProblem_onAStoredUpload_isRethrown_notRecreated() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 300 }
        val file = MutableTusFile("f", testBytes(200)).apply { sizeOverride = 300 } // short read
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 300, null)) }
        assertFailsWith<TusLocalFileException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(0, server.count(HttpMethod.Post))
        assertNotNull(store.get("fp"))
    }

    // ---------------------------------------------------------------- F10

    @Test
    fun uncounted423Retries_haveAMinimumBackoff_evenWithZeroInitialDelay() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 100; lockedUntil = Long.MAX_VALUE }
        server.onRequest = { check(server.requests.size < 2_000) { "runaway: ${server.requests.size} requests" } }
        val e = assertFailsWith<TusHttpException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(
                retryOptions = RetryOptions(initialDelayMillis = 0, maxElapsedMillis = 10_000, jitter = false,
                    timeSource = testScheduler.timeSource)))
        }
        assertEquals(423, e.statusCode)
        assertTrue(server.requests.size <= 10_000 / 250 + 2, "${server.requests.size} attempts in 10 s")
    }

    // ---------------------------------------------------------------- F5 in-flight heartbeat

    private fun TestScope.slowHeadClient(silenceMillis: Long, server: ScriptedTusServer): HttpClient {
        val config = MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.method == HttpMethod.Head) {
                    delay(silenceMillis)
                    respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("100"), "Upload-Length" to listOf("100")))
                } else {
                    respond("", HttpStatusCode.MethodNotAllowed)
                }
            }
        }
        return HttpClient(MockEngine(config))
    }

    @Test
    fun aLongSilentRequest_stillProducesActivityTicks_andTheTickerStopsWithTheCall() = runTest {
        val stamps = mutableListOf<Long>()
        val client = slowHeadClient(30_000, ScriptedTusServer())
        client.uploadTus(url, InMemoryTusFile("f", testBytes(100)), onActivity = { stamps += currentTime })
        assertEquals(30_000, currentTime)
        val gaps = (listOf(0L) + stamps + listOf(currentTime)).zipWithNext { a, b -> b - a }
        assertTrue(gaps.max() <= 5_000, "silent for ${gaps.max()} ms: $stamps")
        val count = stamps.size
        advanceTimeBy(60_000)
        assertEquals(count, stamps.size, "the ticker outlived the call")
    }

    @Test
    fun theInFlightHeartbeat_canBeTurnedOff() = runTest {
        val stamps = mutableListOf<Long>()
        val client = slowHeadClient(30_000, ScriptedTusServer())
        client.uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(activityHeartbeatMillis = null), onActivity = { stamps += currentTime })
        assertEquals(listOf(0L, 30_000L), stamps.distinct())
    }
}
