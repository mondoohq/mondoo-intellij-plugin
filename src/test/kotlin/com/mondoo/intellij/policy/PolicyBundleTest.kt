// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyBundleTest {

    private val bundle = """
        policies:
          - uid: ssh-baseline
            name: SSH Baseline
            version: "1.0.0"
            groups:
              - title: Server hardening
                filters: asset.family.contains("unix")
                checks:
                  - uid: sshd-permit-root
                  - uid: sshd-protocol
                queries:
                  - uid: sshd-port
              - title: Empty group
          - uid: no-groups
            name: Nothing To Do
        queries:
          - uid: sshd-permit-root
            title: Disallow root login
            mql: sshd.config.params["PermitRootLogin"] == "no"
          - uid: sshd-protocol
            title: Use protocol 2
            mql: sshd.config.params["Protocol"] == 2
          - uid: sshd-port
            title: Report the port
            mql: sshd.config.params["Port"]
    """.trimIndent()

    @Test
    fun `reads policies, groups and queries`() {
        val parsed = PolicyBundle.parse(bundle)

        assertEquals(listOf("ssh-baseline", "no-groups"), parsed.policies.map { it.uid })
        assertEquals("SSH Baseline", parsed.policies[0].displayName)
        assertEquals(listOf("Server hardening", "Empty group"), parsed.policies[0].groups.map { it.title })
        assertEquals(3, parsed.queries.size)
    }

    /**
     * A group references checks and data queries under two different keys. Both are
     * things the group contains, and a tree that showed only `checks` would silently
     * omit every data query.
     */
    @Test
    fun `a group collects both checks and data queries`() {
        val group = PolicyBundle.parse(bundle).policies[0].groups[0]
        assertEquals(listOf("sshd-permit-root", "sshd-protocol", "sshd-port"), group.checkUids)
    }

    @Test
    fun `resolves a check reference to its query`() {
        val parsed = PolicyBundle.parse(bundle)
        assertEquals("Disallow root login", parsed.queryByUid("sshd-permit-root")?.title)
        assertNull(parsed.queryByUid("never-defined"), "a dangling reference must resolve to null")
    }

    /** Line numbers are the whole reason for parsing to nodes rather than objects. */
    @Test
    fun `records the line each item is declared on`() {
        val parsed = PolicyBundle.parse(bundle)
        val lines = bundle.lines()
        fun lineOf(text: String) = lines.indexOfFirst { it.trim() == text }

        assertEquals(lineOf("- uid: ssh-baseline"), parsed.policies[0].line)
        assertEquals(lineOf("- title: Server hardening"), parsed.policies[0].groups[0].line)
        // `- uid: sshd-permit-root` appears twice: once as the group's check reference
        // and once as the query definition. Navigation must land on the definition,
        // so anchor on a line only the definition has.
        assertEquals(lineOf("title: Disallow root login") - 1, parsed.queries[0].line)
    }

    @Test
    fun `a policy without a uid is skipped rather than shown unnamed`() {
        val parsed = PolicyBundle.parse(
            """
            policies:
              - name: Anonymous
            queries:
              - title: Also anonymous
            """.trimIndent(),
        )
        assertTrue(parsed.isEmpty, "nothing identifiable, so nothing to show")
    }

    /**
     * Every keystroke in an open bundle re-parses it, so half-written YAML is the
     * normal case. It must degrade to empty, never throw into the tree model.
     */
    @Test
    fun `malformed yaml is empty rather than an exception`() {
        assertSame(PolicyBundle.EMPTY, PolicyBundle.parse("policies:\n  - uid: [unclosed"))
        assertSame(PolicyBundle.EMPTY, PolicyBundle.parse("\t\tnot yaml at all: ["))
        assertTrue(PolicyBundle.parse("").isEmpty)
        assertTrue(PolicyBundle.parse("# just a comment").isEmpty)
        assertTrue(PolicyBundle.parse("- a list at the root").isEmpty)
    }

    @Test
    fun `an unrelated yaml document yields nothing`() {
        val parsed = PolicyBundle.parse("name: some-helm-chart\nversion: 1.2.3\n")
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `falls back to the uid when a name or title is missing`() {
        val parsed = PolicyBundle.parse(
            """
            policies:
              - uid: bare-policy
            queries:
              - uid: bare-query
            """.trimIndent(),
        )
        assertEquals("bare-policy", parsed.policies[0].displayName)
        assertEquals("bare-query", parsed.queries[0].displayName)
    }
}
