package com.ldartools.ktus

import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * Resolves a possibly-relative [location] URL against this URL as the base,
 * following RFC 3986 semantics.
 *
 * - If [location] is already absolute (has a scheme), it is returned as-is.
 * - If [location] starts with `/`, it is treated as an absolute path on the same origin.
 * - Otherwise, it is resolved relative to the path of this URL.
 *
 * This matches the behavior of official TUS clients (tus-js-client uses `new URL(loc, base)`,
 * tus-java-client uses `new URL(base, loc)`, TUSKit uses `URL(string:relativeTo:)`).
 */
internal fun String.resolveUrl(location: String): String {
    // Already absolute — nothing to resolve
    if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
        return location
    }

    return try {
        val base = Url(this)
        val builder = URLBuilder(base)

        // Clear query parameters and fragment from the base URL — only
        // the scheme, host, port, and (possibly) path are used for resolution.
        builder.parameters.clear()
        builder.fragment = ""

        if (location.startsWith("/")) {
            // Absolute path — replace the entire path, keep scheme+host+port
            builder.encodedPathSegments = location.trimStart('/').split("/")
        } else {
            // Relative path — resolve against the base path's parent directory
            val basePath = base.encodedPath.substringBeforeLast("/", "")
            val resolved = if (basePath.isEmpty()) "/$location" else "$basePath/$location"
            builder.encodedPathSegments = resolved.trimStart('/').split("/")
        }

        builder.buildString()
    } catch (e: Exception) {
        // Fallback: return location as-is if base URL is unparseable
        location
    }
}
