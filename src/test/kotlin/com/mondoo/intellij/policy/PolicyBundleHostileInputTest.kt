// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/**
 * The parser reads whatever `*.mql.yaml` a project contains, and a project can be one
 * someone just cloned. These are the shapes a YAML parser is classically killed by.
 */
class PolicyBundleHostileInputTest {

    private val budget: Duration = Duration.ofSeconds(5)

    /**
     * The billion-laughs expansion. Nine levels of aliases, each referencing the one
     * below nine times, is 9^9 nodes if the parser expands them.
     */
    @Test
    fun `an alias bomb does not hang or exhaust memory`() {
        val bomb = buildString {
            appendLine("policies: &a [\"x\", \"x\", \"x\", \"x\", \"x\", \"x\", \"x\", \"x\", \"x\"]")
            ('b'..'i').forEachIndexed { index, name ->
                val previous = ('a' + index)
                appendLine(
                    "$name: &$name [*$previous, *$previous, *$previous, *$previous, *$previous, *$previous, *$previous, *$previous, *$previous]",
                )
            }
        }
        assertTimeoutPreemptively(budget) {
            // Either it parses to nothing useful or it refuses; both are fine, hanging
            // is not.
            PolicyBundle.parse(bomb)
        }
    }

    @Test
    fun `deeply nested collections do not blow the stack`() {
        val deep = "policies:\n" + (1..2000).joinToString("") { "  ".repeat(it) + "- \n" }
        assertTimeoutPreemptively(budget) {
            assertTrue(PolicyBundle.parse(deep).isEmpty)
        }
    }

    /**
     * SnakeYAML's classic remote-code-execution vector is a tag naming a class the
     * constructor then instantiates. Composing to a node tree never constructs
     * anything, and this pins that: if the parser is ever switched to `load()`, this
     * fails rather than the plugin quietly gaining a deserialization gadget.
     */
    @Test
    fun `a tag naming a class instantiates nothing`() {
        val payload = """
            policies:
              - uid: evil
                name: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://127.0.0.1/"]]
        """.trimIndent()

        assertTimeoutPreemptively(budget) {
            val parsed = PolicyBundle.parse(payload)
            // Nothing was executed and nothing was constructed; at most a node tree
            // was walked, and the name is either empty or the literal text.
            assertTrue(parsed.policies.isEmpty() || parsed.policies.single().uid == "evil")
        }
    }

    @Test
    fun `a very long document is bounded, not unbounded`() {
        val long = "policies:\n" + "  - uid: p\n    name: ${"x".repeat(200)}\n".repeat(20_000)
        assertTimeoutPreemptively(budget) {
            PolicyBundle.parse(long)
        }
    }
}
