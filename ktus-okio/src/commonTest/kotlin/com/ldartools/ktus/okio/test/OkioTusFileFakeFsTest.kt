package com.ldartools.ktus.okio.test

import com.ldartools.ktus.RetryOptions
import com.ldartools.ktus.TusHttpException
import com.ldartools.ktus.TusProtocolException
import com.ldartools.ktus.TusUploadOptions
import com.ldartools.ktus.createAndUploadTus
import com.ldartools.ktus.okio.OkioTusFile
import com.ldartools.ktus.uploadTus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import io.ktor.utils.io.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

/** R8: positional reads and no leaked file handles, checked with okio's FakeFileSystem. */
class OkioTusFileFakeFsTest {

    private val path = "/uploads/photo.jpg".toPath()

    private fun fs(size: Int): Pair<FakeFileSystem, ByteArray> {
        val fs = FakeFileSystem()
        val bytes = ByteArray(size) { ((it * 13) xor (it ushr 9)).toByte() }
        fs.createDirectories(path.parent!!)
        fs.write(path) { write(bytes) }
        return fs to bytes
    }

    private suspend fun OkioTusFile.section(offset: Long, length: Long): ByteArray {
        val channel = ByteChannel()
        return coroutineScope {
            val reader = async { channel.readRemaining().readByteArray() }
            try {
                writeSection(offset, length, channel)
                channel.flushAndClose()
            } catch (e: Throwable) {
                channel.cancel(e)
                reader.cancel()
                throw e
            }
            reader.await()
        }
    }

    // ---------------------------------------------------------------- positional reads

    @Test
    fun sectionsAtLargeOffsets_matchTheFile() = runTest {
        val (fs, bytes) = fs(6 * 1024 * 1024 + 17)
        val file = OkioTusFile(path, fs)
        for ((offset, length) in listOf(0L to 1000L, 5L * 1024 * 1024 + 3 to 1024L * 1024, bytes.size - 10L to 10L)) {
            val read = file.section(offset, length)
            assertContentEquals(bytes.copyOfRange(offset.toInt(), (offset + length).toInt()), read, "offset $offset")
        }
        fs.checkNoOpenFiles()
    }

