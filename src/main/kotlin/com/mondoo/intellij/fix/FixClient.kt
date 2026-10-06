// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.google.gson.JsonObject
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * A client for one running `xgrep fix serve`.
 *
 * Every call blocks, so call it off the EDT. HTTP/1.1 only: the JDK client would
 * otherwise try an h2c upgrade on plain http, and closing the response stream of an
 * HTTP/1.1 call closes its connection, which is how a cancelled run reaches the
 * server as a cancelled context.
 */
class FixClient(private val baseUrl: String, private val token: String) {

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun serverInfo(): ServerInfo = FixApi.serverInfo(unary("GetServerInfo", JsonObject()))

    fun listFindings(): FindingList = FixApi.findingList(unary("ListFindings", JsonObject()))

    /** Scans the project; as slow as a scan. */
    fun rescan(): Int = unary("Rescan", JsonObject(), SCAN_TIMEOUT).get("findingCount")?.asInt ?: 0

    fun preview(fingerprint: String): Preview =
        FixApi.preview(unary("Preview", FixApi.fingerprintRequest(fingerprint), SCAN_TIMEOUT))

    fun setTriage(fingerprint: String, status: TriageStatus, rationale: String = ""): FixFinding? =
        unary("SetTriage", FixApi.setTriageRequest(fingerprint, status, rationale))
            .getAsJsonObject("finding")?.let(FixApi::finding)

    fun createPullRequest(): PullRequestResult =
        FixApi.pullRequest(unary("CreatePullRequest", JsonObject(), AGENT_TIMEOUT))

    fun graphContext(fingerprint: String): String =
        unary("GraphContext", FixApi.fingerprintRequest(fingerprint), SCAN_TIMEOUT).get("text")?.asString.orEmpty()

    /**
     * Starts a RunFix stream. Read it with [RunFixStream.next] until it returns null;
     * [RunFixStream.cancel] from any thread stops the run.
     */
    fun runFix(fingerprints: Collection<String>): RunFixStream {
        val body = ConnectProtocol.envelope(FixApi.runFixRequest(fingerprints).toString())
        val request = request("RunFix", ConnectProtocol.STREAM_CONTENT_TYPE, body, timeout = null)
        val response = send(request)
        if (response.statusCode() != 200) {
            val text = response.body().use { it.readBytes().toString(Charsets.UTF_8) }
            throw ConnectProtocol.unaryError(response.statusCode(), text)
        }
        return RunFixStream(response.body())
    }

    private fun unary(method: String, body: JsonObject, timeout: Duration = DEFAULT_TIMEOUT): JsonObject {
        val request = request(method, ConnectProtocol.UNARY_CONTENT_TYPE, body.toString().toByteArray(), timeout)
        val response = send(request)
        val text = response.body().use { it.readBytes().toString(Charsets.UTF_8) }
        if (response.statusCode() != 200) throw ConnectProtocol.unaryError(response.statusCode(), text)
        return ConnectProtocol.parseObject(text)
            ?: throw ConnectException("internal", "$method returned a response that is not JSON")
    }

    private fun request(method: String, contentType: String, body: ByteArray, timeout: Duration?): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/$SERVICE/$method"))
            .header("Content-Type", contentType)
            .header("Authorization", "Bearer $token")
            .header(ConnectProtocol.PROTOCOL_VERSION_HEADER, ConnectProtocol.PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        timeout?.let { builder.timeout(it) }
        return builder.build()
    }

    private fun send(request: HttpRequest): HttpResponse<InputStream> = try {
        http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (e: IOException) {
        throw ConnectException("unavailable", "the fix server is not reachable: ${e.message}")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ConnectException("canceled", "interrupted")
    }

    private companion object {
        const val SERVICE = "xgrep.fix.v1.FixService"
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
        val SCAN_TIMEOUT: Duration = Duration.ofMinutes(15)

        /** The server bounds agent runs itself (XGREP_AGENT_TIMEOUT, 15 minutes by default). */
        val AGENT_TIMEOUT: Duration = Duration.ofMinutes(20)
    }
}

/** An open RunFix stream. Not thread-safe except for [cancel]. */
class RunFixStream internal constructor(private val input: InputStream) : AutoCloseable {

    private val cancelled = AtomicReference(false)

    /** The next event, or null once the stream ended. Throws the call's error, if any. */
    fun next(): RunFixEvent? {
        val frame = try {
            ConnectProtocol.readFrame(input)
        } catch (e: IOException) {
            if (cancelled.get()) return null
            throw ConnectException("unavailable", "the fix stream broke: ${e.message}")
        } ?: return null
        if (frame.isEndStream) {
            ConnectProtocol.endStreamError(frame)?.let { throw it }
            return null
        }
        val obj = ConnectProtocol.parseObject(frame.text)
            ?: throw ConnectException("internal", "the fix stream sent a message that is not JSON")
        return FixApi.runFixEvent(obj)
    }

    /** Closes the connection; the server sees the call cancelled and stops the agent. */
    fun cancel() {
        cancelled.set(true)
        runCatching { input.close() }
    }

    override fun close() = cancel()
}
