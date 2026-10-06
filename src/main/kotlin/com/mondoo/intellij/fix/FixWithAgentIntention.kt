// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiFile
import com.mondoo.intellij.MondooIcons
import com.mondoo.intellij.findings.Finding
import com.mondoo.intellij.findings.XgrepFindingsStore
import com.mondoo.intellij.lsp.XgrepDiagnosticData

/**
 * Alt+Enter on a fixable finding: fixes it through `xgrep fix serve`, the same way
 * the Fix tab does, and opens that tab so the run, its diff and the agent's output
 * are in view.
 *
 * - Deterministic: "Fix with xgrep". The language server also offers its own
 *   "Apply xgrep fix", which writes the edit without the verification harness; this
 *   one is verified by a re-scan.
 * - Assisted: "Fix with coding agent (xgrep)".
 * - Advisory or no fix: not offered; there is nothing to apply.
 *
 * Availability comes from the findings store by file and line, like the suppress
 * intentions, so it needs no PSI and works in every IDE.
 */
internal class FixWithAgentIntention :
    IntentionAction,
    Iconable {

    override fun getFamilyName(): String = "Fix xgrep finding"

    // The platform asks for the text only after isAvailable, on the same caret, so
    // the finding it describes is the one isAvailable found.
    private var lastKind: String? = null

    override fun getText(): String =
        if (lastKind == XgrepDiagnosticData.FIX_KIND_ASSISTED) "Fix with coding agent (xgrep)" else "Fix with xgrep"

    override fun getIcon(flags: Int) = MondooIcons.Fix

    /** It starts a background run; nothing is written under this action. */
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val finding = fixableFindingAt(project, editor, file)
        lastKind = finding?.fixKind
        return finding != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val finding = fixableFindingAt(project, editor, file) ?: return
        val absolute = file?.virtualFile?.toNioPathOrNull()?.toString() ?: return
        FixSession.getInstance(project).fixFindings(listOf(FixTarget(absolute, finding.line + 1, finding.ruleId)))
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(
            if (lastKind == XgrepDiagnosticData.FIX_KIND_ASSISTED) {
                "Hands this finding to your coding agent. xgrep re-scans the file and reports the fix " +
                    "as applied only if the finding is gone. Opens the <b>Fix</b> tab, and the agent's " +
                    "run shows step by step in its <b>Run log</b>."
            } else {
                "Applies xgrep's fix and re-scans the file to confirm the finding is gone. Opens the " +
                    "<b>Fix</b> tab with the result."
            },
        )

    private fun fixableFindingAt(project: Project, editor: Editor?, file: PsiFile?): Finding? {
        if (editor == null || file == null) return null
        val absolute = file.virtualFile?.toNioPathOrNull() ?: return null
        val line = editor.caretModel.logicalPosition.line
        val path = project.basePath
            ?.let { runCatching { java.nio.file.Path.of(it).relativize(absolute).toString() }.getOrNull() }
            ?: absolute.toString()
        return XgrepFindingsStore.getInstance(project).findingsAt(path, line)
            .firstOrNull { it.fixKind in FIXABLE_KINDS }
    }

    private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): java.nio.file.Path? =
        runCatching { toNioPath() }.getOrNull()

    private companion object {
        val FIXABLE_KINDS = setOf(XgrepDiagnosticData.FIX_KIND_DETERMINISTIC, XgrepDiagnosticData.FIX_KIND_ASSISTED)
    }
}
