// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.bom

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Everything about a bill of materials on one page: what goes in it, the format,
 * and where it is saved. It replaces three pick lists in a row, whose long labels
 * were cut off and which hid that the kinds can be combined.
 */
class GenerateBomDialog(private val project: Project) : DialogWrapper(project) {

    // Declared before init(), which builds the panel from them.
    private val kinds = BomContent.entries.associateWith { JBCheckBox(it.title, it == BomContent.SCA) }
    private val format = ComboBox(BomFormat.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.title }
    }
    private val includeDev = JBCheckBox("Include development dependencies")
    private val directOnly = JBCheckBox("Direct dependencies only")
    private val output = TextFieldWithBrowseButton()
    private val formatNote = com.intellij.ui.components.JBLabel().apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    /** Whether the user typed a path; until then it follows the choices. */
    private var outputEdited = false

    init {
        title = "Generate Bill of Materials"
        setOKButtonText("Generate")
        output.addActionListener { chooseOutput() }
        kinds.values.forEach { box -> box.addItemListener { choicesChanged() } }
        format.addItemListener { choicesChanged() }
        init()
        choicesChanged()
        output.textField.document.addDocumentListener(
            object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    if (output.textField.hasFocus()) outputEdited = true
                }
            },
        )
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Include") {
            BomContent.entries.forEach { kind ->
                row { cell(kinds.getValue(kind)).bold() }
                indent { row { comment(kind.description) } }
            }
        }
        group("Format") {
            row { cell(format).align(AlignX.FILL) }
            row { cell(formatNote) }
            row { cell(includeDev) }
            row { cell(directOnly) }
                .rowComment("Development dependencies and the direct-only limit apply to the software bill.")
        }
        row("Save to:") { cell(output).align(AlignX.FILL) }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
    }.apply { preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(520), preferredSize.height) }

    private fun selectedKinds(): Set<BomContent> = kinds.filterValues { it.isSelected }.keys

    /** The request as chosen, or null when nothing is selected yet. */
    private fun request(): BomRequest? {
        val content = selectedKinds().ifEmpty { return null }
        return BomRequest(
            content = content,
            format = format.selectedItem as BomFormat,
            includeDev = includeDev.isSelected,
            directOnly = directOnly.isSelected,
        )
    }

    private fun choicesChanged() {
        val request = request()
        val cycloneOnly = request?.requiresCycloneDxJson == true
        // Cryptography and AI bills exist only as CycloneDX JSON; say so rather than
        // offering formats the scanner would reject.
        format.isEnabled = !cycloneOnly
        if (cycloneOnly) format.selectedItem = BomFormat.CYCLONEDX_JSON
        formatNote.text = if (cycloneOnly) "Cryptography and AI bills are written as CycloneDX (JSON)." else " "
        val software = BomContent.SCA in selectedKinds()
        includeDev.isEnabled = software
        directOnly.isEnabled = software
        if (!outputEdited && request != null) {
            val dir = project.basePath?.let(Path::of) ?: Path.of(System.getProperty("user.home"))
            output.text = dir.resolve(request.defaultFileName(project.name)).toString()
        }
    }

    private fun chooseOutput() {
        val request = request() ?: return
        val descriptor = FileSaverDescriptor("Save Bill of Materials", "Choose where to write the generated document")
        val current = Path.of(output.text)
        FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(
                current.parent?.let { VfsUtil.findFile(it, true) },
                current.fileName?.toString() ?: request.defaultFileName(project.name),
            )
            ?.let {
                output.text = it.file.path
                outputEdited = true
            }
    }

    override fun doValidate(): ValidationInfo? = when {
        selectedKinds().isEmpty() -> ValidationInfo(
            "Choose at least one bill of materials",
            kinds.getValue(BomContent.SCA),
        )
        output.text.isBlank() -> ValidationInfo("Choose where to save it", output.textField)
        else -> null
    }

    override fun doOKAction() {
        val request = request() ?: return
        BomService.getInstance(project).generate(request, Path.of(output.text.trim()))
        super.doOKAction()
    }
}
