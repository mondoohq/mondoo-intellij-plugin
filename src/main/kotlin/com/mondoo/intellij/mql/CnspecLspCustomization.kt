// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mql

import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
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
 * Enables exactly what `cnspec lsp` advertises, and nothing more.
 *
 * Near the inverse of the xgrep client, which is the point of having two. Verified
 * against cnspec on 2026-09-02, its capabilities are:
 *
 *  - `textDocumentSync` (full), with `save.includeText`
 *  - `completionProvider`, trigger characters `.` and space, with resolve
 *  - `hoverProvider`
 *  - `codeActionProvider`, kinds `quickfix` and `source`
 *
 * There is **no** `executeCommandProvider`, so unlike xgrep there are no scans to
 * drive over LSP — the policy commands are CLI-only.
 *
 * Completion and hover are the reason this client exists: MQL has a large resource
 * schema that nobody memorises, and the server knows it. That is also why they are
 * safe to enable here where they were disabled for xgrep — for a `.mql.yaml` file no
 * other language plugin is competing to provide them.
 */
internal class CnspecLspCustomization : LspCustomization() {

    override val diagnosticsCustomizer = LspDiagnosticsSupport()

    /** MQL resource and field completion, the server's main value. */
    override val completionCustomizer = LspCompletionSupport()

    /** Documentation for a resource or field under the caret. */
    override val hoverCustomizer = LspHoverSupport()

    override val codeActionsCustomizer = object : LspCodeActionsSupport() {
        override val quickFixesSupport = true

        /** The server advertises the `source` kind, so caret-context actions are real. */
        override val intentionActionsSupport = true
    }

    // --- Not advertised by the server. ---
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
