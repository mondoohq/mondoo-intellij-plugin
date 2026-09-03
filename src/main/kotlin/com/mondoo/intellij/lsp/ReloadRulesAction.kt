// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

// The platform renamed LspServer* to LspClient* in 2026.1.4, but the old names are
// the ones present in every build this plugin supports, and untilBuild is open. The
// registration reason is the same as in XgrepLspServerSupportProvider; see
// docs/adr/0001.
@file:Suppress("DEPRECATION")

package com.mondoo.intellij.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager

/**
 * Reloads the rule set.
 *
 * Implemented as a server restart, because the scanner exposes no reload command —
 * it advertises exactly four (`scanWorkspace`, `scanChanged`, `search`,
 * `exportRule`). Rules are read at startup from the configured rules path, so
 * restarting is what picks up an edited rule file, and it is what the action has to
 * do until a reload command exists.
 *
 * Lives in the optional LSP module: reloading rules is meaningless without a
 * running server, so the action should not appear where the module did not load.
 */
internal class ReloadRulesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        restart(project)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification("Reloading xgrep rules", NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        /** Restarts the scanner so it re-reads its rules and settings. */
        fun restart(project: Project) {
            LspServerManager.getInstance(project)
                .stopAndRestartIfNeeded(XgrepLspServerSupportProvider::class.java)
        }
    }
}
