package com.ldartools.ktus.test

import com.ldartools.ktus.*
import kotlin.test.*

/** Every Location Ktus accepts must point at the creation host as java.net.URI parses it too. */
class UrlSafetyJvmTest {
    @Test
    fun acceptedLocations_resolveToTheCreateHost_inJavaNetUri() {
        val create = "https://api.example.com/files/"
        val locations = listOf(
            "https://evil.com#@api.example.com/x", "https://evil.com?@api.example.com/x",
            "https://api.example.com.evil.com/x", "https://API.EXAMPLE.COM/x", "https://api.example.com./x",
            "https://api.example.com:443/x", "https://api.example.com:0443/x", "//evil.com/x", "///evil.com/x",
            "https:/evil.com/x", "https:evil.com/x", "/%2F%2Fevil.com/x", "https://api.example.com%2eevil.com/x",
            "https://api.example.com%40evil.com/x", "https://xn--pple-43d.com/x", "https://аpple.com/x",
            "https://[::1]/x", "https://api.example.com:/x", "https://api.example.com:65980/x",
            "https://api.example.com:-1/x", "HTTPS://api.example.com/x", "https://api.example.com。evil.com/x",
            "https://api.example.com%00.evil.com/x", "https://api.example.com;@evil.com/x",
            "https://api.example.com/..//evil.com/x", "https://user@api.example.com/x", "/files/../../x",
        )
        val accepted = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (loc in locations) {
            val resolved = try { create.resolveUrl(loc) } catch (e: Exception) { continue }
            val ok = try { validateUploadUrl(create, resolved, emptySet()); true } catch (e: TusProtocolException) { false }
            val ktorUrl = try { io.ktor.http.Url(resolved).let { "${it.host}:${it.port}" } } catch (e: Exception) { "ERR" }
            val javaHost = try { java.net.URI(resolved).let { "${it.host}:${it.port}" } } catch (e: Exception) { "ERR ${e.message}" }

            if (ok) {
                accepted += resolved
                if (java.net.URI(resolved).host?.lowercase() != "api.example.com") problems += "$loc -> $resolved ($javaHost)"
            }
        }
        assertTrue(problems.isEmpty(), "accepted but resolves elsewhere: $problems")
    }
}
