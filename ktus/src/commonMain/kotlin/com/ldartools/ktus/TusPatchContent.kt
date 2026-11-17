package com.ldartools.ktus

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The body of one PATCH request: [length] bytes of [file] starting at [offset].
 *
 * The file is read inside [writeTo], which the engine calls in the request's coroutine while it sends the
 * body. Cancelling or failing the request therefore cancels the read too. Each attempt creates a new
 * instance, so a retried PATCH always re-reads the file from [offset].
 *
 * A server may answer before it has read the whole body (tusdotnet answers 409 or 423 straight away). The
 * engine then hands Ktus the response while the body writer is still blocked on a socket nobody reads, and
 * nothing would ever cancel it. Ktus calls [abandon] once the attempt is over, which stops the write and
 * lets [ITusFile.writeSection] close the file.
 */
internal class TusPatchContent(
    private val file: ITusFile,
    private val offset: Long,
    private val length: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentLength: Long = length
    override val contentType: ContentType = OFFSET_OCTET_STREAM

    private val abandoned = Job()
    private val localError = CompletableDeferred<TusLocalFileException>()

    /** A [TusLocalFileException] the file raised while this body was written, whatever the engine reported. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun localFailure(): TusLocalFileException? = if (localError.isCompleted) localError.getCompleted() else null

    override suspend fun writeTo(channel: ByteWriteChannel) {
        coroutineScope {
            val write = async {
                try {
                    file.writeSection(offset, length, channel)
                } catch (e: TusLocalFileException) {
                    localError.complete(e)
                    throw e
                }
            }
            val watcher = launch {
                abandoned.join()
                write.cancel()
            }
            try {
                write.await()
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Stops the body write if it is still running; harmless once it has finished. */
    fun abandon() {
        abandoned.complete()
    }

    companion object {
        val OFFSET_OCTET_STREAM: ContentType = ContentType.parse("application/offset+octet-stream")
    }
}
