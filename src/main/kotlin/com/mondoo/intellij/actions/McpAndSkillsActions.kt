// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.mcp.McpConfig
import com.mondoo.intellij.skills.MondooSkills
import com.mondoo.intellij.util.MondooDialogs
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offers xgrep to AI agents over MCP.
 *
 * There is no public API to register an external MCP server with AI Assistant or
 * Junie, so this writes the config files whose locations are known (Claude Code)
 * and puts the JSON on the clipboard for the ones that are only editable through
 * a settings dialog.
 */
class ConfigureMcpAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            Messages.showWarningDialog(project, "xgrep is not installed yet.", "Configure MCP")
            return
        }

        val projectConfig = project?.basePath?.let { Path.of(it, ".mcp.json") }
        val choices = buildList {
            if (projectConfig != null) add("This project (.mcp.json)")
            add("Copy the JSON to the clipboard")
        }
        val index = MondooDialogs.choose(
            project,
            "Where should the xgrep MCP server be registered?",
            "Configure MCP",
            choices,
        ) ?: return

        val json = McpConfig.serverEntryJson(binary.toString())
        if (projectConfig != null && index == 0) {
            val existing = runCatching { Files.readString(projectConfig) }.getOrNull()
            val merged = McpConfig.merge(existing, binary.toString())
            if (merged == null) {
                // Refuse rather than clobber a config we cannot parse.
                Messages.showWarningDialog(
                    project,
                    "${projectConfig.fileName} exists but could not be parsed, so it was left alone.\n" +
                        "The entry has been copied to the clipboard instead.",
                    "Configure MCP",
                )
                CopyPasteManager.copyTextToClipboard(json)
                return
            }
            runCatching { Files.writeString(projectConfig, merged) }
                .onSuccess { notify(project, "Registered the xgrep MCP server in ${projectConfig.fileName}") }
                .onFailure { Messages.showErrorDialog(project, it.message, "Configure MCP") }
        } else {
            CopyPasteManager.copyTextToClipboard(json)
            notify(project, "MCP server JSON copied. Paste it into Settings | Tools | AI Assistant | MCP.")
        }
    }

    private fun notify(project: Project?, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(content, NotificationType.INFORMATION).notify(project)
    }
}

/**
 * Installs Mondoo's agent skills from https://github.com/mondoohq/skills.
 *
 * The skills live in their own repository and are installed through Claude Code's
 * plugin marketplace, not from the scanner binary. Registering the marketplace only
 * makes them available; each skill is installed individually, so this offers a
 * choice rather than pulling all seven.
 *
 * Falls back to copying the commands when the `claude` CLI is not on PATH, since
 * they work verbatim inside an agent session too.
 */
class InstallAiSkillsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val skills = MondooSkills.ALL

        val index = MondooDialogs.choose(
            project,
            "Install which Mondoo agent skill?",
            "Install AI Skills",
            skills.map { "${it.title} — ${it.description}" },
        ) ?: return
        val skill = skills[index]

        val claude = com.intellij.execution.configurations.PathEnvironmentVariableUtil
            .findInPath(
                if (com.intellij.util.system.OS.CURRENT ==
                    com.intellij.util.system.OS.Windows
                ) {
                    "claude.exe"
                } else {
                    "claude"
                },
            )
        if (claude == null) {
            // No CLI: hand over the commands, which work inside an agent session.
            CopyPasteManager.copyTextToClipboard(MondooSkills.slashCommands(listOf(skill)))
            Messages.showInfoMessage(
                project,
                "The Claude Code CLI was not found on your PATH.\n\n" +
                    "The commands to install ${skill.title} have been copied to the clipboard; " +
                    "paste them into your agent session.\n\n" +
                    "Skills: ${MondooSkills.REPOSITORY_URL}",
                "Install AI Skills",
            )
            return
        }

        object : Task.Backgroundable(project, "Installing the ${skill.title} skill", true) {
            override fun run(indicator: ProgressIndicator) {
                // Registering the marketplace is idempotent and required before the
                // install, which otherwise fails with "Marketplace not found".
                val steps = listOf(
                    MondooSkills.marketplaceAddArgs(),
                    MondooSkills.installArgs(skill),
                )
                for (args in steps) {
                    val command = GeneralCommandLine(claude.absolutePath)
                        .withParameters(args)
                        .withWorkDirectory(project?.basePath)
                    val output = CapturingProcessHandler(command).runProcess(SKILL_INSTALL_TIMEOUT_MS)
                    if (output.exitCode != 0) {
                        notify(
                            project,
                            "Could not install ${skill.title}: ${output.stderr.take(300)}",
                            NotificationType.ERROR,
                        )
                        return
                    }
                }
                notify(
                    project,
                    "Installed the ${skill.title} skill from ${MondooSkills.MARKETPLACE}",
                    NotificationType.INFORMATION,
                )
            }
        }.queue()
    }

    private fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(content, type).notify(project)
    }

    private companion object {
        const val SKILL_INSTALL_TIMEOUT_MS = 120_000
    }
}
