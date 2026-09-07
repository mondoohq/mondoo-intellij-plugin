// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.binary.MqlrBinaryService
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.lsp.LspAvailability

/**
 * Reports which binary each feature resolved to.
 *
 * All three at once rather than an action each. "Which xgrep am I running" and "why is
 * there no MQL support" are the same question asked about different tools, and the
 * answer to either is more useful next to the others — a machine with two cnspecs on
 * the PATH is exactly the situation this exists to expose.
 *
 * The action id still says Xgrep because that is what it was; renaming it would break
 * anyone's keymap for no gain.
 */
class ShowXgrepPathAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val lines = listOf(
            "xgrep" to XgrepBinaryService.getInstance().resolvedBinaryOrNull(),
            "cnspec" to CnspecBinaryService.getInstance().resolvedBinaryOrNull(),
            "mqlr" to MqlrBinaryService.getInstance().resolvedBinaryOrNull(),
        ).joinToString("\n") { (tool, path) ->
            "$tool: ${path ?: "not found"}"
        }

        // Whether live scanning is possible at all, which is a different question from
        // whether a binary exists: the LSP client API is an optional platform module,
        // and where it is absent the scanners still run on demand but nothing appears
        // as you type. This is the first thing worth knowing in a "no findings" report,
        // and asking here beats asking someone for a log file.
        val liveScanning = if (LspAvailability.isPresent()) {
            "available"
        } else {
            "unavailable — this IDE has no LSP client, so findings appear only on an explicit scan"
        }

        Messages.showInfoMessage(
            e.project,
            "$lines\n\nLive scanning: $liveScanning" +
                "\n\nSet any of these explicitly in Settings | Tools | Mondoo.",
            "Mondoo Tool Paths",
        )
    }
}
