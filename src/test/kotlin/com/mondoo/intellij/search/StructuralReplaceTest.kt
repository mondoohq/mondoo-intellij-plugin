// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuralReplaceTest {

    private fun match(
        path: String = "a.py",
        line: Int,
        column: Int,
        endLine: Int = line,
        endColumn: Int,
        replacement: String? = "REPLACED",
    ) = XgrepSearchMatch(path, line, column, endLine, endColumn, text = "orig", replacement = replacement)

    @Test
    fun `applies later matches first so offsets stay valid`() {
        val plan = StructuralReplace.plan(
            listOf(
                match(line = 1, column = 0, endColumn = 5),
                match(line = 9, column = 4, endColumn = 9),
                match(line = 4, column = 2, endColumn = 7),
            ),
        )
        assertEquals(listOf(9, 4, 1), plan.map { it.line })
    }

    @Test
    fun `orders by column within a line`() {
        val plan = StructuralReplace.plan(
            listOf(
                match(line = 3, column = 2, endColumn = 5),
                match(line = 3, column = 20, endColumn = 25),
                match(line = 3, column = 10, endColumn = 15),
            ),
        )
        assertEquals(listOf(20, 10, 2), plan.map { it.column })
    }

    /**
     * `f($X)` against `f(f(1))` matches twice, nested. Applying both would splice one
     * replacement into text the other had already rewritten.
     */
    @Test
    fun `drops a match nested inside another`() {
        val outer = match(line = 0, column = 0, endColumn = 10)
        val inner = match(line = 0, column = 2, endColumn = 8)
        val plan = StructuralReplace.plan(listOf(outer, inner))

        assertEquals(1, plan.size)
        assertEquals(0, plan.single().column, "the outer match is the one kept")
        assertEquals(1, StructuralReplace.skipped(listOf(outer, inner)))
    }

    /** The enclosing match wins here too, even when it spans several lines. */
    @Test
    fun `drops an overlap that spans lines`() {
        val enclosing = match(line = 0, column = 0, endLine = 3, endColumn = 2)
        val inside = match(line = 2, column = 0, endLine = 2, endColumn = 4)
        assertEquals(listOf(0), StructuralReplace.plan(listOf(enclosing, inside)).map { it.line })
    }

    @Test
    fun `adjacent matches both survive`() {
        val first = match(line = 0, column = 0, endColumn = 5)
        val second = match(line = 0, column = 5, endColumn = 10)
        assertEquals(2, StructuralReplace.plan(listOf(first, second)).size)
        assertEquals(0, StructuralReplace.skipped(listOf(first, second)))
    }

    /** The server only fills in a replacement for a replace search. */
    @Test
    fun `matches with no replacement are not applied`() {
        val plan = StructuralReplace.plan(
            listOf(
                match(line = 0, column = 0, endColumn = 5, replacement = null),
                match(line = 2, column = 0, endColumn = 5),
            ),
        )
        assertEquals(1, plan.size)
        assertEquals(2, plan.single().line)
    }

    @Test
    fun `overlap is judged per file, not across them`() {
        val plan = StructuralReplace.plan(
            listOf(
                match(path = "a.py", line = 0, column = 0, endColumn = 10),
                match(path = "b.py", line = 0, column = 2, endColumn = 8),
            ),
        )
        assertEquals(2, plan.size, "identical ranges in different files do not overlap")
        assertTrue(plan.map { it.path }.containsAll(listOf("a.py", "b.py")))
    }

    @Test
    fun `nothing to do is empty, not an error`() {
        assertTrue(StructuralReplace.plan(emptyList()).isEmpty())
        assertEquals(0, StructuralReplace.skipped(emptyList()))
    }
}
