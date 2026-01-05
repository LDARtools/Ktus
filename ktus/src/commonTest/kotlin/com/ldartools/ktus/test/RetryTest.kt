package com.ldartools.ktus.test

import com.ldartools.ktus.RetryOptions
import com.ldartools.ktus.TusUploadExpiredException
import com.ldartools.ktus.retryWithBackoff
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    private val defaultOptions = RetryOptions(maxRetries = 3, initialDelayMillis = 100, maxDelayMillis = 1000, factor = 2.0)

    @Test
    fun `returns value on first try`() = runTest {
        val result = retryWithBackoff(defaultOptions) {
            "success"
        }
        assertEquals("success", result)
    }

    @Test
    fun `succeeds after one IOException`() = runTest {
        var attempts = 0
        val result = retryWithBackoff(defaultOptions) {
            attempts++
            if (attempts < 2) throw IOException("Network error")
            "success"
        }
        assertEquals("success", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `fails after max retries with IOException`() = runTest {
        var attempts = 0
        assertFailsWith<IOException> {
            retryWithBackoff(defaultOptions) {
                attempts++
                throw IOException("Network error")
            }
        }
        assertEquals(defaultOptions.maxRetries, attempts)
    }

    @Test
    fun `succeeds after a 5xx error`() = runTest {
        var attempts = 0
        val result = retryWithBackoff(defaultOptions) {
            attempts++
            if (attempts < 2) throw mockClientRequestException(HttpStatusCode.InternalServerError)
            "success"
        }
        assertEquals("success", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `fails immediately on 400 error`() = runTest {
        var attempts = 0
        assertFailsWith<ClientRequestException> {
            retryWithBackoff(defaultOptions) {
                attempts++
                throw mockClientRequestException(HttpStatusCode.BadRequest)
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `throws TusUploadExpiredException on 404 error`() = runTest {
        assertFailsWith<TusUploadExpiredException> {
            retryWithBackoff(defaultOptions) {
                throw mockClientRequestException(HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `throws TusUploadExpiredException on 410 error`() = runTest {
        assertFailsWith<TusUploadExpiredException> {
            retryWithBackoff(defaultOptions) {
                throw mockClientRequestException(HttpStatusCode.Gone)
            }
        }
    }

    @Test
    fun `exponential backoff delays are correct`() = runTest {
        var attempts = 0
        var expectedElapsed = 0.0
        val startTime = currentTime
        assertFailsWith<IOException> {
            retryWithBackoff(defaultOptions) {
                attempts++
                if (attempts > 1) {
                    val stepDelay = defaultOptions.initialDelayMillis * defaultOptions.factor.pow((attempts - 2).toDouble())
                    expectedElapsed += stepDelay
                    val elapsedTime = currentTime - startTime
                    // Loosely check if the total time elapsed is at least the cumulative expected delay
                    assertTrue(elapsedTime >= expectedElapsed.toLong())
                }
                throw IOException("Network error")
            }
        }
        assertEquals(defaultOptions.maxRetries, attempts)
    }

    private suspend fun mockClientRequestException(statusCode: HttpStatusCode): ClientRequestException {
        val engine = MockEngine { _ ->
            respond(content = "", status = statusCode, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
        }
        val client = HttpClient(engine)
        val response = client.request("")
        return ClientRequestException(response, "Error")
    }
}