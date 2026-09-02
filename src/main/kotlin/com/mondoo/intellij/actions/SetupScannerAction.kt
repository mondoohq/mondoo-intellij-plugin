// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.mondoo.intellij.binary.XgrepBinaryService

/**
 * Finds or downloads the xgrep binary in one step.
 *
 * Forces the install even when automatic download is off: the user just asked for
 * it explicitly, which is exactly the consent that setting withholds by default.
 */
class SetupScannerAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        XgrepBinaryService.getInstance().ensureInstalled(e.project, force = true)
    }
}
