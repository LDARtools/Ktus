package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Adv15Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    private fun opts(sched: kotlinx.coroutines.test.TestCoroutineScheduler) = TusUploadOptions(
        activityHeartbeatMillis = null,
        retryOptions = RetryOptions(maxRetries = 1_000, initialDelayMillis = 7_000, maxDelayMillis = 7_000,
            jitter = false, maxElapsedMillis = 10_000, timeSource = sched.timeSource))

    /**
     * The stored upload fails with a 503, then its HEAD is answered 404 just after the budget ran out. That
     * definitive answer ends the run: the fall-through sends OPTIONS only after the 404, and the new upload
     * completes. Nothing was sent after the spent budget before that answer.
     */
    @Test
    fun resumeFallThrough_startsAfterTheDefinitiveAnswer_withAFreshBudget() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime }, dispatcher = StandardTestDispatcher(sched))
        server.script(HttpMethod.Head, Reply.Status(503), Reply.Custom { _, _ ->
            delay(5_000) // in flight while the budget runs out (t = 7 s .. 12 s)
            respond("", HttpStatusCode.NotFound)
        })
        val store = InMemoryTusUrlStore()
        store.set("fp", TusUpload(url, 10))
        val r = runCatching {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), "fp", store,
                options = opts(sched))
        }
        assertTrue(r.isSuccess, "$r")
        val heads = server.times(HttpMethod.Head)
        assertEquals(listOf(0L, 7_000L), heads.take(2), "the stored upload's HEADs")
        assertTrue(server.times(HttpMethod.Options).all { it >= 12_000 }, "OPTIONS before the 404: ${server.times(HttpMethod.Options)}")
        assertEquals(10, server.offset)
    }

    /** The new upload created by the fall-through gets a full budget: a 503 on its OPTIONS is retried. */
    @Test
    fun resumeFallThrough_newUploadGetsAFreshRetryBudget() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime }, dispatcher = StandardTestDispatcher(sched))
        server.script(HttpMethod.Head, Reply.Status(503), Reply.Custom { _, _ ->
            delay(5_000)
            respond("", HttpStatusCode.NotFound)
        })
        server.script(HttpMethod.Options, Reply.Status(503))
        val store = InMemoryTusUrlStore()
        store.set("fp", TusUpload(url, 10))
        val r = runCatching {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), "fp", store,
                options = opts(sched))
        }
        assertTrue(r.isSuccess, "a single 503 on the new upload's OPTIONS ended the call: $r, options=${server.count(HttpMethod.Options)}")
        assertEquals(2, server.count(HttpMethod.Options))
    }

    /**
     * Documented: the last resolution HEAD is sent only when Ktus gives up retrying the final PATCH itself. When
     * a recovery HEAD is what runs out, no further HEAD is sent, and the outcome is unknown.
     */
    @Test
    fun givingUpOnUnresolvedFinal_viaRecoveryHead_sendsNoLastHead() = runTest {
        val sched = testScheduler
        val server = ScriptedTusServer(clock = { sched.currentTime }).apply { uploadLength = 10 }
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "10")))
        server.script(HttpMethod.Patch, Reply.Status(504))
        repeat(50) { server.script(HttpMethod.Head, Reply.Status(503)) }
        val r = runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(10)), options = opts(sched))
        }
        assertIs<TusUploadOutcomeUnknownException>(r.exceptionOrNull())
        // The first HEAD, then the one recovery HEAD (its retry would not fit the budget); nothing after.
        assertEquals(listOf(0L, 7_000L), server.times(HttpMethod.Head))
    }
}
