// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.vfs.VfsUtil
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.bom.BomContent
import com.mondoo.intellij.bom.BomFormat
import com.mondoo.intellij.bom.BomRequest
import com.mondoo.intellij.bom.BomService
import com.mondoo.intellij.util.MondooDialogs
import com.mondoo.intellij.util.ProjectTrust
import java.nio.file.Path

/**
 * Generates a bill of materials for the project.
 *
 * One action rather than one per kind: the scanner merges the selected kinds into a
 * single document, so the choice is a step in the flow rather than three menu
 * entries that mostly repeat each other.
 */
class GenerateBomAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            ProjectTrust.isTrusted(project) &&
            XgrepBinaryService.getInstance().resolvedBinaryOrNull() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val contents = BomContent.entries
        val contentIndex = MondooDialogs.choose(
            project,
            "Which bill of materials?",
            "Generate Bill of Materials",
            contents.map { "${it.title} — ${it.description}" },
        ) ?: return
        val content = setOf(contents[contentIndex])

        // Ask for a format only when there is a choice. Cryptography and AI bills are
        // CycloneDX JSON only, so offering SPDX there would build a rejected command.
        val probe = BomRequest(content, BomFormat.CYCLONEDX_JSON)
        val formats = probe.availableFormats()
        val format = if (formats.size == 1) {
            formats.single()
        } else {
            val index = MondooDialogs.choose(
                project,
                "Output format?",
                "Generate Bill of Materials",
                formats.map { it.title },
            ) ?: return
            formats[index]
        }

        val request = BomRequest(content, format)
        val descriptor = FileSaverDescriptor(
            "Save Bill of Materials",
            "Choose where to write the generated document",
        )
        val saved = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(
                project.basePath?.let { VfsUtil.findFile(Path.of(it), true) },
                request.defaultFileName(project.name),
            ) ?: return

        BomService.getInstance(project).generate(request, saved.file.toPath())
    }
}
