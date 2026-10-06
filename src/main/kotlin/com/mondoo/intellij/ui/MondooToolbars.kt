// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionUtil

/**
 * Builds the Mondoo tool window's tab toolbars from the registered action groups,
 * so each tab carries the actions that belong to it and the menu can stay short.
 */
object MondooToolbars {

    /** The registered action or group [id], or null where its module did not load. */
    fun action(id: String): AnAction? = ActionManager.getInstance().getAction(id)

    /**
     * The action [id] shown with its text next to its icon. For the few actions a tab
     * is about (Scan, Generate SBOM, Bundle, Target), an icon alone was too easy to miss.
     */
    fun labeled(id: String): AnAction? = action(id)?.also {
        it.templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }
}
