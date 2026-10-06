// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.mondoo.intellij.findings.XgrepFindingsStore
import com.mondoo.intellij.lsp.XgrepDiagnosticData

/**
 * Alt+Enter on an agent-assisted finding: hands it to the coding agent through
 * `xgrep fix serve`, the same way the Fix tab does.
 *
 * Only for assisted findings. A deterministic one already has the language
 * server's own quick fix, and an advisory one has nothing to apply.
 *
 * Availability comes from the findings store by file and line, like the suppress
 * intentions, so it needs no PSI and works in every IDE.
 */
internal class FixWithAgentIntention : IntentionAction {

    override fun getFamilyName(): String = "Fix xgrep finding with coding agent"

    override fun getText(): String = "Fix with coding agent (xgrep)"

    /** It starts a background run; nothing is written under this action. */
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        assistedFindingAt(project, editor, file) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val finding = assistedFindingAt(project, editor, file) ?: return
        val absolute = file?.virtualFile?.toNioPathOrNull()?.toString() ?: return
        FixSession.getInstance(project).fixAt(absolute, finding.line + 1, finding.ruleId)
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(
            "Hands this finding to your coding agent. xgrep re-scans the file and reports the fix " +
                "as applied only if the finding is gone. The agent's output streams to the " +
                "<b>xgrep fix</b> console.",
        )

    private fun assistedFindingAt(project: Project, editor: Editor?, file: PsiFile?) =
        findingAt(project, editor, file)?.takeIf { it.fixKind == XgrepDiagnosticData.FIX_KIND_ASSISTED }

    private fun findingAt(project: Project, editor: Editor?, file: PsiFile?): com.mondoo.intellij.findings.Finding? {
        if (editor == null || file == null) return null
        val absolute = file.virtualFile?.toNioPathOrNull() ?: return null
        val line = editor.caretModel.logicalPosition.line
        val path = project.basePath
            ?.let { runCatching { java.nio.file.Path.of(it).relativize(absolute).toString() }.getOrNull() }
            ?: absolute.toString()
        return XgrepFindingsStore.getInstance(project).findingsAt(path, line)
            .firstOrNull { it.fixKind == XgrepDiagnosticData.FIX_KIND_ASSISTED }
    }

    private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): java.nio.file.Path? =
        runCatching { toNioPath() }.getOrNull()
}
