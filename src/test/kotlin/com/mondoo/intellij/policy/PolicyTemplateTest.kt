// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyTemplateTest {

    @Test
    fun `accepts the names the plugin and cnspec both recognise`() {
        assertNull(PolicyTemplate.validateName("example-policy.mql.yaml"))
        assertNull(PolicyTemplate.validateName("ssh.mql.yml"))
        assertNull(PolicyTemplate.validateName("  spaced.mql.yaml  "))
    }

    /** A bundle the tree and the language server would both ignore is not useful. */
    @Test
    fun `rejects a name that is not a bundle`() {
        assertNotNull(PolicyTemplate.validateName("policy.yaml"))
        assertNotNull(PolicyTemplate.validateName("policy.txt"))
        assertNotNull(PolicyTemplate.validateName("policy"))
    }

    @Test
    fun `rejects an empty name`() {
        assertNotNull(PolicyTemplate.validateName(""))
        assertNotNull(PolicyTemplate.validateName("   "))
    }

    /**
     * The name is joined to a directory the user picked. A separator would put the
     * file somewhere they did not choose and were not shown.
     */
    @Test
    fun `rejects a path rather than a name`() {
        assertNotNull(PolicyTemplate.validateName("../escape.mql.yaml"))
        assertNotNull(PolicyTemplate.validateName("sub/dir.mql.yaml"))
        assertNotNull(PolicyTemplate.validateName("""sub\dir.mql.yaml"""))
        assertNotNull(PolicyTemplate.validateName("/absolute.mql.yaml"))
    }

    @Test
    fun `rejects a bare suffix with no name in front of it`() {
        assertNotNull(PolicyTemplate.validateName(".mql.yaml"))
    }

    /** cnspec exits 0 on failure, so the FTL line is the only signal. */
    @Test
    fun `finds the fatal line among the chatter`() {
        val reason = PolicyTemplate.failureReason(
            stdout = "",
            stderr = """
                ! provider flag shorthand already in use
                FTL Could not write 'x.mql.yaml' error="open x.mql.yaml: permission denied"
            """.trimIndent(),
        )
        assertEquals("""Could not write 'x.mql.yaml' error="open x.mql.yaml: permission denied"""", reason)
    }

    @Test
    fun `reports the already-exists refusal`() {
        val reason = PolicyTemplate.failureReason("", "FTL Policy 'example-policy.mql.yaml' already exists")
        assertEquals("Policy 'example-policy.mql.yaml' already exists", reason)
    }

    @Test
    fun `a successful run has no fatal line`() {
        assertNull(PolicyTemplate.failureReason("→ Example policy file written to example-policy.mql.yaml", ""))
    }

    @Test
    fun `a very long fatal line is truncated`() {
        val reason = PolicyTemplate.failureReason("", "FTL " + "e".repeat(2000))
        assertTrue(reason!!.length <= 300)
    }
}
