// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyTreeTest {

    private fun source(path: String, yaml: String) = PolicyTree.Source(path, PolicyBundle.parse(yaml))

    private val sshBundle = """
        policies:
          - uid: ssh-baseline
            name: SSH Baseline
            groups:
              - title: Hardening
                checks:
                  - uid: permit-root
                  - uid: never-defined
        queries:
          - uid: permit-root
            title: Disallow root login
            mql: sshd.config.params["PermitRootLogin"] == "no"
    """.trimIndent()

    @Test
    fun `nests files under their directories`() {
        val tree = PolicyTree.build(
            listOf(
                source("policies/linux/ssh.mql.yaml", sshBundle),
                source("policies/windows/rdp.mql.yaml", sshBundle),
                source("top.mql.yaml", sshBundle),
            ),
        )

        val policies = tree.filterIsInstance<PolicyNode.Directory>().single()
        assertEquals("policies", policies.name)
        assertEquals(
            listOf("linux", "windows"),
            policies.children.filterIsInstance<PolicyNode.Directory>().map {
                it.name
            },
        )
        // Directories first, then files at this level.
        assertEquals("top.mql.yaml", tree.filterIsInstance<PolicyNode.File>().single().name)
    }

    @Test
    fun `summarises what a file contains`() {
        val file = PolicyTree.build(listOf(source("a.mql.yaml", sshBundle)))
            .filterIsInstance<PolicyNode.File>().single()
        assertEquals("1 policy, 1 query", file.summary)
    }

    @Test
    fun `resolves a group's checks to their queries`() {
        val file = PolicyTree.build(listOf(source("a.mql.yaml", sshBundle)))
            .filterIsInstance<PolicyNode.File>().single()
        val policies = file.children.filterIsInstance<PolicyNode.Section>().first { it.label == "Policies" }
        val group = policies.children
            .filterIsInstance<PolicyNode.PolicyRef>().single()
            .children.filterIsInstance<PolicyNode.Group>().single()

        assertEquals("Disallow root login", group.children.filterIsInstance<PolicyNode.Query>().single().query.title)
    }

    /**
     * A check naming a uid nothing defines is a bug in the bundle. Dropping it would
     * hide exactly the thing worth seeing, so the tree shows it.
     */
    @Test
    fun `shows a check whose query does not exist`() {
        val file = PolicyTree.build(listOf(source("a.mql.yaml", sshBundle)))
            .filterIsInstance<PolicyNode.File>().single()
        val group = file.children.filterIsInstance<PolicyNode.Section>().first { it.label == "Policies" }
            .children.filterIsInstance<PolicyNode.PolicyRef>().single()
            .children.filterIsInstance<PolicyNode.Group>().single()

        assertEquals("never-defined", group.children.filterIsInstance<PolicyNode.MissingQuery>().single().uid)
    }

    /** The order in a group is the order the author wrote, not alphabetical. */
    @Test
    fun `keeps a group's checks in declaration order`() {
        val yaml = """
            policies:
              - uid: p
                groups:
                  - title: g
                    checks:
                      - uid: zebra
                      - uid: aardvark
            queries:
              - uid: zebra
                title: Zebra
              - uid: aardvark
                title: Aardvark
        """.trimIndent()
        val group = PolicyTree.build(listOf(source("a.mql.yaml", yaml)))
            .filterIsInstance<PolicyNode.File>().single()
            .children.filterIsInstance<PolicyNode.Section>().first()
            .children.filterIsInstance<PolicyNode.PolicyRef>().single()
            .children.filterIsInstance<PolicyNode.Group>().single()

        assertEquals(
            listOf("Zebra", "Aardvark"),
            group.children.filterIsInstance<PolicyNode.Query>().map {
                it.query.title
            },
        )
    }

    @Test
    fun `files with nothing in them do not appear`() {
        val tree = PolicyTree.build(
            listOf(
                source("empty.mql.yaml", "name: not-a-bundle\n"),
                source("real.mql.yaml", sshBundle),
            ),
        )
        assertEquals(listOf("real.mql.yaml"), tree.filterIsInstance<PolicyNode.File>().map { it.name })
    }

    @Test
    fun `no bundles is an empty tree, not a tree of empties`() {
        assertTrue(PolicyTree.build(emptyList()).isEmpty())
        assertTrue(PolicyTree.build(listOf(source("a.mql.yaml", "# nothing"))).isEmpty())
    }

    @Test
    fun `navigable nodes carry a path and line, headings do not`() {
        val file = PolicyTree.build(listOf(source("dir/a.mql.yaml", sshBundle)))
            .filterIsInstance<PolicyNode.Directory>().single()
            .children.filterIsInstance<PolicyNode.File>().single()

        assertEquals(PolicyTarget("dir/a.mql.yaml", 0), file.target)

        val section = file.children.filterIsInstance<PolicyNode.Section>().first()
        assertNull(section.target, "a heading is not a place in a file")

        val policy = section.children.filterIsInstance<PolicyNode.PolicyRef>().single()
        assertEquals("dir/a.mql.yaml", policy.target.path)
        assertEquals(1, policy.target.line, "the policy is declared on the line after `policies:`")
    }
}
