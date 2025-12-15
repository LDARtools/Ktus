package io.github.ldartools.ktus

import io.ktor.http.Url

fun String.getRootUrl(): String? {
    return try {
        val url = Url(this)

        // Ktor's 'port' property automatically returns 80 for http or 443 for https
        // if not specified. We check if it matches the default to decide if we
        // need to append it explicitly.
        val isDefaultPort = url.port == url.protocol.defaultPort
        val portString = if (isDefaultPort) "" else ":${url.port}"

        "${url.protocol.name}://${url.host}$portString"
    } catch (e: Exception) {
        // Handle invalid URLs (URLParserException)
        null
    }
}