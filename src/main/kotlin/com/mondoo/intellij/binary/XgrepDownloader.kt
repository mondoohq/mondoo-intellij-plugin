package com.mondoo.intellij.binary

import com.intellij.util.net.PlatformHttpClient
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The network boundary of the installer.
 *
 * Exists so [XgrepInstaller] can be unit-tested without touching the network:
 * tests supply a fake. Nothing else in the installer talks to the outside world.
 */
interface XgrepDownloader {
    fun fetchText(url: String, timeout: Duration = Duration.ofSeconds(10)): String

    /** Streams [url] into [sink]. The stream is closed by the caller. */
    fun <T> openStream(url: String, timeout: Duration = Duration.ofMinutes(10), sink: (InputStream) -> T): T
}

/**
 * Uses the IDE's HTTP client, so proxy and TLS settings configured in
 * Settings | Appearance & Behavior | System Settings | HTTP Proxy are honoured.
 */
internal class PlatformXgrepDownloader : XgrepDownloader {

    override fun fetchText(url: String, timeout: Duration): String {
        val request = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build()
        val response = PlatformHttpClient.client().send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) { "GET $url returned HTTP ${response.statusCode()}" }
        return response.body()
    }

    override fun <T> openStream(url: String, timeout: Duration, sink: (InputStream) -> T): T {
        val request = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build()
        val response = PlatformHttpClient.client().send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() == 200) { "GET $url returned HTTP ${response.statusCode()}" }
        return response.body().use(sink)
    }
}
