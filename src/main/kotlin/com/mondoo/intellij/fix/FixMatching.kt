// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

/**
 * Matches findings the editor knows (from the language server) to findings the fix
 * session knows (from the scan cache). Pure, so it is tested without the platform.
 *
 * The two do not always agree on the rule. Where two rules flag the same code — a
 * generic SQL injection rule and a Django-specific one, say — the language server
 * reports both and `xgrep scan` keeps one. So a target matches its exact rule first,
 * and otherwise the finding the scan reports on the same line of the same file,
 * preferring a fixable one. It is the code on that line the user asked to fix.
 */
object FixMatching {

    fun match(
        targets: List<FixTarget>,
        findings: List<FixFinding>,
        samePath: (String, String) -> Boolean,
    ): List<FixFinding> = targets.mapNotNull { t ->
        val onLine = findings.filter { it.startLine == t.line && samePath(it.absolutePath, t.absolutePath) }
        onLine.firstOrNull { it.ruleId == t.ruleId }
            ?: onLine.firstOrNull { it.fixable }
            ?: onLine.firstOrNull()
    }.distinctBy { it.fingerprint }
}
