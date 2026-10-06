// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * The parts of the Connect protocol a JSON client needs, with no I/O of its own.
 *
 * Connect is plain HTTP, so the plugin speaks it with the JDK's HTTP client and the
 * platform's Gson rather than connect-kotlin and a protobuf runtime: no new
 * dependencies, and nothing to clash with the protobuf the IDE bundles.
 *
 * - A unary call is a POST of JSON; a non-200 answer carries `{code, message}`.
 * - A server stream is a POST of one enveloped message; the answer is a sequence of
 *   envelopes, the last of which has [FLAG_END_STREAM] set and carries the call's
 *   error, if any.
 *
 * An envelope is one flags byte, a 4-byte big-endian length and that many bytes.
 * See https://connectrpc.com/docs/protocol.
 */
object ConnectProtocol {

    const val FLAG_COMPRESSED = 0x01
    const val FLAG_END_STREAM = 0x02

    /** Refuses a frame bigger than this; the server never sends one near it. */
    const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    const val UNARY_CONTENT_TYPE = "application/json"
    const val STREAM_CONTENT_TYPE = "application/connect+json"
    const val PROTOCOL_VERSION_HEADER = "Connect-Protocol-Version"
    const val PROTOCOL_VERSION = "1"

    fun envelope(json: String, flags: Int = 0): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(payload.size + 5)
        out.write(flags)
        out.write(payload.size ushr 24 and 0xFF)
        out.write(payload.size ushr 16 and 0xFF)
        out.write(payload.size ushr 8 and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    data class Frame(val flags: Int, val payload: ByteArray) {
        val isEndStream: Boolean get() = flags and FLAG_END_STREAM != 0
        val text: String get() = String(payload, Charsets.UTF_8)

        override fun equals(other: Any?) =
            other is Frame && other.flags == flags && other.payload.contentEquals(payload)

        override fun hashCode() = 31 * flags + payload.contentHashCode()
    }

    /**
     * Reads one envelope, or returns null at a clean end of input (no bytes of a
     * next frame). A frame cut short is an error: the stream broke mid-message.
     */
    fun readFrame(input: InputStream): Frame? {
        val data = DataInputStream(input)
        val flags = data.read()
        if (flags < 0) return null
        if (flags and FLAG_COMPRESSED != 0) {
            throw ConnectException("internal", "the server sent a compressed message; none was negotiated")
        }
        val length = try {
            data.readInt()
        } catch (e: EOFException) {
            throw ConnectException("unavailable", "the stream ended inside a message header")
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw ConnectException("internal", "invalid message length $length")
        }
        val payload = ByteArray(length)
        try {
            data.readFully(payload)
        } catch (e: EOFException) {
            throw ConnectException("unavailable", "the stream ended inside a message")
        }
        return Frame(flags, payload)
    }

    /**
     * The error an end-of-stream frame carries, or null when the call succeeded.
     * `{"error": {"code", "message"}, "metadata": {...}}`; both keys are optional.
     */
    fun endStreamError(frame: Frame): ConnectException? {
        val obj = parseObject(frame.text) ?: return null
        val error = obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return errorFrom(error)
    }

    /**
     * The error in a unary call's non-200 body. A body that is not a Connect error
     * (a proxy page, nothing at all) falls back to the HTTP status.
     */
    fun unaryError(httpStatus: Int, body: String): ConnectException {
        parseObject(body)?.let { obj ->
            if (obj.has("code")) return errorFrom(obj)
        }
        return ConnectException(codeForHttpStatus(httpStatus), "HTTP $httpStatus")
    }

    private fun errorFrom(obj: JsonObject): ConnectException = ConnectException(
        code = obj.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: "unknown",
        message = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
    )

    /** Connect's mapping from HTTP status to code, for bodies without one. */
    fun codeForHttpStatus(status: Int): String = when (status) {
        400 -> "internal"
        401 -> "unauthenticated"
        403 -> "permission_denied"
        404 -> "unimplemented"
        429, 502, 503, 504 -> "unavailable"
        else -> "unknown"
    }

    fun parseObject(text: String): JsonObject? = runCatching {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()
}

/** A Connect error. [code] is the protocol's lower-snake-case code name. */
class ConnectException(val code: String, message: String) : RuntimeException(message.ifBlank { code })
