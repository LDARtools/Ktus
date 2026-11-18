package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Adv10Test {
    /** Docs: "maxRetries below 1 means one attempt, as in 1.0.x" and "423 is not counted against maxRetries". */
    @Test
    fun maxRetriesZero_behavesLikeOne_for423() = runTest {
        val posts = SyncMap<Int, Int>()
        for (maxRetries in listOf(1, 0)) {
            val server = ScriptedTusServer()
            server.script(HttpMethod.Post, Reply.Status(423))
            runCatching {
                server.client().createTusUpload("https://example.com/files", InMemoryTusFile("f", testBytes(10)),
                    options = TusUploadOptions(checkServerCapabilities = false, retryOptions = RetryOptions(
                        maxRetries = maxRetries, jitter = false, timeSource = testScheduler.timeSource)))
            }
            posts[maxRetries] = server.count(HttpMethod.Post)
        }
        assertEquals(posts[1], posts[0], "maxRetries=0 and 1 should both mean one attempt with 423 uncounted: $posts")
    }
}
