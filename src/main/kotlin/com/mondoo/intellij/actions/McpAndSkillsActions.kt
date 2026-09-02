package com.mondoo.intellij.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.mcp.McpConfig
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
        val index = Messages.showChooseDialog(
            project,
            "Where should the xgrep MCP server be registered?",
            "Configure MCP",
            null,
            choices.toTypedArray(),
            choices.first(),
        )
        if (index < 0) return

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
 * Installs xgrep's bundled Claude Code skills.
 *
 * The skills ship inside the binary, so this is a thin wrapper around
 * `xgrep skill install --dir`, matching the VS Code extension (ADR-0004).
 */
class InstallAiSkillsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            Messages.showWarningDialog(project, "xgrep is not installed yet.", "Install AI Skills")
            return
        }

        val destinations = buildList {
            add(Path.of(System.getProperty("user.home"), ".claude", "plugins"))
            project?.basePath?.let { add(Path.of(it, ".claude", "plugins")) }
        }
        val labels = destinations.map { it.toString() }.toTypedArray()
        val index = Messages.showChooseDialog(
            project, "Install xgrep's AI skills where?", "Install AI Skills", null, labels, labels.first(),
        )
        if (index < 0) return
        val destination = destinations[index]

        object : Task.Backgroundable(project, "Installing xgrep AI skills", true) {
            override fun run(indicator: ProgressIndicator) {
                val command = GeneralCommandLine(binary.toString(), "skill", "install", "--dir", destination.toString())
                    .withWorkDirectory(project?.basePath)
                val output = CapturingProcessHandler(command).runProcess(SKILL_INSTALL_TIMEOUT_MS)
                ApplicationManager.getApplication().invokeLater {
                    val notifications = NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
                    if (output.exitCode == 0) {
                        val summary = output.stdout.lines().lastOrNull { it.isNotBlank() }
                            ?: "Skills installed into $destination"
                        notifications.createNotification(summary, NotificationType.INFORMATION).notify(project)
                    } else {
                        notifications.createNotification(
                            "Could not install skills: ${output.stderr.take(300)}",
                            NotificationType.ERROR,
                        ).notify(project)
                    }
                }
            }
        }.queue()
    }

    private companion object {
        const val SKILL_INSTALL_TIMEOUT_MS = 60_000
    }
}
