package com.ldartools.ktus.test

import com.ldartools.ktus.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.*

class KtusTest {

    @Test
    fun testCoreProtocol_allRequestsHaveTusResumableHeader() = runTest {
        val requestHeaders = mutableListOf<Pair<HttpMethod, String?>>()

        val engine = MockEngine { request ->
            requestHeaders.add(request.method to request.headers["Tus-Resumable"])

            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
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
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("50"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)
        val testFile = InMemoryTusFile("test.bin", ByteArray(50))

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = true)
        )

        // Verify all requests have Tus-Resumable header
        requestHeaders.forEach { (method, header) ->
            assertEquals("1.0.0", header, "Request $method should have Tus-Resumable header")
        }

        client.close()
    }

    // ========== Basic Upload Tests ==========

    @Test
    fun testBasicUpload_singleChunk() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    assertEquals("100", request.headers["Upload-Length"])
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
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
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

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(chunkSize = 256L, checkServerCapabilities = true)
        )

        assertEquals(100L, serverOffset)
        client.close()
    }

    @Test
    fun testBasicUpload_multipleChunks() = runTest {
        val fileData = ByteArray(1024) { (it and 0xFF).toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        val receivedChunks = mutableListOf<Int>()

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
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
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
                    receivedChunks.add(bytes.size)
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

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(chunkSize = 256L, checkServerCapabilities = true)
        )

        assertEquals(1024L, serverOffset)
        assertEquals(4, receivedChunks.size) // 1024 / 256 = 4 chunks
        assertEquals(listOf(256, 256, 256, 256), receivedChunks)
        client.close()
    }

    @Test
    fun testBasicUpload_emptyFile() = runTest {
        val testFile = InMemoryTusFile("empty.bin", ByteArray(0))

        var optionsReceived = false
        var postReceived = false
        var headReceived = false
        var patchReceived = false

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    optionsReceived = true
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    postReceived = true
                    assertEquals("0", request.headers["Upload-Length"])
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                HttpMethod.Head -> {
                    headReceived = true
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    patchReceived = true
                    fail("Should not send PATCH for empty file")
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = true)
        )

        assertTrue(optionsReceived, "Expected OPTIONS request for capability check")
        assertTrue(postReceived, "Expected POST request to create upload")
        assertTrue(headReceived, "Expected HEAD request to check offset")
        assertFalse(patchReceived, "Did not expect PATCH for empty file")

        client.close()
    }

    // ========== Resume Upload Tests ==========

    @Test
    fun testResumeUpload_partiallyUploaded() = runTest {
        val fileData = ByteArray(1000) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 300L // Already uploaded 300 bytes
        val patchCount = mutableListOf<Int>()

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
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
                    patchCount.add(bytes.size)
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
            options = TusUploadOptions(chunkSize = 200L)
        )

        assertEquals(1000L, serverOffset)
        // Should upload remaining 700 bytes in chunks of 200: [200, 200, 200, 100]
        assertEquals(4, patchCount.size)
        client.close()
    }

    @Test
    fun testResumeUpload_alreadyComplete() = runTest {
        val fileData = ByteArray(500) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        val serverOffset = 500L // Already fully uploaded

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
                    fail("Should not send PATCH when upload is already complete")
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.uploadTus(
            uploadUrl = "https://example.com/files/uploads/123",
            file = testFile
        )

        assertEquals(500L, serverOffset)
        client.close()
    }

    // ========== Upload Offset (Rewind) Tests ==========

    @Test
    fun testUploadOffset_patchRequestContainsCorrectOffset() = runTest {
        val fileData = ByteArray(600) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        val patchOffsets = mutableListOf<Long>()

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
                    val requestOffset = request.headers["Upload-Offset"]?.toLongOrNull()
                    assertNotNull(requestOffset)
                    patchOffsets.add(requestOffset!!)

                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
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
            options = TusUploadOptions(chunkSize = 200L)
        )

        assertEquals(listOf(0L, 200L, 400L), patchOffsets)
        client.close()
    }

    @Test
    fun testUploadOffset_mismatchThrowsException() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
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
                    // Server returns same offset (no advancement)
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusOffsetMismatchException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile
            )
        }

        client.close()
    }

    // ========== Error Handling Tests ==========

    @Test
    fun testErrorHandling_uploadExpired() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Gone
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusUploadExpiredException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile
            )
        }

        client.close()
    }

    @Test
    fun testErrorHandling_uploadNotFound() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NotFound
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusUploadExpiredException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile
            )
        }

        client.close()
    }

    @Test
    fun testErrorHandling_missingUploadOffsetInHead() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf() // Missing Upload-Offset
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusProtocolException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile
            )
        }

        client.close()
    }

    @Test
    fun testErrorHandling_missingUploadOffsetInPatch() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
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
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf() // Missing Upload-Offset
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusProtocolException> {
            client.uploadTus(
                uploadUrl = "https://example.com/files/uploads/123",
                file = testFile
            )
        }

        client.close()
    }

    @Test
    fun testErrorHandling_missingLocationInCreate() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf() // Missing Location
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusProtocolException> {
            client.createTus(
                createUrl = "https://example.com/files",
                file = testFile,
                options = TusUploadOptions(checkServerCapabilities = true)
            )
        }

        client.close()
    }

    @Test
    fun testErrorHandling_createFailsWithBadStatus() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.BadRequest
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        assertFailsWith<TusProtocolException> {
            client.createTus(
                createUrl = "https://example.com/files",
                file = testFile,
                options = TusUploadOptions(checkServerCapabilities = true)
            )
        }

        client.close()
    }

    // ========== Metadata Tests ==========

    @Test
    fun testMetadata_sentInCreateRequest() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var receivedMetadata: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
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
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("100"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)
        val metadata = mapOf(
            "filename" to "test.bin",
            "filetype" to "application/octet-stream"
        )

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            metadata = metadata,
            options = TusUploadOptions(checkServerCapabilities = true)
        )

        assertNotNull(receivedMetadata)
        assertTrue(receivedMetadata!!.contains("filename"))
        assertTrue(receivedMetadata!!.contains("filetype"))
        client.close()
    }

    @Test
    fun testMetadata_emptyMetadata() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var receivedMetadata: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
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
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("100"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            metadata = emptyMap(),
            options = TusUploadOptions(checkServerCapabilities = true)
        )

        assertEquals("", receivedMetadata)
        client.close()
    }

    // ========== Progress Tests ==========

    @Test
    fun testProgress_callbackInvokedDuringUpload() = runTest {
        val fileData = ByteArray(1000) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        val progressUpdates = mutableListOf<Pair<Long, Long>>()

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
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
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
            options = TusUploadOptions(chunkSize = 300L),
            onProgress = { sent, total ->
                progressUpdates.add(sent to total)
            }
        )

        assertTrue(progressUpdates.isNotEmpty())
        progressUpdates.forEach { (sent, total) ->
            assertEquals(1000L, total)
            assertTrue(sent <= total)
        }
        client.close()
    }

    @Test
    fun testProgress_finalProgressMatchesFileSize() = runTest {
        val fileData = ByteArray(500) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var serverOffset = 0L
        var lastProgress: Pair<Long, Long>? = null

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
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readByteArray() ?: ByteArray(0)
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
            options = TusUploadOptions(chunkSize = 200L),
            onProgress = { sent, total ->
                lastProgress = sent to total
            }
        )

        val finalProgress = assertNotNull(lastProgress)
        assertEquals(500L, finalProgress.first)
        assertEquals(500L, finalProgress.second)
        client.close()
    }

    // ========== Creation Tests ==========

    @Test
    fun testCreation_uploadUrlReturnedInCallback() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var createdUrl: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/abc123"))
                    )
                }
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("100"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = true),
            onCreate = { uploadUrl ->
                createdUrl = uploadUrl
            }
        )

        assertNotNull(createdUrl)
        assertTrue(createdUrl!!.contains("/uploads/abc123"))
        client.close()
    }

    @Test
    fun testCreation_relativeLocationUrlIsResolved() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var resolvedUrl: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/xyz"))
                    )
                }
                HttpMethod.Head -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("100"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = true),
            onCreate = { uploadUrl ->
                resolvedUrl = uploadUrl
            }
        )

        assertEquals("https://example.com/uploads/xyz", resolvedUrl)
        client.close()
    }

    @Test
    fun testCreation_uploadLengthHeaderSet() = runTest {
        val fileData = ByteArray(12345) { it.toByte() }
        val testFile = InMemoryTusFile("large.bin", fileData)
        var uploadLength: String? = null

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
                    uploadLength = request.headers["Upload-Length"]
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
                        headers = headersOf("Upload-Offset" to listOf("12345"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = true)
        )

        assertEquals("12345", uploadLength)
        client.close()
    }

    @Test
    fun testCreation_skipCapabilitiesCheck() = runTest {
        val fileData = ByteArray(100) { it.toByte() }
        val testFile = InMemoryTusFile("test.bin", fileData)
        var optionsRequestReceived = false

        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> {
                    optionsRequestReceived = true
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Tus-Resumable" to listOf("1.0.0"))
                    )
                }
                HttpMethod.Post -> {
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
                        headers = headersOf("Upload-Offset" to listOf("0"))
                    )
                }
                HttpMethod.Patch -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf("Upload-Offset" to listOf("100"))
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(engine)

        client.createAndUploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            options = TusUploadOptions(checkServerCapabilities = false)
        )

        assertFalse(optionsRequestReceived)
        client.close()
    }
}