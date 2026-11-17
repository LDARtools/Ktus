package com.ldartools.ktus

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.util.encodeBase64
import kotlin.math.max
import kotlin.math.min
import io.ktor.client.request.options as optionsRequest

private const val TUS_VERSION = "1.0.0"

/**
 * Create a new tus upload on the server and return the upload URL.
 *
 * Same as [createTusUpload], returning only [TusUpload.uploadUrl].
 *
 * @receiver HttpClient The Ktor HTTP client used to send requests.
 * @param createUrl The endpoint used to create the upload (server create URL).
 * @param file The file to be uploaded; its size is sent as the `Upload-Length` header.
 * @param metadata Optional metadata sent as `Upload-Metadata`.
 * @param options Upload options controlling retries, chunk size and capability checks.
 * @param block Optional lambda to customize the underlying HTTP request builder.
 * @return The absolute upload URL returned by the server (from the `Location` header).
 * @throws TusProtocolException If the server does not support tus, creation fails, or the server omits
 *   a valid Location header or sends one Ktus may not use.
 * @throws TusHttpException If the server answers with an HTTP error status Ktus does not retry.
 */
suspend fun HttpClient.createTus(createUrl: String,
                                 file: ITusFile,
                                 metadata: Map<String, String> = emptyMap(),
                                 options: TusUploadOptions = TusUploadOptions(),
                                 block: suspend HttpRequestBuilder.() -> Unit = {}
) : String = createTusUpload(createUrl, file, metadata, options, block).uploadUrl

/**
 * Create a new tus upload on the server.
 *
 * Performs the "Create Upload" step of the tus protocol by sending a POST to [createUrl]. If
 * [TusUploadOptions.checkServerCapabilities] is true, an OPTIONS request is sent first to verify the server
 * supports tus and that the file is within the server's `Tus-Max-Size`.
 *
 * The `Location` header of the response is resolved against [createUrl] (RFC 3986). It must stay on the
 * scheme, host and port of [createUrl] unless its host is listed in [TusUploadOptions.allowedUploadHosts],
 * and an `https` creation URL never accepts an `http` upload URL.
 *
 * @return the upload URL, its length and the server's `Upload-Expires` time, if it sent one. Persist it to
 *   resume the upload later with [uploadTus], or let [resumeOrCreateAndUploadTus] do that for you.
 * @throws IllegalArgumentException If the file is empty (see [TusUploadOptions.allowEmptyUpload]) or a
 *   metadata key is not a valid tus metadata key.
 */
suspend fun HttpClient.createTusUpload(
    createUrl: String,
    file: ITusFile,
    metadata: Map<String, String> = emptyMap(),
    options: TusUploadOptions = TusUploadOptions(),
    block: suspend HttpRequestBuilder.() -> Unit = {}
): TusUpload = withFileLock(file, options) {
    val total = snapshotSize(file, options)
    createUploadInternal(createUrl, total, metadata, options, block)
}

/**
 * Upload the provided [file] to an existing tus upload at [uploadUrl], resuming wherever the server is.
 *
 * Sends a HEAD first to learn the server's offset, then PATCHes the rest of the file in chunks. Every
 * failure whose outcome is unknown (network error, timeout, 5xx, 408, 423, 429, 409, or any 2xx other
 * than 204) is followed by a backoff and a HEAD, and the upload continues from the offset the server
 * reports; a chunk is never blindly re-sent at the old offset. See [RetryOptions] for the limits.
 *
 * The file size is read once at the start. The upload fails if the size changes before it completes,
 * if the server's `Upload-Length` differs from it, or if the server reports an impossible offset.
 *
 * @receiver HttpClient Ktor HTTP client used for requests. Configure it with `followRedirects = false`;
 *   Ktus rejects redirected tus requests.
 * @param uploadUrl Absolute URL of the tus upload resource (returned by server on create).
 * @param file The file to upload; must implement [ITusFile].
 * @param options Upload options controlling retries, chunk size and capability checks.
 * @param onProgress Optional progress callback invoked with `(sent, total)` bytes. `sent` counts bytes
 *   handed to the HTTP engine during the current PATCH (it fires while the body is written) so it can go
 *   backwards when a chunk is repeated. Use [onConfirmed] for progress the server has acknowledged.
 * @param onConfirmed Optional callback invoked with `(offset, total)` each time the server confirms a
 *   larger offset (a PATCH answered 204, or a HEAD after a failure), and exactly once with
 *   `offset == total` when the upload is complete, including when the HEAD shows it was already complete.
 * @param block Optional lambda to customize the underlying HTTP request builder for each request.
 *
 * @throws TusProtocolException When the server response does not follow the tus protocol.
 * @throws TusUploadExpiredException If the server reports the upload as expired or missing (404/410).
 * @throws TusUploadLengthMismatchException If the server's `Upload-Length` differs from the file size.
 * @throws TusOffsetMismatchException If the server reports an impossible offset.
 * @throws TusHttpException If the server answers with an HTTP error status Ktus does not retry, or keeps
 *   answering with a retryable one until the retry limits are reached.
 */
