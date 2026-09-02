// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

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
import com.mondoo.intellij.util.ProjectTrust
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
        // Never spawn the scanner over a project the user has not trusted.
        if (!ProjectTrust.isTrusted(project)) {
            LOG.info("project is not trusted; not starting the scanner")
            return
        }
        if (!MondooSettings.getInstance().state.xgrepEnabled) {
            LOG.debug("xgrep disabled by setting; not starting for ${file.name}")
            return
        }
        if (!XgrepLanguages.isSupported(file.name)) return
        if (!inScope(project, file)) {
            LOG.debug("out of scan scope, not starting for ${file.name}")
            return
        }

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

/**
 * Applies the include/exclude globs.
 *
 * IntelliJ's LSP client has no middleware hook, so unlike the VS Code extension
 * this cannot be enforced in one place. It is applied twice instead: here, so an
 * out-of-scope file is never synced to the server, and again when diagnostics are
 * published, so findings for a newly-excluded file disappear.
 *
 * Not in `isSupportedFile`: that is annotated `@RequiresReadLock`, its result is
 * cached, and it is documented to depend only on the file — a settings lookup
 * there would be wrong.
 */
internal fun inScope(project: Project, file: VirtualFile): Boolean {
    val scope = MondooSettings.getInstance().scanScope()
    if (scope.includePatterns.isEmpty() && scope.excludePatterns.isEmpty()) return true
    val base = project.basePath ?: return true
    val relative = runCatching {
        java.nio.file.Path.of(base).relativize(file.toNioPath()).toString()
    }.getOrNull() ?: return true
    return scope.isScanned(relative)
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

    /**
     * Decorates the platform's notifications handler so published diagnostics are
     * mirrored into the plugin's own findings store. See [XgrepNotificationsHandler]
     * for why this is the only available seam.
     */
    override fun createLsp4jClient(handler: com.intellij.platform.lsp.api.LspServerNotificationsHandler) =
        XgrepLsp4jClient(
            XgrepNotificationsHandler(
                handler,
                project,
                project.basePath?.let { java.nio.file.Path.of(it) },
            ),
        )

    override fun createInitializationOptions(): Any? {
        val scanJobs = MondooSettings.getInstance().state.xgrepScanJobs
        return if (scanJobs >= 1) XgrepInitializationOptions(scanJobs) else null
    }
}

/** Serialized by lsp4j's Gson into `InitializeParams.initializationOptions`. */
internal data class XgrepInitializationOptions(val scanJobs: Int)
