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
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(minChunkSize > 0) { "minChunkSize must be positive" }
    }

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
