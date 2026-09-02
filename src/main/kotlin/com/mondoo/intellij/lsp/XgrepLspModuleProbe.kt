// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.lsp

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

private val LOG = Logger.getInstance("com.mondoo.intellij.lsp.XgrepLspModuleProbe")

internal const val LSP_MODULE_LOADED_MARKER =
    "Mondoo: LSP module loaded (com.intellij.modules.lsp is available)"

/**
 * Announces that the optional LSP module loaded, at **application** level.
 *
 * `<depends optional>` is completely silent: the platform logs nothing whether the
 * dependency resolved or not, so without this there is no way to tell whether a
 * given IDE exposes `com.intellij.modules.lsp`.
 *
 * Application-level on purpose. A `ProjectActivity` only fires once a project has
 * finished opening, so an IDE closed at the welcome screen — or an experiment cut
 * short — produces no line and looks indistinguishable from "the module is
 * missing". That false negative already caused one wrong conclusion; see
 * docs/adr/0001.
 */
internal class XgrepLspModuleProbe : ApplicationInitializedListener {
    override suspend fun execute() {
        LOG.info(LSP_MODULE_LOADED_MARKER)
    }
}

/** Same signal per project, for diagnosing project-scoped problems. */
internal class XgrepLspProjectProbe : ProjectActivity {
    override suspend fun execute(project: Project) {
        LOG.info("Mondoo: LSP module active for project ${project.name}")
    }
}
