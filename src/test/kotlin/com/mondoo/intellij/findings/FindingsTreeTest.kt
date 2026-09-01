package com.mondoo.intellij.findings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindingsTreeTest {

    private fun finding(
        path: String,
        line: Int,
        ruleId: String,
        severity: FindingSeverity,
        column: Int = 0,
    ) = Finding(path, line, column, ruleId, "message", severity)

    private val findings = listOf(
        finding("src/a.py", 10, "py-os-system", FindingSeverity.HIGH),
        finding("src/a.py", 3, "py-os-system", FindingSeverity.HIGH),
        finding("src/b.go", 7, "go-exec", FindingSeverity.HIGH),
        finding("src/b.go", 1, "go-weak-hash", FindingSeverity.MEDIUM),
        finding("src/c.ts", 2, "ts-eval", FindingSeverity.LOW),
    )

    @Test
    fun `groups by severity, highest first`() {
        val tree = FindingsTree.build(findings, GroupMode.SEVERITY)
        assertEquals(listOf("High", "Medium", "Low"), tree.map { (it as FindingsNode.Group).label })
        assertEquals(3, (tree[0] as FindingsNode.Group).count)
    }

    @Test
    fun `orders rules by descending count then rule id`() {
        val high = FindingsTree.build(findings, GroupMode.SEVERITY)[0] as FindingsNode.Group
        val rules = high.children.map { (it as FindingsNode.Group).label to it.count }
        // py-os-system has 2, go-exec has 1.
        assertEquals(listOf("py-os-system" to 2, "go-exec" to 1), rules)
    }

    @Test
    fun `breaks a rule count tie alphabetically`() {
        val tied = listOf(
            finding("a.go", 1, "z-rule", FindingSeverity.HIGH),
            finding("a.go", 2, "a-rule", FindingSeverity.HIGH),
        )
        val high = FindingsTree.build(tied, GroupMode.SEVERITY)[0] as FindingsNode.Group
        assertEquals(listOf("a-rule", "z-rule"), high.children.map { (it as FindingsNode.Group).label })
    }

    @Test
    fun `omits empty severity buckets`() {
        val onlyLow = listOf(finding("a.ts", 1, "ts-eval", FindingSeverity.LOW))
        val tree = FindingsTree.build(onlyLow, GroupMode.SEVERITY)
        assertEquals(listOf("Low"), tree.map { (it as FindingsNode.Group).label })
    }

    @Test
    fun `groups by file, ordered by path`() {
        val tree = FindingsTree.build(findings, GroupMode.FILE)
        assertEquals(
            listOf("src/a.py", "src/b.go", "src/c.ts"),
            tree.map { (it as FindingsNode.Group).label },
        )
        assertEquals(2, (tree[0] as FindingsNode.Group).count)
    }

    @Test
    fun `sorts leaves by path then line then column`() {
        val leaves = (FindingsTree.build(findings, GroupMode.FILE)[0] as FindingsNode.Group)
            .children.map { (it as FindingsNode.Leaf).finding.line }
        assertEquals(listOf(3, 10), leaves)
    }

    @Test
    fun `sorts by column when lines tie`() {
        val sameLine = listOf(
            finding("a.go", 5, "r", FindingSeverity.HIGH, column = 20),
            finding("a.go", 5, "r", FindingSeverity.HIGH, column = 4),
        )
        val leaves = (FindingsTree.build(sameLine, GroupMode.FILE)[0] as FindingsNode.Group)
            .children.map { (it as FindingsNode.Leaf).finding.column }
        assertEquals(listOf(4, 20), leaves)
    }

    @Test
    fun `empty input yields an empty tree so the view shows its welcome content`() {
        assertTrue(FindingsTree.build(emptyList(), GroupMode.SEVERITY).isEmpty())
        assertTrue(FindingsTree.build(emptyList(), GroupMode.FILE).isEmpty())
    }

    @Test
    fun `maps lsp severities to buckets, folding hint into low`() {
        assertEquals(FindingSeverity.HIGH, FindingSeverity.fromLsp(1))
        assertEquals(FindingSeverity.MEDIUM, FindingSeverity.fromLsp(2))
        assertEquals(FindingSeverity.LOW, FindingSeverity.fromLsp(3))
        assertEquals(FindingSeverity.LOW, FindingSeverity.fromLsp(4))
        assertEquals(FindingSeverity.LOW, FindingSeverity.fromLsp(null))
    }
}
