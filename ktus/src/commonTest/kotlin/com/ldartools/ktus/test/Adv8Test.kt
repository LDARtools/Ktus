package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Eighth review: probes kept as regression tests, plus the stale-HEAD re-ask. */
class Adv8Test {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"
    private fun kotlinx.coroutines.test.TestScope.fast(maxRetries: Int = 5) = RetryOptions(maxRetries = maxRetries,
        initialDelayMillis = 10, maxDelayMillis = 10, jitter = false, timeSource = testScheduler.timeSource)

    /** Liveness: one 504 on the final PATCH (not applied), then a clean continuation, ends in success. */
    @Test
    fun single504OnFinalPatch_thenContinuation_succeeds() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "500"), apply = true), Reply.Status(504))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
            options = TusUploadOptions(chunkSize = 500, activityHeartbeatMillis = null, retryOptions = fast()))
        assertContentEquals(testBytes(1000), server.data)
    }

    /** L-d: a single stale HEAD below the confirmed offset is asked again; the correct answer lets it continue. */
    @Test
    fun oneStaleHeadBelowConfirmed_isAskedAgain_andTheUploadContinues() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "250"), apply = true),
            Reply.Throw(kotlinx.io.IOException("reset")))
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000")), // initial
            Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000"))) // stale instance, once
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
            options = TusUploadOptions(chunkSize = 250, activityHeartbeatMillis = null, retryOptions = fast()))
        assertContentEquals(testBytes(1000), server.data)
        assertEquals(3, server.count(HttpMethod.Head))
        assertTrue(server.patchOffsets().none { it < 250 && it != 0L } && server.patchOffsets().count { it == 0L } == 1,
            "never re-sent below the confirmed offset: ${server.patchOffsets()}")
    }

    /** L-d: a stale answer that persists through the bounded re-asks fails with TusOffsetMismatchException. */
    @Test
    fun aPersistentlyStaleHead_failsAfterABoundedNumberOfReasks() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "250"), apply = true),
            Reply.Throw(kotlinx.io.IOException("reset")))
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000")))
        repeat(10) { server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "1000"))) }
        assertFailsWith<TusOffsetMismatchException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(chunkSize = 250, activityHeartbeatMillis = null, retryOptions = fast()))
        }
        assertEquals(1 + 1 + 3, server.count(HttpMethod.Head), "initial + recovery + 3 re-asks")
        assertEquals(listOf(0L, 250L), server.patchOffsets())
    }

    /** A marked entry while simply offline is OutcomeUnknown, not an IOException. */
    @Test
    fun markedEntry_offline_isOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        repeat(10) { server.script(HttpMethod.Head, Reply.Throw(kotlinx.io.IOException("offline"))) }
        val store = InMemoryTusUrlStore()
        store.set("fp", TusUpload(url, 1000, finalSentAt = 1))
        val e = runCatching {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = fast(3)))
        }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e)
    }

    @Test
    fun expectSuccess_403OnFirstFinalPatch_isDefinitive() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        server.script(HttpMethod.Patch, Reply.Status(403))
        val e = runCatching { server.client(expectSuccess = true).uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = fast())) }.exceptionOrNull()
        assertIs<ClientRequestException>(e)
    }

    @Test
    fun expectSuccess_501OnFirstFinalPatch_isOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        server.script(HttpMethod.Patch, Reply.Status(501))
        val e = runCatching { server.client(expectSuccess = true).uploadTus(url, InMemoryTusFile("f", testBytes(100)),
            options = TusUploadOptions(activityHeartbeatMillis = null, retryOptions = fast())) }.exceptionOrNull()
        assertIs<TusUploadOutcomeUnknownException>(e)
    }
}
