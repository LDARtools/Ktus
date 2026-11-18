package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Adv16Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    /**
     * Documented: when Ktus gives up retrying a PATCH while a final PATCH is unresolved, it sends one last HEAD.
     * Here the final PATCH times out, the chunk is halved, and Ktus gives up on a NON-final PATCH: the last HEAD
     * is still sent.
     */
    @Test
    fun lastResolutionHead_isAlsoSentWhenGivingUpOnANonFinalPatch() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime })
        server.script(HttpMethod.Patch,
            Reply.Throw(SocketTimeoutException("read timed out")), // final PATCH [0,10): unresolved, chunk halved
            Reply.Status(503), Reply.Status(503), Reply.Status(503), Reply.Status(503))
        server.uploadLength = 10
        val opts = TusUploadOptions(chunkSize = 10, minChunkSize = 4, activityHeartbeatMillis = null,
            retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
                timeSource = sched.timeSource))
        val r = runCatching { server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = opts) }
        assertIs<TusUploadOutcomeUnknownException>(r.exceptionOrNull())
        val log = server.requests.map { "${it.method}@${it.uploadOffset}/${it.bodySize}" }
        val lastPatch = server.requests.indexOfLast { it.method == "PATCH" }
        assertEquals(5, server.requests[lastPatch].bodySize, "gave up on a non-final 5-byte PATCH: $log")
        assertEquals(lastPatch + 1, server.requests.lastIndex, "exactly one last HEAD after giving up: $log")
        assertEquals("HEAD", server.requests.last().method, log.toString())
    }

    /**
     * A stored, UNMARKED entry: the single PATCH carrying the whole file is answered 404 (exactly what tusdotnet
     * answers once another request completed the upload and the server removed it). The outcome is unknown:
     * the entry stays, marked, and nothing is re-created or re-sent.
     */
    @Test
    fun finalPatch404_isUploadGone_andResumeOrCreateDoesNotReupload() = runTest {
        val server = ScriptedTusServer()
        server.uploadLength = 10
        server.script(HttpMethod.Patch, Reply.Status(404))
        val store = InMemoryTusUrlStore()
        store.set("fp", TusUpload(url, 10))
        val r = runCatching {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), "fp", store,
                options = TusUploadOptions(activityHeartbeatMillis = null))
        }
        val e = assertIs<TusUploadOutcomeUnknownException>(r.exceptionOrNull(), "$r")
        assertEquals(TusUploadOutcomeUnknownException.Kind.UPLOAD_GONE, e.kind)
        assertEquals(0, server.count(HttpMethod.Post), "no new upload")
        assertEquals(listOf(10), server.patchSizes(), "the file was sent once")
        assertNotNull(store.get("fp")?.finalSentAt, "the entry stays marked")
    }

    /** Same with createAndUploadTus: a 404 on the only final PATCH is UPLOAD_GONE, not a plain expired upload. */
    @Test
    fun finalPatch404_createAndUpload_isUploadGone() = runTest {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Patch, Reply.Status(404))
        val r = runCatching {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)),
                options = TusUploadOptions(activityHeartbeatMillis = null))
        }
        val e = assertIs<TusUploadOutcomeUnknownException>(r.exceptionOrNull(), "$r")
        assertEquals(TusUploadOutcomeUnknownException.Kind.UPLOAD_GONE, e.kind)
        assertIs<TusUploadExpiredException>(e.cause)
        assertEquals(1, server.count(HttpMethod.Post))
        assertEquals(1, server.count(HttpMethod.Patch))
    }

    /** Out of scope, unchanged: a 404 on a NON-final PATCH is still a plain expired upload. */
    @Test
    fun nonFinalPatch404_isStillExpired() = runTest {
        val server = ScriptedTusServer()
        server.uploadLength = 20
        server.script(HttpMethod.Patch, Reply.Status(404))
        val r = runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(20)),
                options = TusUploadOptions(chunkSize = 10, activityHeartbeatMillis = null))
        }
        assertIs<TusUploadExpiredException>(r.exceptionOrNull(), "$r")
    }
}