    @Test
    fun aSectionIsReadPositionally_neverFromTheStartOfTheFile() = runTest {
        val (fake, bytes) = fs(4 * 1024 * 1024)
        val recording = RecordingFileSystem(fake)
        val file = OkioTusFile(path, recording)
        val offset = 3L * 1024 * 1024
        val read = file.section(offset, 1024)
        assertContentEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + 1024), read)
        assertEquals(0, recording.sequentialOpens, "must not open a sequential source and skip to the offset")
        assertTrue(recording.readOffsets.isNotEmpty())
        assertTrue(recording.readOffsets.all { it >= offset }, "read offsets ${recording.readOffsets} start before $offset")
        fake.checkNoOpenFiles()
    }

    @Test
    fun aShortFile_throwsInsteadOfProducingATruncatedSection() = runTest {
        val (fs, _) = fs(100)
        val file = OkioTusFile(path, fs)
        val error = assertFailsWith<TusProtocolException> { file.section(50, 100) }
        assertTrue(error.message!!.contains("ended early"), error.message)
        fs.checkNoOpenFiles()
    }

    @Test
    fun theSizeComesFromTheGivenFileSystem() {
        val (fs, bytes) = fs(1234)
        assertEquals(bytes.size.toLong(), OkioTusFile(path, fs).size)
        assertEquals("photo.jpg", OkioTusFile(path, fs).name)
    }

    // ---------------------------------------------------------------- no open files after an upload

    /** A tiny tus server; [patchHandler] overrides PATCH when set. */
    private class Server(val length: Long) {
        var data = ByteArray(0)
        var patchHandler: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)? = null

        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Options -> respond("", HttpStatusCode.NoContent, headersOf("Tus-Resumable", "1.0.0"))
                HttpMethod.Post -> respond("", HttpStatusCode.Created, headersOf("Location", "/files/abc"))
                HttpMethod.Head -> respond("", HttpStatusCode.OK, headersOf("Upload-Offset" to listOf(data.size.toString()),
                    "Upload-Length" to listOf(length.toString())))
                HttpMethod.Patch -> patchHandler?.invoke(this, request) ?: run {
                    data += (request.body as OutgoingContent.ReadChannelContent).readFrom().readRemaining().readByteArray()
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", data.size.toString()))
                }
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        })
    }

    private val quick = TusUploadOptions(chunkSize = 256 * 1024,
        retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 5))

    @Test
    fun afterASuccessfulUpload_noFileIsOpen() = runTest {
        val (fs, bytes) = fs(1024 * 1024 + 5)
        val server = Server(bytes.size.toLong())
        server.client.createAndUploadTus("https://example.com/files", OkioTusFile(path, fs), options = quick)
        assertContentEquals(bytes, server.data)
        fs.checkNoOpenFiles()
    }

    @Test
    fun afterAFailedUpload_noFileIsOpen() = runTest {
        val (fs, _) = fs(600 * 1024)
        val server = Server(600 * 1024)
        server.patchHandler = { request ->
            // Read part of the body, then refuse.
            (request.body as OutgoingContent.ReadChannelContent).readFrom().readAvailable(ByteArray(1024))
            respond("forbidden", HttpStatusCode.Forbidden)
        }
        assertFailsWith<TusHttpException> {
            server.client.uploadTus("https://example.com/files/abc", OkioTusFile(path, fs), options = quick)
        }
        awaitNoOpenFiles(fs)
    }

    @Test
    fun afterAnEarlyServerAnswer_theAbandonedBodyClosesTheFile() = runTest {
        // The server answers 409 after reading only a little of a large body, while the client's body
        // writer is blocked. The upload then recovers through HEAD; the first writer must not stay open.
        val (fs, bytes) = fs(8 * 1024 * 1024)
        val server = Server(bytes.size.toLong())
        var first = true
        server.patchHandler = { request ->
            val body = (request.body as OutgoingContent.ReadChannelContent).readFrom()
            if (first) {
                first = false
                body.readAvailable(ByteArray(1024))
                respond("conflict", HttpStatusCode.Conflict)
            } else {
                server.data += body.readRemaining().readByteArray()
                respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", server.data.size.toString()))
            }
        }
        server.client.uploadTus("https://example.com/files/abc", OkioTusFile(path, fs),
            options = quick.copy(chunkSize = 8L * 1024 * 1024))
        assertContentEquals(bytes, server.data)
        awaitNoOpenFiles(fs)
    }

    @Test
    fun afterCancellationMidPatch_noFileIsOpen() = runTest {
        val (fs, _) = fs(8 * 1024 * 1024)
        val server = Server(8L * 1024 * 1024)
        val started = CompletableDeferred<Unit>()
        server.patchHandler = { request ->
            (request.body as OutgoingContent.ReadChannelContent).readFrom().readAvailable(ByteArray(1024))
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) {
            server.client.uploadTus("https://example.com/files/abc", OkioTusFile(path, fs),
                options = quick.copy(chunkSize = 8L * 1024 * 1024))
        }
        withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
        assertEquals(1, fs.openPaths.size, "precondition: the file is open while the PATCH body is written")
        withContext(Dispatchers.Default) { withTimeout(10_000) { job.cancelAndJoin() } }
        awaitNoOpenFiles(fs)
    }

    private suspend fun awaitNoOpenFiles(fs: FakeFileSystem) {
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { while (fs.openPaths.isNotEmpty()) delay(10) }
        }
        fs.checkNoOpenFiles()
    }
}

/** Records how files are opened and which file offsets are read. */
private class RecordingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var sequentialOpens = 0
    val readOffsets = mutableListOf<Long>()

    override fun source(file: Path): okio.Source {
        sequentialOpens++
        return super.source(file)
    }

    override fun openReadOnly(file: Path): FileHandle = RecordingHandle(super.openReadOnly(file), readOffsets)

    private class RecordingHandle(private val delegate: FileHandle, private val offsets: MutableList<Long>) : FileHandle(false) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            offsets += fileOffset
            return delegate.read(fileOffset, array, arrayOffset, byteCount)
        }
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) = error("read-only")
        override fun protectedFlush() = Unit
        override fun protectedResize(size: Long) = error("read-only")
        override fun protectedSize(): Long = delegate.size()
        override fun protectedClose() = delegate.close()
    }
}
