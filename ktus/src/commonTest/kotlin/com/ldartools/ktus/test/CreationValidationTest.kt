package com.ldartools.ktus.test

import com.ldartools.ktus.*
import com.ldartools.ktus.test.ScriptedTusServer.Reply
import io.ktor.http.HttpMethod
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** R9 (metadata), R10 (Tus-Max-Size) and S1 (Location handling) on the creation request. */
class CreationValidationTest {

    private val createUrl = "https://example.com/api/v1/files"

    // ---------------------------------------------------------------- R9

    @Test
    fun emptyMetadataValues_areOmittedByDefault() = runTest {
        val server = ScriptedTusServer()
        server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)),
            metadata = mapOf("filename" to "a.jpg", "checksum" to "", "note" to ""))
        val header = server.requests.single { it.method == "POST" }.headers["Upload-Metadata"]
        assertEquals("filename ${"a.jpg".encodeBase64()}", header)
    }

    @Test
    fun metadataWithOnlyEmptyValues_sendsNoHeader() = runTest {
        val server = ScriptedTusServer()
        server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)), metadata = mapOf("flag" to ""))
        assertNull(server.requests.single { it.method == "POST" }.headers["Upload-Metadata"])
    }

    @Test
    fun metadataValues_areBase64Encoded_andRoundTrip() = runTest {
        val server = ScriptedTusServer()
        val values = mapOf("filename" to "résumé 2026.pdf", "type" to "application/pdf", "comma" to "a,b c")
        server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)), metadata = values)
        val header = server.requests.single { it.method == "POST" }.headers["Upload-Metadata"]!!
        val decoded = header.split(",").associate { pair ->
            val (key, value) = pair.split(" ")
            key to value.decodeBase64String()
        }
        assertEquals(values, decoded)
    }

    @Test
    fun invalidMetadataKeys_areRejected_beforeAnyRequest() = runTest {
        val badKeys = listOf("", "file name", "file,name", "naïve", "ключ", "tab\tkey", "nl\nkey", "del\u007Fkey", "emoji😀")
        for (key in badKeys) {
            val server = ScriptedTusServer()
            assertFailsWith<IllegalArgumentException>("key \"$key\"") {
                server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)), metadata = mapOf(key to "v"))
            }
            assertTrue(server.requests.isEmpty(), "key \"$key\": nothing may be sent")
        }
    }

    @Test
    fun invalidKeys_areRejectedEvenWhenTheirValueIsEmpty() = runTest {
        val server = ScriptedTusServer()
        assertFailsWith<IllegalArgumentException> {
            server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)), metadata = mapOf("bad key" to ""))
        }
    }

    @Test
    fun printableAsciiKeys_areAccepted() = runTest {
        val server = ScriptedTusServer()
        server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)),
            metadata = mapOf("a-b_c.d~!#\$%&'*+/:;<=>?@[]^`{|}" to "v"))
        assertNotNull(server.requests.single { it.method == "POST" }.headers["Upload-Metadata"])
    }

    // ---------------------------------------------------------------- R10

    @Test
    fun aFileLargerThanTusMaxSize_failsBeforeCreation() = runTest {
        val server = ScriptedTusServer().apply { maxSize = 100 }
        val error = assertFailsWith<TusProtocolException> {
            server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(101)))
        }
        assertTrue(error.message!!.contains("Tus-Max-Size"), error.message)
        assertEquals(listOf("OPTIONS"), server.methods())
    }

    @Test
    fun aFileOfExactlyTusMaxSize_isUploaded() = runTest {
        val server = ScriptedTusServer().apply { maxSize = 100 }
        server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(100)))
        assertEquals(100, server.offset)
    }

    @Test
    fun tusMaxSize_isNotCheckedWithoutTheCapabilityCheck() = runTest {
        val server = ScriptedTusServer().apply { maxSize = 100 }
        server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(150)),
            options = TusUploadOptions(checkServerCapabilities = false))
        assertEquals(150, server.offset)
        assertEquals(0, server.count(HttpMethod.Options))
    }

    @Test
    fun aServerWithoutTusResumable_isRejected() = runTest {
        val server = ScriptedTusServer().apply { tusResumableOnOptions = false }
        assertFailsWith<TusProtocolException> {
            server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)))
        }
        assertEquals(0, server.count(HttpMethod.Post))
    }

    // ---------------------------------------------------------------- S1

    private suspend fun createWithLocation(location: String, options: TusUploadOptions = TusUploadOptions(), url: String = createUrl): String {
        val server = ScriptedTusServer(location = location)
        return server.client().createTus(url, InMemoryTusFile("f", testBytes(10)), options = options)
    }

    @Test
    fun aRelativeLocationWithAQuery_isResolvedPerRfc3986() = runTest {
        assertEquals("https://example.com/api/v1/uploads/abc?token=x%20y&a=b",
            createWithLocation("uploads/abc?token=x%20y&a=b"))
        assertEquals("https://example.com/uploads/abc?sig=1", createWithLocation("/uploads/abc?sig=1"))
        assertEquals("https://example.com/api/uploads/abc", createWithLocation("../uploads/abc"))
    }

    @Test
    fun aLocationOnAnotherHost_isRejected() = runTest {
        for (location in listOf("https://cdn.example.com/uploads/abc", "//cdn.example.com/uploads/abc",
                "https://example.com.evil.net/x", "https://evil.net/x")) {
            val server = ScriptedTusServer(location = location)
            assertFailsWith<TusProtocolException>(location) {
                server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)))
            }
            assertEquals(0, server.count(HttpMethod.Head), "$location: nothing may be sent to the rejected URL")
            assertEquals(0, server.count(HttpMethod.Patch))
        }
    }

    @Test
    fun aLocationOnAnotherPortOrScheme_isRejected() = runTest {
        assertFailsWith<TusProtocolException> { createWithLocation("https://example.com:8443/uploads/abc") }
        assertFailsWith<TusProtocolException> {
            createWithLocation("https://example.com/uploads/abc", url = "http://example.com/files")
        }
    }

    @Test
    fun anAllowListedHost_isAccepted_caseInsensitively() = runTest {
        val options = TusUploadOptions(allowedUploadHosts = setOf("CDN.example.com"))
        assertEquals("https://cdn.example.com/uploads/abc", createWithLocation("https://cdn.example.com/uploads/abc", options))
        assertEquals("https://cdn.example.com:8443/u", createWithLocation("https://cdn.example.com:8443/u", options))
    }

    @Test
    fun anHttpsToHttpDowngrade_isRejected_evenForTheSameOrAnAllowListedHost() = runTest {
        val options = TusUploadOptions(allowedUploadHosts = setOf("example.com", "cdn.example.com"))
        for (location in listOf("http://example.com/uploads/abc", "http://cdn.example.com/uploads/abc")) {
            val error = assertFailsWith<TusProtocolException>(location) { createWithLocation(location, options) }
            assertTrue(error.message!!.contains("downgrade"), error.message)
        }
    }

    @Test
    fun anHttpToHttpsUpgrade_onAnAllowListedHost_isAccepted() = runTest {
        val options = TusUploadOptions(allowedUploadHosts = setOf("example.com"))
        assertEquals("https://example.com/u", createWithLocation("https://example.com/u", options, url = "http://example.com/files"))
    }

    @Test
    fun anUnparseableLocation_isRejected_withoutFallingBackToTheRawValue() = runTest {
        for (location in listOf("http://exa mple.com/x", "javascript:alert(1)", "https://", "ftp://example.com/x",
                "1http://example.com", "https://example.com/a\\b", "<https://example.com/x>")) {
            val server = ScriptedTusServer(location = location)
            assertFailsWith<TusProtocolException>(location) {
                server.client().createAndUploadTus(createUrl, InMemoryTusFile("f", testBytes(10)))
            }
            assertEquals(listOf("OPTIONS", "POST"), server.methods(), location)
        }
    }

    @Test
    fun aBlankLocation_isRejected() = runTest {
        assertFailsWith<TusProtocolException> { createWithLocation("   ") }
    }

    @Test
    fun aNonCreatedSuccessStatus_isRejected() = runTest {
        val server = ScriptedTusServer()
        server.script(HttpMethod.Post, Reply.Status(200, mapOf("Location" to "/files/abc")))
        assertFailsWith<TusProtocolException> {
            server.client().createTusUpload(createUrl, InMemoryTusFile("f", testBytes(10)))
        }
    }
}
