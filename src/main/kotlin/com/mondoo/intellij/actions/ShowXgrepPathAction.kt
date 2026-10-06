// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.binary.MqlrBinaryService
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.lsp.LspAvailability
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent

/**
 * Reports which binary each feature resolved to.
 *
 * All three at once rather than an action each. "Which xgrep am I running" and "why is
 * there no MQL support" are the same question asked about different tools, and the
 * answer to either is more useful next to the others — a machine with two cnspecs on
 * the PATH is exactly the situation this exists to expose.
 *
 * The action id still says Xgrep because that is what it was; renaming it would break
 * anyone's keymap for no gain.
 */
class ShowXgrepPathAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val tools = listOf(
            Tool(
                "xgrep",
                "Code security scanning and fixing",
                XgrepBinaryService.getInstance().resolvedBinaryOrNull(),
                "version",
            ),
            Tool(
                "cnspec",
                "Policies, MQL and infrastructure scans",
                CnspecBinaryService.getInstance().resolvedBinaryOrNull(),
                "version",
            ),
            Tool("mqlr", "LR resource definitions", MqlrBinaryService.getInstance().resolvedBinaryOrNull(), null),
        )
        ToolPathsDialog(e.project, tools).show()
    }
}

internal data class Tool(val name: String, val purpose: String, val path: Path?, val versionArg: String?)

/** One row per tool: whether it was found, what it is for, where it is, which version. */
private class ToolPathsDialog(private val project: Project?, private val tools: List<Tool>) : DialogWrapper(project) {

    private val versionLabels = tools.associateWith {
        JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    }

    init {
        title = "Mondoo Tool Paths"
        init()
        loadVersions()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = panel {
        tools.forEach { tool ->
            row {
                icon(if (tool.path != null) AllIcons.General.InspectionsOK else AllIcons.General.Warning)
                label(tool.name).bold()
                cell(versionLabels.getValue(tool))
            }
            indent {
                row { comment(tool.purpose) }
                row {
                    if (tool.path == null) {
                        label("Not found").applyToComponent { foreground = UIUtil.getErrorForeground() }
                    } else {
                        cell(
                            JBLabel(ToolPaths.display(tool.path.toString())).apply {
                                setCopyable(true)
                                toolTipText = tool.path.toString()
                            },
                        )
                        link("Copy") {
                            CopyPasteManager.getInstance().setContents(StringSelection(tool.path.toString()))
                        }
                    }
                }.bottomGap(com.intellij.ui.dsl.builder.BottomGap.SMALL)
            }
        }
        separator()
        row("Live scanning:") {
            // Whether live scanning is possible at all, which is a different question
            // from whether a binary exists: the LSP client API is an optional platform
            // module, and where it is absent the scanners still run on demand but
            // nothing appears as you type. The first thing worth knowing in a "no
            // findings" report.
            if (LspAvailability.isPresent()) {
                label("available")
            } else {
                label("unavailable").comment("This IDE has no LSP client, so findings appear only on an explicit scan.")
            }
        }
        row {
            link("Choose paths in Settings | Tools | Mondoo") {
                close(OK_EXIT_CODE)
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Mondoo")
            }
        }
    }.apply { border = JBUI.Borders.empty(4, 4, 0, 4) }

    /** Versions come from the binaries themselves, so they are read off the EDT. */
    private fun loadVersions() {
        val modality = ModalityState.stateForComponent(contentPane)
        tools.filter { it.path != null && it.versionArg != null }.forEach { tool ->
            versionLabels.getValue(tool).text = "…"
            ApplicationManager.getApplication().executeOnPooledThread {
                val version = runCatching {
                    val out = ExecUtil.execAndGetOutput(
                        GeneralCommandLine(tool.path.toString(), tool.versionArg!!).withCharset(Charsets.UTF_8),
                        VERSION_TIMEOUT_MS,
                    )
                    ToolPaths.version(out.stdout + "\n" + out.stderr)
                }.getOrNull()
                ApplicationManager.getApplication().invokeLater({
                    versionLabels.getValue(tool).text = version?.let { "v$it" }.orEmpty()
                }, modality)
            }
        }
    }

    private companion object {
        const val VERSION_TIMEOUT_MS = 5_000
    }
}

/** Display helpers for tool paths and versions. Pure, so they are tested without the platform. */
object ToolPaths {

    /** The path with the home directory shown as `~`, which is most of what makes them long. */
    fun display(path: String, home: String = System.getProperty("user.home")): String =
        if (home.isNotEmpty() && (path == home || path.startsWith("$home/"))) "~" + path.removePrefix(home) else path

    /** The first version number in a tool's `version` output, e.g. `cnspec 14.3.0 (…)` → `14.3.0`. */
    fun version(output: String): String? = Regex("""\b\d+\.\d+\.\d+(?:[-+][\w.]+)?""").find(output)?.value
}
