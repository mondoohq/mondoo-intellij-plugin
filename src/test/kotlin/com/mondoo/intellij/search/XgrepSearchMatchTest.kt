package com.mondoo.intellij.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepSearchMatchTest {

    /** Verbatim from the 2026-09-01 capture of xgrep.search. */
    private val captured = mapOf(
        "path" to "/abs/path/vuln.py",
        "line" to 4.0,
        "col" to 5.0,
        "endLine" to 4.0,
        "endCol" to 19.0,
        "text" to "    os.system(cmd)",
    )

    @Test
    fun `converts one-based server positions to zero-based`() {
        val match = XgrepSearchMatch.parse(captured)!!
        // Server said line 4 / col 5 for a four-space-indented os.system(cmd).
        assertEquals(3, match.line)
        assertEquals(4, match.column)
        assertEquals(3, match.endLine)
        assertEquals(18, match.endColumn)
    }

    @Test
    fun `column lines up with the text the server sent`() {
        val match = XgrepSearchMatch.parse(captured)!!
        assertEquals("os.system(cmd)", match.text.substring(match.column, match.endColumn))
    }

    @Test
    fun `decodes gson doubles as well as ints`() {
        val asInts = captured.mapValues { (_, v) -> if (v is Double) v.toInt() else v }
        assertEquals(XgrepSearchMatch.parse(captured), XgrepSearchMatch.parse(asInts))
    }

    @Test
    fun `carries the replacement for a replace search`() {
        val match = XgrepSearchMatch.parse(
            captured + ("replacement" to "subprocess.run(cmd, shell=False)"),
        )!!
        assertEquals("subprocess.run(cmd, shell=False)", match.replacement)
    }

    @Test
    fun `a plain search has no replacement`() {
        assertNull(XgrepSearchMatch.parse(captured)!!.replacement)
    }

    @Test
    fun `skips malformed rows instead of failing the whole search`() {
        val rows = listOf(captured, mapOf("line" to 1.0), "not-a-row", null, captured)
        assertEquals(2, XgrepSearchMatch.parseAll(rows).size)
    }

    @Test
    fun `a null result yields no matches`() {
        // sendRequest returns null on timeout or error. Callers must treat that as
        // "unavailable", never as "no matches" — parseAll simply yields nothing.
        assertTrue(XgrepSearchMatch.parseAll(null).isEmpty())
    }

    @Test
    fun `never produces a negative position`() {
        val match = XgrepSearchMatch.parse(
            captured + mapOf("line" to 0.0, "col" to 0.0, "endLine" to 0.0, "endCol" to 0.0),
        )!!
        assertEquals(0, match.line)
        assertEquals(0, match.column)
    }

    @Test
    fun `degrades to a zero-width range when the end is missing`() {
        val row = captured.filterKeys { it != "endLine" && it != "endCol" }
        val match = XgrepSearchMatch.parse(row)!!
        assertEquals(match.line, match.endLine)
        assertEquals(match.column, match.endColumn)
    }
}
