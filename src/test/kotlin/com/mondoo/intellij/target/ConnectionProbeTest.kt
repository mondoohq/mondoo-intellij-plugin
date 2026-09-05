// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionProbeTest {

    private fun interpret(stdout: String, stderr: String = "", timedOut: Boolean = false) =
        ConnectionProbe.interpret(stdout, stderr, timedOut)

    @Test
    fun `a populated array means the target answered`() {
        val result = interpret("""[{"asset.platform":"macos"}]""")
        assertEquals(ConnectionResult.Reachable("macos"), result)
    }

    /**
     * The case this whole class exists for: cnspec exits 0 when it cannot reach the
     * target, so only the empty array distinguishes failure from success.
     */
    @Test
    fun `an empty array means it never reached the target`() {
        val result = interpret(
            stdout = "[]",
            stderr = """
                → load inventory inventory-file=/tmp/inv.yml
                x unable to create runtime for asset error="no authentication method defined" asset=
            """.trimIndent(),
        )
        val unreachable = assertInstanceOf(ConnectionResult.Unreachable::class.java, result)
        assertEquals(
            """unable to create runtime for asset error="no authentication method defined" asset=""",
            unreachable.reason,
        )
    }

    @Test
    fun `progress chatter is not mistaken for the failure`() {
        val result = interpret(
            stdout = "[]",
            stderr = """
                → Connecting to your local system.
                → loaded configuration from /home/u/.config/mondoo/mondoo.yml
                x could not initialize client authentication
            """.trimIndent(),
        )
        assertEquals(
            ConnectionResult.Unreachable("could not initialize client authentication"),
            result,
        )
    }

    @Test
    fun `a timeout is unreachable, and says so`() {
        val result = interpret(stdout = "", timedOut = true)
        val unreachable = assertInstanceOf(ConnectionResult.Unreachable::class.java, result)
        assertTrue(unreachable.reason.contains("in time"))
    }

    @Test
    fun `output that is not json at all is unreachable`() {
        val result = interpret(stdout = "Usage:\n  cnspec run [flags]", stderr = "x unknown flag: --nope")
        assertEquals(ConnectionResult.Unreachable("unknown flag: --nope"), result)
    }

    @Test
    fun `nothing at all still produces a reason`() {
        val unreachable = assertInstanceOf(
            ConnectionResult.Unreachable::class.java,
            interpret(stdout = "", stderr = ""),
        )
        assertTrue(unreachable.reason.isNotEmpty())
    }

    /** A provider that answers with something other than a string must not throw. */
    @Test
    fun `a non-string answer is still reachable`() {
        val result = interpret("""[{"asset.platform":{"name":"debian"}}]""")
        assertInstanceOf(ConnectionResult.Reachable::class.java, result)
    }

    @Test
    fun `a long failure is truncated rather than filling a dialog`() {
        val unreachable = assertInstanceOf(
            ConnectionResult.Unreachable::class.java,
            interpret(stdout = "[]", stderr = "x " + "e".repeat(2000)),
        )
        assertTrue(unreachable.reason.length <= 300)
    }
}
