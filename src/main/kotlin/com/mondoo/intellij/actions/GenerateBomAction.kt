// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.util.ProjectTrust

/**
 * Generates a bill of materials for the project.
 *
 * One action rather than one per kind: the scanner merges the selected kinds into a
 * single document, so the choice is a step in the flow rather than three menu
 * entries that mostly repeat each other.
 */
class GenerateBomAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            ProjectTrust.isTrusted(project) &&
            XgrepBinaryService.getInstance().resolvedBinaryOrNull() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        com.mondoo.intellij.bom.GenerateBomDialog(project).show()
    }
}
