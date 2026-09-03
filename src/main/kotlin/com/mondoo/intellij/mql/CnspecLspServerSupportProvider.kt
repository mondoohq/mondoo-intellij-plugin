// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

// The deprecated LSP names are used deliberately; see docs/adr/0001.
@file:Suppress("DEPRECATION")

package com.mondoo.intellij.mql

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.settings.MondooSettings
import com.mondoo.intellij.util.ProjectTrust

/**
 * Starts `cnspec lsp` for MQL policy bundles and query files.
 *
 * A second language server alongside xgrep's. They cannot collide: the platform keys
 * servers by provider class, so each has its own lifecycle, and the two answer
 * different files.
 */
internal class CnspecLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (!ProjectTrust.isTrusted(project)) return
        if (!MondooSettings.getInstance().state.cnspecEnabled) return
        if (!MqlFiles.isSupported(file.name)) return

        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            LOG.info("Mondoo: no cnspec binary resolved; not starting for ${file.name}")
            return
        }

        LOG.info("Mondoo: starting cnspec lsp for ${file.name} using $binary")
        serverStarter.ensureServerStarted(CnspecLspServerDescriptor(project, binary.toString()))
    }

    private companion object {
        val LOG = Logger.getInstance(CnspecLspServerSupportProvider::class.java)
    }
}

internal class CnspecLspServerDescriptor(project: Project, private val binaryPath: String) :
    ProjectWideLspServerDescriptor(project, "Mondoo MQL") {

    override fun isSupportedFile(file: VirtualFile): Boolean = MqlFiles.isSupported(file.name)

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(binaryPath).apply {
            addParameter("lsp")
            project.basePath?.let { withWorkDirectory(it) }
        }

    /**
     * Policy bundles are YAML by extension; the server distinguishes them by path.
     * Reporting the id the server expects keeps its own routing intact.
     */
    override fun getLanguageId(file: VirtualFile): String = MqlFiles.languageIdFor(file.name)

    override val lspCustomization = CnspecLspCustomization()
}