suspend fun HttpClient.uploadTus(
    uploadUrl: String,
    file: ITusFile,
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    onConfirmed: ((offset: Long, total: Long) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    withFileLock(file, options) {
        val total = snapshotSize(file, options)
        uploadInternal(uploadUrl, file, total, options, onProgress, onConfirmed, block)
    }
}

/** Binary-compatibility overload for callers compiled against Ktus 1.0.x. */
@Deprecated("Kept for binary compatibility with Ktus 1.0.x", level = DeprecationLevel.HIDDEN)
suspend fun HttpClient.uploadTus(
    uploadUrl: String,
    file: ITusFile,
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) = uploadTus(uploadUrl, file, options, onProgress, onConfirmed = null, block = block)

/**
 * Create a new tus upload on the server and upload the provided [file].
 *
 * This function performs both phases of the tus flow:
 * 1. Creates the upload on the server (see [createTusUpload]) and invokes [onCreate] with the upload URL.
 * 2. Uploads the file in chunks (see [uploadTus]), reporting progress via [onProgress] and [onConfirmed].
 *
 * To resume across attempts or app restarts, use [resumeOrCreateAndUploadTus].
 *
 * @receiver HttpClient The Ktor HTTP client used to perform requests.
 * @param createUrl The server endpoint used to create the upload.
 * @param file The file to upload; must implement [ITusFile].
 * @param metadata Optional metadata sent as `Upload-Metadata` during creation.
 * @param options Upload options controlling retries, chunk size and capability checks.
 * @param onProgress Optional callback invoked with `(sent, total)` bytes during upload; see [uploadTus].
 * @param onCreate Optional callback invoked with the upload URL returned by the server after creation.
 * @param onConfirmed Optional callback with the server-confirmed offset; see [uploadTus].
 * @param block Optional lambda to customize the underlying [HttpRequestBuilder] for requests.
 *
 * @throws TusProtocolException If the server responses do not follow the tus protocol.
 * @throws TusUploadExpiredException If the server reports the upload as expired or missing.
 * @throws TusOffsetMismatchException If the server returns an impossible offset during upload.
 * @throws TusHttpException If the server answers with an HTTP error status Ktus does not retry.
 */
suspend fun HttpClient.createAndUploadTus(
    createUrl: String,
    file: ITusFile,
    metadata: Map<String, String> = emptyMap(),
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    onCreate: ((uploadUrl: String) -> Unit)? = null,
    onConfirmed: ((offset: Long, total: Long) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    withFileLock(file, options) {
        val total = snapshotSize(file, options)
        val upload = createUploadInternal(createUrl, total, metadata, options, block)
        onCreate?.invoke(upload.uploadUrl)
        uploadInternal(upload.uploadUrl, file, total, options, onProgress, onConfirmed, block)
    }
}

/** Binary-compatibility overload for callers compiled against Ktus 1.0.x. */
@Deprecated("Kept for binary compatibility with Ktus 1.0.x", level = DeprecationLevel.HIDDEN)
suspend fun HttpClient.createAndUploadTus(
    createUrl: String,
    file: ITusFile,
    metadata: Map<String, String> = emptyMap(),
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    onCreate: ((uploadUrl: String) -> Unit)? = null,
    block: suspend HttpRequestBuilder.() -> Unit = {}
) = createAndUploadTus(createUrl, file, metadata, options, onProgress, onCreate, onConfirmed = null, block = block)

/**
 * Upload [file], resuming a previous upload of the same file when [store] remembers one.
 *
 * 1. If [store] has an entry for [fingerprint] whose length matches the file, which has not passed its
 *    `expiresAt`, and whose URL is allowed for [createUrl], the upload resumes there (HEAD, then PATCH).
 *    If the server says it expired (404/410) or reports a different `Upload-Length`, the entry is dropped
 *    and a new upload is created.
 * 2. Otherwise a new upload is created and saved to [store] immediately, before any data is sent, so a
 *    crash or cancellation mid-upload can resume next time.
 * 3. When the upload completes the entry is removed.
 *
 * Any other failure leaves the entry in place for the next attempt.
 *
 * @param fingerprint identifies this file (and this version of it) in [store]. Choose something that
 *   changes when the content changes, such as path + size + modification time, or a hash.
 * @param onCreate invoked with the upload URL when a new upload is created (not when resuming).
 * @param nowMillis the current time in epoch milliseconds, for checking `expiresAt`. Replace only in tests.
 * @see createAndUploadTus for the other parameters.
 */
suspend fun HttpClient.resumeOrCreateAndUploadTus(
    createUrl: String,
    file: ITusFile,
    fingerprint: String,
    store: TusUrlStore,
    metadata: Map<String, String> = emptyMap(),
    options: TusUploadOptions = TusUploadOptions(),
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    onCreate: ((uploadUrl: String) -> Unit)? = null,
    onConfirmed: ((offset: Long, total: Long) -> Unit)? = null,
    nowMillis: () -> Long = { GMTDate().timestamp },
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    withFileLock(file, options) {
        val total = snapshotSize(file, options)

        val stored = store.get(fingerprint)
        if (stored != null) {
            if (isResumable(stored, total, createUrl, options, nowMillis)) {
                try {
                    uploadInternal(stored.uploadUrl, file, total, options, onProgress, onConfirmed, block)
                    store.remove(fingerprint)
                    return@withFileLock
                } catch (e: TusUploadExpiredException) {
                    // Gone on the server: fall through and create a new upload.
                } catch (e: TusUploadLengthMismatchException) {
                    // The URL belongs to a different version of the file: start over.
                }
            }
            store.remove(fingerprint)
        }

        val upload = createUploadInternal(createUrl, total, metadata, options, block)
        store.set(fingerprint, upload)
        onCreate?.invoke(upload.uploadUrl)
        uploadInternal(upload.uploadUrl, file, total, options, onProgress, onConfirmed, block)
        store.remove(fingerprint)
    }
}

/**
 * Terminate (delete) an upload on the server, using the tus
 * [Termination](https://tus.io/protocols/resumable-upload#termination) extension.
 *
 * `204 No Content` and also `404`/`410` (the upload is already gone) count as success. Network errors and
 * retryable statuses are retried as described by [TusUploadOptions.retryOptions].
 *
 * @throws TusHttpException If the server refuses, for example with 403 or 405 when it does not support
 *   termination.
 */
suspend fun HttpClient.terminateTus(
    uploadUrl: String,
    options: TusUploadOptions = TusUploadOptions(),
    block: suspend HttpRequestBuilder.() -> Unit = {}
) {
    val retry = options.retryOptions
    retryTusOperation(
        op = TusOp.DELETE,
        targetUrl = uploadUrl,
        options = retry,
        backoff = Backoff(retry),
        send = {
            delete(uploadUrl) {
                tusVersionHeader()
                block()
            }
        },
        onSuccess = { },
    )
}

// ---------------------------------------------------------------------------------------------------------

private suspend fun <T> withFileLock(file: ITusFile, options: TusUploadOptions, action: suspend () -> T): T {
    val lock = if (options.useFileLock) file.fileReadLock() else null
    try {
        return action()
    } finally {
        lock?.close()
    }
}

private fun snapshotSize(file: ITusFile, options: TusUploadOptions): Long {
    val size = file.size
    require(size >= 0) { "File size must not be negative (was $size)" }
    require(size > 0 || options.allowEmptyUpload) {
        "Refusing to upload an empty file \"${file.name}\"; set TusUploadOptions.allowEmptyUpload to allow it"
    }
    return size
}

private fun ensureSizeUnchanged(file: ITusFile, total: Long) {
    val now = file.size
    if (now != total) {
        throw TusProtocolException("File \"${file.name}\" changed size during the upload: was $total bytes, now $now")
    }
}

private fun isResumable(
    stored: TusUpload,
    total: Long,
    createUrl: String,
    options: TusUploadOptions,
    nowMillis: () -> Long,
): Boolean {
    if (stored.uploadLength != total) return false
    val expiresAt = stored.expiresAt
    if (expiresAt != null && expiresAt <= nowMillis()) return false
    return try {
        validateUploadUrl(createUrl, stored.uploadUrl, options.allowedUploadHosts)
        true
    } catch (e: TusProtocolException) {
        false
    }
}

private suspend fun HttpClient.createUploadInternal(
    createUrl: String,
    total: Long,
    metadata: Map<String, String>,
    options: TusUploadOptions,
    block: suspend HttpRequestBuilder.() -> Unit,
): TusUpload {
    // Validate the metadata before touching the network.
    val encodedMetadata = encodeMetadata(metadata, options.omitEmptyMetadataValues)
    val retry = options.retryOptions

    if (options.checkServerCapabilities) {
        retryTusOperation(
            op = TusOp.OPTIONS,
            targetUrl = createUrl,
            options = retry,
            backoff = Backoff(retry),
            send = {
                optionsRequest(urlString = createUrl) {
                    // Per TUS spec, Tus-Resumable MUST NOT be included in OPTIONS requests.
                    block()
                }
            },
            onSuccess = { response ->
                if (response.headers["Tus-Resumable"] == null) {
                    throw TusProtocolException("Server does not support tus protocol.")
                }
                val maxSize = response.headers["Tus-Max-Size"]?.trim()?.toLongOrNull()
                if (maxSize != null && total > maxSize) {
                    throw TusProtocolException("File is $total bytes but the server accepts at most $maxSize (Tus-Max-Size)")
                }
            },
        )
    }

    return retryTusOperation(
        op = TusOp.POST,
        targetUrl = createUrl,
        options = retry,
        backoff = Backoff(retry),
        send = {
            post(urlString = createUrl) {
                header("Upload-Length", total.toString())
                tusVersionHeader()
                if (encodedMetadata != null) {
                    header("Upload-Metadata", encodedMetadata)
                }
                block()
            }
        },
        onSuccess = { response ->
            if (response.status != HttpStatusCode.Created) {
                throw TusProtocolException("Failed to create upload: ${response.status}")
            }
            val location = response.headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() }
                ?: throw TusProtocolException("Server did not provide a Location header")
            val uploadUrl = createUrl.resolveUrl(location)
            validateUploadUrl(createUrl, uploadUrl, options.allowedUploadHosts)
            TusUpload(
                uploadUrl = uploadUrl,
                uploadLength = total,
                expiresAt = response.headers["Upload-Expires"]?.let { parseHttpDate(it) },
            )
        },
    )
}

private suspend fun HttpClient.uploadInternal(
    uploadUrl: String,
    file: ITusFile,
    total: Long,
    options: TusUploadOptions,
    onProgress: ((sent: Long, total: Long) -> Unit)?,
    onConfirmed: ((offset: Long, total: Long) -> Unit)?,
    block: suspend HttpRequestBuilder.() -> Unit,
) {
    val retry = options.retryOptions
    val backoff = Backoff(retry)

    var offset = headOffset(uploadUrl, total, retry, backoff, block)
    backoff.reset()
    var confirmed = offset
    if (offset == total) {
        onConfirmed?.invoke(total, total)
        return
    }

    val minChunkSize = options.effectiveMinChunkSize
    var chunkSize = options.chunkSize
    var patchAttempts = 0

    while (offset < total) {
        ensureSizeUnchanged(file, total)
        val start = offset
        val length = min(chunkSize, total - start)
        patchAttempts++

        val body = TusPatchContent(file, start, length)
        val outcome = try {
            attemptTus(TusOp.PATCH, uploadUrl, retry) {
                patch(urlString = uploadUrl) {
                    tusVersionHeader()
                    header("Upload-Offset", start.toString())
                    // Content-Length comes from the body's contentLength.
                    setBody(body)

                    // Progress listener hook (Ktor capability): bytes handed to the engine so far.
                    onUpload { bytesSentTotal, _ ->
                        onProgress?.invoke(start + bytesSentTotal, total)
                    }
                    block()
                }
            }
        } finally {
            // If the server answered before reading the whole body, stop writing it.
            body.abandon()
        }

        when (outcome) {
            is Outcome.Ok -> {
                val serverOffset = outcome.response.headers["Upload-Offset"]?.trim()?.toLongOrNull()
                    ?: throw TusProtocolException("Missing Upload-Offset in PATCH response")
                // A 204 must account for some, and at most all, of the bytes we sent.
                if (serverOffset <= start || serverOffset > start + length) {
                    throw TusOffsetMismatchException(
                        "Server returned an invalid offset: $serverOffset (sent $length bytes at offset $start)")
                }
                offset = serverOffset
                patchAttempts = 0
                backoff.reset()
                ensureSizeUnchanged(file, total)
                if (offset > confirmed) {
                    confirmed = offset
                    onConfirmed?.invoke(offset, total)
                }
            }

            is Outcome.Fail -> throw outcome.error

            is Outcome.Retry -> {
                if (outcome.timedOut && options.adaptiveChunkSize) {
                    chunkSize = max(minChunkSize, chunkSize / 2)
                }
                if (!backoff.awaitRetry(patchAttempts, outcome.retryAfterMillis)) {
                    throw patchRetriesExhausted(outcome, patchAttempts)
                }
                // The PATCH may or may not have been applied: ask the server where it is.
                val serverOffset = headOffset(uploadUrl, total, retry, backoff, block)
                if (serverOffset > start) {
                    // The server kept (some of) the data: that is progress, start a fresh failure run.
                    patchAttempts = 0
                    backoff.reset()
                }
                offset = serverOffset
                if (offset > confirmed) {
                    confirmed = offset
                    onConfirmed?.invoke(offset, total)
                }
            }
        }
    }
}

private fun patchRetriesExhausted(outcome: Outcome.Retry, attempts: Int): Throwable {
    val cause = outcome.cause
    // A network error stays a network error, so callers can tell "offline" from "server said no".
    if (cause != null && cause !is ResponseException) return cause
    return TusHttpException(
        statusCode = outcome.statusCode ?: 0,
        method = TusOp.PATCH.method,
        body = outcome.body,
        message = "Upload failed after $attempts consecutive PATCH failures (last status: ${outcome.describe()})",
        cause = cause,
    )
}

private suspend fun HttpClient.headOffset(
    uploadUrl: String,
    total: Long,
    retry: RetryOptions,
    backoff: Backoff,
    block: suspend HttpRequestBuilder.() -> Unit,
): Long = retryTusOperation(
    op = TusOp.HEAD,
    targetUrl = uploadUrl,
    options = retry,
    backoff = backoff,
    send = {
        head(uploadUrl) {
            tusVersionHeader()
            block()
        }
    },
    onSuccess = { response ->
        response.headers["Upload-Length"]?.let { header ->
            val serverLength = header.trim().toLongOrNull()
                ?: throw TusProtocolException("Invalid Upload-Length in HEAD response: \"$header\"")
            if (serverLength != total) throw TusUploadLengthMismatchException(serverLength, total)
        }
        val serverOffset = response.headers["Upload-Offset"]?.trim()?.toLongOrNull()
            ?: throw TusProtocolException("Missing Upload-Offset in HEAD response")
        if (serverOffset < 0 || serverOffset > total) {
            throw TusOffsetMismatchException("HEAD reported Upload-Offset $serverOffset outside [0, $total]")
        }
        serverOffset
    },
)

private fun HttpMessageBuilder.tusVersionHeader(): Unit = this.header("Tus-Resumable", TUS_VERSION)

/**
 * Encodes [metadata] for `Upload-Metadata`, or returns null when nothing is left to send.
 *
 * @throws IllegalArgumentException for a key that is empty or contains anything other than printable
 *   ASCII without spaces and commas.
 */
internal fun encodeMetadata(metadata: Map<String, String>, omitEmptyValues: Boolean): String? {
    metadata.keys.forEach { key ->
        require(key.isNotEmpty()) { "Metadata key must not be empty" }
        require(key.all { it.code in 0x21..0x7E && it != ',' }) {
            "Metadata key must be printable ASCII without spaces or commas: \"$key\""
        }
    }
    val entries = if (omitEmptyValues) metadata.filterValues { it.isNotEmpty() } else metadata
    if (entries.isEmpty()) return null
    return entries.entries.joinToString(",") { (key, value) ->
        if (value.isEmpty()) key else "$key ${value.encodeBase64()}"
    }
}
