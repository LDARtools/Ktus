package com.ldartools.ktus.test

import com.ldartools.ktus.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Eighth review: a short read on the final PATCH over OkHttp is a local error and clears the mark. */
class OkHttpShortReadMarkTest {
    /** Claims [claimed] bytes, holds fewer: the final chunk ends early. */
    class ShortFile(private val bytes: ByteArray, private val claimed: Long) : ITusFile {
        override val size: Long get() = claimed
        override val name: String get() = "short.bin"
        override suspend fun writeSection(offset: Long, length: Long, channel: ByteWriteChannel) {
            val avail = (bytes.size - offset).coerceAtLeast(0).coerceAtMost(length)
            if (avail > 0) channel.writeFully(bytes, offset.toInt(), (offset + avail).toInt())
            if (avail < length) throw TusLocalFileException("short read at $offset")
        }
        override suspend fun readSection(offset: Long, length: Long): ByteReadChannel = error("unused")
        override suspend fun fileReadLock(): AutoCloseable = AutoCloseable { }
    }

    @Test
    fun okHttp_shortReadOnFinalPatch_isLocalFileError_andClearsTheMark() = runBlocking {
        RealSocketTusServer().use { server ->
            val store = InMemoryTusUrlStore()
            val file = ShortFile(ByteArray(300 * 1024) { it.toByte() }, claimed = 512 * 1024)
            val error = runCatching {
                withTimeout(30_000) {
                    HttpClient(OkHttp) { followRedirects = false }.use { c ->
                        c.resumeOrCreateAndUploadTus("${server.baseUrl}/files", file, "fp", store,
                            options = TusUploadOptions(chunkSize = 256 * 1024, retryOptions = RetryOptions(
                                maxRetries = 3, initialDelayMillis = 10, maxDelayMillis = 50)))
                    }
                }
            }.exceptionOrNull()
            assertIs<TusLocalFileException>(error)
            assertNull(store.get("fp")?.finalSentAt)
        }
    }
}
