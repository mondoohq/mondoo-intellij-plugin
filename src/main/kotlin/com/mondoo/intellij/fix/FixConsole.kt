// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory

/**
 * The "xgrep fix" console tab: what the coding agent prints while it works, and
 * each finding's outcome as it lands.
 *
 * The terminal UI shows only a spinner while the agent runs; here the agent's own
 * output streams live, so a long assisted fix is something to watch rather than wait
 * on. One tab, reused and cleared per run.
 */
@Service(Service.Level.PROJECT)
class FixConsole(private val project: Project) : Disposable {

    private var console: ConsoleView? = null
    private var content: Content? = null

    fun startRun() = onEdt {
        val view = ensureTab() ?: return@onEdt
        view.clear()
        view.print("Starting xgrep fix...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun agentStarted(agent: AgentInfo) = onEdt {
        console?.print("\n$ ${agent.commandLine}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        // Bring the console forward only when there is something to watch.
        content?.let { it.manager?.setSelectedContent(it) }
    }

    fun agentOutput(text: String) = onEdt {
        console?.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    fun outcome(outcome: Outcome) = onEdt {
        val type = when {
            outcome.applied -> ConsoleViewContentType.SYSTEM_OUTPUT
            outcome.rejected -> ConsoleViewContentType.ERROR_OUTPUT
            else -> ConsoleViewContentType.LOG_INFO_OUTPUT
        }
        val why = listOf(outcome.reason, outcome.detail).filter { it.isNotBlank() }.joinToString(": ")
        console?.print(
            "[${outcome.status}] ${outcome.ruleId} ${outcome.path}${if (why.isNotEmpty()) " — $why" else ""}\n",
            type,
        )
    }

    fun error(message: String) = onEdt {
        console?.print("\n$message\n", ConsoleViewContentType.ERROR_OUTPUT)
    }

    private fun ensureTab(): ConsoleView? {
        val window = ToolWindowManager.getInstance(project).getToolWindow("Mondoo") ?: return null
        val manager = window.contentManager
        val existing = content
        if (existing != null && manager.getIndexOfContent(existing) >= 0) return console

        val view = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val tab = ContentFactory.getInstance().createContent(view.component, TAB_TITLE, false)
        // The tab owns the console: closing it, or the project, disposes it.
        tab.setDisposer(view)
        Disposer.register(tab, {
            if (content === tab) {
                content = null
                console = null
            }
        })
        manager.addContent(tab)
        console = view
        content = tab
        return view
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ block() }, project.disposed)
    }

    override fun dispose() = Unit

    companion object {
        const val TAB_TITLE = "xgrep fix"

        fun getInstance(project: Project): FixConsole = project.service()
    }
}
