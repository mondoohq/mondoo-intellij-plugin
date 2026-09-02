package com.mondoo.intellij.lsp

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspDocumentColorDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspSelectionRangeDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolDisabled
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * Restricts the client to what xgrep actually is: a scanner.
 *
 * This is the most important correctness detail in the LSP layer. [LspCustomization]
 * drives `createClientCapabilities()`, so a capability left enabled is *advertised*
 * to the server and the IDE will issue those requests. xgrep answers none of them —
 * its capabilities are exactly `publishDiagnostics`, `codeAction` and
 * `executeCommand` (verified 2026-09-01; see docs/adr/0001) — so leaving them on
 * means a security scanner injecting itself into completion, go-to-definition and
 * rename, competing with GoLand's Go support or PyCharm's Python support.
 *
 * Everything is therefore off except diagnostics and code actions.
 */
internal class XgrepLspCustomization : LspCustomization() {

    override val diagnosticsCustomizer = XgrepDiagnosticsSupport()

    override val codeActionsCustomizer = object : LspCodeActionsSupport() {
        /** xgrep's quick fixes: one per finding with a computed fix. */
        override val quickFixesSupport = true

        /**
         * xgrep has no caret-context actions, so asking for them on every caret move
         * would be pure round-trip cost.
         */
        override val intentionActionsSupport = false
    }

    // --- Everything below: not provided by xgrep, and owned by the IDE. ---
    override val completionCustomizer = LspCompletionDisabled
    override val hoverCustomizer = LspHoverDisabled
    override val goToDefinitionCustomizer = LspGoToDefinitionDisabled
    override val goToTypeDefinitionCustomizer = LspGoToTypeDefinitionDisabled
    override val findReferencesCustomizer = LspFindReferencesDisabled
    override val formattingCustomizer = LspFormattingDisabled
    override val onTypeFormattingCustomizer = LspOnTypeFormattingDisabled
    override val optimizeImportsCustomizer = LspOptimizeImportsDisabled
    override val renameCustomizer = LspRenameDisabled
    override val semanticTokensCustomizer = LspSemanticTokensDisabled
    override val documentSymbolCustomizer = LspDocumentSymbolDisabled
    override val workspaceSymbolCustomizer = LspWorkspaceSymbolDisabled
    override val documentHighlightsCustomizer = LspDocumentHighlightsDisabled
    override val documentLinkCustomizer = LspDocumentLinkDisabled
    override val documentColorCustomizer = LspDocumentColorDisabled
    override val foldingRangeCustomizer = LspFoldingRangeDisabled
    override val inlayHintCustomizer = LspInlayHintDisabled
    override val codeLensCustomizer = LspCodeLensDisabled
    override val signatureHelpCustomizer = LspSignatureHelpDisabled
    override val selectionRangeCustomizer = LspSelectionRangeDisabled
    override val callHierarchyCustomizer = LspCallHierarchyDisabled
    override val typeHierarchyCustomizer = LspTypeHierarchyDisabled
}

/**
 * Maps xgrep diagnostics into the editor, and attaches the plugin's own quick fixes.
 */
internal class XgrepDiagnosticsSupport : LspDiagnosticsSupport() {

    /**
     * xgrep pushes diagnostics; it advertises no `diagnosticProvider`, so the IDE
     * must never pull.
     */
    override fun shouldAskServerForDiagnostics(file: com.intellij.openapi.vfs.VirtualFile): Boolean = false

    override fun getHighlightSeverity(diagnostic: Diagnostic): HighlightSeverity =
        when (diagnostic.severity) {
            DiagnosticSeverity.Error -> HighlightSeverity.ERROR
            DiagnosticSeverity.Warning -> HighlightSeverity.WARNING
            else -> HighlightSeverity.WEAK_WARNING
        }

    /** Prefixes the rule id so a finding is attributable at a glance. */
    override fun getMessage(diagnostic: Diagnostic): String {
        val ruleId = xgrepDataOf(diagnostic)?.ruleId ?: return diagnostic.message
        return "$ruleId: ${diagnostic.message}"
    }

    override fun getTooltip(diagnostic: Diagnostic): String {
        val data = xgrepDataOf(diagnostic) ?: return escape(diagnostic.message)
        return buildString {
            data.ruleId?.let { append("<b>").append(escape(it)).append("</b><br/>") }
            append(escape(diagnostic.message))
            if (data.cwe.isNotEmpty()) {
                append("<br/><br/>").append(data.cwe.joinToString("<br/>") { escape(it) })
            }
            if (data.owasp.isNotEmpty()) {
                append("<br/>OWASP: ").append(escape(data.owasp.joinToString(", ")))
            }
        }
    }

    private fun escape(text: String): String = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text)
}
