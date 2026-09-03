// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.lsp.XgrepScanCoordinator

/**
 * Base for actions that need a running scanner.
 *
 * Enabled whenever a scanner binary is available, not only once the server happens
 * to be running. The server starts when the first supported file is opened, so
 * gating on it left the whole menu greyed out before then with nothing explaining
 * why — a dead menu reads as a broken plugin.
 */
abstract class XgrepScanActionBase : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !com.mondoo.intellij.util.ProjectTrust.isTrusted(project)) {
            e.presentation.isEnabled = false
            return
        }
        val available = XgrepBinaryService.getInstance().resolvedBinaryOrNull() != null
        e.presentation.isEnabled = available
        if (!available) {
            e.presentation.description = "The xgrep scanner is not installed. Run Set Up Scanner."
        }
    }

    /**
     * The running server, or null after telling the user how to get one. Actions call
     * this instead of failing silently.
     */
    protected fun requireServer(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        if (XgrepScanCoordinator.getInstance(project).runningServer() != null) return true
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(
                "The scanner starts with the first file it can scan. Open a file in a " +
                    "supported language, then try again.",
                NotificationType.INFORMATION,
            )
            .notify(project)
        return false
    }
}

class ScanWorkspaceAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        if (!requireServer(e)) return
        e.project?.let { XgrepScanCoordinator.getInstance(it).scanWorkspace() }
    }
}

class ScanChangedFilesAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        if (!requireServer(e)) return
        e.project?.let { XgrepScanCoordinator.getInstance(it).scanChanged() }
    }
}

/**
 * Scans only what changed since a git ref, for reviewing a branch against main
 * without scanning the whole tree.
 */
class ScanChangesSinceAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        if (!requireServer(e)) return
        val project = e.project ?: return
        val ref = Messages.showInputDialog(
            project,
            "Scan files changed since which git ref?",
            "Scan Changes Since",
            null,
            "main",
            null,
        )?.trim().orEmpty()
        if (ref.isEmpty()) return
        XgrepScanCoordinator.getInstance(project).scanChangedSince(ref)
    }
}
