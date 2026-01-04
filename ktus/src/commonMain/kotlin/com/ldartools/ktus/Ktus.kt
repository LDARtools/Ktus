package com.ldartools.ktus

import io.ktor.client.HttpClient
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
import kotlin.math.min

/**
 * Create a new tus upload on the server and return the upload URL.
 *
 * Performs the "Create Upload" step of the tus protocol by sending a POST to [createUrl].
 * If [options.checkServerCapabilities] is true, an OPTIONS request is sent first to verify
 * the server supports the tus protocol.
 *
 * @receiver HttpClient The Ktor HTTP client used to send requests.
 * @param createUrl The endpoint used to create the upload (server create URL).
 * @param file The file to be uploaded; its size is sent as the `Upload-Length` header.
 * @param metadata Optional metadata sent as `Upload-Metadata`.
 * @param options Upload options controlling retries, chunk size and capability checks.
 * @param block Optional lambda to customize the underlying HTTP request builder.
 * @return The upload URL returned by the server (from the `Location` header).
 * @throws TusProtocolException If the server does not support tus, creation fails, or the server omits a valid Location header.
 */
suspend fun HttpClient.createTus(createUrl: String,
                                 file: ITusFile,
                                 metadata: Map<String, String> = emptyMap(),
                                 options: TusUploadOptions = TusUploadOptions(),
                                 block: suspend HttpRequestBuilder.() -> Unit = {}
) : String {
    return createTus(createUrl, file, metadata, options, block, fileLockHandled = false)
}

private suspend fun HttpClient.createTus(createUrl: String,
                                 file: ITusFile,
                                 metadata: Map<String, String> = emptyMap(),
                                 options: TusUploadOptions = TusUploadOptions(),
                                 block: suspend HttpRequestBuilder.() -> Unit = {},
                                 fileLockHandled: Boolean
                                 ) : String {
    if(options.checkServerCapabilities) {
        // Optional: Before the "Create Upload" phase
        val optionsResponse = retryWithBackoff(options.retryOptions) { this.options(urlString = createUrl) {
            tusVersionHeader()
            block()
        }
        }
        if (optionsResponse.headers["Tus-Resumable"] == null) {
            throw TusProtocolException("Server does not support tus protocol.")
        }
        //todo You could also check for supported versions if the server returns Tus-Version
    }

    val createResponse = retryWithBackoff(options.retryOptions) {
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
    if (!uploadUrl.startsWith(serverRoot, ignoreCase = true)) {
        uploadUrl = "$serverRoot$uploadUrl"
    }

    return uploadUrl
}

/**
 * Upload the provided [file] to an existing tus upload at [uploadUrl].
 *
 * This is a convenience overload that performs the upload using the configured
 * [options], reporting progress via [onProgress] and allowing request
 * customization via [block]. It delegates to the private implementation that
 * accepts an explicit `fileLockHandled` flag (set to `false` here).
 *
 * @receiver HttpClient Ktor HTTP client used for requests.
 * @param uploadUrl Absolute URL of the tus upload resource (returned by server on create).
 * @param file The file to upload; must implement [ITusFile].
 * @param options Upload options controlling retries, chunk size and capability checks.
 * @param onProgress Optional progress callback invoked with `(sent, total)` bytes.
 * @param block Optional lambda to customize the underlying HTTP request builder for each request.
 *
 * @throws TusProtocolException When the server response does not follow the tus protocol.
 * @throws TusUploadExpiredException If the server reports the upload as expired or missing.
 * @throws TusOffsetMismatchException If the server returns a non-advancing offset.
 */
suspend fun HttpClient.uploadTus(
    uploadUrl: String,
    file: ITusFile,
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    uploadTus(uploadUrl, file, options, onProgress, block, fileLockHandled = false)
}

private suspend fun HttpClient.uploadTus(
    uploadUrl: String,
    file: ITusFile,
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {},
    fileLockHandled: Boolean
) {
    var offset: Long

    //initial head check to see if file is already uploaded
    offset = retryWithBackoff(options.retryOptions) { headRequest(this, uploadUrl, block) }

    /*
     * Phase 2: Upload Chunks
     */
    while (offset < file.size) {
        // 1. Calculate chunk size
        val bytesRemaining = file.size - offset
        val currentChunkSize = min(options.chunkSize, bytesRemaining)

        // 2. Prepare the stream
        val chunk = file.readSection(offset, currentChunkSize)

        // 3. Send PATCH request
        val patchResponse = retryWithBackoff(options.retryOptions) {
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
            offset = retryWithBackoff(options.retryOptions) { headRequest(this, uploadUrl, block) }

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


/**
 * Create a new tus upload on the server and upload the provided [file].
 *
 * This function performs both phases of the tus flow:
 * 1. Creates the upload on the server by calling [createTus] and invokes [onCreate] with the returned upload URL.
 * 2. Uploads the file in chunks by calling [uploadTus], reporting progress via [onProgress].
 *
 * @receiver HttpClient The Ktor HTTP client used to perform requests.
 * @param createUrl The server endpoint used to create the upload.
 * @param file The file to upload; must implement [ITusFile].
 * @param metadata Optional metadata sent as `Upload-Metadata` during creation.
 * @param options Upload options controlling retries, chunk size and capability checks.
 * @param onProgress Optional callback invoked with `(sent, total)` bytes during upload.
 * @param onCreate Optional callback invoked with the upload URL returned by the server after creation.
 * @param block Optional lambda to customize the underlying [HttpRequestBuilder] for requests.
 *
 * @throws TusProtocolException If the server responses do not follow the tus protocol.
 * @throws TusUploadExpiredException If the server reports the upload as expired or missing.
 * @throws TusOffsetMismatchException If the server returns a non-advancing offset during upload.
 */
suspend fun HttpClient.createAndUploadTus(
    createUrl: String,
    file: ITusFile,
    metadata: Map<String, String> = emptyMap(),
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    onCreate: ((uploadUrl: String) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    val fileLock = if(options.useFileLock) file.fileReadLock() else AutoCloseable {}
    fileLock.use {
        /*
         * Phase 1: Create Upload
         */
        val uploadUrl = createTus(createUrl, file, metadata, options, block, fileLockHandled = true)
        onCreate?.invoke(uploadUrl)

        /*
         * Phase 2: Upload Chunks
         */
        uploadTus(uploadUrl, file, options, onProgress, block, fileLockHandled = true)
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