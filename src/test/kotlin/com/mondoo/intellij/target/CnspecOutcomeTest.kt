// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each of these is a real cnspec output, captured from the binary. They exist because
 * every one of them accompanied exit code 0.
 */
class CnspecOutcomeTest {

    @Test
    fun `finds a runtime failure`() {
        assertEquals(
            """unable to create runtime for asset error="no authentication method defined" asset=""",
            CnspecOutcome.failureReason(
                stdout = "[]",
                stderr = """
                    → load inventory inventory-file=/tmp/inv.yml
                    x unable to create runtime for asset error="no authentication method defined" asset=
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `finds a fatal failure`() {
        assertEquals(
            "Policy 'example-policy.mql.yaml' already exists",
            CnspecOutcome.failureReason("", "FTL Policy 'example-policy.mql.yaml' already exists"),
        )
    }

    @Test
    fun `finds an upload failure`() {
        assertEquals(
            "failed to create upstream client: agent credentials must be set",
            CnspecOutcome.failureReason(
                stdout = "→ valid policy bundle\nx failed to create upstream client: agent credentials must be set",
                stderr = "",
            ),
        )
    }

    @Test
    fun `finds a cobra flag error`() {
        assertEquals(
            "unknown flag: --inventory-file",
            CnspecOutcome.failureReason("", "Error: unknown flag: --inventory-file"),
        )
    }

    @Test
    fun `progress and lint output are not failures`() {
        assertNull(
            CnspecOutcome.failureReason(
                stdout = """
                    → loaded configuration from /home/u/.config/mondoo/mondoo.yml
                    policy-missing-require  warning  good.mql.yaml  3  does not define any required providers
                    → valid policy bundle
                    → policies uploaded successfully
                """.trimIndent(),
                stderr = "! provider flag shorthand already in use",
            ),
        )
    }

    /** A word beginning with x is not cnspec's failure marker. */
    @Test
    fun `a line merely starting with the letter x is not a failure`() {
        assertNull(CnspecOutcome.failureReason("xgrep finished cleanly", ""))
        assertNull(CnspecOutcome.failureReason("x86_64 detected", ""))
    }

    @Test
    fun `the first failure wins, since later ones are usually consequences`() {
        val reason = CnspecOutcome.failureReason(
            "",
            """
            x could not resolve bundle files error="could not load file"
            x failed to upload policies: could not load file
            """.trimIndent(),
        )
        assertEquals("""could not resolve bundle files error="could not load file"""", reason)
    }

    @Test
    fun `nothing at all is not a failure`() {
        assertNull(CnspecOutcome.failureReason("", ""))
        assertNull(CnspecOutcome.failureReason("   \n  \n", "  "))
    }

    @Test
    fun `a very long failure is truncated`() {
        val reason = CnspecOutcome.failureReason("", "x " + "e".repeat(2000))
        assertTrue(reason!!.length <= 300)
    }
}
