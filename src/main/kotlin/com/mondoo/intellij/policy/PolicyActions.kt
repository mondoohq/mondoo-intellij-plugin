// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.mql.MqlFiles
import com.mondoo.intellij.util.ProjectTrust
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Base for actions that operate on the policy bundle in the editor. */
abstract class PolicyBundleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null &&
            file != null &&
            MqlFiles.isPolicyBundle(file.name) &&
            ProjectTrust.isTrusted(project) &&
            CnspecBinaryService.getInstance().resolvedBinaryOrNull() != null
    }

    protected fun bundle(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { MqlFiles.isPolicyBundle(it.name) }

    protected fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(content, type).notify(project)
    }
}

/**
 * Lints the bundle and reports what it found.
 *
 * The linter checks policy hygiene the language server does not — required tags,
 * asset filters, unused queries — so this surfaces problems that never appear as you
 * type.
 */
class LintPolicyAction : PolicyBundleAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = bundle(e) ?: return
        val path = file.toNioPath()

        object : Task.Backgroundable(project, "Linting ${file.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val findings = PolicyLintService.getInstance(project).lint(path)
                ApplicationManager.getApplication().invokeLater {
                    when {
                        findings == null -> notify(
                            project,
                            "Could not lint ${file.name}. Check that cnspec is installed.",
                            NotificationType.WARNING,
                        )
                        findings.isEmpty() -> notify(
                            project,
                            "${file.name} is clean.",
                            NotificationType.INFORMATION,
                        )
                        else -> {
                            val errors = findings.count { it.severity == LintSeverity.ERROR }
                            val notification = NotificationGroupManager.getInstance()
                                .getNotificationGroup("Mondoo")
                                .createNotification(
                                    "${file.name}: ${findings.size} lint finding(s), $errors error(s)",
                                    if (errors > 0) NotificationType.WARNING else NotificationType.INFORMATION,
                                )
                            notification.addAction(
                                NotificationAction.createSimpleExpiring("Show") {
                                    ToolWindowManager.getInstance(project)
                                        .getToolWindow("Mondoo")?.activate(null)
                                },
                            )
                            notification.notify(project)
                        }
                    }
                }
            }
        }.queue()
    }
}

/**
 * Applies cnspec's own formatting to the bundle.
 *
 * Formatting rewrites the file on disk, so the editor's buffer is saved first and
 * refreshed after — otherwise the IDE would hold a stale copy and silently undo the
 * change on the next save.
 */
open class FormatPolicyAction(private val sort: Boolean = false) : PolicyBundleAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = bundle(e) ?: return
        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull() ?: return

        // The formatter reads from disk, so unsaved edits would be silently discarded.
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().getDocument(file)?.let {
                FileDocumentManager.getInstance().saveDocument(it)
            }
        }

        object : Task.Backgroundable(project, "Formatting ${file.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val args = buildList {
                    add("policy")
                    add("format")
                    if (sort) add("--sort")
                    add(file.name)
                }
                val command = GeneralCommandLine(binary.toString())
                    .withParameters(args)
                    .withWorkDirectory(file.parent?.path)

                val output = CapturingProcessHandler(command).runProcess(FORMAT_TIMEOUT_MS, true)
                ApplicationManager.getApplication().invokeLater {
                    if (output.exitCode == 0) {
                        // Pick up what the formatter wrote underneath the editor.
                        file.refresh(false, false)
                        notify(project, "Formatted ${file.name}", NotificationType.INFORMATION)
                    } else {
                        notify(
                            project,
                            "Could not format ${file.name}: " +
                                output.stderr.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(200),
                            NotificationType.ERROR,
                        )
                    }
                }
            }
        }.queue()
    }

    private companion object {
        const val FORMAT_TIMEOUT_MS = 60_000
    }
}

/** The platform instantiates actions with a no-argument constructor. */
class FormatPolicySortedAction : FormatPolicyAction(sort = true)

/**
 * Scaffolds a bundle with `cnspec policy init` and opens it.
 *
 * The on-ramp to writing a policy at all: the format is a nested YAML shape nobody
 * types from memory, and the example cnspec produces is a working policy rather than
 * an empty file.
 *
 * Unlike the other policy actions this is not tied to the focused file — there may not
 * be one yet, which is rather the point.
 */
class NewPolicyFromTemplateAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            ProjectTrust.isTrusted(project) &&
            CnspecBinaryService.getInstance().resolvedBinaryOrNull() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull() ?: run {
            CnspecBinaryService.getInstance().notifyMissing(project)
            return
        }
        val directory = project.guessProjectDir()?.toNioPathOrNull() ?: run {
            Messages.showWarningDialog(project, "This project has no directory to write to.", TITLE)
            return
        }

        val name = Messages.showInputDialog(
            project,
            "File name for the new policy bundle:",
            TITLE,
            null,
            PolicyTemplate.DEFAULT_NAME,
            object : InputValidator {
                override fun checkInput(input: String?) = PolicyTemplate.validateName(input.orEmpty()) == null
                override fun canClose(input: String?) = checkInput(input)
            },
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        val target = directory.resolve(name)
        if (Files.exists(target)) {
            // Not an overwrite prompt: cnspec refuses to overwrite, so offering the
            // choice would be offering something that cannot be honoured.
            Messages.showWarningDialog(
                project,
                "$name already exists, and cnspec will not overwrite it.\n\n" +
                    "Choose another name, or remove the file first.",
                TITLE,
            )
            return
        }

        object : Task.Backgroundable(project, "Creating $name", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val command = GeneralCommandLine(binary.toString())
                    // The name is one argv token and never reaches a shell, and it has
                    // already been refused if it contains a separator.
                    .withParameters("policy", "init", name)
                    .withWorkDirectory(directory.toString())
                    .withCharset(StandardCharsets.UTF_8)

                val output = CapturingProcessHandler(command).runProcess(INIT_TIMEOUT_MS, true)

                // Not the exit code: cnspec exits 0 when it fails to write. Whether a
                // file appeared is the only honest signal.
                val created = Files.exists(target)
                ApplicationManager.getApplication().invokeLater {
                    if (created) {
                        openCreated(project, target, name)
                    } else {
                        val reason = PolicyTemplate.failureReason(output.stdout, output.stderr)
                            ?: "cnspec wrote no file and reported nothing."
                        Messages.showErrorDialog(project, "Could not create $name.\n\n$reason", TITLE)
                    }
                }
            }
        }.queue()
    }

    private fun openCreated(project: Project, target: java.nio.file.Path, name: String) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        if (file == null) {
            Messages.showWarningDialog(project, "Created $name, but it could not be opened.", TITLE)
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        // The new bundle belongs in the Policies tab straight away; the VFS event
        // would normally do this, and asking directly costs nothing if it already has.
        PolicyIndexService.getInstance(project).refresh()
    }

    private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): java.nio.file.Path? =
        runCatching { toNioPath() }.getOrNull()

    private companion object {
        const val TITLE = "New Policy from Template"

        /** Generous: the first run may install a provider before it writes anything. */
        const val INIT_TIMEOUT_MS = 60_000
    }
}
