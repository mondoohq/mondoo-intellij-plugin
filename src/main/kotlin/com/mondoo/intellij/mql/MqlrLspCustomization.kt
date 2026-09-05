// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mql

import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspDocumentColorDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolSupport
import com.intellij.platform.lsp.api.customization.LspFindReferencesSupport
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionSupport
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverSupport
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspSelectionRangeDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolDisabled

/**
 * Enables exactly what `mqlr lsp --mode=server` advertises, and nothing more.
 *
 * Taken from the server's own initialize response rather than from documentation.
 * Probed against mqlr on 2026-09-05, its capabilities are exactly four:
 *
 *  - `hoverProvider`
 *  - `definitionProvider`
 *  - `referencesProvider`
 *  - `documentSymbolProvider`
 *
 * with `textDocumentSync` open/close and incremental changes.
 *
 * Worth stating because it contradicts the VS Code extension's own install prompt,
 * which offers mqlr as providing "diagnostics, completion, hover". There is no
 * `completionProvider`; advertising completion to the IDE would mean asking a server
 * that cannot answer on every keystroke.
 *
 * Diagnostics are enabled even though no `diagnosticProvider` is advertised, because
 * the server pushes them: a `textDocument/publishDiagnostics` arrives on open. Push
 * diagnostics need no capability, which is why the customizer is on and pulling is
 * off.
 */
internal class MqlrLspCustomization : LspCustomization() {

    /**
     * Push only. The server volunteers diagnostics and has no pull endpoint, so
     * asking would be a request nothing answers.
     */
    override val diagnosticsCustomizer = object : LspDiagnosticsSupport() {
        override fun shouldAskServerForDiagnostics(file: com.intellij.openapi.vfs.VirtualFile) = false
    }

    /** Documentation for the resource or field under the caret. */
    override val hoverCustomizer = LspHoverSupport()

    /** Jump from a field's type to the resource that defines it. */
    override val goToDefinitionCustomizer = LspGoToDefinitionSupport()

    /** Find usages of a resource across the schema. */
    override val findReferencesCustomizer = LspFindReferencesSupport()

    /** Populates the Structure view with the file's resources. */
    override val documentSymbolCustomizer = LspDocumentSymbolSupport()

    // --- Not advertised by the server. ---
    override val completionCustomizer = LspCompletionDisabled
    override val codeActionsCustomizer = object : LspCodeActionsSupport() {
        override val quickFixesSupport = false
        override val intentionActionsSupport = false
    }
    override val goToTypeDefinitionCustomizer = LspGoToTypeDefinitionDisabled
    override val formattingCustomizer = LspFormattingDisabled
    override val onTypeFormattingCustomizer = LspOnTypeFormattingDisabled
    override val optimizeImportsCustomizer = LspOptimizeImportsDisabled
    override val renameCustomizer = LspRenameDisabled
    override val semanticTokensCustomizer = LspSemanticTokensDisabled
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
