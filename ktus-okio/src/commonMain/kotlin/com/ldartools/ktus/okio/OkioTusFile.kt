package com.ldartools.ktus.okio
import com.ldartools.ktus.ITusFile
import com.ldartools.ktus.TusLocalFileException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use

/**
 * An [ITusFile] backed by a file on an Okio [FileSystem].
 *
 * Each chunk is read with a positional read ([FileSystem.openReadOnly] + `source(offset)`), so resuming
 * at a large offset does not read the start of the file. The file is opened inside the PATCH request and
 * closed when the chunk has been written, or when the request fails or is cancelled.
 *
 * @param path the file to upload.
 * @param fileSystem the file system holding [path]; [FileSystem.SYSTEM] unless you pass another one (for
 *   example okio's `FakeFileSystem` in tests).
 */
class OkioTusFile(private val path: Path, private val fileSystem: FileSystem) : ITusFile {

    constructor(path: Path) : this(path, FileSystem.SYSTEM)

    override val size: Long
        get() = localFile { fileSystem.metadata(path).size }
            ?: throw TusLocalFileException("Cannot read the size of \"${path.name}\"")
    override val name: String
        get() = path.name

    override suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) {
        // Okio file operations block, so read on the IO dispatcher. withContext keeps this in the
        // request's coroutine: cancelling the request cancels the read and closes the file.
        withContext(Dispatchers.IO) {
            localFile { fileSystem.openReadOnly(path) }.use { handle ->
                handle.source(offset).buffer().use { source ->
                    copySection(source, offset, length) { buffer, count -> channel.writeFully(buffer, 0, count) }
                }
            }
        }
    }

    /**
     * Legacy channel-returning read; Ktus 1.1 uploads through [writeSection] instead. The channel is
     * filled by a background writer that closes the file when it finishes, fails, or the channel is
     * cancelled. As in 1.0, a range past the end of the file yields the bytes that exist.
     */
    override suspend fun readSection(offset: Long, length: Long): ByteReadChannel {
        return CoroutineScope(Dispatchers.IO).writer {
            fileSystem.openReadOnly(path).use { handle ->
                handle.source(offset).buffer().use { source ->
                    val buffer = ByteArray(8 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        channel.writeFully(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        }.channel
    }

    /** A missing, deleted or unreadable file is a [TusLocalFileException]: retrying cannot fix it. */
    private inline fun <T> localFile(action: () -> T): T = try {
        action()
    } catch (e: okio.IOException) {
        throw TusLocalFileException("Cannot read \"${path.name}\": ${e.message}", e)
    }

    override suspend fun fileReadLock(): AutoCloseable {
        throw NotImplementedError("File read locks are not supported by Okio. If you want this feature, please upvote https://github.com/square/okio/issues/1464.")
    }

    private suspend inline fun copySection(
        source: BufferedSource,
        offset: Long,
        length: Long,
        write: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(8 * 1024)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = source.read(buffer, 0, toRead)
            if (read == -1) {
                throw TusLocalFileException(
                    "File \"${path.name}\" ended early: expected $length bytes at offset $offset, got ${length - remaining}")
            }
            write(buffer, read)
            remaining -= read
        }
    }
}

fun okio.Path.toTusFile(): ITusFile = OkioTusFile(this)

fun okio.Path.toTusFile(fileSystem: FileSystem): ITusFile = OkioTusFile(this, fileSystem)
