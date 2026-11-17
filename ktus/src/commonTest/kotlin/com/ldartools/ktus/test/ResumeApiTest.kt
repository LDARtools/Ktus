package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** R4 (resume across attempts and restarts) and R11 (termination). */
class ResumeApiTest {

    private val createUrl = "https://example.com/files"
    private val now = 1_800_000_000_000L // a fixed "current time" in epoch ms

    // ---------------------------------------------------------------- createTusUpload / uploadTus

    @Test
    fun createTusUpload_returnsTheUrl_lengthAndExpiry() = runTest {
        val server = ScriptedTusServer(location = "/files/xyz").apply { uploadExpires = "Wed, 25 Nov 2026 10:30:00 GMT" }
        val upload = server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(42)))
        assertEquals("https://example.com/files/xyz", upload.uploadUrl)
        assertEquals(42, upload.uploadLength)
        assertEquals(parseHttpDate("Wed, 25 Nov 2026 10:30:00 GMT"), upload.expiresAt)
        assertEquals(1795602600000L, upload.expiresAt)
    }

    @Test
    fun createTusUpload_withoutOrWithAnInvalidUploadExpires_hasNoExpiry() = runTest {
        for (expires in listOf(null, "tomorrow-ish")) {
            val server = ScriptedTusServer().apply { uploadExpires = expires }
            assertNull(server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(42))).expiresAt)
        }
    }

    @Test
    fun uploadTus_resumesAnExistingUrl_startingWithHead() = runTest {
        val file = InMemoryTusFile("f", testBytes(900))
        val server = ScriptedTusServer().apply { uploadLength = 900; data = file.readAll().copyOfRange(0, 300) }
        server.client().uploadTus("https://example.com/files/abc", file, options = TusUploadOptions(chunkSize = 300))
        assertEquals(listOf("HEAD", "PATCH", "PATCH"), server.methods())
        assertEquals(listOf(300L, 600L), server.patchOffsets())
        assertContentEquals(file.readAll(), server.data)
    }

    // ---------------------------------------------------------------- resumeOrCreateAndUploadTus

    @Test
    fun aStoredUpload_isResumed_withoutCreatingANewOne_andForgottenOnSuccess() = runTest {
        val file = InMemoryTusFile("f", testBytes(600))
        val server = ScriptedTusServer().apply { uploadLength = 600; data = file.readAll().copyOfRange(0, 200) }
        val store = InMemoryTusUrlStore()
        store.set("fp", TusUpload("https://example.com/files/abc", 600, expiresAt = now + 60_000))
        var created = false

        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store,
            options = TusUploadOptions(chunkSize = 200), onCreate = { created = true }, nowMillis = { now })

        assertEquals(listOf("HEAD", "PATCH", "PATCH"), server.methods())
        assertFalse(created)
        assertContentEquals(file.readAll(), server.data)
        assertTrue(store.snapshot().isEmpty(), "the entry is removed after success")
    }

    @Test
    fun aStoredUploadWithoutExpiry_isResumed() = runTest {
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer().apply { uploadLength = 100 }
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/abc", 100, null)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
        assertEquals(0, server.count(HttpMethod.Post))
    }

    @Test
    fun aNewUpload_isSavedBeforeAnyDataIsSent() = runTest {
        val file = InMemoryTusFile("f", testBytes(400))
        val server = ScriptedTusServer(location = "/files/new")
        val store = InMemoryTusUrlStore()
        val seenAtFirstPatch = mutableListOf<TusUpload?>()
        server.onRequest = { request ->
            if (request.method == HttpMethod.Patch && seenAtFirstPatch.isEmpty()) seenAtFirstPatch += store.get("fp")
        }
        server.uploadExpires = "Wed, 25 Nov 2026 10:30:00 GMT"
        var createdUrl: String? = null

        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store,
            options = TusUploadOptions(chunkSize = 200), onCreate = { createdUrl = it }, nowMillis = { now })

        assertEquals(TusUpload("https://example.com/files/new", 400, 1795602600000L), seenAtFirstPatch.single())
        assertEquals("https://example.com/files/new", createdUrl)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun aFailedUpload_keepsTheEntry_forTheNextAttempt() = runTest {
        // 600 bytes so the refused PATCH is not the final one (a flagged entry would throw OutcomeUnknown).
        val file = InMemoryTusFile("f", testBytes(600))
        val server = ScriptedTusServer(location = "/files/new")
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "200"), apply = true), Reply.Status(403))
        val store = InMemoryTusUrlStore()
        assertFailsWith<TusHttpException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store,
                options = TusUploadOptions(chunkSize = 200), nowMillis = { now })
        }
        assertEquals("https://example.com/files/new", store.get("fp")?.uploadUrl)

        // Next attempt resumes at 200 without a POST.
        server.requests.clear()
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store,
            options = TusUploadOptions(chunkSize = 200), nowMillis = { now })
        assertEquals(listOf("HEAD", "PATCH", "PATCH"), server.methods())
        assertContentEquals(file.readAll(), server.data)
        assertNull(store.get("fp"))
    }

    @Test
    fun anExpiredStoredUpload_isNotContacted_andANewOneIsCreated() = runTest {
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer(location = "/files/new")
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, expiresAt = now)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
        assertEquals(listOf("OPTIONS", "POST", "HEAD", "PATCH"), server.methods())
        assertFalse(server.requests.any { it.url.endsWith("/old") })
    }

    @Test
    fun aStoredUploadTheServerForgot_isRecreated() = runTest {
        for (status in listOf(404, 410)) {
            val file = InMemoryTusFile("f", testBytes(100))
            val server = ScriptedTusServer(location = "/files/new")
            server.script(HttpMethod.Head, Reply.Status(status))
            val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
            assertEquals(listOf("HEAD", "OPTIONS", "POST", "HEAD", "PATCH"), server.methods(), "$status")
            assertContentEquals(file.readAll(), server.data)
            assertNull(store.get("fp"))
        }
    }

    @Test
    fun aStoredUploadWithADifferentServerLength_isRecreated() = runTest {
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer(location = "/files/new")
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "50", "Upload-Length" to "150")))
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
        assertEquals(listOf("HEAD", "OPTIONS", "POST", "HEAD", "PATCH"), server.methods())
        assertEquals(listOf(0L), server.patchOffsets(), "the new upload starts from 0, not the old offset")
    }

    @Test
    fun aStoredUploadForADifferentFileSize_isRecreated_withoutContactingIt() = runTest {
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer(location = "/files/new")
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 99, null)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
        assertEquals(listOf("OPTIONS", "POST", "HEAD", "PATCH"), server.methods())
    }

    @Test
    fun aStoredUploadOnAnotherHost_isNotContacted() = runTest {
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer(location = "/files/new")
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://evil.example.net/files/old", 100, null)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
        assertFalse(server.requests.any { it.url.contains("evil") })
        assertEquals(listOf("OPTIONS", "POST", "HEAD", "PATCH"), server.methods())
    }

    @Test
    fun transientResumeFailures_propagate_andKeepTheEntry() = runTest {
        // A permanent refusal (403) now re-creates the upload; see ProductionHardeningTest.
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer()
        repeat(5) { server.script(HttpMethod.Head, Reply.Status(502)) }
        val store = InMemoryTusUrlStore().apply { set("fp", TusUpload("https://example.com/files/old", 100, null)) }
        assertFailsWith<TusHttpException> {
            server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now },
                options = TusUploadOptions(retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1,
                    timeSource = testScheduler.timeSource)))
        }
        assertEquals(0, server.count(HttpMethod.Post))
        assertNotNull(store.get("fp"))
    }

    @Test
    fun differentFingerprints_doNotShareUploads() = runTest {
        val file = InMemoryTusFile("f", testBytes(100))
        val server = ScriptedTusServer(location = "/files/new")
        val store = InMemoryTusUrlStore().apply { set("other", TusUpload("https://example.com/files/old", 100, null)) }
        server.client().resumeOrCreateAndUploadTus(createUrl, file, "fp", store, nowMillis = { now })
        assertEquals(1, server.count(HttpMethod.Post))
        assertNotNull(store.get("other"))
    }

    // ---------------------------------------------------------------- R11

    @Test
    fun terminateTus_sendsDeleteWithTusResumable() = runTest {
        val server = ScriptedTusServer()
        server.client().terminateTus("https://example.com/files/abc")
        val request = server.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("https://example.com/files/abc", request.url)
        assertEquals("1.0.0", request.headers["Tus-Resumable"])
    }

    @Test
    fun terminateTus_retriesTransientFailures_andReportsRefusals() = runTest {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Delete, Reply.Status(503), Reply.Status(204))
        server.client().terminateTus("https://example.com/files/abc",
            options = TusUploadOptions(retryOptions = RetryOptions(initialDelayMillis = 1, timeSource = testScheduler.timeSource)))
        assertEquals(2, server.count(HttpMethod.Delete))

        val refusing = ScriptedTusServer()
        refusing.script(HttpMethod.Delete, Reply.Status(405))
        val error = assertFailsWith<TusHttpException> { refusing.client().terminateTus("https://example.com/files/abc") }
        assertEquals(405, error.statusCode)
        assertEquals("DELETE", error.method)
    }
}
