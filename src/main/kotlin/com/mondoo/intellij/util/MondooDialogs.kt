// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * The plugin's one "pick one of these" prompt.
 *
 * `Messages.showChooseDialog` is deprecated in both its overloads at 2026.1.4, and
 * the platform ships no non-deprecated equivalent: `showEditableChooseDialog` returns
 * free text from an editable field, which is a different question. The alternative is
 * a hand-written `DialogWrapper` per site, and eight of those is eight new dialogs to
 * get right by hand for no behaviour the user would notice.
 *
 * So it stays, deliberately and in exactly one place. That is the point of this file:
 * one suppression to review instead of eight scattered warnings, and one edit when
 * the platform finally offers a replacement.
 *
 * The project may be null — it only parents the dialog — matching the platform's
 * own nullability here.
 *
 * Must be called on the EDT, like any modal dialog.
 */
object MondooDialogs {

    /** The index chosen, or null when the user cancelled. */
    @Suppress("DEPRECATION")
    fun choose(
        project: Project?,
        message: String,
        title: String,
        options: List<String>,
        initial: String = options.first(),
    ): Int? {
        if (options.isEmpty()) return null
        val index = Messages.showChooseDialog(
            project,
            message,
            title,
            null,
            options.toTypedArray(),
            initial,
        )
        return index.takeIf { it >= 0 }
    }

    /** The chosen element, or null when the user cancelled. */
    fun <T> chooseFrom(
        project: Project?,
        message: String,
        title: String,
        items: List<T>,
        label: (T) -> String,
    ): T? {
        val labels = items.map(label)
        return choose(project, message, title, labels)?.let(items::getOrNull)
    }
}
