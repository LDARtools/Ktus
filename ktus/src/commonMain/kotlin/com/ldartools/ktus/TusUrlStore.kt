package com.ldartools.ktus

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An upload created on a tus server.
 *
 * @property uploadUrl the absolute URL of the upload resource (from the creation response's `Location`).
 * @property uploadLength the `Upload-Length` the upload was created with: the file size in bytes.
 * @property expiresAt when the server will discard an unfinished upload, in epoch milliseconds, from the
 *   `Upload-Expires` header of the creation response; null when the server did not say.
 */
data class TusUpload(
    val uploadUrl: String,
    val uploadLength: Long,
    val expiresAt: Long? = null,
)

/**
 * Where [resumeOrCreateAndUploadTus] remembers uploads in progress, keyed by a caller-chosen fingerprint
 * of the file (for example its path plus size plus modification time, or a content hash).
 *
 * Persist the entries (database, preferences, a file) to resume uploads across app restarts.
 * Implementations must be safe to call from the coroutine that runs the upload.
 */
interface TusUrlStore {
    /** The stored upload for [fingerprint], or null. */
    suspend fun get(fingerprint: String): TusUpload?

    /** Stores [upload] for [fingerprint], replacing any previous entry. */
    suspend fun set(fingerprint: String, upload: TusUpload)

    /** Removes the entry for [fingerprint], if any. */
    suspend fun remove(fingerprint: String)
}

/**
 * A [TusUrlStore] that keeps entries in memory. Uploads resume within the life of the process only;
 * useful for tests and as a reference implementation.
 */
class InMemoryTusUrlStore : TusUrlStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, TusUpload>()

    override suspend fun get(fingerprint: String): TusUpload? = mutex.withLock { entries[fingerprint] }

    override suspend fun set(fingerprint: String, upload: TusUpload) {
        mutex.withLock { entries[fingerprint] = upload }
    }

    override suspend fun remove(fingerprint: String) {
        mutex.withLock { entries.remove(fingerprint) }
    }

    /** A copy of the stored entries. */
    suspend fun snapshot(): Map<String, TusUpload> = mutex.withLock { entries.toMap() }
}
