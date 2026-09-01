package com.mondoo.intellij.binary

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.lsp.api.LspServerManager
import java.nio.file.Path

/**
 * Restarts language servers once a binary becomes available.
 *
 * `fileOpened` must not block, so it returns early when no binary is resolved yet.
 * Something therefore has to re-trigger server startup once an install finishes;
 * this is it.
 *
 * Lives in the core (not the optional LSP module) so the installer has nothing to
 * conditionally link against, and reaches the LSP API reflectively — a direct
 * reference would drag `com.intellij.modules.lsp` into the core plugin and break
 * loading wherever that module is absent.
 */
internal object XgrepInstallListener {

    fun notifyInstalled(@Suppress("UNUSED_PARAMETER") binary: Path) {
        ProjectManager.getInstanceIfCreated()?.openProjects?.forEach(::restartServers)
    }

    private fun restartServers(project: Project) {
        if (project.isDisposed) return
        runCatching {
            val providerClass = Class.forName(
                "com.mondoo.intellij.lsp.XgrepLspServerSupportProvider",
                false,
                XgrepInstallListener::class.java.classLoader,
            )
            @Suppress("UNCHECKED_CAST")
            LspServerManager.getInstance(project).startServersIfNeeded(
                providerClass as Class<out com.intellij.platform.lsp.api.LspServerSupportProvider>,
            )
        }
    }
}
