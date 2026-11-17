package com.ldartools.ktus
import kotlinx.io.IOException

/**
 * Base class for every error Ktus raises because the server (or the file) did not behave the way the
 * tus protocol requires. Ktus never retries these.
 *
 * It extends [IOException] for backwards compatibility with 1.0.x, where callers caught it as one.
 */
open class TusProtocolException(message: String?, cause: Throwable?) : IOException(message, cause) {
    constructor(message: String) : this(message, null)
}

/**
 * The upload resource no longer exists on the server: a HEAD or PATCH answered `404 Not Found` or
 * `410 Gone`. The upload has to be created again.
 *
 * This is only thrown for an existing upload URL. A 404/410 from the creation endpoint (POST or
 * OPTIONS) is reported as a [TusHttpException] instead, because nothing has expired there.
 */
class TusUploadExpiredException(message: String) : TusProtocolException(message) {
    constructor() : this("Upload expired or invalid.")
}

/** The server reported an offset that is impossible for the bytes the client sent. */
class TusOffsetMismatchException(message: String) : TusProtocolException(message)

/**
 * The server reports an `Upload-Length` for this upload that differs from the size of the local file,
 * so the URL belongs to a different file (or to an older version of it). Resuming would corrupt the
 * upload, so the caller should create a new one.
 */
class TusUploadLengthMismatchException(
    val serverLength: Long,
    val localLength: Long,
) : TusProtocolException("Server Upload-Length $serverLength does not match the local file size $localLength")

/**
 * The server answered a tus request with an HTTP status that Ktus does not retry, or kept answering
 * with a retryable status until the retry budget ran out.
 *
 * @property statusCode the HTTP status code, for example 403.
 * @property method the HTTP method of the failing request (`POST`, `HEAD`, `PATCH`, `OPTIONS`, `DELETE`).
 * @property body the start of the response body when one could be read, for diagnostics.
 */
class TusHttpException(
    val statusCode: Int,
    val method: String,
    val body: String? = null,
    message: String = "tus $method request failed with HTTP $statusCode",
    cause: Throwable? = null,
) : TusProtocolException(message, cause)

/**
 * The response to the PATCH that carried the last bytes of the file was lost, and the server then answered
 * the follow-up HEAD with 404/410. The server may have stored the whole file and finished (and, like
 * tusdotnet, removed) the upload, or the upload may really have expired. Ktus cannot tell which.
 *
 * Check on the server (by the file's id, checksum or name) whether the file arrived before uploading it
 * again. It is deliberately not a [TusUploadExpiredException], so code that re-creates expired uploads
 * does not upload the file twice.
 *
 * @property uploadUrl the upload whose final response was lost.
 */
class TusUploadPossiblyCompleteException(
    val uploadUrl: String,
    cause: Throwable? = null,
) : TusProtocolException(
    "The final PATCH response for $uploadUrl was lost and the upload is now gone (404/410); the server may " +
        "already have the complete file. Verify on the server before uploading again.",
    cause,
)
