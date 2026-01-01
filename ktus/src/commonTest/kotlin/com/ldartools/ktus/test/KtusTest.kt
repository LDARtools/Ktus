package com.ldartools.ktus.test

import com.ldartools.ktus.uploadTus
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KtusTest {

    @Test
    fun testUploadTus_happyPath() = runTest {
        val fileData = ByteArray(1024) { (it and 0xFF).toByte() }
        val testFile = InMemoryTusFile("mem.bin", fileData)

        // track server-side offset
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
                    // Create upload: return Location header
                    respond(
                        content = "",
                        status = HttpStatusCode.Created,
                        headers = headersOf("Location" to listOf("/uploads/123"))
                    )
                }
                HttpMethod.Head -> {
                    // initial offset check - start at 0
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Upload-Offset" to listOf(serverOffset.toString()))
                    )
                }
                HttpMethod.Patch -> {
                    // Read the request body (the outgoing content is ReadChannelContent produced by upload code)
                    val bodyChannel = (request.body as? OutgoingContent.ReadChannelContent)?.readFrom()
                    val bytes = bodyChannel?.readRemaining()?.readBytes() ?: ByteArray(0)
                    // advance server offset by received bytes
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

        // Call the function under test; it should complete successfully and advance to file size
        client.uploadTus(
            createUrl = "https://example.com/files",
            file = testFile,
            chunkSize = 256L,
            checkServerCapabilities = true
        )

        // After upload, serverOffset should equal file size
        assertEquals(testFile.size, serverOffset)
        client.close()
    }
}