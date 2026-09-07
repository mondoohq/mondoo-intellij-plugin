// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

private val LOG = Logger.getInstance("com.mondoo.intellij.lsp.XgrepLspModuleProbe")

internal const val LSP_MODULE_LOADED_MARKER =
    "Mondoo: LSP module loaded (com.intellij.modules.lsp is available)"

/**
 * Announces that the optional LSP module loaded.
 *
 * `<depends optional>` is completely silent — the platform logs nothing whether the
 * dependency resolved or not — so without this there is no way to tell from a log
 * whether a given IDE exposes `com.intellij.modules.lsp`. That silence caused one
 * wrong conclusion during development; see docs/adr/0001.
 *
 * This class lives inside the optional module, so its running at all is the proof.
 *
 * It used to be an `ApplicationInitializedListener`, which fired before any project
 * opened. That was dropped, and the reason is worth recording because it was a bad
 * trade rather than a neutral one:
 *
 *  - `ApplicationInitializedListener` is `@ApiStatus.Internal`, and its
 *    `componentsInitialized` is deprecated as well — five internal and two deprecated
 *    API usages, all for a log line.
 *  - Worse, `com.intellij.applicationInitializedListener` is a **non-dynamic**
 *    extension point, so declaring it meant the whole plugin could not be enabled or
 *    disabled without restarting the IDE. Every user paid that, every time.
 *  - `AppLifecycleListener` is no better: also `@ApiStatus.Internal`.
 *
 * A project activity is dynamic and public API. What it cannot do is report at the
 * welcome screen, and that loss is covered elsewhere: **Show Tool Paths** reports
 * whether live scanning is available without needing a log at all, which is the form
 * a user can actually act on.
 */
internal class XgrepLspModuleProbe : ProjectActivity {
    override suspend fun execute(project: Project) {
        LOG.info(LSP_MODULE_LOADED_MARKER)
        LOG.info("Mondoo: LSP module active for project ${project.name}")
    }
}

/**
 * Whether the LSP client API is present in this IDE.
 *
 * A class-presence check rather than an extension point, so it can be asked from
 * anywhere — including the core plugin, which must not link against the LSP module.
 */
internal object LspAvailability {
    fun isPresent(): Boolean = runCatching {
        Class.forName("com.intellij.platform.lsp.api.LspServerManager", false, javaClass.classLoader)
    }.isSuccess
}
