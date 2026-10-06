// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiFile
import com.mondoo.intellij.MondooIcons
import java.awt.datatransfer.StringSelection

/**
 * Alt+Enter on a vulnerable dependency in `requirements.txt` or `package.json`:
 * "Upgrade pg to 7.1.2 (fixes 1 critical)". Rewrites the version in place; for npm
 * it then says to run `npm install`, since the lockfile is the package manager's.
 *
 * Like the other Mondoo intentions it reads no PSI, so it works in every IDE.
 */
internal class UpgradeDependencyIntention :
    IntentionAction,
    Iconable {

    private var lastText = "Upgrade vulnerable dependency"

    override fun getFamilyName() = "Upgrade vulnerable dependency"

    override fun getText() = lastText

    override fun getIcon(flags: Int) = MondooIcons.Fix

    override fun startInWriteAction() = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val target = target(project, editor, file) ?: return false
        lastText = "Upgrade ${target.first.name} to ${target.first.upgradeTo} (fixes ${target.first.counts})"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val (pkg, loc) = target(project, editor, file) ?: return
        editor!!.document.replaceString(loc.version.first, loc.version.last + 1, pkg.upgradeTo)
        val vfile = file!!.virtualFile
        ApplicationManager.getApplication().invokeLater {
            ManifestHighlighter.getInstance(project).highlight(vfile)
            if (vfile.name == "package.json") {
                val command = "npm install"
                NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
                    .createNotification(
                        "Updated ${pkg.name} to ${pkg.upgradeTo} in package.json. Run npm install to update the lockfile.",
                        NotificationType.INFORMATION,
                    )
                    .addAction(
                        NotificationAction.createSimpleExpiring("Copy npm install") {
                            CopyPasteManager.getInstance().setContents(StringSelection(command))
                        },
                    )
                    .notify(project)
            }
        }
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val (pkg, _) = target(project, editor, file) ?: return IntentionPreviewInfo.EMPTY
        return IntentionPreviewInfo.Html(
            "Changes ${pkg.name} from ${pkg.version} to ${pkg.upgradeTo}, the first version without its " +
                "known vulnerabilities (${pkg.counts}). Scan again to confirm.",
        )
    }

    private fun target(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Pair<PackageVulnerabilities, ManifestLocator.Location>? {
        val vfile = file?.virtualFile ?: return null
        if (editor == null || !ManifestLocator.supports(vfile.name)) return null
        val caret = editor.caretModel.offset
        val text = editor.document.text
        return ManifestHighlighter.getInstance(project).vulnerablePackages(vfile)
            .filter { it.upgradeTo.isNotEmpty() }
            .firstNotNullOfOrNull { pkg ->
                ManifestLocator.find(vfile.name, text, pkg.name, pkg.version)
                    ?.takeIf { caret in it.line.first..it.line.last + 1 }
                    ?.let { pkg to it }
            }
    }
}
