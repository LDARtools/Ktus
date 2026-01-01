package com.ldartools.ktus.okio
import com.ldartools.ktus.ITusFile
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use

class OkioTusFile(private val path: Path) : ITusFile {
    override val size: Long
        get() = FileSystem.SYSTEM.metadata(path).size ?: 0L
    override val name: String
        get() = path.name

    override suspend fun readSection(offset: Long, length: Long): ByteReadChannel {
        // We use Dispatchers.IO because Okio file operations are blocking
        return CoroutineScope(Dispatchers.IO).writer {
            // Open the file source
            FileSystem.SYSTEM.source(path).buffer().use { source ->

                // 1. Seek to the upload offset
                // Okio's skip() is efficient and works across platforms
                source.skip(offset)

                // 2. Read only the requested 'length' bytes
                var bytesRemaining = length
                val buffer = ByteArray(8 * 1024) // 8KB buffer

                while (bytesRemaining > 0) {
                    // Calculate how much to read in this iteration (buffer size or remaining)
                    val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()

                    // Read into buffer
                    val bytesRead = source.read(buffer, 0, bytesToRead)

                    // Handle EOF (End of File) unexpectedly
                    if (bytesRead == -1) break

                    // Write to the Ktor channel
                    channel.writeFully(buffer, 0, bytesRead)

                    bytesRemaining -= bytesRead
                }
            }
        }.channel
    }

    override suspend fun fileReadLock(): AutoCloseable {
        throw NotImplementedError("File read locks are not supported by Okio. If you want this feature, please upvote https://github.com/square/okio/issues/1464.")
    }
}

fun okio.Path.toTusFile(): ITusFile = OkioTusFile(this)