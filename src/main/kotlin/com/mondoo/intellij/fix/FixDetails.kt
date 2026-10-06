// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

/**
 * What the Fix tab says about a finding: the detail pane's HTML and the one-line
 * badge in the tree. The same facts as the terminal UI's detail pane.
 *
 * Pure, so it is tested without the platform.
 */
object FixDetails {

    /** The short state shown after a finding in the tree. */
    fun badge(f: FixFinding): String {
        f.outcome?.let { o ->
            return when {
                o.applied -> "fixed"
                o.rejected -> "rejected: ${o.reason.ifBlank { "see details" }}"
                else -> o.status
            }
        }
        val triage = when (f.triage?.status) {
            TriageStatus.TRUE_POSITIVE -> "true positive"
            TriageStatus.FALSE_POSITIVE -> "false positive"
            TriageStatus.NEEDS_REVIEW -> "needs review"
            else -> null
        }
        val tier = when {
            f.stale -> "stale, scan again"
            f.tier == FixTier.DETERMINISTIC -> "auto-fix"
            f.tier == FixTier.ASSISTED -> "agent fix"
            f.tier == FixTier.ADVISORY -> "advice"
            f.tier == FixTier.ECOSYSTEM -> "upgrade"
            else -> null
        }
        return listOfNotNull(tier, triage).joinToString(" · ")
    }

    /**
     * The row label. A dependency finding's rule is a CVE id, which says nothing about
     * which package; its message names both ("pg@7.1.0 is affected by CVE-2017-16082:
     * Remote Code Execution in pg"), so the label is the package and what is wrong.
     */
    fun label(f: FixFinding): String {
        if (f.tier == FixTier.ECOSYSTEM && " is affected by " in f.message) {
            val pkg = f.message.substringBefore(" is affected by ")
            val what = f.message.substringAfter(": ", "").ifBlank { f.ruleId }
            return "$pkg: $what (${f.ruleId})"
        }
        return f.title.ifBlank { f.ruleId }
    }

    fun html(f: FixFinding): String = buildString {
        append("<html><body>")
        append("<h3>").append(esc(label(f))).append("</h3>")
        append("<p>").append(esc(f.message)).append("</p>")

        append("<table cellpadding='2'>")
        row("Rule", "<code>${esc(f.ruleId)}</code>")
        row(
            "Severity",
            esc(f.severity.lowercase().replaceFirstChar(Char::titlecase)) +
                if (f.confidence.isNotBlank()) " &nbsp;·&nbsp; confidence ${esc(f.confidence.lowercase())}" else "",
        )
        row("Location", "${esc(f.path)}:${f.startLine}")
        row("Fix", esc(tierDescription(f)))
        f.triage?.let { t ->
            val by = if (t.reviewedBy.isNotBlank()) " (${esc(t.reviewedBy)})" else ""
            row("Verdict", esc(t.status.label) + by)
            if (t.rationale.isNotBlank()) row("Rationale", esc(t.rationale))
        }
        f.outcome?.let { o ->
            row("Outcome", "<b>${esc(o.status)}</b>" + if (o.reason.isNotBlank()) " — ${esc(o.reason)}" else "")
            if (o.detail.isNotBlank()) row("", esc(o.detail))
        }
        append("</table>")

        if (f.lines.isNotBlank()) {
            append("<pre>").append(esc(f.lines.trimEnd())).append("</pre>")
        }

        val contract = f.contract
        if (contract != null && f.tier == FixTier.ASSISTED) {
            append("<h4>Fix plan</h4>")
            if (contract.strategy.isNotBlank()) append("<p>").append(esc(contract.strategy)).append("</p>")
            if (contract.canonicalFix.isNotBlank()) {
                append("<p>Converge on: <code>").append(esc(contract.canonicalFix)).append("</code></p>")
            }
            if (contract.recognizedSanitizers.isNotEmpty()) {
                append("<p>Recognized safe constructs: ")
                append(contract.recognizedSanitizers.joinToString(", ") { "<code>${esc(it)}</code>" })
                append("</p>")
            }
            if (contract.acceptanceCriteria.isNotEmpty()) {
                append("<p>xgrep accepts the fix only if:</p><ul>")
                contract.acceptanceCriteria.forEach { append("<li>").append(esc(it)).append("</li>") }
                append("</ul>")
            }
        } else if (f.hint.isNotBlank()) {
            append("<h4>").append(if (f.tier == FixTier.ADVISORY) "What to do" else "Hint").append("</h4>")
            append("<p>").append(esc(f.hint)).append("</p>")
        }

        val diff = f.outcome?.diff.orEmpty()
        if (diff.isNotBlank()) {
            append("<h4>Change</h4><pre>").append(esc(diff.trimEnd())).append("</pre>")
        }
        append("</body></html>")
    }

    private fun tierDescription(f: FixFinding): String = when {
        f.stale -> "The file changed since the scan. Scan again before fixing."
        f.tier == FixTier.DETERMINISTIC -> "Deterministic: xgrep applies the edit and re-scans to confirm it."
        f.tier == FixTier.ASSISTED ->
            "Agent-assisted: the coding agent writes the edit, and xgrep re-scans to confirm it."
        f.tier == FixTier.ADVISORY -> "Advisory: guidance only, nothing is applied."
        f.tier == FixTier.ECOSYSTEM ->
            "Dependency upgrade: xgrep runs the package manager, then checks the vulnerability is gone."
        else -> "This rule offers no fix."
    }

    private fun StringBuilder.row(label: String, valueHtml: String) {
        append(
            "<tr><td valign='top'><b>",
        ).append(esc(label)).append("</b></td><td>").append(valueHtml).append("</td></tr>")
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
