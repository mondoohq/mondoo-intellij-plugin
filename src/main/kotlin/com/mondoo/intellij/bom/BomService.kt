// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.bom

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.mondoo.intellij.binary.XgrepBinaryService
import java.nio.file.Path

/**
 * Generates bills of materials by running the scanner.
 *
 * A CLI invocation rather than an LSP command: the scanner exposes BOM generation
 * only on the command line, and a BOM is a document the user saves rather than
 * something to render as editor state.
 */
@Service(Service.Level.PROJECT)
class BomService(private val project: Project) {

    private val log = Logger.getInstance(BomService::class.java)

    /**
     * Runs [request] and writes the result to [output].
     *
     * Cancellable, and generous with time: a cryptography or AI bill runs the scan
     * engine over the whole tree, which on a large repository is minutes, not seconds.
     */
    fun generate(request: BomRequest, output: Path) {
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            notify("The xgrep scanner is not installed. Run Set Up Scanner.", NotificationType.WARNING)
            return
        }
        val projectPath = project.basePath
        if (projectPath == null) {
            notify("This project has no directory to inventory.", NotificationType.WARNING)
            return
        }

        object : Task.Backgroundable(project, "Generating ${request.describeContent()}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val command = GeneralCommandLine(binary.toString())
                    .withParameters(request.arguments(projectPath, output.toString()))
                    .withWorkDirectory(projectPath)

                log.info("Mondoo: ${command.commandLineString}")
                val result = CapturingProcessHandler(command).runProcess(TIMEOUT_MS, true)

                when {
                    indicator.isCanceled -> return
                    result.isTimeout ->
                        notify("Generating the bill of materials timed out.", NotificationType.ERROR)
                    result.exitCode != 0 ->
                        notify(
                            "Could not generate the bill of materials: " +
                                result.stderr.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(300),
                            NotificationType.ERROR,
                        )
                    else -> notifySuccess(output)
                }
            }
        }.queue()
    }

    private fun notifySuccess(output: Path) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification("Bill of materials written to ${output.fileName}", NotificationType.INFORMATION)
        notification.addAction(
            NotificationAction.createSimpleExpiring("Open") {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(output)?.let {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
            },
        )
        notification.notify(project)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(content, type).notify(project)
    }

    companion object {
        /** Crypto and AI bills run the scan engine over the whole tree. */
        private const val TIMEOUT_MS = 15 * 60 * 1000

        @JvmStatic
        fun getInstance(project: Project): BomService = project.service()
    }
}

/** "a software bill of materials", for progress and notification text. */
internal fun BomRequest.describeContent(): String = when {
    content == setOf(BomContent.SCA) -> "a software bill of materials"
    content == setOf(BomContent.CBOM) -> "a cryptography bill of materials"
    content == setOf(BomContent.AIBOM) -> "an AI bill of materials"
    else -> "a bill of materials"
}
