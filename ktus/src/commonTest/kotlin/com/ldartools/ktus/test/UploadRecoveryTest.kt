package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

/** R3 (HEAD before resuming), R5 (validation), R6 (backoff on PATCH), R7 (adaptive chunks), onConfirmed. */
class UploadRecoveryTest {

    private val url = "https://example.com/files/abc"

    private fun TestScope.retry(maxRetries: Int = 6) = RetryOptions(
        maxRetries = maxRetries, initialDelayMillis = 100, maxDelayMillis = 100, jitter = false,
        timeSource = testScheduler.timeSource,
    )

    private fun server(size: Int) = ScriptedTusServer(clock = { 0L }).apply { uploadLength = size.toLong() }

    // ---------------------------------------------------------------- R3

    @Test
    fun anUnknownPatchOutcome_isFollowedByHead_andTheUploadResumesFromTheServerOffset() = runTest {
        val failures = listOf<() -> Reply>(
            { Reply.Throw(IOException("connection reset"), apply = true) },
            { Reply.Throw(HttpRequestTimeoutException(url, 1, null), apply = true) },
            { Reply.Status(500, apply = true) },
            { Reply.Status(502, apply = true) },
            { Reply.Status(503, apply = true) },
            { Reply.Status(504, apply = true) },
            { Reply.Status(408, apply = true) },
            { Reply.Status(423, apply = true) },
            { Reply.Status(429, apply = true) },
            { Reply.Status(200, apply = true) },
        )
        for (expectSuccess in listOf(false, true)) {
            for (failure in failures) {
                val server = server(1000)
                // The second chunk is stored by the server but the client never learns it.
                server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "250"), apply = true), failure())
                val file = InMemoryTusFile("f", testBytes(1000))

                server.client(expectSuccess).uploadTus(url, file,
                    options = TusUploadOptions(chunkSize = 250, adaptiveChunkSize = false, retryOptions = retry()))

