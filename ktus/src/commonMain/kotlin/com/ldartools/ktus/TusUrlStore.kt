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
 * @property finalSentAt set (epoch milliseconds) by [resumeOrCreateAndUploadTus] just before it sends the PATCH
 *   carrying the last bytes. It is cleared only when a later PATCH is answered 204 with an offset below the
 *   total (which proves the upload still exists and is incomplete), or when the final PATCH gets a definitive
 *   refusal (a 4xx, a redirect or a short local read, with no earlier final PATCH left unknown; never a 5xx). A HEAD never clears it: it may be answered by a stale server instance. While it is
 *   set the upload's outcome may be unknown: Ktus never re-creates the upload, and any failure (a 404/410
 *   included) throws [TusUploadOutcomeUnknownException]. Persist it with the rest of the entry.
 */
data class TusUpload(
    val uploadUrl: String,
    val uploadLength: Long,
    val expiresAt: Long? = null,
    val finalSentAt: Long? = null,
)

/**
 * Where [resumeOrCreateAndUploadTus] remembers uploads in progress, keyed by a caller-chosen fingerprint
 * of the file (for example its path plus size plus modification time, or a content hash).
 *
 * Persist the entries (database, preferences, a file) to resume uploads across app restarts.
 * Implementations must be safe to call from the coroutine that runs the upload.
 *
 * **Writes must be durable when [set] and [remove] return** (for example `SharedPreferences.commit()` or
 * DataStore's `edit`, not `apply()`). Ktus writes the [TusUpload.finalSentAt] mark right before sending the
 * last bytes; if that write is lost (the process dies before an asynchronous write reaches disk), the next
 * call cannot know the final PATCH may have landed and may upload the file a second time.
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
