package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.lsp.XgrepScanCoordinator

/** Base for actions that need a running scanner. */
abstract class XgrepScanActionBase : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled =
            project != null && XgrepScanCoordinator.getInstance(project).runningServer() != null
    }
}

class ScanWorkspaceAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { XgrepScanCoordinator.getInstance(it).scanWorkspace() }
    }
}

class ScanChangedFilesAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { XgrepScanCoordinator.getInstance(it).scanChanged() }
    }
}

/**
 * Scans only what changed since a git ref, for reviewing a branch against main
 * without scanning the whole tree.
 */
class ScanChangesSinceAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
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
