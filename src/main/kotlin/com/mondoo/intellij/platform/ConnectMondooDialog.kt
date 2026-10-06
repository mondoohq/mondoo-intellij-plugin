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
import com.intellij.ui.dsl.builder.columns
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

    private companion object {
        /** Characters per line for the explanatory text, so the dialog stays a readable width. */
        const val TEXT_WIDTH = 72

        const val CONSOLE_URL = "https://console.mondoo.com"
    }

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
        val connected = status is MondooPlatform.Status.Connected
        token.emptyText.text = "Paste your registration token"
        existing.textField.let {
            (it as? com.intellij.ui.components.JBTextField)?.emptyText?.text = "Path to mondoo.yml"
        }

        return panel {
            // Where things stand, in a headline and one sentence, before any choice.
            row {
                icon(
                    when (status) {
                        is MondooPlatform.Status.Connected -> AllIcons.General.InspectionsOK
                        is MondooPlatform.Status.Unusable -> AllIcons.General.Warning
                        is MondooPlatform.Status.Missing -> AllIcons.General.Information
                    },
                ).align(com.intellij.ui.dsl.builder.AlignY.TOP)
                text(
                    "<b>${MondooPlatform.describe(status)}</b><br>${
                        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(MondooPlatform.explain(status))
                    }",
                    maxLineLength = TEXT_WIDTH,
                )
            }.bottomGap(com.intellij.ui.dsl.builder.BottomGap.MEDIUM)

            buttonsGroup {
                row {
                    useToken = radioButton("Register with a registration token")
                        .applyToComponent { isSelected = !connected }
                        .bold()
                    comment("Recommended")
                }
                indent {
                    row {
                        cell(token).columns(com.intellij.ui.dsl.builder.COLUMNS_LARGE).align(AlignX.FILL)
                    }.enabledIf(useToken.selected)
                    row {
                        comment(
                            "Find one in the Mondoo Console under Space → Settings → Registration Token. " +
                                "It creates a service account for this machine.",
                            maxLineLength = TEXT_WIDTH,
                        )
                    }
                    row {
                        browserLink("Open the Mondoo Console", CONSOLE_URL)
                    }.bottomGap(com.intellij.ui.dsl.builder.BottomGap.SMALL)
                }
                row {
                    useFile = radioButton("Use a service account file you already have")
                        .applyToComponent { isSelected = connected }
                        .bold()
                }
                indent {
                    row {
                        cell(existing).columns(com.intellij.ui.dsl.builder.COLUMNS_LARGE).align(AlignX.FILL)
                    }.enabledIf(useFile.selected)
                    row {
                        comment(
                            "For example the mondoo.yml that <code>cnspec login</code> wrote.",
                            maxLineLength = TEXT_WIDTH,
                        )
                    }
                }
            }

            collapsibleGroup("Advanced") {
                row("Save new service accounts to:") {
                    cell(saveTo).align(AlignX.FILL)
                }.rowComment("Used when registering with a token. A file already at this path is replaced.")
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
            is MondooPlatform.Status.Missing -> ValidationInfo("There is no file at this path", existing.textField)
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
