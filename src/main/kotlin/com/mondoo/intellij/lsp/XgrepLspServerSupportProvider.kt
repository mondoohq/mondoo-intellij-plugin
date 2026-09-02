// See the class KDoc below and docs/adr/0001: the deprecated LSP names are used
// deliberately, because they are the ones present in every build we support.
@file:Suppress("DEPRECATION")

package com.mondoo.intellij.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.settings.MondooSettings
import com.mondoo.intellij.util.XgrepLanguages

/**
 * Starts `xgrep lsp` for files xgrep can scan.
 *
 * Registered under `com.intellij.platform.lsp.serverSupportProvider` rather than
 * the newer `integrationProvider`: both exist at the 261.26222 floor, but some
 * later EAP branches (e.g. GO-262.6228.35) ship only the former, and untilBuild
 * is open. `LspServerSupportProvider` extends `LspIntegrationProvider`, so this
 * costs nothing.
 *
 * The deprecation suppression is therefore deliberate, not an oversight: the
 * platform renamed these to `LspIntegrationProvider` / `ProjectWideLspClientDescriptor`
 * in 2026.1.4, but the old names are the ones guaranteed to resolve across every
 * build in our compatibility range. Revisit once the floor rises past the branches
 * that lack the new extension point. See docs/adr/0001.
 */
internal class XgrepLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (!MondooSettings.getInstance().state.xgrepEnabled) {
            LOG.debug("xgrep disabled by setting; not starting for ${file.name}")
            return
        }
        if (!XgrepLanguages.isSupported(file.name)) return

        // Must not block: binary download/resolution happens off this call, and
        // startServersIfNeeded() re-enters once a binary is available.
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            LOG.info("Mondoo: no xgrep binary resolved; not starting for ${file.name}")
            return
        }

        LOG.info("Mondoo: starting xgrep lsp for ${file.name} using $binary")
        serverStarter.ensureServerStarted(XgrepLspServerDescriptor(project, binary.toString()))
    }

    private companion object {
        val LOG = Logger.getInstance(XgrepLspServerSupportProvider::class.java)
    }
}

internal class XgrepLspServerDescriptor(
    project: Project,
    private val binaryPath: String,
) : ProjectWideLspServerDescriptor(project, "xgrep Security Scanner") {

    override fun isSupportedFile(file: VirtualFile): Boolean =
        XgrepLanguages.isSupported(file.name)

    override fun createCommandLine(): GeneralCommandLine {
        val rulesPath = MondooSettings.getInstance().state.xgrepRulesPath.orEmpty()
        val command = GeneralCommandLine(binaryPath)
        // `-f` precedes the subcommand: `xgrep -f <rules> lsp`.
        if (rulesPath.isNotBlank()) {
            command.addParameters("-f", rulesPath)
        }
        command.addParameter("lsp")
        project.basePath?.let { command.withWorkDirectory(it) }
        return command
    }

    /**
     * The platform default falls through to the raw file extension for anything
     * it does not recognise, which would send xgrep ids it does not know
     * (`cs`, `kt`, `rs`, `sh`, `yml`). Use our explicit table instead.
     */
    override fun getLanguageId(file: VirtualFile): String =
        XgrepLanguages.languageIdFor(file.name)

    /** See [XgrepLspCustomization]: diagnostics and code actions only. */
    override val lspCustomization = XgrepLspCustomization()

    override fun createInitializationOptions(): Any? {
        val scanJobs = MondooSettings.getInstance().state.xgrepScanJobs
        return if (scanJobs >= 1) XgrepInitializationOptions(scanJobs) else null
    }
}

/** Serialized by lsp4j's Gson into `InitializeParams.initializationOptions`. */
internal data class XgrepInitializationOptions(val scanJobs: Int)
