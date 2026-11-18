package com.ldartools.ktus.test

import com.ldartools.ktus.TusProtocolException
import com.ldartools.ktus.resolveUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [resolveUrl] — the Location header URL resolution logic.
 *
 * Per TUS spec, the Location header returned from a POST (creation) may be
 * absolute or relative. These tests verify RFC 3986 resolution behavior
 * matching the official TUS clients (tus-js-client, tus-java-client, TUSKit).
 */
class UrlResolutionTest {

    // ========== Absolute URLs (returned as-is) ==========

    @Test
    fun absoluteUrl_https() {
        val result = "https://example.com/files".resolveUrl("https://cdn.example.com/uploads/abc")
        assertEquals("https://cdn.example.com/uploads/abc", result)
    }

    @Test
    fun absoluteUrl_http() {
        val result = "https://example.com/files".resolveUrl("http://other.example.com/uploads/abc")
        assertEquals("http://other.example.com/uploads/abc", result)
    }

    @Test
    fun absoluteUrl_caseInsensitive() {
        val result = "https://example.com/files".resolveUrl("HTTPS://cdn.example.com/uploads/abc")
        assertEquals("HTTPS://cdn.example.com/uploads/abc", result)
    }

    @Test
    fun absoluteUrl_withPort() {
        val result = "https://example.com/files".resolveUrl("https://cdn.example.com:9090/uploads/abc")
        assertEquals("https://cdn.example.com:9090/uploads/abc", result)
    }

    // ========== Absolute paths (leading /) ==========

    @Test
    fun absolutePath_simple() {
        val result = "https://example.com/files".resolveUrl("/uploads/abc")
        assertEquals("https://example.com/uploads/abc", result)
    }

    @Test
    fun absolutePath_nested() {
        val result = "https://example.com/api/v1/files".resolveUrl("/uploads/abc")
        assertEquals("https://example.com/uploads/abc", result)
    }

    @Test
    fun absolutePath_preservesPort() {
        val result = "https://example.com:8443/files".resolveUrl("/uploads/abc")
        assertEquals("https://example.com:8443/uploads/abc", result)
    }

    @Test
    fun absolutePath_preservesNonDefaultPort() {
        val result = "http://localhost:1080/files".resolveUrl("/uploads/abc")
        assertEquals("http://localhost:1080/uploads/abc", result)
    }

    @Test
    fun absolutePath_deepPath() {
        val result = "https://example.com/api/v2/tus/files".resolveUrl("/tus/uploads/abc123")
        assertEquals("https://example.com/tus/uploads/abc123", result)
    }

    // ========== Relative paths (no leading /) ==========

    @Test
    fun relativePath_resolvesAgainstParentDirectory() {
        // "/api/v1/files" parent is "/api/v1"
        val result = "https://example.com/api/v1/files".resolveUrl("uploads/abc")
        assertEquals("https://example.com/api/v1/uploads/abc", result)
    }

    @Test
    fun relativePath_singleSegmentBase() {
        // "/files" parent is "" → resolves to "/uploads/abc"
        val result = "https://example.com/files".resolveUrl("uploads/abc")
        assertEquals("https://example.com/uploads/abc", result)
    }

    @Test
    fun relativePath_deepBase() {
        val result = "https://example.com/a/b/c/d".resolveUrl("uploads/abc")
        assertEquals("https://example.com/a/b/c/uploads/abc", result)
    }

    @Test
    fun relativePath_trailingSlashBase() {
        // "/files/" parent is "/files" → resolves to "/files/uploads/abc"
        val result = "https://example.com/files/".resolveUrl("uploads/abc")
        assertEquals("https://example.com/files/uploads/abc", result)
    }

    @Test
    fun relativePath_preservesPort() {
        val result = "https://example.com:9090/api/v1/files".resolveUrl("uploads/abc")
        assertEquals("https://example.com:9090/api/v1/uploads/abc", result)
    }

