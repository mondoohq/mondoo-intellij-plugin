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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.mql.MqlFiles
import com.mondoo.intellij.util.ProjectTrust

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
                            project, "${file.name} is clean.", NotificationType.INFORMATION,
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
