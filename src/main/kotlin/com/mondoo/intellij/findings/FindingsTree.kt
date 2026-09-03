// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.findings

/**
 * The findings tree model.
 *
 * IntelliJ has no project-wide diagnostics query: LSP diagnostics become per-file
 * annotations that the daemon computes lazily for open editors only. A workspace
 * scan publishes findings for hundreds of files nobody has opened, and those would
 * simply vanish. So the plugin keeps its own store, and this is the pure grouping
 * over it.
 *
 * Ported from `buildFindingsTree` / `buildBySeverity` / `buildByFile` /
 * `compareFindings` in vscode-mondoo/src/providers/xgrepFindingsTreeProvider.ts,
 * preserving the ordering rules exactly so both editors present findings the same way.
 *
 * Pure: no platform types.
 */

/** xgrep emits Error/Warning/Information; Hint folds into Low for completeness. */
enum class FindingSeverity(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    ;

    companion object {
        /** Maps an LSP `DiagnosticSeverity` integer (1..4) to a bucket. */
        fun fromLsp(severity: Int?): FindingSeverity = when (severity) {
            1 -> HIGH
            2 -> MEDIUM
            else -> LOW // Information (3) and Hint (4)
        }
    }
}

data class Finding(
    /** Workspace-relative path, used for display and ordering. */
    val path: String,
    val line: Int,
    val column: Int,
    val ruleId: String,
    val message: String,
    val severity: FindingSeverity,
)

sealed interface FindingsNode {
    data class Group(
        val label: String,
        val count: Int,
        val severity: FindingSeverity?,
        val children: List<FindingsNode>,
    ) : FindingsNode

    data class Leaf(val finding: Finding) : FindingsNode
}

enum class GroupMode { SEVERITY, FILE }

object FindingsTree {

    fun build(findings: List<Finding>, mode: GroupMode): List<FindingsNode> =
        when (mode) {
            GroupMode.FILE -> byFile(findings)
            GroupMode.SEVERITY -> bySeverity(findings)
        }

    /** Severity bucket → rule, rules ordered by descending count then rule id. */
    private fun bySeverity(findings: List<Finding>): List<FindingsNode> =
        FindingSeverity.entries.mapNotNull { severity ->
            val inBucket = findings.filter { it.severity == severity }
            if (inBucket.isEmpty()) return@mapNotNull null

            val rules = inBucket.groupBy { it.ruleId }
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<Finding>>> { it.value.size }
                        .thenBy { it.key },
                )
                .map { (ruleId, items) ->
                    FindingsNode.Group(
                        label = ruleId,
                        count = items.size,
                        severity = severity,
                        children = items.sortedWith(ORDER).map(FindingsNode::Leaf),
                    )
                }

            FindingsNode.Group(severity.label, inBucket.size, severity, rules)
        }

    /** File → findings, files ordered by path. */
    private fun byFile(findings: List<Finding>): List<FindingsNode> =
        findings.groupBy { it.path }
            .entries
            .sortedBy { it.key }
            .map { (path, items) ->
                FindingsNode.Group(
                    label = path,
                    count = items.size,
                    severity = null,
                    children = items.sortedWith(ORDER).map(FindingsNode::Leaf),
                )
            }

    /** Path, then line, then column — the same order as `compareFindings`. */
    private val ORDER: Comparator<Finding> =
        compareBy<Finding> { it.path }.thenBy { it.line }.thenBy { it.column }
}
