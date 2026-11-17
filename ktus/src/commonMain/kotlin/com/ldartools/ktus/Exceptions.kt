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
 * A problem with the local file rather than the server: it changed size during the upload, or it held fewer
 * bytes than its size while a chunk was read. Uploading the same file again will not help until it is fixed.
 */
open class TusLocalFileException(message: String, cause: Throwable? = null) : TusProtocolException(message, cause)

/**
 * Ktus sent the PATCH carrying the last bytes of the file, did not learn whether the server stored them, and
 * then could not find out. The server may hold the complete file (tusdotnet removes an upload once it is
 * complete, so a later HEAD or PATCH answers 404), or it may not.
 *
 * Thrown instead of any other failure (including [TusUploadExpiredException], exhausted retries and HTTP
 * errors) once that is the case; the original failure is the [cause].
 *
 * **Never start a fresh upload of the same file blindly after it**: that can make the server receive and
 * process the file twice. With [resumeOrCreateAndUploadTus], calling it again later is always safe: it sends
 * HEAD to the same upload URL, resumes or confirms completion, and succeeds if the server is fine; it never
 * re-creates a marked entry. When the cause is a 404/410 or an expired entry (the upload URL is gone), check on
 * your server whether the file arrived, then remove the store entry. See the README section "When the outcome
 * is unknown".
 *
 * It deliberately does not extend [IOException][kotlinx.io.IOException] or [TusProtocolException], so a
 * generic "network error, retry later" or "expired, re-create" handler does not swallow it.
 *
 * @property uploadUrl the upload whose outcome is unknown.
 */
class TusUploadOutcomeUnknownException(
    val uploadUrl: String,
    cause: Throwable? = null,
) : Exception(
    "The outcome of the final PATCH to $uploadUrl is unknown; the server may already have the complete file. " +
        "Verify on the server before uploading again.",
    cause,
) {
    /**
     * The upload URL is gone: the server answered 404/410 (typically because it finished and removed the
     * upload), or the stored entry expired. Only the server can say whether the file arrived; check it.
     */
    val isUploadGone: Boolean
        get() = when (val c = cause) {
            null -> true
            is TusUploadExpiredException -> true
            is TusHttpException -> c.statusCode == 404 || c.statusCode == 410
            else -> false
        }

    /**
     * The last failure was a network error (offline, timeout, connection lost), not an answer from the server.
     * Calling [resumeOrCreateAndUploadTus] again later is the right response. Note that [TusProtocolException]
     * (and so [TusHttpException] and [TusUploadExpiredException]) also extends `IOException`; those are not
     * network failures and are excluded here.
     */
    val isNetworkFailure: Boolean
        get() = cause.let { it is kotlinx.io.IOException && it !is TusProtocolException }
}

/**
 * The answer came from another origin because the client followed a redirect. The request (and its body) may
 * have reached the original server first, so for a final PATCH the outcome is unknown.
 */
internal class RedirectedAnswerException(message: String) : TusProtocolException(message)

/** Carries an exception thrown by one of the caller's callbacks (or store) so it is never reclassified. */
internal class CallbackFailure(val error: Throwable) : RuntimeException(error)
