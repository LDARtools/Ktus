package com.ldartools.ktus

import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * How Ktus retries failed tus requests.
 *
 * A request is retried when it fails with a network error (including timeouts) or when the server answers
 * with one of [retryableStatusCodes] (or 401 when [retryOnUnauthorized] is set, so the request `block` can
 * refresh a token). Between attempts Ktus waits an exponentially growing, randomised ("full jitter") delay:
 * a random value in `[0, min(maxDelayMillis, initialDelayMillis * factor^n)]`, where `n` is the number of
 * failures so far. A `Retry-After` header on a retryable response is honoured when it asks for a longer wait.
 *
 * Retrying stops at whichever comes first:
 * - [maxElapsedMillis] — the primary cap, measured over a run of consecutive failures: the run starts at the
 *   first failure, and any request that succeeds at its job ends it (OPTIONS answered, POST 201, the first HEAD
 *   answered, a PATCH 204, a recovery HEAD showing more data). A recovery HEAD that shows no new data does not
 *   end it. When `resumeOrCreateAndUploadTus` drops a stored upload after a definitive answer and creates a new
 *   one, the run ends too. Once the budget is spent no request is sent, except one last HEAD when giving up
 *   retrying a PATCH while a final PATCH is unresolved.
 * - [maxRetries] — the maximum number of **attempts** for one operation (one OPTIONS, POST, HEAD or
 *   DELETE, or the PATCHes for one chunk), including the first attempt. Despite its name it is not
 *   "retries after the first try": `maxRetries = 3` means at most 3 attempts. 423 Locked answers are not
 *   counted: tusdotnet's per-file lock is bounded by [maxElapsedMillis] alone.
 *
 * @param maxRetries maximum attempts per operation, including the first one (default 10). Values below 1 mean
 *   one attempt, as in 1.0.x.
 * @param initialDelayMillis upper bound of the first backoff delay in milliseconds (default 500). A value above
 *   [maxDelayMillis] is capped at [maxDelayMillis].
 * @param maxDelayMillis upper bound of any single computed backoff delay in milliseconds (default 30000).
 *   A server `Retry-After` may ask for longer; one wait is capped at max([maxDelayMillis], 60 s). If the
 *   remaining [maxElapsedMillis] is shorter than the wait a retry requires (an honoured `Retry-After`,
 *   [minDelayMillis], the 423 floor), the call gives up with the last failure instead of retrying early.
 * @param factor multiplier applied to the delay bound after each failure (default 2.0); must not be negative.
 * @param maxElapsedMillis total time budget for a run of consecutive failures (default 10 minutes).
 * @param retryableStatusCodes HTTP statuses that are retried (default 408, 423, 429, 500, 502, 503, 504).
 * @param retryOnUnauthorized retry 401 responses so the request block can supply a refreshed token (default true).
 * @param honorRetryAfter wait at least as long as a `Retry-After` header asks (default true).
 * @param jitter randomise each delay within its bound (default true). Turn off only for deterministic tests.
 * @param random random source for the jitter. Replace only in tests.
 * @param minDelayMillis the shortest wait before retrying a PATCH the origin may still be processing (default 0):
 *   one that timed out (request or socket timeout), was answered 502/503/504 or 423, or failed with any other
 *   network error that does not prove the request never reached the origin. It does not apply to failures
 *   that prove that: connection refused or unreachable, DNS failure, no network, connect timeout, TLS
 *   handshake failure (java.net `ConnectException`, `UnknownHostException`, `NoRouteToHostException`,
 *   `SSLHandshakeException`, Ktor's `ConnectTimeoutException`, and on Darwin the NSURLErrorDomain codes -1003,
 *   -1004, -1006, -1022 and -1201 to -1206). Nor does it apply to HEAD, OPTIONS, POST and DELETE retries.
 *   It is applied after the jitter. If the remaining
 *   [maxElapsedMillis] is shorter, the call gives up rather than retrying early; when set it must be
 *   below [maxElapsedMillis]. Use it to make a retry after a gateway timeout wait longer than the origin's own
 *   request timeout; with full jitter, raising [initialDelayMillis] alone does not guarantee that.
 * @param timeSource monotonic clock for [maxElapsedMillis]. Replace only in tests (for example with
 *   `TestCoroutineScheduler.timeSource`).
 */
data class RetryOptions(
    val maxRetries: Int = 10,
    val initialDelayMillis: Long = 500L,
    val maxDelayMillis: Long = 30_000L,
    val factor: Double = 2.0,
    val maxElapsedMillis: Long = 10 * 60 * 1000L,
    val retryableStatusCodes: Set<Int> = DEFAULT_RETRYABLE_STATUS_CODES,
    val retryOnUnauthorized: Boolean = true,
    val honorRetryAfter: Boolean = true,
    val jitter: Boolean = true,
    val random: Random = Random.Default,
    val timeSource: TimeSource = TimeSource.Monotonic,
    val minDelayMillis: Long = 0L,
) {
    init {
        // maxRetries below 1 means one attempt, as in 1.0.x.
        require(initialDelayMillis >= 0) { "initialDelayMillis must not be negative" }
        require(maxDelayMillis >= 0) { "maxDelayMillis must not be negative" }
        require(factor >= 0.0) { "factor must not be negative or NaN" }
        require(maxElapsedMillis >= 0) { "maxElapsedMillis must not be negative" }
        require(minDelayMillis >= 0) { "minDelayMillis must not be negative" }
        require(minDelayMillis == 0L || minDelayMillis < maxElapsedMillis) {
            "minDelayMillis ($minDelayMillis) must be below maxElapsedMillis ($maxElapsedMillis): otherwise no retry could ever be sent"
        }
    }

    /** Binary-compatibility constructor for callers compiled against Ktus 1.0.x. */
    @Deprecated("Kept for binary compatibility with Ktus 1.0.x", level = DeprecationLevel.HIDDEN)
    constructor(
        maxRetries: Int = 10,
        initialDelayMillis: Long = 500L,
        maxDelayMillis: Long = 30_000L,
        factor: Double = 2.0,
    ) : this(maxRetries, initialDelayMillis, maxDelayMillis, factor, maxElapsedMillis = 10 * 60 * 1000L)

    /** Binary-compatibility `copy` for callers compiled against Ktus 1.0.x. */
    @Deprecated("Kept for binary compatibility with Ktus 1.0.x", level = DeprecationLevel.HIDDEN)
    fun copy(
        maxRetries: Int = this.maxRetries,
        initialDelayMillis: Long = this.initialDelayMillis,
        maxDelayMillis: Long = this.maxDelayMillis,
        factor: Double = this.factor,
    ): RetryOptions = copy(maxRetries = maxRetries, initialDelayMillis = initialDelayMillis,
        maxDelayMillis = maxDelayMillis, factor = factor, maxElapsedMillis = maxElapsedMillis)

    companion object {
        /** 408, 423 (tusdotnet's per-file lock), 429, 500, 502, 503, 504. */
        val DEFAULT_RETRYABLE_STATUS_CODES: Set<Int> = setOf(408, 423, 429, 500, 502, 503, 504)
    }
}
