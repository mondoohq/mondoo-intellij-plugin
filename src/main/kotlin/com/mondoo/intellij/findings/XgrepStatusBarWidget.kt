package com.mondoo.intellij.findings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.binary.XgrepStatus
import com.mondoo.intellij.settings.MondooSettings

private const val WIDGET_ID = "mondoo.xgrep"

internal class XgrepStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Mondoo Code Security"
    override fun isAvailable(project: Project): Boolean = MondooSettings.getInstance().state.xgrepEnabled
    override fun createWidget(project: Project): StatusBarWidget = XgrepStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: com.intellij.openapi.wm.StatusBar): Boolean = true
}

/**
 * Shows scanner state and the project-wide finding count.
 *
 * Port of `xgrepStatusBar.ts`. The count comes from [XgrepFindingsStore] rather
 * than from re-counting diagnostics, which is why the VS Code implementation's
 * 300 ms debounce and full recount are unnecessary here.
 */
internal class XgrepStatusBarWidget(project: Project) : EditorBasedStatusBarPopup(project, false) {

    init {
        project.messageBus.connect(this).subscribe(
            XgrepFindingsStore.TOPIC,
            XgrepFindingsStore.Listener { update(null) },
        )
    }

    override fun ID(): String = WIDGET_ID

    override fun createInstance(project: Project): StatusBarWidget = XgrepStatusBarWidget(project)

    override fun getWidgetState(file: VirtualFile?): WidgetState {
        if (!MondooSettings.getInstance().state.xgrepEnabled) return WidgetState.HIDDEN

        return when (val status = XgrepBinaryService.getInstance().status) {
            is XgrepStatus.Disabled -> WidgetState.HIDDEN

            is XgrepStatus.Resolving ->
                WidgetState("Looking for the xgrep security scanner", "xgrep", true)

            is XgrepStatus.Downloading ->
                WidgetState(
                    "Downloading xgrep ${status.version}",
                    "xgrep: ${status.percent}%",
                    true,
                )

            is XgrepStatus.Ready -> {
                val count = XgrepFindingsStore.getInstance(project).findingCount()
                val version = status.version?.let { " $it" }.orEmpty()
                WidgetState(
                    if (count == 0) "xgrep$version: no findings" else "xgrep$version: $count findings",
                    if (count == 0) "xgrep" else "xgrep: $count",
                    true,
                ).also { it.icon = AllIcons.General.InspectionsOK.takeIf { count == 0 } }
            }

            is XgrepStatus.Unavailable ->
                WidgetState("xgrep unavailable: ${status.reason} — click to set it up", "xgrep: set up", true)
                    .also { it.icon = AllIcons.General.Warning }
        }
    }

    override fun createPopup(context: DataContext): ListPopup {
        // Built from the registered actions, so descriptions and enablement live in
        // one place instead of being duplicated as menu literals.
        val group = ActionManager.getInstance().getAction("Mondoo.CodeSecurity") as? DefaultActionGroup
            ?: DefaultActionGroup()
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Mondoo Code Security",
            group,
            context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }
}
