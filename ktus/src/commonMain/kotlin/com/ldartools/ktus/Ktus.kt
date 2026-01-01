package com.ldartools.ktus

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.util.encodeBase64
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.math.min

suspend fun HttpClient.uploadTus(
    createUrl: String,
    file: ITusFile,
    metadata: Map<String, String> = emptyMap(),
    chunkSize: Long = 2 * 1024 * 1024, // 2MB default
    useFileLock: Boolean = false,
    checkServerCapabilities: Boolean = true,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    val fileLock = if(useFileLock) file.fileReadLock() else AutoCloseable {}
    fileLock.use {


        /*
         * Phase 0: Verify Server Capabilities
         */

        if(checkServerCapabilities) {
            // Optional: Before the "Create Upload" phase
            val optionsResponse = retryWithBackoff { this.options(urlString = createUrl) {
                tusVersionHeader()
                block()
            }
            }
            if (optionsResponse.headers["Tus-Resumable"] == null) {
                throw TusProtocolException("Server does not support tus protocol.")
            }
            //todo You could also check for supported versions if the server returns Tus-Version
        }

        /*
         * Phase 1: Create Upload
         */

        val createResponse = retryWithBackoff {
            this.post(urlString = createUrl) {
                header("Upload-Length", file.size.toString())
                tusVersionHeader()
                header("Upload-Metadata", encodeMetadata(metadata))
                block()
            }
        }

        if (createResponse.status != HttpStatusCode.Created) {
            throw TusProtocolException("Failed to create upload: ${createResponse.status}")
        }

        var uploadUrl = createResponse.headers["Location"]
            ?: throw TusProtocolException("Server did not provide a Location header")

        //need to append the uploadUrl to the root of the createUrl
        val serverRoot = createUrl.getRootUrl() ?: ""
        if(!uploadUrl.startsWith(serverRoot, ignoreCase = true)) {
            uploadUrl = "$serverRoot$uploadUrl"
        }

        var offset: Long

        //initial head check to see if file is already uploaded
        offset = retryWithBackoff { headRequest(this, uploadUrl, block) }

        /*
         * Phase 2: Upload Chunks
         */
        while (offset < file.size) {
            // 1. Calculate chunk size
            val bytesRemaining = file.size - offset
            val currentChunkSize = min(chunkSize, bytesRemaining)

            // 2. Prepare the stream
            val chunk = file.readSection(offset, currentChunkSize)

            // 3. Send PATCH request
            val patchResponse = retryWithBackoff {
                this.patch(urlString = uploadUrl) {
                    tusVersionHeader()
                    header("Upload-Offset", offset.toString())
                    header("Content-Length", currentChunkSize.toString())
                    setBody(TusPatchContent(chunk, currentChunkSize))

                    // Progress listener hook (Ktor capability)
                    onUpload { bytesSentTotal, _ ->
                        onProgress?.invoke(offset + bytesSentTotal, file.size)
                    }
                    block()
                }
            }

            // 4. Verify Response
            if (patchResponse.status != HttpStatusCode.NoContent) {

                // Handle failure
                offset = retryWithBackoff { headRequest(this, uploadUrl, block) }

                continue
            }

            // 5. Update Offset
            val serverOffset = patchResponse.headers["Upload-Offset"]?.toLongOrNull()
                ?: throw TusProtocolException("Missing Upload-Offset in PATCH response")

            // Validate offset advancement
            if (serverOffset <= offset) {
                // This implies no data was written. Potential infinite loop if not handled.
                throw TusOffsetMismatchException("Server returned an invalid offset: $serverOffset")
            }

            offset = serverOffset
        }
    }
}

private suspend fun headRequest(httpClient: HttpClient, uploadUrl:String, block: suspend HttpRequestBuilder.() -> Unit = {}) : Long {
    val response = httpClient.head(uploadUrl) {
        tusVersionHeader()
        block()
    }

    if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone) {
        // The upload is expired or invalid. We must restart from creation.
        throw TusUploadExpiredException()
    }

    val serverOffset = response.headers["Upload-Offset"]?.toLongOrNull()
        ?: throw TusProtocolException("Missing Upload-Offset in HEAD response")

    return serverOffset
}

private fun HttpMessageBuilder.tusVersionHeader(): Unit = this.header("Tus-Resumable", "1.0.0")

private fun encodeMetadata(metadata: Map<String, String>): String {
    return metadata.entries.joinToString(",") { (key, value) ->
        "$key ${value.encodeBase64()}"
    }
}

/**
 * Mobile networks are bursty. Immediate retries often fail. We implement a standard exponential backoff using Kotlin Coroutines delay.
 */
private suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelay: Long = 500,
    maxDelay: Long = 10000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) {
        try {
            return block()
        } catch (e: ClientRequestException) {
            // Immediately fail on 4xx errors, except for specific ones we might handle elsewhere if needed
            val statusCode = e.response.status.value
            if (statusCode in 400..499) {
                // Special case for expired uploads, which is a recoverable client error
                if (statusCode == HttpStatusCode.NotFound.value || statusCode == HttpStatusCode.Gone.value) {
                    throw TusUploadExpiredException()
                }
                // For other 4xx errors, fail fast as they are not typically retry-able
                throw e
            }
            // For 5xx server errors, we allow retrying.
        } catch (e: IOException) {
            // General network errors, allow retrying.
        }
        delay(currentDelay)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
    }
    return block() // Last attempt
}