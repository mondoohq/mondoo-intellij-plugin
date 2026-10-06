// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent

/**
 * The run log on the Fix tab: what a fix run does, step by step.
 *
 * - xgrep's own steps (each deterministic fix);
 * - what the coding agent does, from its structured stream: the files it reads, the
 *   commands it runs, the files it changes, what it says;
 * - each finding's outcome, with a link to the file.
 *
 * One log, cleared per run, shown inside the Fix tab rather than as a tab of its own,
 * so fixing lives in one place.
 */
@Service(Service.Level.PROJECT)
class FixConsole(private val project: Project) : Disposable {

    private var view: ConsoleView? = null

    /**
     * Outcomes waiting to be printed as one line. A dependency upgrade reports one
     * outcome per vulnerability it cleared, all alike but for the advisory; printed
     * one by one, upgrading lodash alone filled ten lines.
     */
    private var pending: OutcomeGroup? = null

    private class OutcomeGroup(val first: Outcome) {
        val rules = mutableListOf(first.ruleId)
        fun matches(o: Outcome) = o.path == first.path &&
            o.status == first.status &&
            o.reason == first.reason &&
            o.detail == first.detail
    }
    private val runListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The log's component, created on first use. EDT only. */
    fun component(): JComponent = console().component

    /** [listener] runs on the EDT whenever a run starts, until [parent] is disposed. */
    fun onRunStarted(parent: Disposable, listener: () -> Unit) {
        runListeners += listener
        Disposer.register(parent) { runListeners -= listener }
    }

    fun startRun(findingCount: Int) = onEdt {
        pending = null
        console().clear()
        print(
            "Fixing $findingCount finding${if (findingCount == 1) "" else "s"}\n",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )
        runListeners.forEach { it() }
    }

