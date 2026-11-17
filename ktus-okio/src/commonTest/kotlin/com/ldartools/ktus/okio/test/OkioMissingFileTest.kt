package com.ldartools.ktus.okio.test

import com.ldartools.ktus.RetryOptions
import com.ldartools.ktus.TusLocalFileException
import com.ldartools.ktus.TusUploadOptions
import com.ldartools.ktus.okio.OkioTusFile
import com.ldartools.ktus.uploadTus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/** L5: a missing or deleted file is a non-retryable TusLocalFileException. */
class OkioMissingFileTest {
    private val path = "/uploads/gone.bin".toPath()

    @Test
    fun theSizeOfAMissingFile_isALocalFileError() {
        val fs = FakeFileSystem()
        assertFailsWith<TusLocalFileException> { OkioTusFile(path, fs).size }
    }

    @Test
    fun readingADeletedFile_isALocalFileError() = runTest {
        val fs = FakeFileSystem()
        assertFailsWith<TusLocalFileException> { OkioTusFile(path, fs).writeSection(0, 10, ByteChannel()) }
    }

    @Test
    fun aFileDeletedMidUpload_failsWithoutRetries() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(path.parent!!)
        fs.write(path) { write(ByteArray(100)) }
        var patches = 0
        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> {
                    fs.delete(path) // deleted after the size was read
                    respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf("0"), "Upload-Length" to listOf("100")))
                }
                HttpMethod.Patch -> {
                    patches++
                    (request.body as OutgoingContent.ReadChannelContent).readFrom().readRemaining()
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "100"))
                }
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        })
        assertFailsWith<TusLocalFileException> {
            client.uploadTus("https://example.com/files/abc", OkioTusFile(path, fs),
                options = TusUploadOptions(retryOptions = RetryOptions(maxRetries = 5, initialDelayMillis = 1)))
        }
        assertTrue(patches <= 1, "retried a deleted file $patches times")
        fs.checkNoOpenFiles()
    }
}
