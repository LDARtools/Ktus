package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class Adv14Test {
    private val url = "https://example.com/files/abc"

    /** The stale-HEAD re-ask sends no HEAD once its wait used up the budget. */
    @Test
    fun aStaleHeadReAsk_isNotSentOnceTheBudgetIsSpent() = runTest {
        val server = ScriptedTusServer(clock = { testScheduler.currentTime }).apply { uploadLength = 30 }
        repeat(20) { server.script(HttpMethod.Head, Reply.Status(200, mapOf("Upload-Offset" to "0", "Upload-Length" to "30"))) }
        server.script(HttpMethod.Patch, Reply.Status(204, mapOf("Upload-Offset" to "10"), apply = true))
        repeat(20) { server.script(HttpMethod.Patch, Reply.Status(503)) }
        val r = runCatching {
            server.client().uploadTus(url, InMemoryTusFile("f", testBytes(30)), options = TusUploadOptions(chunkSize = 10,
                activityHeartbeatMillis = null, retryOptions = RetryOptions(maxRetries = 100, initialDelayMillis = 7_000,
                    maxDelayMillis = 7_000, jitter = false, maxElapsedMillis = 10_000, timeSource = testScheduler.timeSource)))
        }
        assertTrue(r.isFailure, "$r")
        val heads = server.times(HttpMethod.Head)
        assertTrue(heads.none { it >= 10_000 }, "HEAD sent after the budget: $heads")
        assertTrue(heads.size >= 2, "the recovery HEAD itself was expected: $heads")
    }

    /** The legacy exception-driven retry loop sends nothing once its wait used up the budget. */
    @Test
    fun legacyRetryWithBackoff_sendsNothingOnceTheBudgetIsSpent() = runTest {
        val attempts = mutableListOf<Long>()
        assertFailsWith<kotlinx.io.IOException> {
            retryWithBackoff(RetryOptions(maxRetries = 100, initialDelayMillis = 5_000, maxDelayMillis = 5_000, jitter = false,
                maxElapsedMillis = 10_000, timeSource = testScheduler.timeSource)) {
                attempts += testScheduler.currentTime
                throw kotlinx.io.IOException("reset")
            }
        }
        assertTrue(attempts.none { it >= 10_000 }, "attempt after the budget: $attempts")
    }
}
