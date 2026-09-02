// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.mondoo.intellij.findings.XgrepFindingsStore

/**
 * Empties the findings view.
 *
 * Findings from a workspace scan persist until something replaces them, which is
 * usually what you want — but not after changing rules or scope, when the list is
 * a mix of old and new results and there is no other way to tell them apart.
 *
 * Clears the plugin's own view only. Editor highlights for open files belong to the
 * server and return on its next publish.
 */
class ClearFindingsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled =
            project != null && XgrepFindingsStore.getInstance(project).findingCount() > 0
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { XgrepFindingsStore.getInstance(it).clear() }
    }
}