                val name = "${failure()::class.simpleName} expectSuccess=$expectSuccess"
                assertEquals(listOf("HEAD", "PATCH", "PATCH", "HEAD", "PATCH", "PATCH"), server.methods(), name)
                assertEquals(listOf(0L, 250L, 500L, 750L), server.patchOffsets(), "$name: never re-send at the old offset")
                assertContentEquals(file.readAll(), server.data, name)
            }
        }
    }

    @Test
    fun anUnknownPatchOutcome_thatTheServerDidNotApply_resendsFromTheSameOffsetAfterHead() = runTest {
        val server = server(500)
        server.script(HttpMethod.Patch, Reply.Throw(IOException("reset before the body arrived")))
        val file = InMemoryTusFile("f", testBytes(500))
        server.client().uploadTus(url, file, options = TusUploadOptions(chunkSize = 500, retryOptions = retry()))
        assertEquals(listOf("HEAD", "PATCH", "HEAD", "PATCH"), server.methods())
        assertContentEquals(file.readAll(), server.data)
    }

    @Test
    fun conflict_isFollowedByHead_inBothExpectSuccessModes() = runTest {
        for (expectSuccess in listOf(false, true)) {
            val server = server(400)
            server.script(HttpMethod.Patch, Reply.Status(409, apply = true))
            val file = InMemoryTusFile("f", testBytes(400))
            server.client(expectSuccess).uploadTus(url, file,
                options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
            assertEquals(listOf("HEAD", "PATCH", "HEAD", "PATCH"), server.methods())
            assertEquals(listOf(0L, 200L), server.patchOffsets())
            assertContentEquals(file.readAll(), server.data)
        }
    }

    // ---------------------------------------------------------------- R6

    @Test
    fun everyFailedPatch_backsOffBeforeTheHead() = runTest {
        for (failure in listOf<Reply>(Reply.Status(200), Reply.Status(409), Reply.Status(503), Reply.Throw(IOException("x")))) {
            val server = ScriptedTusServer(clock = { currentTime }).apply { uploadLength = 100 }
            server.script(HttpMethod.Patch, failure)
            val start = currentTime
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(retryOptions = retry()))
            val patchTime = server.times(HttpMethod.Patch).first()
            val recoveryHead = server.times(HttpMethod.Head)[1]
            assertEquals(100, recoveryHead - patchTime, "${failure::class.simpleName}: backoff before HEAD")
            assertEquals(100, currentTime - start)
        }
    }

    @Test
    fun consecutivePatchFailures_giveUpAfterMaxRetries_withTheLastStatus() = runTest {
        val server = server(100)
        repeat(20) { server.script(HttpMethod.Patch, Reply.Status(503)) }
        val error = assertFailsWith<TusHttpException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)),
                options = TusUploadOptions(retryOptions = retry(maxRetries = 4)))
        }
        assertEquals(503, error.statusCode)
        assertEquals("PATCH", error.method)
        assertTrue(error.message!!.contains("4 consecutive PATCH failures"), error.message)
        assertEquals(4, server.count(HttpMethod.Patch))
    }

    // ---------------------------------------------------------------- R5

    @Test
    fun headOffset_outsideTheFile_isAProtocolError() = runTest {
        for (offset in listOf("-1", "101", "999999")) {
            val server = server(100)
            server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to offset)))
            assertFailsWith<TusOffsetMismatchException>("Upload-Offset $offset") {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
            }
            assertEquals(0, server.count(HttpMethod.Patch))
        }
    }

    @Test
    fun headOffset_unparseable_isAProtocolError() = runTest {
        val server = server(100)
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "ten")))
        assertFailsWith<TusProtocolException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
        }
    }

    @Test
    fun headUploadLength_differentFromTheFile_isALengthMismatch() = runTest {
        val server = server(100)
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "120")))
        val error = assertFailsWith<TusUploadLengthMismatchException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
        }
        assertEquals(120, error.serverLength)
        assertEquals(100, error.localLength)
        assertEquals(0, server.count(HttpMethod.Patch))
    }

    @Test
    fun headUploadLength_unparseable_isAProtocolError() = runTest {
        val server = server(100)
        server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "lots")))
        assertFailsWith<TusProtocolException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
        }
    }

    @Test
    fun headWithoutUploadLength_isAccepted() = runTest {
        val server = server(100).apply { reportUploadLength = false }
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(100)), options = TusUploadOptions(retryOptions = retry()))
        assertEquals(100, server.offset)
    }

    @Test
    fun patchOffset_beyondTheBytesSent_isAnOffsetMismatch() = runTest {
        val server = server(400)
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "201")))
        assertFailsWith<TusOffsetMismatchException> {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(400)),
                options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
        }
        assertEquals(1, server.count(HttpMethod.Patch))
    }

    @Test
    fun patchOffset_notAdvancing_orGoingBack_isAnOffsetMismatch() = runTest {
        for (reported in listOf("0", "-5")) {
            val server = server(400)
            server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to reported)))
            assertFailsWith<TusOffsetMismatchException>(reported) {
                server.client().uploadTus(url, InMemoryTusFile("f", testBytes(400)),
                    options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
            }
        }
    }

    @Test
    fun aPartialPatch_isAccepted_andTheNextChunkStartsWhereTheServerStopped() = runTest {
        val server = server(400)
        server.script(HttpMethod.Patch, Reply.Custom { _, body ->
            // Store only the first 50 bytes of the chunk.
            server.data += body.copyOfRange(0, 50)
            respondNoContent(server.offset)
        })
        val file = InMemoryTusFile("f", testBytes(400))
        server.client().uploadTus(url, file, options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
        assertEquals(listOf(0L, 50L, 250L), server.patchOffsets())
        assertContentEquals(file.readAll(), server.data)
    }

    @Test
    fun aFileThatChangesSize_failsTheUpload() = runTest {
        val file = MutableTusFile("f", testBytes(600))
        val server = server(600)
        server.onRequest = { request ->
            if (request.method == HttpMethod.Patch && server.count(HttpMethod.Patch) == 1) file.sizeOverride = 700
        }
        val error = assertFailsWith<TusProtocolException> {
            server.client().uploadTus(url, file, options = TusUploadOptions(chunkSize = 200, retryOptions = retry()))
        }
        assertTrue(error.message!!.contains("changed size"), error.message)
        assertEquals(2, server.count(HttpMethod.Patch), "no PATCH after the size change was noticed")
    }

    @Test
    fun anEmptyFile_isRejectedByDefault_beforeAnyRequest() = runTest {
        val server = ScriptedTusServer()
        assertFailsWith<IllegalArgumentException> {
            server.client().createAndUploadTus("https://example.com/files", InMemoryTusFile("e", ByteArray(0)))
        }
        assertFailsWith<IllegalArgumentException> {
            server.client().uploadTus(url, InMemoryTusFile("e", ByteArray(0)))
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun anEmptyFile_isUploaded_whenAllowed() = runTest {
        val server = ScriptedTusServer()
        val confirmed = mutableListOf<Pair<Long, Long>>()
        server.client().createAndUploadTus("https://example.com/files", InMemoryTusFile("e", ByteArray(0)),
            options = TusUploadOptions(allowEmptyUpload = true), onConfirmed = { o, t -> confirmed += o to t })
        assertEquals(listOf("OPTIONS", "POST", "HEAD"), server.methods())
        assertEquals(listOf(0L to 0L), confirmed)
    }

    @Test
    fun aShortRead_failsWithoutRetrying_andWithoutSendingATruncatedChunk() = runTest {
        // The file claims 300 bytes but only 250 can be read.
        val file = MutableTusFile("f", testBytes(250)).apply { sizeOverride = 300 }
        val server = server(300)
        var patchAttempts = 0
        server.onRequest = { if (it.method == HttpMethod.Patch) patchAttempts++ }
        val error = assertFailsWith<TusProtocolException> {
            server.client().uploadTus(url, file, options = TusUploadOptions(chunkSize = 300, retryOptions = retry()))
        }
        assertTrue(error.message!!.contains("ended early"), error.message)
        assertEquals(0, server.offset, "the truncated chunk must not be stored")
        assertEquals(1, patchAttempts, "a short read is not retried")
    }

    // ---------------------------------------------------------------- R7

    @Test
    fun timeouts_halveTheChunkSize_downToTheFloor() = runTest {
        val server = server(4096)
        server.script(HttpMethod.Patch,
            Reply.Status(504), Reply.Throw(HttpRequestTimeoutException(url, 1, null)), Reply.Status(408), Reply.Status(504))
        val file = InMemoryTusFile("f", testBytes(4096))
        server.client().uploadTus(url, file, options = TusUploadOptions(
            chunkSize = 1024, minChunkSize = 256, retryOptions = retry(maxRetries = 10)))
        val sizes = server.patchSizes()
        assertEquals(listOf(1024, 512, 256, 256), sizes.take(4), "halve on 504/timeout/408, never below the floor")
        assertTrue(sizes.drop(4).all { it == 256 }, "smaller chunks are kept for the rest of the upload: $sizes")
        assertContentEquals(file.readAll(), server.data)
    }

    @Test
    fun otherFailures_doNotShrinkTheChunk() = runTest {
        val server = server(2048)
        server.script(HttpMethod.Patch, Reply.Status(500), Reply.Status(503), Reply.Throw(IOException("reset")), Reply.Status(409))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(2048)), options = TusUploadOptions(
            chunkSize = 1024, minChunkSize = 256, retryOptions = retry(maxRetries = 10)))
        assertTrue(server.patchSizes().all { it == 1024 }, "${server.patchSizes()}")
    }

    @Test
    fun adaptiveChunkSize_canBeTurnedOff() = runTest {
        val server = server(1024)
        server.script(HttpMethod.Patch, Reply.Status(504), Reply.Status(504))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1024)), options = TusUploadOptions(
            chunkSize = 1024, minChunkSize = 256, adaptiveChunkSize = false, retryOptions = retry()))
        assertEquals(listOf(1024, 1024, 1024), server.patchSizes())
    }

    @Test
    fun theFloor_isNeverAboveTheConfiguredChunkSize() = runTest {
        val server = server(300)
        server.script(HttpMethod.Patch, Reply.Status(504), Reply.Status(504))
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(300)), options = TusUploadOptions(
            chunkSize = 100, minChunkSize = 256 * 1024, retryOptions = retry()))
        assertTrue(server.patchSizes().all { it == 100 }, "${server.patchSizes()}")
    }

    @Test
    fun theDefaultFloor_is256KiB() {
        assertEquals(256L * 1024, TusUploadOptions().minChunkSize)
        assertTrue(TusUploadOptions().adaptiveChunkSize)
    }

    // ---------------------------------------------------------------- onConfirmed / onProgress

    @Test
    fun onConfirmed_reportsEachAcceptedPatch_andCompletionOnce() = runTest {
        val server = server(1000)
        val confirmed = mutableListOf<Pair<Long, Long>>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
            options = TusUploadOptions(chunkSize = 300, retryOptions = retry()),
            onConfirmed = { o, t -> confirmed += o to t })
        assertEquals(listOf(300L, 600L, 900L, 1000L).map { it to 1000L }, confirmed)
    }

    @Test
    fun onConfirmed_firesOnce_whenHeadShowsTheUploadIsAlreadyComplete() = runTest {
        val server = server(1000).apply { data = testBytes(1000) }
        val confirmed = mutableListOf<Pair<Long, Long>>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
            onConfirmed = { o, t -> confirmed += o to t })
        assertEquals(listOf(1000L to 1000L), confirmed)
        assertEquals(0, server.count(HttpMethod.Patch))
    }

    @Test
    fun onConfirmed_reportsProgressRevealedByARecoveryHead_andNeverRepeats() = runTest {
        val server = server(600)
        server.script(HttpMethod.Patch, Reply.Status(500, apply = true), Reply.Status(409))
        val confirmed = mutableListOf<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(600)),
            options = TusUploadOptions(chunkSize = 200, retryOptions = retry()),
            onConfirmed = { o, _ -> confirmed += o })
        assertEquals(listOf(200L, 400L, 600L), confirmed)
    }

    @Test
    fun onConfirmed_isNotCalledForTheStartingOffsetOfAResume() = runTest {
        val server = server(600).apply { data = testBytes(600).copyOfRange(0, 200) }
        val confirmed = mutableListOf<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(600)),
            options = TusUploadOptions(chunkSize = 200, retryOptions = retry()),
            onConfirmed = { o, _ -> confirmed += o })
        assertEquals(listOf(400L, 600L), confirmed)
    }

    @Test
    fun onProgress_stillReportsBytesHandedToTheEngine() = runTest {
        val server = server(1000)
        val progress = mutableListOf<Long>()
        server.client().uploadTus(url, InMemoryTusFile("f", testBytes(1000)),
            options = TusUploadOptions(chunkSize = 400, retryOptions = retry()),
            onProgress = { sent, total -> assertEquals(1000, total); progress += sent })
        assertTrue(progress.isNotEmpty())
        assertEquals(1000, progress.last())
        assertTrue(progress.contains(400) && progress.contains(800), "progress within each chunk: $progress")
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondNoContent(offset: Long) =
    respond("", io.ktor.http.HttpStatusCode.NoContent, io.ktor.http.headersOf("Upload-Offset", offset.toString()))
