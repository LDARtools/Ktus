package com.ldartools.ktus

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.io.IOException
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.TimeMark

/** The tus requests Ktus sends; the status policy differs per method. */
internal enum class TusOp(val method: String) {
    OPTIONS("OPTIONS"),
    POST("POST"),
    HEAD("HEAD"),
    PATCH("PATCH"),
    DELETE("DELETE"),
}

/** What to do with the result of one HTTP attempt. */
internal sealed class Outcome {
    class Ok(val response: HttpResponse) : Outcome()

    /**
     * The attempt failed in a way that may succeed later.
     *
     * @property cause the exception that ended the attempt (network error, or a Ktor `ResponseException`
     *   when the client has `expectSuccess = true`); null when the server simply answered with a status.
     * @property timedOut the request timed out or the server/gateway answered 408 or 504, which suggests a
     *   smaller PATCH might get through.
     */
    class Retry(
        val cause: Throwable?,
        val statusCode: Int?,
        val body: String?,
        val retryAfterMillis: Long?,
        val timedOut: Boolean,
    ) : Outcome() {
        fun describe(): String = statusCode?.let { "HTTP $it" } ?: (cause?.toString() ?: "unknown error")
    }

    class Fail(val error: Throwable) : Outcome()
}

/**
 * Exponential backoff with full jitter over one run of consecutive failures.
 *
 * The elapsed-time budget ([RetryOptions.maxElapsedMillis]) starts at the first failure of the run and the
 * delay exponent grows with every failure in it. [reset] ends the run; Ktus calls it whenever the upload
 * makes progress, so a long upload is never cut short by the budget as long as it keeps moving.
 */
internal class Backoff(
    private val options: RetryOptions,
    private val onActivity: (() -> Unit)? = null,
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
) {
    private var failures = 0
    private var runStart: TimeMark? = null

    fun reset() {
        failures = 0
        runStart = null
    }

    /**
     * Records a failure. When another attempt is allowed, waits the backoff delay and returns true.
     * Returns false (without waiting) when the operation has used [RetryOptions.maxRetries] attempts or
     * the elapsed budget is spent.
     *
     * @param attemptsForOperation attempts made so far for the operation that just failed.
     * @param retryAfterMillis the server's `Retry-After`, if any.
     * @param floorMillis the shortest wait allowed, whatever the jitter draws.
     */
    suspend fun awaitRetry(attemptsForOperation: Int, retryAfterMillis: Long?, floorMillis: Long = 0L): Boolean {
        val start = runStart ?: options.timeSource.markNow().also { runStart = it }
        failures++
        if (attemptsForOperation >= options.maxRetries) return false
        val remaining = options.maxElapsedMillis - start.elapsedNow().inWholeMilliseconds
        if (remaining <= 0) return false

        var wait = maxOf(nextDelayMillis(failures), floorMillis, options.minDelayMillis)
        if (retryAfterMillis != null && options.honorRetryAfter) {
            // Honour the server, but never wait longer than max(maxDelayMillis, 60 s) for one retry.
            wait = maxOf(wait, min(retryAfterMillis, maxOf(options.maxDelayMillis, MAX_RETRY_AFTER_MILLIS)))
        }
        sleepWithHeartbeat(min(wait, remaining))
        return true
    }

    /** Waits [millis], reporting activity at the start and at least every [heartbeatMillis]. */
    private suspend fun sleepWithHeartbeat(millis: Long) {
        onActivity?.invoke()
        var left = millis
        while (left > 0) {
            val slice = min(left, heartbeatMillis.coerceAtLeast(1))
            delay(slice)
            left -= slice
            onActivity?.invoke()
        }
    }

    /** Full jitter: a random delay in `[0, min(maxDelay, initialDelay * factor^(failure - 1))]`. */
    internal fun nextDelayMillis(failure: Int): Long {
        val bound = delayBoundMillis(options, failure)
        if (!options.jitter || bound <= 0) return bound
        // nextLong's upper bound is exclusive; bound + 1 would overflow at Long.MAX_VALUE.
        return if (bound == Long.MAX_VALUE) options.random.nextLong(0, bound) else options.random.nextLong(0, bound + 1)
    }
}

