package com.ldartools.ktus

/**
 * Options for tus uploads.
 *
 * @param checkServerCapabilities send an OPTIONS request before creating an upload, to confirm the
 *   server speaks tus and that the file is within its `Tus-Max-Size` (default true).
 * @param chunkSize the maximum number of bytes sent in one PATCH request (default 2 MiB).
 * @param useFileLock take [ITusFile.fileReadLock] for the duration of the upload (default false).
 * @param retryOptions how failed requests are retried (default [RetryOptions]).
 * @param allowEmptyUpload allow uploading a zero-byte file. Off by default, because an empty file is
 *   almost always a bug in the caller (a file that was not written yet).
 * @param allowedUploadHosts hosts, besides the host of the creation URL, that the server may send the
 *   client to in the `Location` header of a creation response. Matching ignores case. An upload URL on
 *   any other host is rejected, and an `https` creation URL never accepts a plain `http` upload URL.
 * @param omitEmptyMetadataValues leave metadata entries with an empty value out of `Upload-Metadata`
 *   (default true). Some servers, including tusdotnet, reject a key that has no value.
 * @param adaptiveChunkSize after a PATCH times out (or the server answers 408 or 504), halve the size
 *   of the following PATCH requests, never below [minChunkSize] (default true).
 * @param minChunkSize the smallest chunk [adaptiveChunkSize] shrinks to (default 256 KiB). It is never
 *   above [chunkSize].
 * @param activityHeartbeatMillis while a request is in flight, call `onActivity` this often from a side job
 *   that is cancelled with the request (default 5 s; null turns it off). Also the longest gap between
 *   `onActivity` calls while Ktus waits to retry.
 * @param sendStallMillis abandon a request whose body makes no progress for this long while it is not fully
 *   sent (default null: off). The clock starts when the request is handed to the engine, so a stalled connect
 *   or TLS handshake counts, and restarts on every body progress event (the events behind `onProgress`).
 *   A request without a body (OPTIONS, HEAD, POST, DELETE) is fully sent as soon as it is handed to the
 *   engine, so this limit never applies to it; [replyTimeoutMillis] does. Only that request is abandoned, as a
 *   timed-out network failure ([TusStallTimeoutException]): it is retried within the retry budget, and an
 *   abandoned final PATCH leaves its outcome unknown exactly as an engine timeout does.
 * @param replyTimeoutMillis abandon a request that has been fully sent (every body byte handed to the engine)
 *   when no response arrives within this long (default null: off). Applies to every request except the PATCH
 *   carrying the last bytes, which uses [finalReplyTimeoutMillis].
 * @param finalReplyTimeoutMillis the same as [replyTimeoutMillis] for the PATCH carrying the last bytes, which
 *   a server may answer only after slow completion work (default null: off). [replyTimeoutMillis] does not
 *   apply to it.
 *
 * The three silence limits do not depend on the engine, and they do not replace its timeouts: keep the
 * engine's limits (Ktor `HttpTimeout`) as an outer cap, set at least as high as these. Progress means bytes
 * handed to Ktor, which buffers about 1 MiB of a body before the socket: a chunk of up to ~1 MiB counts as
 * fully sent almost at once (its transmission runs on the reply clock), and a larger one moves in ~1 MiB steps.
 * Size each limit above the time the slowest link needs for min([chunkSize], ~1 MiB). See "Weak links" in the
 * README, including why an engine socket timeout is not a silence limit on CIO.
 */
