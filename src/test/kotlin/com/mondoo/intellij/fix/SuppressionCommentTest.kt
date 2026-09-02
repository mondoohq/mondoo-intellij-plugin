// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuppressionCommentTest {

    @Test
    fun `uses the right comment prefix per language`() {
        assertEquals("# nogrep: python-os-system", SuppressionComment.buildLine("a.py", "python-os-system", ""))
        assertEquals("// nogrep: go-exec", SuppressionComment.buildLine("a.go", "go-exec", ""))
        assertEquals("-- nogrep: lua-load", SuppressionComment.buildLine("a.lua", "lua-load", ""))
        assertEquals("# nogrep: sh-eval", SuppressionComment.buildLine("deploy.sh", "sh-eval", ""))
        assertEquals("# nogrep: yaml-secret", SuppressionComment.buildLine("ci.yml", "yaml-secret", ""))
    }

    @Test
    fun `matches the example in the user documentation`() {
        // docs/security-scanning.md shows exactly these two forms.
        assertEquals(
            "# nogrep: python-os-system",
            SuppressionComment.buildLine("a.py", "python-os-system", ""),
        )
        assertEquals(
            "# input is validated upstream nogrep: python-os-system",
            SuppressionComment.buildLine("a.py", "python-os-system", "", "input is validated upstream"),
        )
    }

    @Test
    fun `puts the reason before the keyword`() {
        // Rule ids run to end-of-line, so a trailing reason would be parsed as one.
        val line = SuppressionComment.buildLine("a.go", "go-exec", "", "validated by caller")!!
        assertTrue(line.indexOf("validated by caller") < line.indexOf("nogrep:"), line)
    }

    @Test
    fun `html uses the bare directive with no rule id`() {
        // A rule id would run into `-->` and be parsed as part of the id.
        assertEquals("<!-- nogrep -->", SuppressionComment.buildLine("page.html", "html-xss", ""))
    }

    @Test
    fun `json cannot be suppressed inline`() {
        assertNull(SuppressionComment.buildLine("package.json", "json-secret", ""))
        assertFalse(SuppressionComment.isSupported("package.json"))
    }

    @Test
    fun `preserves the indentation of the flagged line`() {
        assertEquals(
            "        # nogrep: python-os-system",
            SuppressionComment.buildLine("a.py", "python-os-system", "        "),
        )
    }

    @Test
    fun `sanitizes a reason that would corrupt the directive`() {
        assertEquals("bad idea", SuppressionComment.sanitizeReason("bad\nidea"))
        assertEquals("just a note", SuppressionComment.sanitizeReason("just  a   note  "))
        // A second `nogrep` in the reason would confuse the parser.
        assertEquals("see above", SuppressionComment.sanitizeReason("see nogrep above"))
        // A comment terminator would end the comment early.
        assertEquals("closes here", SuppressionComment.sanitizeReason("closes */ here"))
        assertEquals("closes here", SuppressionComment.sanitizeReason("closes --> here"))
    }

    @Test
    fun `appends a second rule to an existing directive`() {
        assertEquals(
            "# nogrep: rule-one, rule-two",
            SuppressionComment.appendRule("# nogrep: rule-one", "rule-two"),
        )
        assertEquals(
            "// reason here nogrep: a, b, c",
            SuppressionComment.appendRule("// reason here nogrep: a, b", "c"),
        )
    }

    @Test
    fun `does not duplicate a rule already suppressed`() {
        assertNull(SuppressionComment.appendRule("# nogrep: rule-one", "rule-one"))
    }

    @Test
    fun `returns null when the line is not a suppression comment`() {
        assertNull(SuppressionComment.appendRule("os.system(cmd)", "rule-one"))
    }

    @Test
    fun `covers every language xgrep scans except json`() {
        val samples = mapOf(
            "a.py" to true, "a.go" to true, "A.java" to true, "a.js" to true,
            "a.jsx" to true, "a.ts" to true, "a.tsx" to true, "a.rb" to true,
            "a.rs" to true, "a.c" to true, "a.cpp" to true, "a.cs" to true,
            "a.kt" to true, "a.scala" to true, "a.php" to true, "a.lua" to true,
            "a.sh" to true, "a.html" to true, "a.yaml" to true,
            "a.json" to false,
        )
        samples.forEach { (name, supported) ->
            assertEquals(supported, SuppressionComment.isSupported(name), name)
        }
    }
}
