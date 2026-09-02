package com.mondoo.intellij.actions

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.BrowserUtil
import com.mondoo.intellij.settings.MondooSettings

private const val ONBOARDING_SHOWN_KEY = "mondoo.onboarding.shown"
private const val DEMO_FILE_NAME = "mondoo-welcome-demo.js"
private const val DOCS_URL = "https://mondoo.com/docs/xgrep"

/**
 * Writes a deliberately vulnerable file and opens it, so findings appear
 * immediately with nothing to configure.
 */
class OpenDemoFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = project.guessProjectDir() ?: run {
            Messages.showWarningDialog(project, "This project has no directory to write to.", "Open Demo File")
            return
        }

        // Never clobber a file that is already there.
        directory.findChild(DEMO_FILE_NAME)?.let { existing ->
            FileEditorManager.getInstance(project).openFile(existing, true)
            return
        }

        val content = javaClass.getResourceAsStream("/demo/welcome.js")?.readAllBytes() ?: return
        val created = WriteAction.computeAndWait<com.intellij.openapi.vfs.VirtualFile?, Exception> {
            directory.createChildData(this, DEMO_FILE_NAME).also { it.setBinaryContent(content) }
        } ?: return
        FileEditorManager.getInstance(project).openFile(created, true)
    }
}

/** Opens the user documentation. */
class OpenDocumentationAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(DOCS_URL)
}

/**
 * First-run prompt.
 *
 * A suggestion notification rather than an auto-opened tool window: stealing focus
 * on first project open is exactly the kind of thing that gets a plugin disabled.
 */
internal class MondooOnboardingActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!MondooSettings.getInstance().state.xgrepEnabled) return
        val properties = PropertiesComponent.getInstance()
        if (properties.getBoolean(ONBOARDING_SHOWN_KEY, false)) return
        properties.setValue(ONBOARDING_SHOWN_KEY, true)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(
                "Mondoo Security",
                "Security findings appear as you edit. Nothing to configure.",
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Try it on a demo file") {
                    OpenDemoFileAction().actionPerformed(
                        AnActionEvent.createFromDataContext(
                            "MondooOnboarding",
                            null,
                        ) { key -> if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(key)) project else null },
                    )
                },
            )
            .addAction(NotificationAction.createSimpleExpiring("Documentation") { BrowserUtil.browse(DOCS_URL) })
            .notify(project)
    }
}