data class TusUploadOptions(
    val checkServerCapabilities: Boolean = true,
    val chunkSize: Long = 2 * 1024 * 1024, // 2MB default
    val useFileLock: Boolean = false,
    val retryOptions: RetryOptions = RetryOptions(),
    val allowEmptyUpload: Boolean = false,
    val allowedUploadHosts: Set<String> = emptySet(),
    val omitEmptyMetadataValues: Boolean = true,
    val adaptiveChunkSize: Boolean = true,
    val minChunkSize: Long = 256 * 1024,
    val activityHeartbeatMillis: Long? = 5_000,
    val sendStallMillis: Long? = null,
    val replyTimeoutMillis: Long? = null,
    val finalReplyTimeoutMillis: Long? = null,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(minChunkSize > 0) { "minChunkSize must be positive" }
        require(activityHeartbeatMillis == null || activityHeartbeatMillis > 0) {
            "activityHeartbeatMillis must be positive, or null to turn the heartbeat off"
        }
        require(sendStallMillis == null || sendStallMillis > 0) { "sendStallMillis must be positive, or null to turn it off" }
        require(replyTimeoutMillis == null || replyTimeoutMillis > 0) { "replyTimeoutMillis must be positive, or null to turn it off" }
        require(finalReplyTimeoutMillis == null || finalReplyTimeoutMillis > 0) {
            "finalReplyTimeoutMillis must be positive, or null to turn it off"
        }
    }

    /** Binary-compatibility constructor for callers compiled against Ktus 1.1.x. */
    @Deprecated("Kept for binary compatibility with Ktus 1.1.x", level = DeprecationLevel.HIDDEN)
    constructor(
        checkServerCapabilities: Boolean = true,
        chunkSize: Long = 2 * 1024 * 1024,
        useFileLock: Boolean = false,
        retryOptions: RetryOptions = RetryOptions(),
        allowEmptyUpload: Boolean = false,
        allowedUploadHosts: Set<String> = emptySet(),
        omitEmptyMetadataValues: Boolean = true,
        adaptiveChunkSize: Boolean = true,
        minChunkSize: Long = 256 * 1024,
        activityHeartbeatMillis: Long? = 5_000,
    ) : this(checkServerCapabilities, chunkSize, useFileLock, retryOptions, allowEmptyUpload, allowedUploadHosts,
        omitEmptyMetadataValues, adaptiveChunkSize, minChunkSize, activityHeartbeatMillis, sendStallMillis = null)

    /** Binary-compatibility `copy` for callers compiled against Ktus 1.1.x. */
    @Deprecated("Kept for binary compatibility with Ktus 1.1.x", level = DeprecationLevel.HIDDEN)
    fun copy(
        checkServerCapabilities: Boolean = this.checkServerCapabilities,
        chunkSize: Long = this.chunkSize,
        useFileLock: Boolean = this.useFileLock,
        retryOptions: RetryOptions = this.retryOptions,
        allowEmptyUpload: Boolean = this.allowEmptyUpload,
        allowedUploadHosts: Set<String> = this.allowedUploadHosts,
        omitEmptyMetadataValues: Boolean = this.omitEmptyMetadataValues,
        adaptiveChunkSize: Boolean = this.adaptiveChunkSize,
        minChunkSize: Long = this.minChunkSize,
        activityHeartbeatMillis: Long? = this.activityHeartbeatMillis,
    ): TusUploadOptions = copy(checkServerCapabilities = checkServerCapabilities, chunkSize = chunkSize,
        useFileLock = useFileLock, retryOptions = retryOptions, allowEmptyUpload = allowEmptyUpload,
        allowedUploadHosts = allowedUploadHosts, omitEmptyMetadataValues = omitEmptyMetadataValues,
        adaptiveChunkSize = adaptiveChunkSize, minChunkSize = minChunkSize,
        activityHeartbeatMillis = activityHeartbeatMillis, sendStallMillis = sendStallMillis)

    /** Binary-compatibility constructor for callers compiled against Ktus 1.0.x. */
    @Deprecated("Kept for binary compatibility with Ktus 1.0.x", level = DeprecationLevel.HIDDEN)
    constructor(
        checkServerCapabilities: Boolean = true,
        chunkSize: Long = 2 * 1024 * 1024,
        useFileLock: Boolean = false,
        retryOptions: RetryOptions = RetryOptions(),
    ) : this(checkServerCapabilities, chunkSize, useFileLock, retryOptions, allowEmptyUpload = false)

    /** Binary-compatibility `copy` for callers compiled against Ktus 1.0.x. */
    @Deprecated("Kept for binary compatibility with Ktus 1.0.x", level = DeprecationLevel.HIDDEN)
    fun copy(
        checkServerCapabilities: Boolean = this.checkServerCapabilities,
        chunkSize: Long = this.chunkSize,
        useFileLock: Boolean = this.useFileLock,
        retryOptions: RetryOptions = this.retryOptions,
    ): TusUploadOptions = copy(checkServerCapabilities = checkServerCapabilities, chunkSize = chunkSize,
        useFileLock = useFileLock, retryOptions = retryOptions, allowEmptyUpload = allowEmptyUpload)

    /** The floor adaptive chunk sizing shrinks to: [minChunkSize], but never above [chunkSize]. */
    internal val effectiveMinChunkSize: Long get() = minOf(minChunkSize, chunkSize)
}