    fun progress(message: String) = onEdt {
        flushOutcomes()
        print("  · $message\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
    }

    fun agentStarted(agent: AgentInfo, findingCount: Int) = onEdt {
        flushOutcomes()
        val what = if (findingCount == 1) "1 finding" else "$findingCount findings"
        print("\nHanding $what to ${agent.name}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        print("  $ ${agent.commandLine}\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
    }

    fun activity(a: AgentActivity) = onEdt {
        flushOutcomes()
        when (a.kind) {
            ActivityKind.TOOL -> print(
                "  ▸ ${listOf(a.tool, a.target).filter {
                    it.isNotBlank()
                }.joinToString(" ")}\n",
                ConsoleViewContentType.LOG_INFO_OUTPUT,
            )
            ActivityKind.FILE_CHANGE -> {
                print("  ✎ ", ConsoleViewContentType.LOG_INFO_OUTPUT)
                fileLink(a.target, 0)
                print("\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
            }
            ActivityKind.MESSAGE -> print("\n${FixText.plain(a.text)}\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
            ActivityKind.FINISHED -> {
                val took = FixText.finished(a)
                if (a.text.isNotBlank()) {
                    print("  $took: ${a.text}\n", ConsoleViewContentType.ERROR_OUTPUT)
                } else {
                    print("  $took\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
                }
            }
            ActivityKind.OUTPUT -> print("${a.text}\n", ConsoleViewContentType.NORMAL_OUTPUT)
            ActivityKind.DENIED -> print(
                "  ✗ ${a.tool} not allowed${if (a.text.isNotBlank()) ": ${a.text}" else ""}\n",
                ConsoleViewContentType.LOG_WARNING_OUTPUT,
            )
        }
    }

    fun outcome(o: Outcome) = onEdt {
        val group = pending
        if (group != null && group.matches(o)) {
            group.rules += o.ruleId
        } else {
            flushOutcomes()
            pending = OutcomeGroup(o)
        }
    }

    private fun flushOutcomes() {
        val group = pending ?: return
        pending = null
        val o = group.first
        val (mark, type) = when {
            o.applied -> "✓ Fixed " to ConsoleViewContentType.SYSTEM_OUTPUT
            o.rejected -> "✗ Not fixed " to ConsoleViewContentType.ERROR_OUTPUT
            else -> "• Skipped " to ConsoleViewContentType.LOG_INFO_OUTPUT
        }
        print(mark + FixText.findings(group.rules) + " in ", type)
        fileLink(o.path, 0)
        val why = listOf(o.reason, o.detail).filter { it.isNotBlank() }.joinToString(": ")
        print(if (why.isNotEmpty()) " — $why\n" else "\n", type)
    }

    fun done(d: RunFixEvent.Done) = onEdt {
        flushOutcomes()
        print("\n${FixText.summary(d)}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /** The run ended without a summary from the server. */
    fun ended(cancelled: Boolean) = onEdt {
        flushOutcomes()
        if (cancelled) print("\nCancelled\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun error(message: String) = onEdt {
        flushOutcomes()
        print("\n$message\n", ConsoleViewContentType.ERROR_OUTPUT)
    }

    private fun print(text: String, type: ConsoleViewContentType) = console().print(text, type)

    /** [path] as a link to the file when it exists, else as text. */
    private fun fileLink(path: String, line: Int) {
        val base = project.basePath
        val file = runCatching {
            val p = Path.of(path).let { if (it.isAbsolute || base == null) it else Path.of(base).resolve(it) }
            LocalFileSystem.getInstance().findFileByNioFile(p.normalize())
        }.getOrNull()
        if (file == null) {
            print(path, ConsoleViewContentType.NORMAL_OUTPUT)
        } else {
            console().printHyperlink(path, OpenFileHyperlinkInfo(project, file, line))
        }
    }

    private fun console(): ConsoleView = view ?: TextConsoleBuilderFactory.getInstance()
        .createBuilder(project).console
        .also {
            view = it
            Disposer.register(this, it)
        }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ block() }, project.disposed)
    }

    override fun dispose() {
        view = null
    }

    companion object {
        fun getInstance(project: Project): FixConsole = project.service()
    }
}

/** Text shaping for the run log. Pure, so it is tested without the platform. */
object FixText {

    /**
     * The agent writes Markdown; a console shows it verbatim. Strips what reads as
     * noise there — code fences, bold and inline-code markers — and indents code
     * blocks so they still stand apart.
     */
    fun plain(markdown: String): String {
        var inCode = false
        return markdown.lines().mapNotNull { line ->
            if (line.trimStart().startsWith("```")) {
                inCode = !inCode
                return@mapNotNull null
            }
            if (inCode) {
                "    $line"
            } else {
                line.replace("**", "").replace(Regex("(?<!`)`([^`]+)`(?!`)"), "$1")
                    .replace(Regex("^#{1,6}\\s+"), "")
            }
        }.joinToString("\n").trim()
    }

    fun finished(a: AgentActivity): String = buildString {
        append("agent finished")
        if (a.durationMs > 0) append(" in %.1fs".format(java.util.Locale.ROOT, a.durationMs / 1000.0))
        if (a.costUsd > 0) append(" · $%.2f".format(java.util.Locale.ROOT, a.costUsd))
    }

    /** "CVE-1", "CVE-1 and CVE-2", "10 findings (CVE-1, CVE-2 and 8 more)". */
    fun findings(rules: List<String>): String {
        val distinct = rules.distinct()
        return when (distinct.size) {
            1 -> distinct[0]
            2 -> "${distinct[0]} and ${distinct[1]}"
            else -> "${distinct.size} findings (${distinct[0]}, ${distinct[1]} and ${distinct.size - 2} more)"
        }
    }

    fun summary(d: RunFixEvent.Done): String = buildString {
        append("Done: ${d.applied} fixed")
        if (d.rejected > 0) append(", ${d.rejected} not fixed")
        if (d.skipped > 0) append(", ${d.skipped} skipped")
        if (d.cancelled) append(" (cancelled)")
    }
}
