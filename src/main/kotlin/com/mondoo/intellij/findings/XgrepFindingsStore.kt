// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.findings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin's own record of every xgrep finding in the project.
 *
 * IntelliJ has no equivalent of VS Code's `languages.getDiagnostics()`: LSP
 * diagnostics become per-file annotations that the daemon computes lazily, and
 * only for open editors. A workspace scan publishes findings for hundreds of
 * files nobody has opened, and those would simply never be seen. So the plugin
 * keeps its own store, fed from `publishDiagnostics`.
 *
 * Deliberately source-agnostic — [update] takes a URI and a list of findings —
 * so cnspec policy-lint can publish into the same store and the same tool window
 * later, the way `explainContextOf` in the VS Code extension already carries a
 * `source` discriminator.
 */
@Service(Service.Level.PROJECT)
class XgrepFindingsStore(private val project: Project) {

    /** Findings by document URI. Concurrent: publishes arrive off the EDT. */
    private val byUri = ConcurrentHashMap<String, List<Finding>>()

    /** Every finding in the project, ordered by path, line, column. */
    fun findings(): List<Finding> = byUri.values.flatten().sortedWith(ORDER)

    fun findingCount(): Int = byUri.values.sumOf { it.size }

    /** Findings at a position, used by the suppression and explain intentions. */
    fun findingsAt(path: String, line: Int): List<Finding> =
        byUri.values.asSequence().flatten().filter { it.path == path && it.line == line }.toList()

    /**
     * Replaces the findings for one document.
     *
     * An empty list is meaningful, not a no-op: it is how a server reports that a
     * file is now clean, and the tool window has to reflect that.
     */
    fun update(uri: String, findings: List<Finding>) {
        if (findings.isEmpty()) byUri.remove(uri) else byUri[uri] = findings
        publishChanged()
    }

    fun clear() {
        byUri.clear()
        publishChanged()
    }

    private fun publishChanged() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(TOPIC).findingsChanged(findingCount())
    }

    fun interface Listener {
        fun findingsChanged(total: Int)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): XgrepFindingsStore = project.service()

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Mondoo xgrep findings", Listener::class.java)

        private val ORDER: Comparator<Finding> =
            compareBy<Finding> { it.path }.thenBy { it.line }.thenBy { it.column }
    }
}
