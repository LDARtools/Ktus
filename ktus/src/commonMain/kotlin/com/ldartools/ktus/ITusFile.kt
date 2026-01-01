package com.ldartools.ktus

import io.ktor.utils.io.ByteReadChannel

/**
 * An interface abstracting the source of a file to be uploaded via TUS.
 * This allows the upload logic to be independent of whether the file is on disk,
 * in memory, or from another source.
 */
interface ITusFile {
    /** The total size of the file in bytes. */
    val size: Long

    /** The name of the file, which may be used for metadata. */
    val name: String

    /**
     * Reads a specific range of the file into a ByteReadChannel.
     * Implementations should ensure this operation is efficient and does not load
     * the entire file into memory, especially for large files.
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
}