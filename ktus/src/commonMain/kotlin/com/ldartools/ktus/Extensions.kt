package com.ldartools.ktus

import io.ktor.http.Url

/**
 * Resolves a possibly-relative [location] reference against this URL as the base, following
 * RFC 3986 section 5.2 (the same algorithm as `new URL(location, base)` in a browser).
 *
 * - An absolute reference (`https://…`) keeps its own scheme, host and path.
 * - A network-path reference (`//host/path`) keeps the base scheme.
 * - An absolute path (`/uploads/1`) replaces the base path.
 * - A relative path (`uploads/1`) is resolved against the base path's directory.
 * - Dot segments are removed, the reference's own query is kept, the base query never leaks into the
 *   result unless the reference is query-only, and fragments are dropped (they are never sent over HTTP).
 *
 * This only resolves; it does not decide whether the result is safe to use. See [validateUploadUrl].
 *
 * @throws TusProtocolException when [location] (or the base) cannot be parsed as a URI reference.
 */
internal fun String.resolveUrl(location: String): String {
    val base = parseUriReference(this)
        ?: throw TusProtocolException("Invalid base URL: \"$this\"")
    if (base.scheme == null || base.authority == null) {
        throw TusProtocolException("Base URL must be absolute: \"$this\"")
    }
    val ref = parseUriReference(location.trim())
        ?: throw TusProtocolException("Unparseable Location header: \"$location\"")

    val scheme: String
    val authority: String?
    val path: String
    val query: String?
    if (ref.scheme != null) {
        scheme = ref.scheme
        authority = ref.authority
        path = removeDotSegments(ref.path)
        query = ref.query
    } else {
        scheme = base.scheme
        if (ref.authority != null) {
            authority = ref.authority
            path = removeDotSegments(ref.path)
            query = ref.query
        } else {
            authority = base.authority
            if (ref.path.isEmpty()) {
                path = base.path
                query = ref.query ?: base.query
            } else {
                path = if (ref.path.startsWith("/")) {
                    removeDotSegments(ref.path)
                } else {
                    removeDotSegments(mergePaths(base, ref.path))
                }
                query = ref.query
            }
        }
    }

    return buildString {
        append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        append(path)
        if (query != null) append('?').append(query)
    }
}

/**
 * Checks that [uploadUrl] (an upload URL resolved from a creation response's `Location` header, or one
 * loaded from a [TusUrlStore]) is safe to send the file and the caller's request headers to.
 *
 * It must be an `http`/`https` URL with a host and no user information; it must be on the same scheme, host and port as
 * [createUrl] unless its host is listed in [allowedHosts]; and an `https` creation URL never accepts a
 * plain `http` upload URL, whatever the host.
 *
 * @throws TusProtocolException when the URL is unparseable or not allowed.
 */
internal fun validateUploadUrl(createUrl: String, uploadUrl: String, allowedHosts: Set<String>) {
    val create = parseHttpUrl(createUrl)
        ?: throw TusProtocolException("Invalid creation URL: \"$createUrl\"")
    val upload = parseHttpUrl(uploadUrl)
        ?: throw TusProtocolException("Invalid upload URL: \"$uploadUrl\"")
    if (parseUriReference(uploadUrl)?.authority?.contains('@') == true) {
        // Credentials in a server-supplied URL are never legitimate, and "https://good.example@evil.net"
        // is a classic way to disguise the real host.
        throw TusProtocolException("Upload URL must not contain user information: \"$uploadUrl\"")
    }

    val createScheme = create.protocol.name.lowercase()
    val uploadScheme = upload.protocol.name.lowercase()
    if (createScheme == "https" && uploadScheme != "https") {
        throw TusProtocolException("Refusing to downgrade from https to $uploadScheme for upload URL \"$uploadUrl\"")
    }

    val sameOrigin = createScheme == uploadScheme &&
        create.host.equals(upload.host, ignoreCase = true) &&
        create.port == upload.port
    if (sameOrigin) return

    val hostAllowed = allowedHosts.any { it.equals(upload.host, ignoreCase = true) }
    if (!hostAllowed) {
        throw TusProtocolException(
            "Upload URL \"$uploadUrl\" is not on the creation URL's origin; add its host to " +
                "TusUploadOptions.allowedUploadHosts if the server legitimately hands out uploads there"
        )
    }
}

/** True when [a] and [b] share scheme, host (ignoring case) and port. False if either is unparseable. */
internal fun sameOrigin(a: Url, b: Url): Boolean =
    a.protocol.name.equals(b.protocol.name, ignoreCase = true) &&
        a.host.equals(b.host, ignoreCase = true) &&
        a.port == b.port

/**
 * True when [a] is the same request target as [b]: same origin, path and query. A different answer URL means
 * the client followed a redirect, even within the same origin.
 */
internal fun sameTarget(a: Url, b: Url): Boolean =
    sameOrigin(a, b) && a.encodedPath.ifEmpty { "/" } == b.encodedPath.ifEmpty { "/" } && a.encodedQuery == b.encodedQuery

private fun parseHttpUrl(value: String): Url? {
    val parts = parseUriReference(value) ?: return null
    val scheme = parts.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (parts.authority.isNullOrEmpty()) return null
    val url = try {
        Url(value)
    } catch (e: Exception) {
        return null
    }
    if (url.host.isEmpty()) return null
    return url
}

private class UriReference(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
)

// RFC 3986 appendix B.
private val URI_REFERENCE = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$")
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

private fun parseUriReference(value: String): UriReference? {
    if (value.isEmpty()) return null
    // Whitespace, control characters and a few characters that are never valid in a URI reference.
    if (value.any { it.code <= 0x20 || it.code == 0x7F || it in "\"<>\\^`{|}" }) return null
    val match = URI_REFERENCE.matchEntire(value) ?: return null
    val scheme = match.groups[2]?.value
    if (scheme != null && !SCHEME.matches(scheme)) return null
    return UriReference(
        scheme = scheme,
        authority = match.groups[4]?.value,
        path = match.groups[5]?.value ?: "",
        query = match.groups[7]?.value,
    )
}

private fun mergePaths(base: UriReference, refPath: String): String =
    if (base.authority != null && base.path.isEmpty()) {
        "/$refPath"
    } else {
        base.path.substring(0, base.path.lastIndexOf('/') + 1) + refPath
    }

// RFC 3986 section 5.2.4.
private fun removeDotSegments(path: String): String {
    var input = path
    val output = StringBuilder()
    while (input.isNotEmpty()) {
        when {
            input.startsWith("../") -> input = input.substring(3)
            input.startsWith("./") -> input = input.substring(2)
            input.startsWith("/./") -> input = input.substring(2)
            input == "/." -> input = "/"
            input.startsWith("/../") -> {
                input = input.substring(3)
                removeLastSegment(output)
            }
            input == "/.." -> {
                input = "/"
                removeLastSegment(output)
            }
            input == "." || input == ".." -> input = ""
            else -> {
                val start = if (input.startsWith("/")) 1 else 0
                val next = input.indexOf('/', start)
                val segmentEnd = if (next == -1) input.length else next
                output.appendRange(input, 0, segmentEnd)
                input = input.substring(segmentEnd)
            }
        }
    }
    return output.toString()
}

private fun removeLastSegment(output: StringBuilder) {
    val lastSlash = output.lastIndexOf("/")
    if (lastSlash >= 0) output.setLength(lastSlash) else output.setLength(0)
}
