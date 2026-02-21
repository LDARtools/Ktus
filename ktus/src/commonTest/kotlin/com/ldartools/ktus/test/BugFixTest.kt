package com.ldartools.ktus.test

import com.ldartools.ktus.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.test.*

class BugFixTest {

    // ==========================================================================
    // BUG 1: ByteReadChannel consumed on retry
    //
    // Previously, readSection() was called OUTSIDE retryWithBackoff, so if the
    // PATCH failed and was retried, the ByteReadChannel had already been consumed.
    // The fix moved readSection() inside the retry block so a fresh channel is
    // created per attempt.
    // ==========================================================================

    @Test
    fun testByteReadChannel_freshChannelCreatedOnRetry() = runTest {
        val fileData = ByteArray(200) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        var patchAttempts = 0
        val receivedBytes = mutableListOf<ByteArray>()

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    patchAttempts++
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)

                    if (patchAttempts == 1) {
                        // First attempt: simulate a 500 server error.
                        // The bytes were already read from the channel by MockEngine,
                        // but the server "fails" so the client must retry with fresh data.
                        respond(
                            content = "",
                            status = HttpStatusCode.InternalServerError
                        )
                    } else {
                        // Second attempt: should receive the same complete data.
                        receivedBytes.add(bytes)
                        serverOffset += bytes.size
                        respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                        )
                    }
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.uploadTus(
            uploadUrl = "https://example.com/files/uploads/123",
            file = testFile,
            options = TusUploadOptions(
                chunkSize = 200L,
                retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
            )
        )

        // The first PATCH failed (500), the retryWithBackoff retried it,
        // and the second attempt should have sent the full 200 bytes.
        assertEquals(200L, serverOffset, "All bytes should have been uploaded")
        assertTrue(patchAttempts >= 2, "Should have attempted PATCH at least twice")
        assertEquals(1, receivedBytes.size, "Should have 1 successful PATCH")
        assertEquals(200, receivedBytes[0].size, "Retry should send complete data, not an empty/consumed channel")

        // Verify the actual content is correct (not zeroes from a consumed channel).
        val expected = ByteArray(200) { it.toByte() }
        assertContentEquals(expected, receivedBytes[0], "Retried PATCH should contain the correct file bytes")
        client.close()
    }

    @Test
    fun testByteReadChannel_multipleRetriesAllGetFreshData() = runTest {
        val fileData = ByteArray(100) { (it + 42).toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        var patchAttempts = 0
        val byteSizesPerAttempt = mutableListOf<Int>()

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    patchAttempts++
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
                    byteSizesPerAttempt.add(bytes.size)

                    if (patchAttempts < 3) {
                        // First two attempts fail with 500
                        respond(content = "", status = HttpStatusCode.InternalServerError)
                    } else {
                        // Third attempt succeeds
                        serverOffset += bytes.size
                        respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                        )
                    }
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.uploadTus(
            uploadUrl = "https://example.com/files/uploads/123",
            file = testFile,
            options = TusUploadOptions(
                chunkSize = 100L,
                retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
            )
        )

        assertEquals(3, patchAttempts, "Should have 3 PATCH attempts (2 failures + 1 success)")
        // Every attempt should have received 100 bytes -- not 0 from a consumed channel
        byteSizesPerAttempt.forEach { size ->
            assertEquals(100, size, "Each retry attempt must send complete data from a fresh channel")
        }
        client.close()
    }

    // ==========================================================================
    // BUG 2: TusProtocolException not retried
    //
    // TusProtocolException extends IOException. Without an explicit catch before
    // the IOException handler, TusProtocolException would be caught by the
    // IOException handler and retried. The fix adds a catch for TusProtocolException
    // BEFORE IOException that immediately re-throws.
    // ==========================================================================

    @Test
    fun testTusProtocolException_notRetriedByRetryWithBackoff() = runTest {
        var attempts = 0
        assertFailsWith<TusProtocolException> {
            retryWithBackoff(RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)) {
                attempts++
                throw TusProtocolException("Missing Upload-Offset in PATCH response")
            }
        }
        assertEquals(1, attempts, "TusProtocolException should fail immediately without retries")
    }

    @Test
    fun testTusUploadExpiredException_notRetriedByRetryWithBackoff() = runTest {
        var attempts = 0
        assertFailsWith<TusUploadExpiredException> {
            retryWithBackoff(RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)) {
                attempts++
                throw TusUploadExpiredException()
            }
        }
        assertEquals(1, attempts, "TusUploadExpiredException (subclass of TusProtocolException) should fail immediately")
    }

    @Test
    fun testTusOffsetMismatchException_notRetriedByRetryWithBackoff() = runTest {
        var attempts = 0
        assertFailsWith<TusOffsetMismatchException> {
            retryWithBackoff(RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)) {
                attempts++
                throw TusOffsetMismatchException("Server returned an invalid offset: 0")
            }
        }
        assertEquals(1, attempts, "TusOffsetMismatchException (subclass of TusProtocolException) should fail immediately")
    }

    @Test
    fun testIOException_isRetriedByRetryWithBackoff() = runTest {
        var attempts = 0
        assertFailsWith<IOException> {
            retryWithBackoff(RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)) {
                attempts++
                throw IOException("Connection reset")
            }
        }
        assertEquals(3, attempts, "IOException should be retried up to maxRetries")
    }

    @Test
    fun testIOException_retriedThenSucceeds() = runTest {
        var attempts = 0
        val result = retryWithBackoff(RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)) {
            attempts++
            if (attempts < 2) throw IOException("Connection reset")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, attempts, "Should succeed on second attempt after IOException")
    }

    // ==========================================================================
    // BUG 3: ServerResponseException caught for 5xx retry
    //
    // 5xx errors from Ktor throw ServerResponseException. The fix adds a catch
    // that allows retrying on 5xx errors.
    // ==========================================================================

    @Test
    fun testServerResponseException_retriedThenFails() = runTest {
        var attempts = 0
        val options = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)

        assertFailsWith<ServerResponseException> {
            retryWithBackoff(options) {
                attempts++
                throw mockServerResponseException(HttpStatusCode.InternalServerError)
            }
        }
        assertEquals(3, attempts, "5xx errors should be retried up to maxRetries")
    }

    @Test
    fun testServerResponseException_retriedThenSucceeds() = runTest {
        var attempts = 0
        val options = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)

        val result = retryWithBackoff(options) {
            attempts++
            if (attempts < 3) throw mockServerResponseException(HttpStatusCode.ServiceUnavailable)
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, attempts, "Should succeed on third attempt after two 503 errors")
    }

    @Test
    fun testMixed5xxAndIOException_allRetried() = runTest {
        var attempts = 0
        val options = RetryOptions(maxRetries = 4, initialDelayMillis = 1, maxDelayMillis = 10)

        val result = retryWithBackoff(options) {
            attempts++
            when (attempts) {
                1 -> throw mockServerResponseException(HttpStatusCode.InternalServerError)
                2 -> throw IOException("Connection reset")
                3 -> throw mockServerResponseException(HttpStatusCode.BadGateway)
                else -> "success"
            }
        }
        assertEquals("success", result)
        assertEquals(4, attempts, "Mixed 5xx and IOException should all be retried")
    }

    // ==========================================================================
    // BUG 4: Infinite loop protection on PATCH failure
    //
    // If the server returns non-204 on PATCH but the same offset on HEAD, the
    // upload loop would spin forever. The fix adds a consecutiveFailures counter
    // that throws TusProtocolException after maxRetries consecutive failures.
    // The counter resets on a successful PATCH.
    // ==========================================================================

    @Test
    fun testInfiniteLoopProtection_throwsAfterMaxConsecutiveFailures() = runTest {
        val fileData = ByteArray(200) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var patchCount = 0
        var headCount = 0

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    headCount++
                    // Always return offset 0 -- the server never made progress
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    patchCount++
                    // Always return 200 OK instead of 204 No Content (non-standard but not an exception)
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)
        val maxRetries = 3

        val exception = assertFailsWith<TusProtocolException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile,
                options = TusUploadOptions(
                    chunkSize = 200L,
                    retryOptions = RetryOptions(maxRetries = maxRetries, initialDelayMillis = 1, maxDelayMillis = 10)
                )
            )
        }

        assertTrue(
            exception.message!!.contains("consecutive PATCH failures"),
            "Exception message should mention consecutive PATCH failures, was: ${exception.message}"
        )

        // The loop should have stopped, not run forever.
        // With maxRetries=3, consecutiveFailures exceeds maxRetries after maxRetries+1 failures.
        assertTrue(patchCount <= maxRetries + 1, "Should not spin forever; got $patchCount PATCH attempts")
        client.close()
    }

    @Test
    fun testInfiniteLoopProtection_counterResetsOnSuccess() = runTest {
        val fileData = ByteArray(600) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        var patchCount = 0
        var headCount = 0

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    headCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    patchCount++
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)

                    // Pattern: fail twice, succeed, fail twice, succeed, fail twice, succeed
                    // This ensures the counter resets after each success.
                    // With maxRetries=3, 2 consecutive failures is within limits.
                    if (patchCount % 3 == 0) {
                        // Every 3rd attempt succeeds
                        serverOffset += bytes.size
                        respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                        )
                    } else {
                        // Non-204 response triggers failure path
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                        )
                    }
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        // This should succeed because consecutive failures never exceed maxRetries.
        client.uploadTus(
            uploadUrl = "https://example.com/files/uploads/123",
            file = testFile,
            options = TusUploadOptions(
                chunkSize = 200L,
                retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
            )
        )

        assertEquals(600L, serverOffset, "All 600 bytes should eventually be uploaded")
        client.close()
    }

    // ==========================================================================
    // BUG 5: 409 Conflict recovery
    //
    // When the server returns 409 Conflict on PATCH, it means the client's
    // Upload-Offset doesn't match the server's. The fix catches
    // ClientRequestException for 409, recovers via HEAD, and retries the PATCH
    // with the corrected offset.
    //
    // NOTE: MockEngine does not throw ClientRequestException by default. We must
    // install HttpResponseValidator with expectSuccess behavior.
    // ==========================================================================

    @Test
    fun testConflictRecovery_409ThenHeadThenSuccess() = runTest {
        val fileData = ByteArray(400) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var patchCount = 0
        var headCount = 0
        var serverOffset = 0L
        val patchOffsets = mutableListOf<Long>()

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    headCount++
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    patchCount++
                    val requestOffset = request.headers["Upload-Offset"]?.toLongOrNull() ?: 0L
                    patchOffsets.add(requestOffset)

                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)

                    if (patchCount == 1) {
                        // The server already received part of this chunk from a previous session.
                        // Simulate: server has 100 bytes but client thinks offset is 0.
                        serverOffset = 100L
                        respond(
                            content = "Conflict",
                            status = HttpStatusCode.Conflict
                        )
                    } else {
                        // After HEAD recovery, client sends from correct offset.
                        serverOffset += bytes.size
                        respond(
                            content = "",
                            status = HttpStatusCode.NoContent,
                            headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                        )
                    }
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        // We need expectSuccess = true so Ktor throws ClientRequestException on 409.
        val client = HttpClient(engine) {
            expectSuccess = true
        }

        client.uploadTus(
            uploadUrl = "https://example.com/files/uploads/123",
            file = testFile,
            options = TusUploadOptions(
                chunkSize = 200L,
                retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
            )
        )

        assertEquals(400L, serverOffset, "All bytes should eventually be uploaded")
        // First PATCH at offset 0 fails with 409, HEAD recovers offset=100,
        // then PATCHes continue from 100 in 200-byte chunks: 100, 300
        assertTrue(patchOffsets[0] == 0L, "First PATCH should be at offset 0")
        assertTrue(patchOffsets.size >= 3, "Should have at least 3 PATCH attempts (1 conflict + recovery chunks)")
        assertTrue(headCount >= 2, "Should have at least 2 HEAD requests (initial + recovery)")
        client.close()
    }

    @Test
    fun testConflictRecovery_nonConflict4xxNotRecovered() = runTest {
        val fileData = ByteArray(200) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    // Return 403 Forbidden -- should NOT be recovered.
                    respond(
                        content = "Forbidden",
                        status = HttpStatusCode.Forbidden
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine) {
            expectSuccess = true
        }

        assertFailsWith<ClientRequestException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile,
                options = TusUploadOptions(
                    chunkSize = 200L,
                    retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
                )
            )
        }

        client.close()
    }

    @Test
    fun testConflictRecovery_409CountsAsConsecutiveFailure() = runTest {
        val fileData = ByteArray(200) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var patchCount = 0

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    // Always return offset 0
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    patchCount++
                    // Always return 409 -- server always rejects the offset
                    respond(
                        content = "Conflict",
                        status = HttpStatusCode.Conflict
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine) {
            expectSuccess = true
        }

        val exception = assertFailsWith<TusProtocolException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile,
                options = TusUploadOptions(
                    chunkSize = 200L,
                    retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
                )
            )
        }

        assertTrue(
            exception.message!!.contains("consecutive PATCH failures"),
            "Persistent 409s should eventually trigger consecutive failure protection"
        )
        assertTrue(patchCount <= 5, "Should not spin forever on repeated 409s; got $patchCount attempts")
        client.close()
    }

    // ==========================================================================
    // BUG 8: Metadata key validation
    //
    // encodeMetadata now validates that keys are non-empty and do not contain
    // spaces or commas. Empty values produce key-only output without a trailing
    // space.
    //
    // encodeMetadata is private, but we test it indirectly via createTus which
    // passes metadata through.
    // ==========================================================================

    @Test
    fun testMetadataValidation_keyWithSpaceThrows() = runTest {
        val fileData = ByteArray(10) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<IllegalArgumentException> {
            client.createTus(
                createUrl = "https://example.com/files",
                file = testFile,
                metadata = mapOf("file name" to "test.bin"),
                options = TusUploadOptions(checkServerCapabilities = false)
            )
        }

        client.close()
    }

    @Test
    fun testMetadataValidation_keyWithCommaThrows() = runTest {
        val fileData = ByteArray(10) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<IllegalArgumentException> {
            client.createTus(
                createUrl = "https://example.com/files",
                file = testFile,
                metadata = mapOf("file,name" to "test.bin"),
                options = TusUploadOptions(checkServerCapabilities = false)
            )
        }

        client.close()
    }

    @Test
    fun testMetadataValidation_emptyKeyThrows() = runTest {
        val fileData = ByteArray(10) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<IllegalArgumentException> {
            client.createTus(
                createUrl = "https://example.com/files",
                file = testFile,
                metadata = mapOf("" to "test.bin"),
                options = TusUploadOptions(checkServerCapabilities = false)
            )
        }

        client.close()
    }

    @Test
    fun testMetadataValidation_emptyValueProducesKeyOnly() = runTest {
        val fileData = ByteArray(10) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var receivedMetadata: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    receivedMetadata = request.headers["Upload-Metadata"]
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("10"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            metadata = mapOf("is-confidential" to ""),
            options = TusUploadOptions(checkServerCapabilities = false)
        )

        assertNotNull(receivedMetadata, "Upload-Metadata header should be present")
        // Per TUS spec, empty value means key only, no trailing space
        assertEquals("is-confidential", receivedMetadata, "Empty value should produce key-only metadata without trailing space")
        client.close()
    }

    @Test
    fun testMetadataValidation_mixedEmptyAndNonEmptyValues() = runTest {
        val fileData = ByteArray(10) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var receivedMetadata: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    receivedMetadata = request.headers["Upload-Metadata"]
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("10"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            metadata = mapOf("filename" to "test.bin", "is-confidential" to ""),
            options = TusUploadOptions(checkServerCapabilities = false)
        )

        assertNotNull(receivedMetadata, "Upload-Metadata header should be present")
        // Parse the metadata to verify structure
        val parts = receivedMetadata!!.split(",")
        assertEquals(2, parts.size, "Should have 2 metadata entries")

        // Find the is-confidential entry -- it should be key-only (no space)
        val confidentialPart = parts.find { it.trim().startsWith("is-confidential") }
        assertNotNull(confidentialPart, "Should contain is-confidential key")
        assertEquals("is-confidential", confidentialPart!!.trim(), "Empty-value key should not have trailing space or base64")

        // Find the filename entry -- it should have key + space + base64-encoded value
        val filenamePart = parts.find { it.trim().startsWith("filename") }
        assertNotNull(filenamePart, "Should contain filename key")
        assertTrue(filenamePart!!.trim().contains(" "), "Non-empty value should have space separator")
        client.close()
    }

    // ==========================================================================
    // Integration-level: PATCH retry sends correct data after IOException
    //
    // End-to-end test where the upload encounters an IOException mid-chunk,
    // verifying the retry mechanism works correctly in the full upload flow.
    // ==========================================================================

    @Test
    fun testIntegration_ioExceptionDuringPatchRetries() = runTest {
        val fileData = ByteArray(500) { (it % 256).toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        var patchAttempts = 0

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    patchAttempts++
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)

                    // Fail the second PATCH attempt (second chunk) with a simulated network error
                    if (patchAttempts == 2) {
                        throw IOException("Simulated network failure")
                    }

                    serverOffset += bytes.size
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.uploadTus(
            uploadUrl = "https://example.com/files/uploads/123",
            file = testFile,
            options = TusUploadOptions(
                chunkSize = 200L,
                retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 10)
            )
        )

        assertEquals(500L, serverOffset, "All bytes should be uploaded despite IOException mid-stream")
        assertTrue(patchAttempts >= 4, "Should have retried the failed chunk (1 success + 1 fail + retry + final chunk)")
        client.close()
    }

    // ==========================================================================
    // URL Resolution: relative Location header handling
    //
    // The old getRootUrl() approach only prepended scheme://host:port, which
    // broke relative paths like "uploads/123" when the creation URL had a path
    // (e.g. /api/v1/files). The fix uses proper RFC 3986 URL resolution.
    // ==========================================================================

    @Test
    fun testUrlResolution_absoluteUrl() = runTest {
        val testFile = InMemoryTusFile("test.bin", ByteArray(10))
        var resolvedUrl: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("https://cdn.example.com/uploads/abc"))
                    )
                }
                HttpMethod.Head -> {
                    respond(content = "", status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("10")))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)
        client.createAndUploadTus(
            createUrl = "https://example.com/api/v1/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = false),
            onCreate = { resolvedUrl = it }
        )

        assertEquals("https://cdn.example.com/uploads/abc", resolvedUrl,
            "Absolute Location URL should be used as-is")
        client.close()
    }

    @Test
    fun testUrlResolution_absolutePath() = runTest {
        val testFile = InMemoryTusFile("test.bin", ByteArray(10))
        var resolvedUrl: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/abc"))
                    )
                }
                HttpMethod.Head -> {
                    respond(content = "", status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("10")))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)
        client.createAndUploadTus(
            createUrl = "https://example.com/api/v1/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = false),
            onCreate = { resolvedUrl = it }
        )

        assertEquals("https://example.com/uploads/abc", resolvedUrl,
            "Absolute path should resolve against the origin only")
        client.close()
    }

    @Test
    fun testUrlResolution_relativePath() = runTest {
        val testFile = InMemoryTusFile("test.bin", ByteArray(10))
        var resolvedUrl: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("uploads/abc"))
                    )
                }
                HttpMethod.Head -> {
                    respond(content = "", status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("10")))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)
        client.createAndUploadTus(
            createUrl = "https://example.com/api/v1/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = false),
            onCreate = { resolvedUrl = it }
        )

        // Per RFC 3986, "uploads/abc" relative to "/api/v1/files"
        // resolves to "/api/v1/uploads/abc" (replaces last segment of base path)
        assertEquals("https://example.com/api/v1/uploads/abc", resolvedUrl,
            "Relative path should resolve against the base URL's parent directory")
        client.close()
    }

    // ==========================================================================
    // Helper functions
    // ==========================================================================

    private suspend fun mockServerResponseException(statusCode: HttpStatusCode): ServerResponseException {
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            )
        }
        val client = HttpClient(engine)
        val response = client.request("")
        return ServerResponseException(response, "Error")
    }
}
