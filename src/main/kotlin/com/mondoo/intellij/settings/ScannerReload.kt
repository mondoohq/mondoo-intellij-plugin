// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.settings

import com.intellij.openapi.project.Project

/**
 * Restarts the xgrep language server, reached reflectively.
 *
 * The restart lives in the optional LSP module. A direct reference would pull
 * com.intellij.modules.lsp into the core plugin and break loading wherever that
 * module is absent — the whole point of keeping LSP optional. A no-op when the
 * module did not load, which is correct: there is no server to restart.
 */
object ScannerReload {
    fun restart(project: Project) {
        runCatching {
            val actionClass = Class.forName("com.mondoo.intellij.lsp.ReloadRulesAction", false, javaClass.classLoader)
            val companion = actionClass.getDeclaredField("Companion").get(null)
            companion.javaClass.getMethod("restart", Project::class.java).invoke(companion, project)
        }
    }
}
