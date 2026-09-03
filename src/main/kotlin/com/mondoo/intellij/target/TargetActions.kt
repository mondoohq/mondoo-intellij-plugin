// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.util.MondooDialogs
import com.mondoo.intellij.util.ProjectTrust

/** Base for actions that run cnspec against something. */
abstract class CnspecTargetAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ProjectTrust.isTrusted(project)
    }

    /** Resolves cnspec, explaining how to install it when absent. */
    protected fun requireCnspec(project: Project): Boolean {
        if (CnspecBinaryService.getInstance().resolvedBinaryOrNull() != null) return true
        CnspecBinaryService.getInstance().notifyMissing(project)
        return false
    }

    /**
     * Asks which target to use.
     *
     * "This machine" is always offered and needs no configuration, so the feature is
     * usable before anyone has set a target up.
     */
    protected fun chooseTarget(project: Project): TargetConfiguration? {
        val configured = TargetStore.getInstance(project).targets()
        val choices = listOf(TargetConfiguration("This machine", TargetType.LOCAL)) + configured

        if (choices.size == 1) return choices.single()

        return MondooDialogs.chooseFrom(project, "Which target?", "Mondoo", choices) { target ->
            if (target.type == TargetType.LOCAL && target.name == "This machine") {
                target.name
            } else {
                "${target.name} — ${target.type.title}"
            }
        }
    }
}

/** Runs a policy scan against a target. */
class ScanTargetAction : CnspecTargetAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!requireCnspec(project)) return
        val target = chooseTarget(project) ?: return

        val missing = target.missingRequired()
        if (missing.isNotEmpty()) {
            Messages.showWarningDialog(
                project,
                "${target.name} is missing: ${missing.joinToString(", ") { it.label }}",
                "Scan Target",
            )
            return
        }
        CnspecRunService.getInstance(project).scan(target)
    }
}

/**
 * Runs a single MQL query against a target.
 *
 * The fastest way to answer "what does this resource actually return here?", which is
 * most of policy authoring.
 */
class RunQueryAction : CnspecTargetAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!requireCnspec(project)) return

        val mql = Messages.showInputDialog(
            project,
            "MQL to run:",
            "Run MQL Query",
            null,
            selectedQuery(e) ?: "asset.platform",
            null,
        )?.trim().orEmpty()
        if (mql.isEmpty()) return

        val target = chooseTarget(project) ?: return
        CnspecRunService.getInstance(project).runQuery(target, mql)
    }

    /** Seeds the prompt with the editor selection, so running a line is two clicks. */
    private fun selectedQuery(e: AnActionEvent): String? =
        e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
            ?.selectionModel?.selectedText?.trim()?.takeIf { it.isNotEmpty() && !it.contains('\n') }
}
