package com.ldartools.ktus

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
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
    repeat(options.maxRetries - 1) {
        try {
            return block()
        } catch (e: ClientRequestException) {
            // 4xx errors: fail fast except for expired uploads
            val statusCode = e.response.status.value
            if (statusCode == HttpStatusCode.NotFound.value || statusCode == HttpStatusCode.Gone.value) {
                throw TusUploadExpiredException()
            }
            throw e
        } catch (e: ServerResponseException) {
            // 5xx server errors are transient — allow retrying.
        } catch (e: TusProtocolException) {
            // Protocol errors (expired uploads, missing headers) are not transient — fail immediately.
            throw e
        } catch (e: IOException) {
            // General network errors, allow retrying.
        }
        delay(currentDelay)
        currentDelay = (currentDelay * options.factor).toLong().coerceAtMost(options.maxDelayMillis)
    }
    return block() // Last attempt
}