/** The longest gap between `onActivity` calls while Ktus waits to retry, when no heartbeat is configured. */
internal const val HEARTBEAT_MILLIS = 5_000L

/** The shortest wait before retrying a 423 Locked answer, which is not counted against maxRetries. */
internal const val LOCKED_MIN_DELAY_MILLIS = 250L

/** The longest single wait a `Retry-After` can impose, unless [RetryOptions.maxDelayMillis] is larger. */
internal const val MAX_RETRY_AFTER_MILLIS = 60_000L

internal fun delayBoundMillis(options: RetryOptions, failure: Int): Long {
    val raw = options.initialDelayMillis * options.factor.pow((failure - 1).coerceAtLeast(0).toDouble())
    return if (raw.isNaN() || raw >= options.maxDelayMillis.toDouble()) options.maxDelayMillis else raw.toLong()
}

/**
 * Runs one request and classifies the result by HTTP status, whether or not the client throws on
 * non-2xx responses (`expectSuccess`).
 */
internal suspend fun attemptTus(
    op: TusOp,
    targetUrl: String,
    options: RetryOptions,
    client: HttpClient? = null,
    onActivity: (() -> Unit)? = null,
    heartbeatMillis: Long? = null,
    send: suspend () -> HttpResponse,
): Outcome {
    // Callbacks run outside the try below: whatever they throw reaches the caller as it is.
    onActivity?.invoke()
    val outcome = try {
        val response = withHeartbeat(onActivity, heartbeatMillis) { send() }
        classifyResponse(op, targetUrl, response, options, cause = null)
    } catch (e: CallbackFailure) {
        throw e
    } catch (e: CancellationException) {
        // Only a cancelled caller is a cancellation. An engine can also throw CancellationException when
        // its own call or connection is torn down; for an active caller that is a network failure.
        currentCoroutineContext().ensureActive()
        client?.closedError(e)?.let { return Outcome.Fail(it) }
        Outcome.Retry(
            cause = IOException("The HTTP engine cancelled the ${op.method} request", e),
            statusCode = null, body = null, retryAfterMillis = null, timedOut = false,
        )
    } catch (e: ResponseException) {
        // expectSuccess = true: Ktor threw instead of returning the response. Classify the same way.
        classifyResponse(op, targetUrl, e.response, options, cause = e)
    } catch (e: Throwable) {
        client?.closedError(e)?.let { return Outcome.Fail(it) }
        classifyThrowable(e)
    }
    onActivity?.invoke()
    return outcome
}

/**
 * Runs [block] (one request), calling [onActivity] every [heartbeatMillis] from a side job while it is in
 * flight. The ticker is a child of the request's scope and is cancelled with it; an exception from
 * [onActivity] cancels the request and is rethrown.
 */
