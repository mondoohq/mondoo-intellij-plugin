// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.deps.DependencyReachabilityService
import com.mondoo.intellij.util.ProjectTrust

/**
 * Builds the dependency reachability graph and shows it.
 *
 * Opens the tool window as well as starting the analysis: an action that produces a
 * result somewhere the user is not looking is an action that appears to do nothing.
 */
class AnalyzeDependenciesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            ProjectTrust.isTrusted(project) &&
            XgrepBinaryService.getInstance().resolvedBinaryOrNull() != null &&
            !DependencyReachabilityService.getInstance(project).isRunning()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Mondoo")?.let { window ->
            window.activate {
                window.contentManager.contents
                    .firstOrNull { it.displayName == "Dependencies" }
                    ?.let { window.contentManager.setSelectedContent(it) }
            }
        }
        DependencyReachabilityService.getInstance(project).refresh()
    }
}
