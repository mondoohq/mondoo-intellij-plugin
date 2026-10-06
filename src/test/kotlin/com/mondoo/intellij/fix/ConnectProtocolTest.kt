// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream

class ConnectProtocolTest {

    @Test
    fun `an envelope is a flags byte, a big-endian length and the payload`() {
        val bytes = ConnectProtocol.envelope("""{"a":1}""")
        assertEquals(0, bytes[0].toInt())
        assertEquals(listOf(0, 0, 0, 7), bytes.slice(1..4).map { it.toInt() })
        assertEquals("""{"a":1}""", String(bytes, 5, 7))
    }

    @Test
    fun `frames read back in order and the end-stream flag is seen`() {
        val input = ByteArrayInputStream(
            ConnectProtocol.envelope("""{"progress":{"message":"x"}}""") +
                ConnectProtocol.envelope("{}", ConnectProtocol.FLAG_END_STREAM),
        )
        val first = ConnectProtocol.readFrame(input)!!
        assertFalse(first.isEndStream)
        assertEquals("""{"progress":{"message":"x"}}""", first.text)
        val last = ConnectProtocol.readFrame(input)!!
        assertTrue(last.isEndStream)
        assertNull(ConnectProtocol.endStreamError(last))
        assertNull(ConnectProtocol.readFrame(input), "clean end of input")
    }

    @Test
    fun `a frame split across reads is reassembled`() {
        val bytes = ConnectProtocol.envelope("""{"agentOutput":{"text":"hello"}}""")
        // Deliver it in three pieces, the way a socket might.
        val input = SequenceInputStream(
            java.util.Collections.enumeration(
                listOf(
                    ByteArrayInputStream(bytes.copyOfRange(0, 2)),
                    ByteArrayInputStream(bytes.copyOfRange(2, 9)),
                    ByteArrayInputStream(bytes.copyOfRange(9, bytes.size)),
                ),
            ),
        )
        assertEquals("""{"agentOutput":{"text":"hello"}}""", ConnectProtocol.readFrame(input)!!.text)
    }

    @Test
    fun `a stream cut inside a frame is an error, not a quiet end`() {
        val bytes = ConnectProtocol.envelope("""{"done":{}}""")
        val header = assertThrows(ConnectException::class.java) {
            ConnectProtocol.readFrame(ByteArrayInputStream(bytes.copyOfRange(0, 3)))
        }
        assertEquals("unavailable", header.code)
        val body = assertThrows(ConnectException::class.java) {
            ConnectProtocol.readFrame(ByteArrayInputStream(bytes.copyOfRange(0, bytes.size - 1)))
        }
        assertEquals("unavailable", body.code)
    }

    @Test
    fun `a compressed frame is refused since none was negotiated`() {
        val bytes = ConnectProtocol.envelope("{}", ConnectProtocol.FLAG_COMPRESSED)
        assertThrows(ConnectException::class.java) { ConnectProtocol.readFrame(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `an absurd length is refused before allocating it`() {
        val bytes = byteArrayOf(0, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertThrows(ConnectException::class.java) { ConnectProtocol.readFrame(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `the end-stream frame carries the call's error`() {
        val frame = ConnectProtocol.Frame(
            ConnectProtocol.FLAG_END_STREAM,
            """{"error":{"code":"failed_precondition","message":"a fix run is already in progress"}}""".toByteArray(),
        )
        val error = ConnectProtocol.endStreamError(frame)!!
        assertEquals("failed_precondition", error.code)
        assertEquals("a fix run is already in progress", error.message)
    }

    /** Bodies captured from `xgrep fix serve` on 2026-10-06. */
    @Test
    fun `unary errors decode, and a body without a code falls back to the status`() {
        val unauth = ConnectProtocol.unaryError(401, """{"code":"unauthenticated","message":"unauthenticated"}""")
        assertEquals("unauthenticated", unauth.code)
        val notFound = ConnectProtocol.unaryError(
            404,
            """{"code":"not_found","message":"no finding with fingerprint \"nope\""}""",
        )
        assertEquals("not_found", notFound.code)
        assertEquals("no finding with fingerprint \"nope\"", notFound.message)

        assertEquals("unavailable", ConnectProtocol.unaryError(503, "<html>proxy</html>").code)
        assertEquals("unimplemented", ConnectProtocol.unaryError(404, "").code)
    }
}
