// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.mondoo.intellij.findings.XgrepFindingsToolWindowFactory

/**
 * Tools | Mondoo Code Security | Fix Findings...: opens the Fix tab. The menu is where
 * people look for what the plugin can do, so fixing has to be listed there and not
 * only behind a tab.
 */
class OpenFixTabAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        XgrepFindingsToolWindowFactory.showFixTab(project) { it.onShown() }
    }
}
