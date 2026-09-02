// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.mondoo.intellij.settings.MondooSettings

/**
 * Resolves the scanner in the background when a project opens, and offers an
 * update when one is due.
 *
 * Deliberately does not download anything on first run: an IDE that reaches out
 * to the network and installs a binary before the user has asked for anything is
 * a surprise. Discovery is silent; installation waits for either the
 * `xgrepAutoInstall` setting or the explicit "Set Up Scanner" action.
 */
internal class XgrepStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = MondooSettings.getInstance().state
        if (!settings.xgrepEnabled) return

        val service = XgrepBinaryService.getInstance()
        if (service.resolvedBinaryOrNull() != null) {
            service.checkForUpdate(project)
        } else if (settings.xgrepAutoInstall) {
            service.ensureInstalled(project)
        }
    }
}