private suspend fun <T> withHeartbeat(onActivity: (() -> Unit)?, heartbeatMillis: Long?, block: suspend () -> T): T {
    if (onActivity == null || heartbeatMillis == null || heartbeatMillis <= 0) return block()
    return coroutineScope {
        val ticker = launch {
            while (true) {
                delay(heartbeatMillis)
                onActivity() // a CallbackFailure here fails this scope, cancelling the request
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }
}

/** A closed or recycled client fails every request; retrying cannot help. */
private fun HttpClient.closedError(cause: Throwable): Throwable? =
    if (isActive && engine.isActive) {
        null
    } else {
        IllegalStateException("The HttpClient (or its engine) is closed; tus requests cannot be sent with it", cause)
    }

internal fun classifyThrowable(e: Throwable): Outcome {
    // Our own protocol errors (for example a short read while writing a PATCH body) can come back
    // wrapped by the engine. They are never retried.
    e.findCause<TusProtocolException>()?.let { return Outcome.Fail(it) }
    if (e.findCause<IOException>() != null) {
        return Outcome.Retry(cause = e, statusCode = null, body = null, retryAfterMillis = null, timedOut = e.isTimeout())
    }
    return Outcome.Fail(e)
}

private suspend fun classifyResponse(
    op: TusOp,
    targetUrl: String,
    response: HttpResponse,
    options: RetryOptions,
    cause: ResponseException?,
): Outcome {
    val status = response.status.value

    if (status in 300..399) {
        return Outcome.Fail(TusProtocolException(
            "tus ${op.method} to $targetUrl was answered with redirect $status " +
                "(${response.headers[HttpHeaders.Location] ?: "no Location"}); tus requests must not be redirected"
        ))
    }
    // The client followed a redirect on its own (HttpClient followRedirects = true): the answer came
    // from somewhere else than the upload URL.
    val answeredUrl = response.call.request.url
    val target = try { Url(targetUrl) } catch (e: Exception) { null }
    if (target != null && !sameOrigin(answeredUrl, target)) {
        return Outcome.Fail(TusProtocolException(
            "tus ${op.method} to $targetUrl was answered by $answeredUrl; the client followed a redirect to " +
                "another origin. Configure the HttpClient with followRedirects = false."
        ))
    }

    if (status in 200..299) {
        return when (op) {
            // Any 2xx other than 204 leaves the PATCH outcome unknown; the caller checks with HEAD.
            TusOp.PATCH -> if (status == 204) Outcome.Ok(response) else retry(response, cause, options, timedOut = false)
            else -> Outcome.Ok(response)
        }
    }

    if (status == 404 || status == 410) {
        return when (op) {
            TusOp.HEAD, TusOp.PATCH -> Outcome.Fail(TusUploadExpiredException(
                "Upload expired or invalid (HTTP $status from ${op.method} $targetUrl)."))
            TusOp.DELETE -> Outcome.Ok(response)
            TusOp.POST, TusOp.OPTIONS -> Outcome.Fail(httpException(op, response, cause))
        }
    }

    if (status == 409 && op == TusOp.PATCH) {
        // Offset mismatch: the caller re-reads the offset with HEAD.
        return retry(response, cause, options, timedOut = false)
    }
    if (status == 401 && options.retryOnUnauthorized) {
        // Lets the request block supply a refreshed token on the next attempt.
        return retry(response, cause, options, timedOut = false)
    }
    if (status in options.retryableStatusCodes) {
        return retry(response, cause, options, timedOut = status == 408 || status == 504)
    }

    // Anything else is fatal. When Ktor already threw (expectSuccess = true), keep its exception so
    // callers that caught ClientRequestException in 1.0.x keep working.
    return Outcome.Fail(cause ?: httpException(op, response, null))
}

private suspend fun retry(response: HttpResponse, cause: ResponseException?, options: RetryOptions, timedOut: Boolean) =
    Outcome.Retry(
        cause = cause,
        statusCode = response.status.value,
        body = null,
        retryAfterMillis = if (options.honorRetryAfter) parseRetryAfterMillis(response) else null,
        timedOut = timedOut,
    )

internal suspend fun httpException(op: TusOp, response: HttpResponse, cause: Throwable?): TusHttpException {
    val body = readBodySnippet(op, response)
    return TusHttpException(
        statusCode = response.status.value,
        method = op.method,
        body = body,
        message = "tus ${op.method} ${response.call.request.url} failed with HTTP ${response.status}" +
            (if (body.isNullOrBlank()) "" else ": $body"),
        cause = cause,
    )
}

private suspend fun readBodySnippet(op: TusOp, response: HttpResponse): String? {
    if (op == TusOp.HEAD) return null
    return try {
        response.bodyAsText().take(512)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads `Retry-After` as delta-seconds or an HTTP-date. A date is measured against the response's own
 * `Date` header when present (immune to client clock skew), otherwise against the local clock.
 */
internal fun parseRetryAfterMillis(response: HttpResponse): Long? {
    val value = response.headers[HttpHeaders.RetryAfter]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all { it.isDigit() }) {
        // Delta-seconds, saturating instead of overflowing.
        val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }
    val until = parseHttpDate(value) ?: return null
    val now = response.headers[HttpHeaders.Date]?.let { parseHttpDate(it) } ?: GMTDate().timestamp
    return (until - now).coerceAtLeast(0)
}

/** Parses an RFC 7231 HTTP-date to epoch milliseconds, or null. */
internal fun parseHttpDate(value: String): Long? = try {
    value.trim().fromHttpToGmtDate().timestamp
} catch (e: Exception) {
    null
}

private fun Throwable.isTimeout(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        // A connect timeout says nothing about the chunk size.
        if (current is ConnectTimeoutException) return false
        if (current is HttpRequestTimeoutException || current is SocketTimeoutException) return true
        // Fallback for platform timeouts Ktor does not wrap (for example java.net.SocketTimeoutException
        // or a Darwin timeout): an IOException whose class name ends in "TimeoutException".
        if (current is IOException && current::class.simpleName?.endsWith("TimeoutException") == true) return true
        current = current.cause.takeIf { it !== current }
    }
    return false
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 16) {
        if (current is T) return current
        current = current.cause.takeIf { it !== current }
        depth++
    }
    return null
}

/**
 * Runs one tus operation (OPTIONS, POST, HEAD or DELETE) with retries, and returns [onSuccess]'s result.
 * The [backoff] is shared with the caller so the elapsed budget spans everything in a run of failures.
 */
internal suspend fun <T> retryTusOperation(
    op: TusOp,
    targetUrl: String,
    options: RetryOptions,
    backoff: Backoff,
    client: HttpClient? = null,
    onActivity: (() -> Unit)? = null,
    heartbeatMillis: Long? = null,
    send: suspend () -> HttpResponse,
    onSuccess: suspend (HttpResponse) -> T,
): T {
    var attempts = 0
    while (true) {
        attempts++
        when (val outcome = attemptTus(op, targetUrl, options, client, onActivity, heartbeatMillis, send)) {
            is Outcome.Ok -> return onSuccess(outcome.response)
            is Outcome.Fail -> throw outcome.error
            is Outcome.Retry -> {
                // 423 Locked (tusdotnet's per-file lock) is bounded by the elapsed budget only.
                val locked = outcome.statusCode == 423
                if (locked) attempts--
                if (!backoff.awaitRetry(attempts, outcome.retryAfterMillis, if (locked) LOCKED_MIN_DELAY_MILLIS else 0L)) {
                    throw retriesExhausted(op, targetUrl, outcome, attempts)
                }
            }
        }
    }
}

/**
 * The error for an operation whose retries ran out: a network error stays a network error; a status
 * becomes a [TusHttpException] (keeping Ktor's exception as the cause when the client threw one).
 */
internal fun retriesExhausted(op: TusOp, targetUrl: String, outcome: Outcome.Retry, attempts: Int): Throwable {
    val cause = outcome.cause
    if (cause != null && cause !is ResponseException) return cause
    return TusHttpException(
        statusCode = outcome.statusCode ?: 0,
        method = op.method,
        body = outcome.body,
        message = "tus ${op.method} $targetUrl still failing after $attempts attempts: ${outcome.describe()}",
        cause = cause,
    )
}

/**
 * Retries [block] on network errors, 5xx and 401, with the backoff described by [options].
 *
 * Kept for the exception-driven callers and tests of 1.0.x; the upload itself classifies responses by
 * status through [attemptTus]. A Ktor 404/410 `ClientRequestException` becomes [TusUploadExpiredException].
 */
internal suspend fun <T> retryWithBackoff(
    options: RetryOptions,
    block: suspend () -> T
): T {
    val backoff = Backoff(options)
    var attempts = 0
    while (true) {
        attempts++
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            val status = e.response.status.value
            when {
                status == 404 || status == 410 -> throw TusUploadExpiredException()
                status == 401 && options.retryOnUnauthorized -> Unit
                status in 500..599 || status in options.retryableStatusCodes -> Unit
                else -> throw e
            }
            if (!backoff.awaitRetry(attempts, parseRetryAfterMillis(e.response))) throw e
        } catch (e: TusProtocolException) {
            throw e
        } catch (e: IOException) {
            if (!backoff.awaitRetry(attempts, null)) throw e
        }
    }
}
