// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.target.CnspecShellCommand
import com.mondoo.intellij.target.TargetChooser
import com.mondoo.intellij.target.TargetCredentials
import com.mondoo.intellij.util.ProjectTrust
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Opens `cnspec shell` against a target, in the IDE's own terminal.
 *
 * The terminal rather than a console, because this is the one cnspec feature that is
 * genuinely interactive: you type MQL, read the answer, and type more. A `ConsoleView`
 * renders output but has no input line, so it cannot host a shell at all — the reason
 * every other cnspec run in this plugin uses one is that those are non-interactive.
 *
 * Lives in an optional module keyed on the Terminal plugin, the same arrangement as
 * the LSP features. Terminal is bundled in IntelliJ IDEA, GoLand and Android Studio —
 * checked, not assumed — but it is a plugin a user can disable, and this action should
 * be absent rather than broken where they have.
 */
internal class OpenCnspecShellAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            ProjectTrust.isTrusted(project) &&
            CnspecBinaryService.getInstance().resolvedBinaryOrNull() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!ProjectTrust.isTrusted(project)) return

        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull() ?: run {
            CnspecBinaryService.getInstance().notifyMissing(project)
            return
        }

        val target = TargetChooser.choose(project) ?: return

        val missing = target.missingRequired()
        if (missing.isNotEmpty()) {
            Messages.showWarningDialog(
                project,
                "${target.name} is missing: ${missing.joinToString(", ") { it.label }}",
                TITLE,
            )
            return
        }

        // Only whether a password exists, never the password itself: there is nowhere
        // on a shell command line it could safely go, so cnspec is asked to prompt.
        val hasPassword = TargetCredentials.forTarget(target).containsKey("password")
        val command = CnspecShellCommand.build(binary.toString(), target, hasPassword) ?: run {
            Messages.showWarningDialog(
                project,
                "${target.name} cannot be opened as a shell — it has no host or target to connect to.",
                TITLE,
            )
            return
        }

        runCatching {
            TerminalToolWindowManager.getInstance(project)
                .createLocalShellWidget(project.basePath, "cnspec: ${target.name}")
                .executeCommand(command)
        }.onFailure {
            LOG.warn("could not open a terminal for ${target.name}", it)
            Messages.showErrorDialog(project, "Could not open a terminal: ${it.message}", TITLE)
        }
    }

    private companion object {
        const val TITLE = "Open cnspec Shell"
        val LOG = Logger.getInstance(OpenCnspecShellAction::class.java)
    }
}
