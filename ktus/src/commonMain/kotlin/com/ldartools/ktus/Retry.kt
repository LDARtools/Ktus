package com.ldartools.ktus

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.io.IOException

/**
 * Mobile networks are bursty. Immediate retries often fail. We implement a standard exponential backoff using Kotlin Coroutines delay.
 */
internal suspend fun <T> retryWithBackoff(
    options: RetryOptions,
    block: suspend () -> T
): T {
    var currentDelay = options.initialDelayMillis
    repeat(options.times - 1) {
        try {
            return block()
        } catch (e: ClientRequestException) {
            // Immediately fail on 4xx errors, except for specific ones we might handle elsewhere if needed
            val statusCode = e.response.status.value
            if (statusCode in 400..499) {
                // Special case for expired uploads, which is a recoverable client error
                if (statusCode == HttpStatusCode.NotFound.value || statusCode == HttpStatusCode.Gone.value) {
                    throw TusUploadExpiredException()
                }
                // For other 4xx errors, fail fast as they are not typically retry-able
                throw e
            }
            // For 5xx server errors, we allow retrying.
        } catch (e: IOException) {
            // General network errors, allow retrying.
        }
        delay(currentDelay)
        currentDelay = (currentDelay * options.factor).toLong().coerceAtMost(options.maxDelayMillis)
    }
    return block() // Last attempt
}