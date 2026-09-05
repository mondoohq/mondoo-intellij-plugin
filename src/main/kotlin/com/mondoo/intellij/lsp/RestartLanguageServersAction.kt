// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

// The platform renamed LspServer* to LspClient* in 2026.1.4, but the old names are
// the ones present in every build this plugin supports; see docs/adr/0001.
@file:Suppress("DEPRECATION")

package com.mondoo.intellij.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.mondoo.intellij.mql.CnspecLspServerSupportProvider
import com.mondoo.intellij.mql.MqlrLspServerSupportProvider

/**
 * Restarts all three Mondoo language servers.
 *
 * One action rather than three. The plugin now runs a server for code security, one
 * for MQL and one for LR, and "something is wedged, start it again" is a single intent
 * that should not require knowing which of them is at fault — which is usually the
 * thing you cannot tell from the outside.
 *
 * Distinct from **Reload Rules**, which is also a restart underneath but answers a
 * different question: "I edited a rule file, pick it up." Collapsing the two would
 * mean naming a recovery action after a routine one.
 *
 * Restarting a server that is not running is a no-op, so this does not need to know
 * which of the three are up.
 */
internal class RestartLanguageServersAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        restartAll(project)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(
                "Restarting the Mondoo language servers",
                "Any that were not running stay stopped until a file needs them.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    companion object {
        private val PROVIDERS: List<Class<out LspServerSupportProvider>> = listOf(
            XgrepLspServerSupportProvider::class.java,
            CnspecLspServerSupportProvider::class.java,
            MqlrLspServerSupportProvider::class.java,
        )

        fun restartAll(project: Project) {
            val manager = LspServerManager.getInstance(project)
            PROVIDERS.forEach { manager.stopAndRestartIfNeeded(it) }
        }
    }
}
