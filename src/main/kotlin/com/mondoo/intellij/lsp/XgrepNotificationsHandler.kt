// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.mondoo.intellij.findings.Finding
import com.mondoo.intellij.findings.FindingSeverity
import com.mondoo.intellij.findings.XgrepFindingsStore
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import java.net.URI
import java.nio.file.Path

/** The platform's client, wired to our decorating handler. */
internal class XgrepLsp4jClient(handler: LspServerNotificationsHandler) : Lsp4jClient(handler)

/**
 * Mirrors published diagnostics into [XgrepFindingsStore], then delegates.
 *
 * This decorator is the only available seam: [Lsp4jClient.publishDiagnostics] and
 * `showMessage` are `final override` in the platform, so they cannot be subclassed.
 * Kotlin's `by` delegation keeps everything we do not care about untouched.
 */
internal class XgrepNotificationsHandler(
    private val delegate: LspServerNotificationsHandler,
    private val project: Project,
    private val projectRoot: Path?,
) : LspServerNotificationsHandler by delegate {

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        val path = runCatching { pathOf(params.uri) }.getOrNull()

        // Second scope enforcement point. A file already synced before the user added
        // an exclude would otherwise keep its findings; dropping them here is the
        // analogue of VS Code's `handleDiagnostics: next(uri, [])`. A path we cannot
        // work out counts as in scope: silently hiding findings is the worse error.
        if (path != null && !scope().isScanned(path)) {
            XgrepFindingsStore.getInstance(project).update(params.uri, emptyList())
            // Both sides, not just ours. Clearing the store alone would leave the
            // editor squiggling a file the tool window no longer lists — precisely
            // the disagreement the store exists to prevent — so the platform is told
            // the file is clean rather than told nothing at all.
            delegate.publishDiagnostics(PublishDiagnosticsParams(params.uri, emptyList()))
            return
        }

        runCatching { mirror(params, path ?: params.uri) }
            .onFailure { LOG.warn("could not mirror diagnostics for ${params.uri}", it) }

        // Always let the platform highlight, whatever our bookkeeping did.
        delegate.publishDiagnostics(params)
    }

    private fun mirror(params: PublishDiagnosticsParams, path: String) {
        XgrepFindingsStore.getInstance(project).update(
            params.uri,
            params.diagnostics.orEmpty().map { diagnostic ->
                val data = xgrepDataOf(diagnostic)
                Finding(
                    path = path,
                    line = diagnostic.range.start.line,
                    column = diagnostic.range.start.character,
                    ruleId = data?.ruleId ?: diagnostic.code?.get()?.toString() ?: "xgrep",
                    message = diagnostic.message.orEmpty(),
                    severity = FindingSeverity.fromLsp(diagnostic.severity?.value),
                )
            },
        )
    }

    override fun showMessage(params: MessageParams) {
        if (XgrepScanMessages.isScanResult(params.message.orEmpty())) {
            XgrepScanNotifier.getInstance(project).onScanCompleted(params.message.orEmpty())
            return
        }
        delegate.showMessage(params)
    }

    private fun scope() = com.mondoo.intellij.settings.MondooSettings.getInstance().scanScope()

    /** Workspace-relative where possible, so the tree shows short paths. */
    private fun pathOf(uri: String): String {
        val absolute = runCatching { Path.of(URI(uri)) }.getOrNull()
            ?: return VfsUtilCore.urlToPath(uri)
        val root = projectRoot ?: return absolute.toString()
        return runCatching { root.relativize(absolute).toString() }.getOrDefault(absolute.toString())
    }

    private companion object {
        val LOG = Logger.getInstance(XgrepNotificationsHandler::class.java)
    }
}
