// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LintReportTest {

    /** Shaped exactly like `cnspec policy lint -o sarif`, captured 2026-09-03. */
    private val captured = """
        {
          "runs": [
            {
              "results": [
                {
                  "ruleId": "bundle-compile-error",
                  "ruleIndex": 0,
                  "level": "error",
                  "message": { "text": "Could not compile policy bundle" },
                  "locations": [
                    { "physicalLocation": {
                        "artifactLocation": { "uri": "broken.mql.yaml" },
                        "region": { "startLine": 1, "startColumn": 1 } } }
                  ]
                },
                {
                  "ruleId": "policy-missing-asset-filter",
                  "level": "warning",
                  "message": { "text": "Check 'missing-query' lacks an asset filter" },
                  "locations": [
                    { "physicalLocation": {
                        "artifactLocation": { "uri": "broken.mql.yaml" },
                        "region": { "startLine": 6, "startColumn": 3 } } }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the document the linter emits`() {
        val findings = LintReport.parse(captured)!!
        assertEquals(2, findings.size)
        assertEquals("bundle-compile-error", findings[0].ruleId)
        assertEquals(LintSeverity.ERROR, findings[0].severity)
        assertEquals(LintSeverity.WARNING, findings[1].severity)
        assertEquals("broken.mql.yaml", findings[0].path)
    }

    @Test
    fun `converts SARIF one-based positions to zero-based`() {
        val findings = LintReport.parse(captured)!!
        // startLine 1 / startColumn 1 is the first character of the file.
        assertEquals(0, findings[0].line)
        assertEquals(0, findings[0].column)
        assertEquals(5, findings[1].line)
        assertEquals(2, findings[1].column)
    }

    @Test
    fun `never produces a negative position`() {
        val json = captured.replace("\"startLine\": 1", "\"startLine\": 0")
        assertEquals(0, LintReport.parse(json)!![0].line)
    }

    @Test
    fun `defaults a missing region to the start of the file`() {
        val json = captured.replace("""{ "startLine": 1, "startColumn": 1 }""", "null")
        val finding = LintReport.parse(json)!![0]
        assertEquals(0, finding.line)
        assertEquals(0, finding.column)
    }

    @Test
    fun `maps SARIF levels, treating anything unknown as informational`() {
        assertEquals(LintSeverity.ERROR, LintSeverity.of("error"))
        assertEquals(LintSeverity.WARNING, LintSeverity.of("warning"))
        assertEquals(LintSeverity.INFO, LintSeverity.of("note"))
        assertEquals(LintSeverity.INFO, LintSeverity.of(null))
        assertEquals(LintSeverity.INFO, LintSeverity.of("something-new"))
    }

    @Test
    fun `skips a malformed result rather than losing the report`() {
        val json = captured.replace("\"results\": [", "\"results\": [ {\"no\": \"location\"}, ")
        assertEquals(2, LintReport.parse(json)!!.size)
    }

    @Test
    fun `a clean bundle parses to no findings`() {
        assertEquals(emptyList<LintFinding>(), LintReport.parse("""{"runs":[{"results":[]}]}"""))
        assertEquals(emptyList<LintFinding>(), LintReport.parse("""{"runs":[]}"""))
    }

    @Test
    fun `garbage returns null rather than throwing`() {
        // cnspec exits non-zero when it finds problems but still writes valid SARIF,
        // so the exit code cannot decide whether to parse — this must be total.
        assertNull(LintReport.parse("not json"))
        assertNull(LintReport.parse(""))
    }

    @Test
    fun `tolerates a result with no message or rule`() {
        val json = """{"runs":[{"results":[{"level":"error","locations":[
            {"physicalLocation":{"artifactLocation":{"uri":"a.mql.yaml"},
             "region":{"startLine":2,"startColumn":1}}}]}]}]}"""
        val f = LintReport.parse(json)!!.single()
        assertTrue(f.ruleId.isEmpty())
        assertTrue(f.message.isEmpty())
        assertEquals(1, f.line)
    }
}
