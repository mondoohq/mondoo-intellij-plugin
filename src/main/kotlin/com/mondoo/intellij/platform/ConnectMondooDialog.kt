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

    /**
     * The entry shows the connection, so the menu answers "am I connected?" without
     * opening anything. Reading the file is cheap (a few hundred bytes), and this
     * runs off the EDT.
     */
    override fun update(e: AnActionEvent) {
        val p = e.presentation
        when (val status = MondooPlatform.status()) {
            is MondooPlatform.Status.Connected -> {
                p.text = "Connected to ${status.space ?: "Mondoo Platform"}"
                p.description = "Change the Mondoo Platform service account (${MondooPlatform.display(status.path)})"
                p.icon = AllIcons.General.InspectionsOK
            }
            is MondooPlatform.Status.Unusable -> {
                p.text = "Fix Mondoo Platform Connection..."
                p.description = "Your service account can't be used: ${MondooPlatform.joinWords(status.problems)}"
                p.icon = AllIcons.General.Warning
            }
            is MondooPlatform.Status.NeedsSpace -> {
                p.text = "Choose a Mondoo Space..."
                p.description =
                    "Your service account belongs to the organization ${status.organization}; choose a space"
                p.icon = AllIcons.General.Warning
            }
            is MondooPlatform.Status.Missing -> {
                p.text = "Connect to Mondoo Platform..."
                p.description = templatePresentation.description
                p.icon = null
            }
        }
    }

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

    // Every component the panel uses is declared here, above init: init() builds the
    // panel, and a property declared further down is still null at that point.
    private val organizationNote = com.intellij.ui.components.JBLabel()
    private val space = com.intellij.ui.components.JBTextField(
        MondooEnvironment.spaceMrn()?.let(MondooSpace::id).orEmpty(),
    )
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
        // A file that works (or only lacks a space) is the one to keep, so start there.
        val connected = status is MondooPlatform.Status.Connected || status is MondooPlatform.Status.NeedsSpace
        space.emptyText.text = "Space ID or console URL, e.g. friendly-nash-115619"
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
                        is MondooPlatform.Status.Unusable,
                        is MondooPlatform.Status.NeedsSpace,
                        -> AllIcons.General.Warning
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

            // Only an organization's service account needs a space, so the field shows
            // up only for one, with the reason right above it.
            val organizationAccount = OrganizationAccount()
            row {
                icon(AllIcons.General.Information).align(com.intellij.ui.dsl.builder.AlignY.TOP)
                cell(organizationNote)
            }.visibleIf(organizationAccount).topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
            row("Space:") {
                cell(space).columns(com.intellij.ui.dsl.builder.COLUMNS_LARGE).align(AlignX.FILL)
            }.rowComment(
                "Paste the space's ID or its URL from the Mondoo Console.",
                maxLineLength = TEXT_WIDTH,
            ).visibleIf(organizationAccount)

            collapsibleGroup("Advanced") {
                row("Save new service accounts to:") {
                    cell(saveTo).align(AlignX.FILL)
                }.rowComment("Used when registering with a token. A file already at this path is replaced.")
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (chosenOrganization() != null && space.text.isNotBlank() && MondooSpace.toMrn(space.text) == null) {
            return ValidationInfo("This is not a space ID or a space URL", space)
        }
        if (useToken.component.isSelected) {
            return when {
                token.password.isEmpty() -> ValidationInfo("Paste a registration token", token)
                saveTo.text.isBlank() -> ValidationInfo("Choose where to save the service account", saveTo.textField)
                else -> null
            }
        }
        val path = existing.text.trim().takeIf { it.isNotEmpty() }
            ?: return ValidationInfo("Choose a service account file", existing.textField)
        return when (val s = MondooPlatform.status(Path.of(path))) {
            is MondooPlatform.Status.Missing -> ValidationInfo("There is no file at this path", existing.textField)
            is MondooPlatform.Status.Unusable ->
                ValidationInfo("This file can't be used: ${MondooPlatform.joinWords(s.problems)}", existing.textField)
            is MondooPlatform.Status.NeedsSpace, is MondooPlatform.Status.Connected ->
                organizationOf(Path.of(path))?.takeIf { space.text.isBlank() }?.let {
                    ValidationInfo("This service account belongs to the organization $it: enter a space", space)
                }
        }
    }

    /** The organization of the chosen service account file, or null for a space account. */
    private fun chosenOrganization(): String? =
        if (::useFile.isInitialized && useFile.component.isSelected) {
            existing.text.trim().takeIf { it.isNotEmpty() }?.let { organizationOf(Path.of(it)) }
        } else {
            null
        }

    /** True while the chosen file is an organization's service account; follows the file field. */
    private inner class OrganizationAccount : com.intellij.ui.layout.ComponentPredicate() {
        override fun invoke(): Boolean {
            val org = chosenOrganization() ?: return false
            val name = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(org)
            organizationNote.text =
                "<html>This service account belongs to the organization <b>$name</b>, not to a space. " +
                "Choose the space to report to and check dependencies in.</html>"
            return true
        }

        override fun addListener(listener: (Boolean) -> Unit) {
            val changed = {
                listener(invoke())
                // The dialog grows or shrinks with the space field.
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { pack() }
            }
            existing.textField.document.addDocumentListener(
                object : com.intellij.ui.DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) = changed()
                },
            )
            useFile.component.addItemListener { changed() }
        }
    }

    private fun organizationOf(path: Path): String? =
        runCatching { MondooConfigFile.inspect(java.nio.file.Files.readString(path)).organization }.getOrNull()

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
            organizationOf(path)?.takeIf { space.text.isBlank() }?.let { org ->
                // Registered, but the token was an organization's: the file is kept,
                // and the user only has to add the space and press Connect again.
                useFile.component.isSelected = true
                existing.text = path.toString()
                setErrorText("This registration token is for the organization $org: enter a space", space)
                return
            }
        } else {
            path = Path.of(existing.text.trim())
        }
        // A space account names its own space; a space left over from an
        // organization account must not override it.
        val before = MondooEnvironment.configPath() to MondooEnvironment.spaceMrn()
        com.mondoo.intellij.settings.MondooSettings.getInstance().state.mondooSpaceMrn =
            if (organizationOf(path) != null) MondooSpace.toMrn(space.text).orEmpty() else ""
        MondooPlatform.use(path)
        if (before != (MondooEnvironment.configPath() to MondooEnvironment.spaceMrn())) {
            // The running language server still has the old account in its environment.
            // (The Fix tab's server notices by itself.)
            com.intellij.openapi.project.ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
                .forEach(com.mondoo.intellij.settings.ScannerReload::restart)
        }
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
