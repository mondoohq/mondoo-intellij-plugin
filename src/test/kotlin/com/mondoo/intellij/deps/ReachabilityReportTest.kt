// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.deps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReachabilityReportTest {

    /** Captured verbatim from `xgrep deps reachability --json` on 2026-09-02. */
    private val captured = """
        {
          "schema_version": 2,
          "packages": [
            {
              "id": "pkg::pkg:golang/gopkg.in/yaml.v2@v2.2.2",
              "name": "gopkg.in/yaml.v2",
              "version": "v2.2.2",
              "purl": "pkg:golang/gopkg.in/yaml.v2@v2.2.2",
              "ecosystem": "golang",
              "reachability": "imported"
            }
          ],
          "edges": [
            { "file": "main.go", "package": "pkg::pkg:golang/gopkg.in/yaml.v2@v2.2.2" }
          ],
          "summary": {
            "imported": 1, "imported_reachable": 0, "imported_dead": 0,
            "direct_unused": 0, "dev_dependency": 0, "transitive": 0,
            "transitive_reachable": 0, "transitive_orphaned": 0, "unknown": 0
          }
        }
    """.trimIndent()

    @Test
    fun `parses the document the scanner actually emits`() {
        val report = ReachabilityReport.parse(captured)!!
        assertEquals(1, report.total)
        val pkg = report.packages.single()
        assertEquals("gopkg.in/yaml.v2", pkg.name)
        assertEquals("v2.2.2", pkg.version)
        assertEquals("golang", pkg.ecosystem)
        assertEquals(Reachability.IMPORTED, pkg.reachability)
        assertEquals("gopkg.in/yaml.v2@v2.2.2", pkg.label)
    }

    @Test
    fun `folds edges into the package that owns them`() {
        val pkg = ReachabilityReport.parse(captured)!!.packages.single()
        assertEquals(listOf("main.go"), pkg.importedBy)
    }

    @Test
    fun `summary omits empty classes`() {
        val summary = ReachabilityReport.parse(captured)!!.summary
        assertEquals(mapOf(Reachability.IMPORTED to 1), summary)
    }

    @Test
    fun `groups for display in a stable order, skipping empty classes`() {
        val json = captured.replace(
            """"reachability": "imported"""",
            """"reachability": "direct_unused"""",
        )
        val grouped = ReachabilityReport.parse(json)!!.grouped()
        assertEquals(1, grouped.size)
        assertEquals(Reachability.DIRECT_UNUSED, grouped.single().first)
    }

    @Test
    fun `deduplicates and sorts importing files`() {
        val json = captured.replace(
            """{ "file": "main.go", "package": "pkg::pkg:golang/gopkg.in/yaml.v2@v2.2.2" }""",
            """{ "file": "z.go", "package": "pkg::pkg:golang/gopkg.in/yaml.v2@v2.2.2" },
               { "file": "a.go", "package": "pkg::pkg:golang/gopkg.in/yaml.v2@v2.2.2" },
               { "file": "a.go", "package": "pkg::pkg:golang/gopkg.in/yaml.v2@v2.2.2" }""",
        )
        assertEquals(listOf("a.go", "z.go"), ReachabilityReport.parse(json)!!.packages.single().importedBy)
    }

    @Test
    fun `an unrecognised reachability class degrades to unknown`() {
        // A new class added upstream must not drop the package from the view.
        val json = captured.replace(""""reachability": "imported"""", """"reachability": "brand-new-class"""")
        assertEquals(Reachability.UNKNOWN, ReachabilityReport.parse(json)!!.packages.single().reachability)
    }

    @Test
    fun `a malformed package is skipped rather than failing the report`() {
        val json = captured.replace(
            """"packages": [""",
            """"packages": [ {"no_id": true}, """,
        )
        assertEquals(1, ReachabilityReport.parse(json)!!.total)
    }

    @Test
    fun `garbage returns null instead of throwing`() {
        // This feeds a tool window; a broken parse must not take the IDE with it.
        assertNull(ReachabilityReport.parse("not json"))
        assertNull(ReachabilityReport.parse(""))
        assertNull(ReachabilityReport.parse("[]"))
    }

    @Test
    fun `an empty project parses to an empty report`() {
        val report = ReachabilityReport.parse("""{"schema_version":2,"packages":[],"edges":[],"summary":{}}""")!!
        assertEquals(0, report.total)
        assertTrue(report.grouped().isEmpty())
    }

    @Test
    fun `a package with no version still labels cleanly`() {
        val json = captured.replace(""""version": "v2.2.2"""", """"version": """"")
        assertEquals("gopkg.in/yaml.v2", ReachabilityReport.parse(json)!!.packages.single().label)
    }
}
