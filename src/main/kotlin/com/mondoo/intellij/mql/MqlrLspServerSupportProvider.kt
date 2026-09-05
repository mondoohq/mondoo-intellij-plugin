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
import com.mondoo.intellij.binary.MqlrBinaryService
import com.mondoo.intellij.settings.MondooSettings
import com.mondoo.intellij.util.ProjectTrust

/**
 * Starts `mqlr lsp` for LR resource-definition files.
 *
 * The third language server, and the narrowest: it answers only `.lr` and `.mqlr`,
 * which nothing else in the plugin touches. The platform keys servers by provider
 * class, so the three have separate lifecycles and cannot collide.
 *
 * When mqlr is absent the offer to install is made once per project rather than on
 * every file opened — mqlr is a developer tool built from source, and someone editing
 * a schema who has decided not to install it should not be asked again for each file.
 */
internal class MqlrLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (!ProjectTrust.isTrusted(project)) return
        if (!MondooSettings.getInstance().state.mqlrEnabled) return
        if (!MqlrFiles.isSupported(file.name)) return

        val binary = MqlrBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            LOG.info("Mondoo: no mqlr binary resolved; not starting for ${file.name}")
            offerInstallOnce(project)
            return
        }

        LOG.info("Mondoo: starting mqlr lsp for ${file.name} using $binary")
        serverStarter.ensureServerStarted(MqlrLspServerDescriptor(project, binary.toString()))
    }

    /**
     * At most one prompt per project.
     *
     * A schema repository holds dozens of `.lr` files and they are often opened in
     * bulk; without this, declining once would be answered by asking again
     * immediately. Ported from the extension's once-per-session guard, per project
     * rather than per window because that is the scope the answer belongs to.
     */
    private fun offerInstallOnce(project: Project) {
        if (!offered.add(project.locationHash)) return
        MqlrBinaryService.getInstance().notifyMissing(project)
    }

    private companion object {
        val LOG = Logger.getInstance(MqlrLspServerSupportProvider::class.java)
        val offered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

internal class MqlrLspServerDescriptor(project: Project, private val binaryPath: String) :
    ProjectWideLspServerDescriptor(project, "Mondoo LR") {

    override fun isSupportedFile(file: VirtualFile): Boolean = MqlrFiles.isSupported(file.name)

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(binaryPath).apply {
            addParameter("lsp")
            // Not optional: `mqlr lsp` defaults to --mode=test, which runs a demo
            // rather than speaking LSP on stdio. Omitting this starts a process that
            // never answers a request.
            addParameter("--mode=server")
            project.basePath?.let { withWorkDirectory(it) }
        }

    /**
     * `.mqlr` reports `lr` too. It is the same language under a new extension, and
     * the id is what the server routes on.
     */
    override fun getLanguageId(file: VirtualFile): String = MqlrFiles.languageIdFor(file.name)

    override val lspCustomization = MqlrLspCustomization()
}