    @Test
    fun relativePath_rootBase() {
        val result = "https://example.com/".resolveUrl("uploads/abc")
        assertEquals("https://example.com/uploads/abc", result)
    }

    @Test
    fun relativePath_noPathBase() {
        val result = "https://example.com".resolveUrl("uploads/abc")
        assertEquals("https://example.com/uploads/abc", result)
    }

    // ========== Query parameter handling ==========

    @Test
    fun absolutePath_doesNotCarryBaseQueryParams() {
        // Base URL query params should NOT leak into the resolved URL
        val result = "https://example.com/files?token=secret".resolveUrl("/uploads/abc")
        assertEquals("https://example.com/uploads/abc", result)
    }

    @Test
    fun relativePath_doesNotCarryBaseQueryParams() {
        val result = "https://example.com/api/files?token=secret".resolveUrl("uploads/abc")
        assertEquals("https://example.com/api/uploads/abc", result)
    }

    // ========== Real-world TUS server patterns ==========

    @Test
    fun tusdPattern_relativeIdOnly() {
        // tusd commonly returns just the upload ID as a relative path under the base
        val result = "https://example.com/files/".resolveUrl("abcdef1234567890")
        assertEquals("https://example.com/files/abcdef1234567890", result)
    }

    @Test
    fun tusdPattern_absolutePathWithBasePath() {
        val result = "https://example.com/files/".resolveUrl("/files/abcdef1234567890")
        assertEquals("https://example.com/files/abcdef1234567890", result)
    }

    // ========== 1.1: RFC 3986 details ==========

    @Test
    fun relativePath_withQuery_keepsTheQueryOutOfThePath() {
        // 1.0.x put "abc?token=x" into a path segment and percent-encoded the '?'.
        val result = "https://example.com/api/v1/files".resolveUrl("uploads/abc?token=x&y=1")
        assertEquals("https://example.com/api/v1/uploads/abc?token=x&y=1", result)
    }

    @Test
    fun absolutePath_withQuery() {
        assertEquals("https://example.com/uploads/abc?sig=a%2Fb",
            "https://example.com/files?old=1".resolveUrl("/uploads/abc?sig=a%2Fb"))
    }

    @Test
    fun queryOnlyReference_keepsTheBasePath() {
        assertEquals("https://example.com/files/abc?x=1", "https://example.com/files/abc?y=2".resolveUrl("?x=1"))
    }

    @Test
    fun dotSegments_areRemoved() {
        assertEquals("https://example.com/a/g", "https://example.com/a/b/c".resolveUrl("../g"))
        assertEquals("https://example.com/a/b/g", "https://example.com/a/b/c".resolveUrl("./g"))
        assertEquals("https://example.com/g", "https://example.com/a/b/c".resolveUrl("../../../../g"))
        assertEquals("https://example.com/x/z", "https://example.com/a".resolveUrl("/x/y/../z"))
    }

    @Test
    fun networkPathReference_keepsTheBaseScheme() {
        assertEquals("https://cdn.example.com/u/1", "https://example.com/files".resolveUrl("//cdn.example.com/u/1"))
    }

    @Test
    fun fragments_areDropped() {
        assertEquals("https://example.com/uploads/abc", "https://example.com/files".resolveUrl("/uploads/abc#frag"))
    }

    @Test
    fun unparseableLocations_throw() {
        for (location in listOf("", "   ", "http://exa mple.com/", "1abc:def", "/a\\b", "/a\"b", "/a\u0001b")) {
            assertFailsWith<TusProtocolException>("\"$location\"") { "https://example.com/files".resolveUrl(location) }
        }
    }

    @Test
    fun aRelativeBase_throws() {
        assertFailsWith<TusProtocolException> { "/files".resolveUrl("uploads/abc") }
    }

    @Test
    fun tusdPattern_absoluteUrlSameHost() {
        val result = "https://example.com/files/".resolveUrl("https://example.com/files/abcdef1234567890")
        assertEquals("https://example.com/files/abcdef1234567890", result)
    }
}
