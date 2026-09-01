package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.XgrepBinaryService

class ShowXgrepPathAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            Messages.showWarningDialog(
                e.project,
                "No xgrep binary found. Set the path in Settings | Tools | Mondoo, or install xgrep.",
                "xgrep Path",
            )
        } else {
            Messages.showInfoMessage(e.project, "xgrep resolved to:\n$binary", "xgrep Path")
        }
    }
}
