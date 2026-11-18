package com.ldartools.ktus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * A request was abandoned by one of Ktus's own silence limits ([TusUploadOptions.sendStallMillis],
 * [TusUploadOptions.replyTimeoutMillis], [TusUploadOptions.finalReplyTimeoutMillis]).
 *
 * It is a network failure like an engine timeout: the request is retried within the retry budget, and an
 * abandoned final PATCH leaves its outcome unknown exactly as a timeout does. Only that request is abandoned,
 * never the upload.
 *
 * @property kind which limit was reached.
 * @property limitMillis the limit that was reached, in milliseconds.
 */
class TusStallTimeoutException(
    val kind: Kind,
    val limitMillis: Long,
    message: String,
) : IOException(message) {
    /** Which silence limit abandoned the request. */
    enum class Kind {
        /** No body progress for [TusUploadOptions.sendStallMillis] while the request was not fully sent. */
        SEND_STALL,

        /** No response within [TusUploadOptions.replyTimeoutMillis] after the request was fully sent. */
        REPLY_TIMEOUT,

        /** No response to the final PATCH within [TusUploadOptions.finalReplyTimeoutMillis] after it was fully sent. */
        FINAL_REPLY_TIMEOUT,
    }
}

/** The silence limits of one call, or null from [TusUploadOptions.stallLimits] when all are off. */
internal class StallLimits(
    val sendStallMillis: Long?,
    val replyTimeoutMillis: Long?,
    val finalReplyTimeoutMillis: Long?,
    val timeSource: TimeSource,
) {
    /** A watch for one attempt of a request whose body is [bodyLength] bytes (0: no body). */
    fun watch(op: TusOp, bodyLength: Long, isFinal: Boolean): StallWatch? {
        val reply = if (isFinal) finalReplyTimeoutMillis else replyTimeoutMillis
        val replyKind = if (isFinal) TusStallTimeoutException.Kind.FINAL_REPLY_TIMEOUT else TusStallTimeoutException.Kind.REPLY_TIMEOUT
        // Without a body there is nothing to stall while sending: the request is fully sent once the engine has it.
        val send = if (bodyLength > 0) sendStallMillis else null
        if (send == null && reply == null) return null
        return StallWatch(op, bodyLength, send, reply, replyKind, timeSource)
    }
}

internal fun TusUploadOptions.stallLimits(): StallLimits? =
    if (sendStallMillis == null && replyTimeoutMillis == null && finalReplyTimeoutMillis == null) null
    else StallLimits(sendStallMillis, replyTimeoutMillis, finalReplyTimeoutMillis, retryOptions.timeSource)

/**
 * The silence watchdog of one request attempt.
 *
 * The send clock starts when the request is handed to the engine (so a stalled connect or TLS handshake is
 * covered) and restarts on every body progress event. Once the bytes handed to the engine reach [bodyLength]
 * the request is fully sent and the reply clock starts. Thread-safe: progress comes from the engine's thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class StallWatch(
    private val op: TusOp,
    private val bodyLength: Long,
    private val sendStallMillis: Long?,
    private val replyMillis: Long?,
    private val replyKind: TusStallTimeoutException.Kind,
    timeSource: TimeSource,
) {
    private val start = timeSource.markNow()
    private fun now(): Long = start.elapsedNow().inWholeMilliseconds

    /** Time (ms since [start]) of the last body progress. */
    private val lastProgress = AtomicLong(0L)

    /** Time (ms since [start]) the request became fully sent, or -1. A body-less request is sent at once. */
    private val fullySentAt = AtomicLong(if (bodyLength > 0) -1L else 0L)

    private val fired = AtomicReference<TusStallTimeoutException?>(null)

    /** Body progress: [bytesSent] of [bodyLength] bytes have been handed to the engine. */
    fun progress(bytesSent: Long) {
        val t = now()
        lastProgress.store(t)
        if (bytesSent >= bodyLength) fullySentAt.compareAndSet(-1L, t)
    }

    /** Milliseconds until the current limit is reached (<= 0: reached), or null when no limit applies now. */
    private fun remaining(): Pair<Long, TusStallTimeoutException>? {
        val sentAt = fullySentAt.load()
        val t = now()
        return if (sentAt < 0) {
            val limit = sendStallMillis ?: return null // no send limit: wait for the reply clock
            (lastProgress.load() + limit - t) to TusStallTimeoutException(TusStallTimeoutException.Kind.SEND_STALL, limit,
                "No ${op.method} body progress for $limit ms; the request was abandoned")
        } else {
            val limit = replyMillis ?: return null
            (sentAt + limit - t) to TusStallTimeoutException(replyKind, limit,
                "No answer to the ${op.method} request within $limit ms after it was sent; the request was abandoned")
        }
    }

    /**
     * Runs [block] (one request) and abandons it when a limit is reached, throwing [TusStallTimeoutException].
     * A cancellation of the caller is rethrown as it is.
     */
    suspend fun <T> guard(block: suspend () -> T): T = coroutineScope {
        val request = async { block() }
        val dog = launch {
            while (true) {
                val next = remaining()
                if (next == null) {
                    // No limit applies yet (send limit off and not fully sent): check again shortly.
                    delay(POLL_MILLIS)
                    continue
                }
                val (left, error) = next
                if (left <= 0) {
                    fired.store(error)
                    request.cancel(CancellationException(error.message, error))
                    return@launch
                }
                delay(minOf(left, POLL_MILLIS))
            }
        }
        try {
            request.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller was cancelled: that wins
            throw fired.load() ?: e
        } finally {
            dog.cancel()
        }
    }

    private companion object {
        /** Re-check interval, so a limit reached while waiting is noticed within this time. */
        const val POLL_MILLIS = 250L
    }
}
