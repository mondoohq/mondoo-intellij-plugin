// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FixHandshakeTest {

    @Test
    fun `a captured handshake parses`() {
        val hs = FixHandshake.parse("""{"addr":"127.0.0.1:55946","api":"xgrep.fix.v1","version":"0.58.0"}""" + "\n")!!
        assertEquals("127.0.0.1:55946", hs.addr)
        assertEquals("0.58.0", hs.version)
    }

    @Test
    fun `IPv6 loopback is accepted`() {
        assertEquals("[::1]:7000", FixHandshake.parse("""{"addr":"[::1]:7000","api":"xgrep.fix.v1"}""")!!.addr)
    }

    @Test
    fun `anything but this API on loopback is refused`() {
        // The token rides on every request, so it must never go off the machine.
        assertNull(FixHandshake.parse("""{"addr":"10.0.0.5:7000","api":"xgrep.fix.v1"}"""))
        assertNull(FixHandshake.parse("""{"addr":"localhost:7000","api":"xgrep.fix.v1"}"""))
        assertNull(FixHandshake.parse("""{"addr":"127.0.0.1:7000","api":"xgrep.fix.v2"}"""))
        assertNull(FixHandshake.parse("""{"addr":"127.0.0.1:7000"}"""))
        // What an older xgrep prints instead of a handshake.
        assertNull(FixHandshake.parse("error: scanning serve: exit status 2"))
        assertNull(FixHandshake.parse(""))
    }
}
