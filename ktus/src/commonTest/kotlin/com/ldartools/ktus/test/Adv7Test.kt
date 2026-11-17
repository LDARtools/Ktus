package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Adv7Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    /** README: minDelayMillis means "every retry waits at least that long". Near the budget end it does not. */
    @Test
    fun adv7_minDelayFloor_isNotHonouredNearTheBudgetEnd() = runTest {
        val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 100 }
        server.script(HttpMethod.Patch, Reply.Status(504), Reply.Status(504))
        // Near the end of the budget the call gives up (an unknown outcome: the final PATCH got 504s) instead
        // of retrying early.
        val e = runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = RetryOptions(
                    initialDelayMillis = 10, minDelayMillis = 1_000, maxElapsedMillis = 1_500, jitter = false,
                    timeSource = testScheduler.timeSource)))
        }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e)
        assertEquals(2, server.count(HttpMethod.Patch), "the third PATCH would have been early, so it is not sent")
        val gaps = server.times(HttpMethod.Patch).zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= 1_000 }, "PATCH re-sent sooner than minDelayMillis after a 504: gaps=$gaps")
    }

    /** KDoc/README/CHANGELOG: a short local read on the final PATCH is a definitive refusal that clears the mark. */
    @Test
    fun adv7_shortReadOnFinalPatch_clearsTheMark_asDocumented() = runTest {
        val server = ScriptedTusServer(location = "/files/new")
        val file = MutableTusFile("f", testBytes(200)).apply { sizeOverride = 300 }
        val store = InMemoryTusUrlStore()
        assertFailsWith<TusLocalFileException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store,
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = RetryOptions(
                    maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
                    timeSource = testScheduler.timeSource)))
        }
        assertNull(store.get("fp")?.finalSentAt, "mark left set after a definitive short read: ${store.get("fp")}")
    }

    /**
     * A final PATCH that once ended unknown stays uncertain across calls, even after a later 204 below the
     * total: the store mark is kept, so the next call reports OutcomeUnknown on the 404 instead of re-creating
     * (the first final PATCH may have completed on another instance).
     */
    @Test
    fun adv7_uncertainFinalPatch_staysUncertainAcrossCalls_evenAfterA204BelowTotal() = runTest {
        val ghostServer = GhostServer()
        val store = InMemoryTusUrlStore()
        val file = InMemoryTusFile("f", testBytes(1000))
        val opts = ghostOptions()
        val e1 = runCatching { ghostServer.client.resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts) }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e1)
        val markAfter1 = store.get("fp")
        assertNotNull(markAfter1?.finalSentAt, "the mark survives the 204 below the total")
        ghostServer.ghostCompletes()
        val e2 = runCatching { ghostServer.client.resumeOrCreateAndUploadTus(createUrl, file, "fp", store, options = opts) }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e2)
        assertEquals(1, ghostServer.processed.size, "processed=${ghostServer.processed}; call1=$e1 entry after call1=$markAfter1; call2=$e2")
    }

    /** The same without a store (Chateau's call shape): the call must not end with a plain IOException. */
    @Test
    fun adv7_noStore_uncertainFinalPatch_thenA204_thenNetworkLoss_isOutcomeUnknown() = runTest {
        val ghostServer = GhostServer()
        val e = runCatching {
            ghostServer.client.createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), options = ghostOptions())
        }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e)
        assertIs<kotlinx.io.IOException>(e.cause)
    }

    private fun kotlinx.coroutines.test.TestScope.ghostOptions() = TusUploadOptions(chunkSize = 1000, minChunkSize = 250,
        activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10,
            maxDelayMillis = 10, jitter = false, timeSource = testScheduler.timeSource))

    /**
     * The first (final) PATCH gets 504 while the origin keeps writing; a smaller PATCH then gets 204 at 250;
     * after that the network is lost for that upload.
     */
    private class GhostServer {
        val uploads = SyncMap<String, ByteArray>()
        val processed = SyncList<String>()
        var nextId = 0
        var patchNo = 0
        var ghost: String? = null

        fun ghostCompletes() {
            // The ghost final PATCH (0..1000) completes on the other instance; the server processes and removes it.
            uploads.remove(ghost!!); processed += ghost!!
        }

        val client = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.substringAfterLast('/')
            when (request.method) {
                HttpMethod.Options -> respond("", HttpStatusCode.NoContent, headersOf("Tus-Resumable", "1.0.0"))
                HttpMethod.Post -> {
                    val newId = "u${nextId++}"; uploads[newId] = ByteArray(0)
                    respond("", HttpStatusCode.Created, headersOf("Location", "/files/$newId"))
                }
                HttpMethod.Head -> {
                    val d = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                    respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("${d.size}"), "Upload-Length" to listOf("1000")))
                }
                HttpMethod.Patch -> {
                    val n = patchNo++
                    val body = ScriptedTusServer.readBody(request)
                    val off = request.headers["Upload-Offset"]!!.toLong()
                    val d = uploads[id] ?: return@MockEngine respond("", HttpStatusCode.NotFound)
                    when {
                        n == 0 -> { ghost = id; respond("", HttpStatusCode.GatewayTimeout) } // origin still writing
                        id == "u0" && n == 1 -> { uploads[id] = d + body.copyOf(250); respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "${off + 250}")) }
                        id == "u0" -> throw kotlinx.io.IOException("offline")
                        else -> {
                            val now = d + body; uploads[id] = now
                            if (now.size == 1000) { processed += id; uploads.remove(id) }
                            respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "${now.size}"))
                        }
                    }
                }
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        }) { expectSuccess = false }
    }

    /** L4: a final PATCH answered by another origin (the client followed a redirect) is an unknown outcome. */
    @Test
    fun aFinalPatchAnsweredByAnotherOrigin_isOutcomeUnknown() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.host == "other.example" -> respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "100"))
                request.method == HttpMethod.Head -> respond("", HttpStatusCode.OK,
                    headersOf("Upload-Offset" to listOf("0"), "Upload-Length" to listOf("100")))
                else -> respond("", HttpStatusCode.TemporaryRedirect, headersOf("Location", "https://other.example/files/abc"))
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpRedirect) { checkHttpMethod = false } // follows the PATCH redirect
        }
        val e = assertFailsWith<TusUploadOutcomeUnknownException> {
            client.uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(activityHeartbeatMillis = null,
                retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10, jitter = false, timeSource = testScheduler.timeSource)))
        }
        assertTrue(e.cause!!.message!!.contains("answered by"), "${e.cause}")
    }
}
