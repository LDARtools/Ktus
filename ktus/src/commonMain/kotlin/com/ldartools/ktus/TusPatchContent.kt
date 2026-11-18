package com.ldartools.ktus

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    // Written on the engine's thread, read by Ktus after the attempt: a CompletableDeferred is safe across threads.
    private val localFailure = CompletableDeferred<TusLocalFileException>()

    /**
     * The [TusLocalFileException] the body write failed with, if any. It is a local fact (the file is short or
     * unreadable), so it takes precedence over whatever the engine reports for the aborted request: depending
     * on timing, an engine reports the writer's exception, a cancellation, an `IOException`, or even the
     * server's answer to the truncated body.
     */
    fun localFailure(): TusLocalFileException? = if (localFailure.isCompleted) localFailure.getCompleted() else null

    private val writeStarted = CompletableDeferred<Unit>()
    private val writeFinished = CompletableDeferred<Unit>()

    /**
     * Waits until a body write the engine started has finished (written, failed or cancelled by [abandon]), so
     * [localFailure] does not depend on which thread got there first: an engine can return (an exception or a
     * server answer) while the writer, on another thread, is still between its short read and recording it.
     * Returns at once when no write was started or it already finished (the normal case). Call after [abandon];
     * the wait is bounded in case a file read ignores cancellation.
     */
    suspend fun awaitWriteSettled() {
        if (writeStarted.isCompleted && !writeFinished.isCompleted) {
            withTimeoutOrNull(WRITE_SETTLE_MILLIS) { writeFinished.await() }
        }
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        writeStarted.complete(Unit)
        try {
            write(channel)
        } finally {
            writeFinished.complete(Unit)
        }
    }

    private suspend fun write(channel: ByteWriteChannel) {
        coroutineScope {
            val write = async {
                try {
                    file.writeSection(offset, length, channel)
                } catch (e: TusLocalFileException) {
                    localFailure.complete(e)
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

        /** Longest wait for an abandoned body write to finish before classifying the attempt without it. */
        const val WRITE_SETTLE_MILLIS = 5_000L
    }
}
