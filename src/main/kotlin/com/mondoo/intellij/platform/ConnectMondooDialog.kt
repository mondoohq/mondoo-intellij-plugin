// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.mondoo.intellij.settings.MondooEnvironment
import java.nio.file.Path
import javax.swing.JComponent

/**
 * More | Connect to Mondoo Platform...: choose the service account xgrep and cnspec use.
 *
 * Two ways in: register a new service account from a registration token (xgrep does
 * the exchange and writes the file), or use a service account file already on disk.
 * Either way the file is checked before it is used, so a broken one is caught here
 * rather than as a failed scan.
 */
class ConnectMondooAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ConnectMondooDialog(e.project).show()
    }
}

class ConnectMondooDialog(private val project: Project?) : DialogWrapper(project) {

    private val token = JBPasswordField()
    private val saveTo = TextFieldWithBrowseButton()
    private val existing = TextFieldWithBrowseButton()
    private lateinit var useToken: Cell<JBRadioButton>
    private lateinit var useFile: Cell<JBRadioButton>

    init {
        title = "Connect to Mondoo Platform"
        setOKButtonText("Connect")
        val current = MondooPlatform.effectivePath()
        saveTo.text = current.toString()
        existing.text = current.toString()
        saveTo.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile().withTitle("Save the Service Account To"),
        )
        existing.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile().withTitle("Service Account File"),
        )
        init()
    }

    override fun createCenterPanel(): JComponent {
        val status = MondooPlatform.status()
        return panel {
            row {
                icon(
                    when (status) {
                        is MondooPlatform.Status.Connected -> AllIcons.General.InspectionsOK
                        is MondooPlatform.Status.Unusable -> AllIcons.General.Warning
                        is MondooPlatform.Status.Missing -> AllIcons.General.Information
                    },
                )
                label(MondooPlatform.describe(status))
            }.comment(status.path.toString())
            separator()
            buttonsGroup {
                row {
                    useToken = radioButton("Register with a registration token")
                        .applyToComponent { isSelected = status !is MondooPlatform.Status.Connected }
                }
                indent {
                    row("Token:") { cell(token).align(AlignX.FILL) }
                        .comment("In the Mondoo Console: Space → Settings → Registration Token.")
                        .enabledIf(useToken.selected)
                    row("Save to:") { cell(saveTo).align(AlignX.FILL) }
                        .comment("An existing file there is replaced.")
                        .enabledIf(useToken.selected)
                }
                row {
                    useFile = radioButton("Use a service account file")
                        .applyToComponent { isSelected = status is MondooPlatform.Status.Connected }
                }
                indent {
                    row("File:") { cell(existing).align(AlignX.FILL) }
                        .comment("A mondoo.yml written by <code>cnspec login</code> or <code>xgrep login</code>.")
                        .enabledIf(useFile.selected)
                }
            }
        }
    }

    override fun doValidate(): ValidationInfo? = if (useToken.component.isSelected) {
        when {
            token.password.isEmpty() -> ValidationInfo("Paste a registration token", token)
            saveTo.text.isBlank() -> ValidationInfo("Choose where to save the service account", saveTo.textField)
            else -> null
        }
    } else {
        val path = existing.text.trim()
        when (val s = if (path.isEmpty()) null else MondooPlatform.status(Path.of(path))) {
            null -> ValidationInfo("Choose a service account file", existing.textField)
            is MondooPlatform.Status.Missing -> ValidationInfo("No file at this path", existing.textField)
            is MondooPlatform.Status.Unusable -> ValidationInfo(
                "This file ${s.problems.joinToString(", ")}",
                existing.textField,
            )
            is MondooPlatform.Status.Connected -> null
        }
    }

    override fun doOKAction() {
        val path: Path
        if (useToken.component.isSelected) {
            path = Path.of(saveTo.text.trim())
            val secret = String(token.password)
            var error: String? = null
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { error = MondooPlatform.register(secret, path) },
                "Registering with Mondoo Platform",
                false,
                project,
            )
            if (error != null) {
                setErrorText(error, token)
                return
            }
        } else {
            path = Path.of(existing.text.trim())
        }
        MondooPlatform.use(path)
        val status = MondooPlatform.status(path)
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(
                MondooPlatform.describe(status) +
                    if (MondooEnvironment.configPath() != null) " ($path)." else ".",
                NotificationType.INFORMATION,
            )
            .notify(project)
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (useToken.component.isSelected) token else existing.textField
}
