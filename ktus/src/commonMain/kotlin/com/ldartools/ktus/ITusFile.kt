package com.ldartools.ktus

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully

/**
 * An interface abstracting the source of a file to be uploaded via TUS.
 * This allows the upload logic to be independent of whether the file is on disk,
 * in memory, or from another source.
 *
 * Whatever the source, it must be re-readable: a chunk is read again when its PATCH has to be repeated.
 */
interface ITusFile {
    /**
     * The total size of the file in bytes. Ktus reads it once when an upload starts and fails the upload
     * if it changes before the upload completes.
     */
    val size: Long

    /** The name of the file, which may be used for metadata. */
    val name: String

    /**
     * Reads a specific range of the file into a ByteReadChannel.
     * Implementations should ensure this operation is efficient and does not load
     * the entire file into memory, especially for large files.
     *
     * Ktus 1.1 calls [writeSection] instead; its default implementation calls this function. Override
     * [writeSection] to read the file inside the request (see there).
     *
     * @param offset The byte offset to start reading from.
     * @param length The number of bytes to read.
     * @return A ByteReadChannel containing the specified section of the file.
     */
    suspend fun readSection(offset: Long, length: Long): ByteReadChannel

    /**
     * Creates a read lock on the file that will be closed when the upload is complete.
     * This is optional and helps prevent the file from being modified during an upload.
     * The returned AutoCloseable will be invoked at the end of the upload process.
     */
    suspend fun fileReadLock(): AutoCloseable

    /**
     * Writes exactly [length] bytes of the file, starting at [offset], to [channel].
     *
     * Ktus calls this from inside the PATCH request while the engine sends the body, in the request's own
     * coroutine. An implementation that opens a file here and closes it in a `finally`/`use` block can
     * therefore never leak the file handle: if the request is cancelled or fails, this function is
     * cancelled with it.
     *
     * It must throw [TusProtocolException] if the file holds fewer than [length] bytes at [offset]; a
     * shorter body would otherwise reach the server as a truncated chunk.
     *
     * The default implementation copies from [readSection], for implementations written for Ktus 1.0.
     */
    suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) {
        val source = readSection(offset, length)
        try {
            copyExactly(source, channel, offset, length)
        } finally {
            source.cancel()
        }
    }
}

/**
 * Copies exactly [length] bytes from [source] to [channel], throwing [TusProtocolException] if [source]
 * ends early.
 */
internal suspend fun copyExactly(source: ByteReadChannel, channel: ByteWriteChannel, offset: Long, length: Long) {
    val buffer = ByteArray(minOf(length, 64L * 1024).toInt().coerceAtLeast(1))
    var remaining = length
    while (remaining > 0) {
        val read = source.readAvailable(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read == -1) {
            throw TusProtocolException(
                "File ended early: expected $length bytes at offset $offset, got ${length - remaining}")
        }
        channel.writeFully(buffer, 0, read)
        remaining -= read
    }
}
