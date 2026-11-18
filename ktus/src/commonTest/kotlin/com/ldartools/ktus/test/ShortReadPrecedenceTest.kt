package com.ldartools.ktus.test

import com.ldartools.ktus.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * A short read in the final PATCH's body is a local fact. Depending on timing, a real engine reports it as the
 * writer's own exception, as a cancellation, as an IOException ("canceled due to ..."), or hands Ktus the
 * server's answer to the truncated body. Every order must be classified the same way: TusLocalFileException,
 * and (nothing earlier uncertain) the mark cleared. These tests force each losing order deterministically.
 */
class ShortReadPrecedenceTest {
    private val createUrl = "https://example.com/files"
    private val url = "https://example.com/files/abc"
    private val claimed = 100L

    private class ShortFile(private val bytes: ByteArray, private val claimed: Long) : ITusFile {
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

    /** Runs the PATCH body to completion (or failure) like an engine would; returns the writer's failure. */
    private suspend fun drain(body: OutgoingContent): Throwable? = runCatching {
        when (body) {
            // Ktus's onUpload listener wraps the body in Ktor's ObservableContent (a ReadChannelContent).
            is OutgoingContent.ReadChannelContent -> body.readFrom().discard()
            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel()
                coroutineScope {
                    launch { channel.discard() }
                    try {
                        body.writeTo(channel)
                    } finally {
                        channel.flushAndClose()
                    }
                }
            }
            else -> error("unexpected body ${body::class}")
        }
        Unit
    }.exceptionOrNull()?.let { generateSequence(it) { e -> e.cause }.firstOrNull { e -> e is TusLocalFileException } ?: it }

    /** A resume of a stored, unmarked entry whose single PATCH (the final one) short-reads. */
    private suspend fun run(
        afterShortRead: suspend MockRequestHandleScope.(writerFailure: Throwable?) -> HttpResponseData,
    ): Pair<Throwable?, InMemoryTusUrlStore> {
        val engine = MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond("", HttpStatusCode.OK, headersOf(
                    "Upload-Offset" to listOf("0"), "Upload-Length" to listOf("$claimed"), "Tus-Resumable" to listOf("1.0.0")))
                HttpMethod.Patch -> afterShortRead(drain(request.body))
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        }
        val store = InMemoryTusUrlStore()
        store.set("fp", TusUpload(url, claimed))
        val error = runCatching {
            HttpClient(engine) { followRedirects = false }.use { client ->
                client.resumeOrCreateAndUploadTus(createUrl, ShortFile(ByteArray(40) { it.toByte() }, claimed), "fp", store,
                    options = TusUploadOptions(activityHeartbeatMillis = null,
                        retryOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 1, maxDelayMillis = 1)))
            }
        }.exceptionOrNull()
        return error to store
    }

    private suspend fun assertLocalFileErrorAndNoMark(result: Pair<Throwable?, InMemoryTusUrlStore>) {
        val (error, store) = result
        assertIs<TusLocalFileException>(error, "$error")
        val entry = store.get("fp")
        assertNull(entry?.finalSentAt, "the mark must be cleared: $entry")
    }

    @Test
    fun engineReportsTheWritersOwnException() = runTest {
        assertLocalFileErrorAndNoMark(run { failure -> throw assertNotNull(failure) })
    }

    @Test
    fun engineReportsAnIOException_afterTheWriterFailed() = runTest {
        assertLocalFileErrorAndNoMark(run { failure ->
            assertIs<TusLocalFileException>(failure)
            throw kotlinx.io.IOException("canceled due to $failure")
        })
    }

    @Test
    fun engineReportsACancellation_afterTheWriterFailed() = runTest {
        assertLocalFileErrorAndNoMark(run { failure ->
            assertIs<TusLocalFileException>(failure)
            throw kotlinx.coroutines.CancellationException("call cancelled")
        })
    }

    @Test
    fun serverAnswersTheTruncatedBody_409() = runTest {
        assertLocalFileErrorAndNoMark(run { failure ->
            assertIs<TusLocalFileException>(failure)
            respond("", HttpStatusCode.Conflict)
        })
    }

    @Test
    fun serverAnswersTheTruncatedBody_503() = runTest {
        assertLocalFileErrorAndNoMark(run { failure ->
            assertIs<TusLocalFileException>(failure)
            respond("", HttpStatusCode.ServiceUnavailable)
        })
    }

    /** A success-like answer to a body we know was short: what the server kept is unknown, the mark stays. */
    @Test
    fun serverAnswers204ToTheTruncatedBody_isAnUnknownOutcome() = runTest {
        val (error, store) = run { failure ->
            assertIs<TusLocalFileException>(failure)
            respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "$claimed"))
        }
        val unknown = assertIs<TusUploadOutcomeUnknownException>(error, "$error")
        assertIs<TusLocalFileException>(unknown.cause)
        assertNotNull(store.get("fp")?.finalSentAt, "the mark stays")
    }
}
