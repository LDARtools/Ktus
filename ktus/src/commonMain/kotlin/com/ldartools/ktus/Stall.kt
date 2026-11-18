package com.ldartools.ktus

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpSendPipeline
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A request was abandoned by one of Ktus's own silence limits ([TusUploadOptions.sendStallMillis],
 * [TusUploadOptions.replyTimeoutMillis], [TusUploadOptions.finalReplyTimeoutMillis]).
 *
 * It is a network failure like an engine timeout: the request is retried within the retry budget, and an
 * abandoned final PATCH leaves its outcome unknown exactly as a timeout does. Only that request is abandoned, not the upload, and only on the client: bytes already handed to the
 * operating system can still reach the server, which may then apply the abandoned PATCH after all. The
 * offset checks keep the data correct (a later PATCH at the old offset is answered 409, and Ktus asks with
 * HEAD before continuing).
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
 * Its clocks start at the hand-off to the engine ([arm]), after the caller's request block (which may, for
 * example, refresh a token) and after the client's own plugins: a send-pipeline interceptor installed by
 * [ensureStallArming] arms the watch in [HttpSendPipeline.Monitoring], the phase right before
 * [HttpSendPipeline.Engine], where every Ktor engine executes the request. From there the send clock covers a
 * stalled connect or TLS handshake and restarts on every body progress event. Once the bytes handed to the
 * engine reach [bodyLength] the request is fully sent and the reply clock starts. Thread-safe: progress comes
 * from the engine's thread.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class StallWatch(
    private val op: TusOp,
    private val bodyLength: Long,
    private val sendStallMillis: Long?,
    private val replyMillis: Long?,
    private val replyKind: TusStallTimeoutException.Kind,
    private val timeSource: TimeSource,
) {
    private val armedAt = AtomicReference<TimeMark?>(null)
    private val armed = CompletableDeferred<Unit>()

    /** The request reached the engine: start the clocks. Only the first call counts (a plugin may re-send). */
    fun arm() {
        armedAt.compareAndSet(null, timeSource.markNow())
        armed.complete(Unit)
    }

    private fun now(): Long = armedAt.load()?.elapsedNow()?.inWholeMilliseconds ?: 0L

    /** Time (ms since [arm]) of the last body progress. */
    private val lastProgress = AtomicLong(0L)

    /** Time (ms since [arm]) the request became fully sent, or -1. A body-less request is sent at once. */
    private val fullySentAt = AtomicLong(if (bodyLength > 0) -1L else 0L)

    private val fired = AtomicReference<TusStallTimeoutException?>(null)

    /** Body progress: [bytesSent] of [bodyLength] bytes have been handed to the engine. */
    fun progress(bytesSent: Long) {
        arm() // progress means the engine has the request
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
            armed.await() // nothing is timed before the hand-off to the engine
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

/** Carries the watch of the current attempt to the request builder ([markRequestTarget]). */
internal class StallWatchElement(val watch: StallWatch) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StallWatchElement>
}

/** The watch of a request, read by the send-pipeline interceptor that arms it. */
internal val StallWatchKey = AttributeKey<StallWatch>("KtusStallWatch")

private val StallArmingInstalledKey = AttributeKey<Unit>("KtusStallArming")

/**
 * Installs, once per client, a send-pipeline interceptor that arms a request's [StallWatch] at
 * [HttpSendPipeline.Monitoring], right before the engine executes it. Requests without a watch pass through.
 */
internal fun HttpClient.ensureStallArming() {
    attributes.computeIfAbsent(StallArmingInstalledKey) {
        sendPipeline.intercept(HttpSendPipeline.Monitoring) {
            context.attributes.getOrNull(StallWatchKey)?.arm()
        }
    }
}
