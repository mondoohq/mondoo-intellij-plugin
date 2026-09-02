package com.mondoo.intellij.fix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.mondoo.intellij.findings.Finding
import com.mondoo.intellij.findings.XgrepFindingsStore

/**
 * Inserts a `nogrep` suppression comment above the flagged line.
 *
 * An [IntentionAction], deliberately not a `LocalQuickFix`: quick fixes require a
 * `LocalInspectionTool`, which requires PSI we do not have — a `.py` file in
 * Android Studio or a `.rb` file in GoLand resolves to plain text. Availability is
 * decided from the findings store by file and line, touching no PSI at all, which
 * is what makes this work in every IDE.
 *
 * @param withReason prompts for a justification and records it in the comment, so
 *   reviewers see *why* a finding was dismissed rather than only that it was.
 */
internal abstract class SuppressFindingIntentionBase(
    private val withReason: Boolean,
) : IntentionAction {

    override fun getFamilyName(): String = "Suppress xgrep finding"

    override fun getText(): String =
        if (withReason) "Suppress xgrep finding with reason..." else "Suppress xgrep finding (nogrep)"

    /** The reason variant shows a dialog, so it must not hold a write action. */
    override fun startInWriteAction(): Boolean = !withReason

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val finding = findingAt(project, editor, file) ?: return false
        return SuppressionComment.isSupported(finding.second)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val (finding, fileName) = findingAt(project, editor, file) ?: return
        val document = editor?.document ?: return

        val reason = if (withReason) {
            Messages.showInputDialog(
                project,
                "Why is ${finding.ruleId} not a problem here?",
                "Suppress xgrep Finding",
                null,
            )?.trim().orEmpty().ifEmpty { return }
        } else {
            null
        }

        val lineStart = document.getLineStartOffset(finding.line)
        val lineText = document.getText(
            com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(finding.line)),
        )
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }

        // Extend an existing directive rather than stacking comments.
        val previousLine = if (finding.line > 0) {
            document.getText(
                com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(finding.line - 1),
                    document.getLineEndOffset(finding.line - 1),
                ),
            )
        } else {
            ""
        }

        val edit: () -> Unit = {
            val appended = SuppressionComment.appendRule(previousLine, finding.ruleId)
            if (appended != null) {
                document.replaceString(
                    document.getLineStartOffset(finding.line - 1),
                    document.getLineEndOffset(finding.line - 1),
                    appended,
                )
            } else {
                val comment = SuppressionComment.buildLine(fileName, finding.ruleId, indent, reason)
                if (comment != null) document.insertString(lineStart, comment + "\n")
            }
        }

        if (withReason) {
            com.intellij.openapi.command.WriteCommandAction
                .runWriteCommandAction(project, familyName, null, edit, file)
        } else {
            edit()
        }
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        // The reason variant cannot be previewed: the text is not known until the
        // user types it.
        if (withReason) {
            return IntentionPreviewInfo.Html(
                "Asks for a justification, then records it in a <code>nogrep</code> comment " +
                    "that the xgrep CLI and CI honour too.",
            )
        }
        val (finding, fileName) = findingAt(project, editor, file) ?: return IntentionPreviewInfo.EMPTY
        val comment = SuppressionComment.buildLine(fileName, finding.ruleId, "")
            ?: return IntentionPreviewInfo.EMPTY
        return IntentionPreviewInfo.Html("Inserts <code>${comment.trim()}</code> above this line.")
    }

    /** The finding under the caret, with the file name needed for comment syntax. */
    private fun findingAt(project: Project, editor: Editor?, file: PsiFile?): Pair<Finding, String>? {
        if (editor == null || file == null) return null
        val virtualFile = file.virtualFile ?: return null
        val line = editor.caretModel.logicalPosition.line
        val base = project.basePath
        val path = virtualFile.toNioPathOrNull()?.let { absolute ->
            base?.let { runCatching { java.nio.file.Path.of(it).relativize(absolute).toString() }.getOrNull() }
                ?: absolute.toString()
        } ?: return null

        val finding = XgrepFindingsStore.getInstance(project).findingsAt(path, line).firstOrNull()
            ?: return null
        return finding to virtualFile.name
    }

    private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): java.nio.file.Path? =
        runCatching { toNioPath() }.getOrNull()
}

/**
 * The platform instantiates intentions through a no-argument constructor, so each
 * variant needs its own concrete class.
 */
internal class SuppressFindingIntention : SuppressFindingIntentionBase(withReason = false)

internal class SuppressFindingWithReasonIntention : SuppressFindingIntentionBase(withReason = true)
