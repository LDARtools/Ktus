package com.ldartools.ktus.test

import com.ldartools.ktus.resolveUrl
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun tusdPattern_absoluteUrlSameHost() {
        val result = "https://example.com/files/".resolveUrl("https://example.com/files/abcdef1234567890")
        assertEquals("https://example.com/files/abcdef1234567890", result)
    }
}
