package com.mondoo.intellij.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.binary.XgrepStatus
import com.mondoo.intellij.settings.MondooConfigurable
import com.mondoo.intellij.settings.MondooSettings
import com.mondoo.intellij.util.XgrepLanguages
import java.util.function.Function
import javax.swing.JComponent

internal const val BANNER_DISMISSED_KEY = "mondoo.xgrep.setupBanner.dismissed"

/**
 * Whether the setup banner belongs on a given file.
 *
 * Split out and kept pure so the conditions are unit-tested: a banner that appears
 * on the wrong files, or keeps appearing after being dismissed, is the kind of
 * nuisance that gets a plugin uninstalled.
 */
internal object XgrepSetupBannerPolicy {
    fun shouldShow(
        scannerEnabled: Boolean,
        dismissed: Boolean,
        fileName: String,
        unavailableReason: String?,
    ): Boolean =
        scannerEnabled &&
            !dismissed &&
            unavailableReason != null &&
            XgrepLanguages.isSupported(fileName)
}

/**
 * An in-editor bar offering to set the scanner up, shown when a scannable file is
 * open but no binary could be resolved.
 *
 * A banner rather than a balloon notification on purpose: the situation persists
 * until acted on, and a balloon that has already been dismissed leaves the user
 * looking at a file with no findings and no explanation for why.
 *
 * Deliberately narrow — it appears only on files xgrep would actually scan, so a
 * user editing Markdown is never told about a scanner that would ignore the file
 * anyway. It is dismissible for good.
 */
internal class XgrepSetupBannerProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val service = XgrepBinaryService.getInstance()
        // Resolve first: the status is only meaningful once discovery has run.
        service.resolvedBinaryOrNull()
        val reason = (service.status as? XgrepStatus.Unavailable)?.reason

        val show = XgrepSetupBannerPolicy.shouldShow(
            scannerEnabled = MondooSettings.getInstance().state.xgrepEnabled,
            dismissed = PropertiesComponent.getInstance().getBoolean(BANNER_DISMISSED_KEY, false),
            fileName = file.name,
            unavailableReason = reason,
        )
        if (!show) return null

        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = "Mondoo: the xgrep security scanner is not available ($reason)"
                createActionLabel("Set up scanner") {
                    XgrepBinaryService.getInstance().ensureInstalled(project, force = true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
                createActionLabel("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MondooConfigurable::class.java)
                }
                createActionLabel("Don't show again") {
                    PropertiesComponent.getInstance().setValue(BANNER_DISMISSED_KEY, true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }
}
