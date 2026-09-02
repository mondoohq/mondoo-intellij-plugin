package com.mondoo.intellij.lsp

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
        runCatching {
            val path = pathOf(params.uri)
            // Second scope enforcement point. A file already synced before the user
            // added an exclude would otherwise keep its findings; dropping them here
            // is the analogue of VS Code's `handleDiagnostics: next(uri, [])`.
            if (!scope().isScanned(path)) {
                XgrepFindingsStore.getInstance(project).update(params.uri, emptyList())
                return
            }
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
        // Always let the platform highlight, whatever our bookkeeping did.
        delegate.publishDiagnostics(params)
    }

    override fun showMessage(params: MessageParams) {
        if (XgrepScanMessages.isScanResult(params.message.orEmpty())) {
            XgrepScanNotifier.onScanCompleted(project, params.message.orEmpty())
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
}
