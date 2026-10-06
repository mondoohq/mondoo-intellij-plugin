// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixDetailsTest {

    private val base = FixFinding(fingerprint = "x", ruleId = "js-eval-input", path = "run.js", startLine = 2)

    @Test
    fun `the badge says what will happen, then the verdict`() {
        assertEquals("agent fix", FixDetails.badge(base.copy(tier = FixTier.ASSISTED)))
        assertEquals(
            "auto-fix · true positive",
            FixDetails.badge(base.copy(tier = FixTier.DETERMINISTIC, triage = Triage(TriageStatus.TRUE_POSITIVE))),
        )
        assertEquals("stale, scan again", FixDetails.badge(base.copy(tier = FixTier.DETERMINISTIC, stale = true)))
        assertEquals("", FixDetails.badge(base))
    }

    @Test
    fun `an outcome replaces the badge`() {
        assertEquals("fixed", FixDetails.badge(base.copy(outcome = Outcome("x", status = "applied"))))
        assertEquals(
            "rejected: finding-not-cleared",
            FixDetails.badge(base.copy(outcome = Outcome("x", status = "rejected", reason = "finding-not-cleared"))),
        )
    }

    @Test
    fun `finding text is escaped, never rendered as markup`() {
        val html = FixDetails.html(base.copy(message = "<img src=x onerror=alert(1)>", lines = "a < b && c"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("a &lt; b &amp;&amp; c"))
    }

    @Test
    fun `an assisted finding shows its fix plan`() {
        val html = FixDetails.html(
            base.copy(
                tier = FixTier.ASSISTED,
                contract = Contract(
                    strategy = "Use JSON.parse.",
                    acceptanceCriteria = listOf("finding no longer fires"),
                ),
            ),
        )
        assertTrue(html.contains("Fix plan"))
        assertTrue(html.contains("<li>finding no longer fires</li>"))
    }

    /** A dependency finding as `xgrep fix serve` lists it, 2026-10-06. */
    @Test
    fun `a dependency finding is labelled by package and problem`() {
        val dep = FixFinding(
            fingerprint = "x",
            ruleId = "CVE-2017-16082",
            tier = FixTier.ECOSYSTEM,
            message = "pg@7.1.0 is affected by CVE-2017-16082: Remote Code Execution in pg",
        )
        assertEquals("pg@7.1.0: Remote Code Execution in pg (CVE-2017-16082)", FixDetails.label(dep))
        assertEquals("upgrade", FixDetails.badge(dep.copy(fixable = true)))
        assertEquals("js-eval-input", FixDetails.label(base))
    }
}
