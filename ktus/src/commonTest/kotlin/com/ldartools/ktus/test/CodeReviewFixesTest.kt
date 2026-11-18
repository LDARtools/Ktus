package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Fixes for the /code-review findings on the 1.1.0 PR. */
class CodeReviewFixesTest {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"

    private fun TestScope.retry() = RetryOptions(
        maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 10, jitter = false,
        timeSource = testScheduler.timeSource)

    // ---------------------------------------------------------------- a cancelled caller leaves no mark

    @Test
    fun aCallerCancelledBeforeTheFinalPatch_leavesNoMarkAndDoesNotCallOnFinalPatch() = runTest {
        val store = InMemoryTusUrlStore()
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        var finalPatchCalls = 0
        lateinit var job: Job
        job = launch {
            server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp", store,
                options = TusUploadOptions(chunkSize = 500, retryOptions = retry()),
                // The first chunk is confirmed: the caller cancels before the final chunk is sent.
                onConfirmed = { offset, _ -> if (offset == 500L) job.cancel() },
                onFinalPatch = { finalPatchCalls++ })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, finalPatchCalls, "onFinalPatch ran for a PATCH that was never sent")
        assertEquals(listOf(0L), server.patchOffsets(), "only the first chunk may have been sent")
        assertNull(store.get("fp")?.finalSentAt, "a PATCH that never left must not mark the entry")
    }

    // ---------------------------------------------------------------- refusals before the body is applied

    @Test
    fun a401Or409Or423OnTheFinalPatch_doesNotTurnALaterRefusalIntoOutcomeUnknown() = runTest {
        for (first in listOf(401, 409, 423)) {
            val store = InMemoryTusUrlStore()
            store.set("fp", TusUpload(url, 1000))
            val server = ScriptedTusServer().apply { uploadLength = 1000 }
            // The (single, final) PATCH is refused before anything is applied, then refused outright. The 403 is
            // definitive, so resumeOrCreate starts a new upload; that one is refused too, ending the call.
            // (Before the fix the first upload ended as outcome unknown and was never re-created.)
            server.script(HttpMethod.Patch, Reply.Status(first), Reply.Status(403), Reply.Status(403))
            val e = assertFails("first answer $first") {
                server.client().resumeOrCreateAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(1000)), "fp",
                    store, options = TusUploadOptions(retryOptions = retry()), nowMillis = { 0L })
            }
            assertIsNot<TusUploadOutcomeUnknownException>(e, "first answer $first: $e")
            assertEquals(0L, server.offset, "first answer $first")
            assertNull(store.get("fp")?.finalSentAt, "first answer $first: the mark must be cleared")
        }
    }

    @Test
    fun a5xxOnTheFinalPatch_stillMakesALaterRefusalOutcomeUnknown() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        server.script(HttpMethod.Patch, Reply.Status(503), Reply.Status(403))
        assertFailsWith<TusUploadOutcomeUnknownException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
                options = TusUploadOptions(retryOptions = retry()))
        }
    }

    // ---------------------------------------------------------------- default writeSection

    @Test
    fun aLegacyReadSectionThatEndsEarly_isALocalFileFailure() = runTest {
        val server = ScriptedTusServer().apply { uploadLength = 1000 }
        // The server records a PATCH only once its body is read, which fails here: count arrivals instead.
        var patches = 0
        server.onRequest = { if (it.method == HttpMethod.Patch) patches++ }
        val short = object : ITusFile {
            override val size = 1000L
            override val name = "short"
            override suspend fun readSection(offset: Long, length: Long) = ByteReadChannel(ByteArray(10))
            override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
        }
        assertFailsWith<TusLocalFileException> {
            server.client().uploadTus(url, short, options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(1, patches, "a local file failure is not retried")
    }
}
