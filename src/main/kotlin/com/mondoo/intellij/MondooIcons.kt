// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij

import com.intellij.openapi.util.IconLoader

/**
 * The Mondoo mark, shared with the VS Code extension.
 *
 * The marketplace icon (`META-INF/pluginIcon.svg`) keeps the brand purple, because
 * that is a listing where the plugin should look like itself. In-IDE icons follow the
 * theme instead, as every other icon in a toolbar does — `IconLoader` picks the
 * `_dark` variant automatically.
 */
object MondooIcons {
    @JvmField
    val Mondoo = IconLoader.getIcon("/icons/mondoo.svg", MondooIcons::class.java)

    /** Fixing a finding: a wrench with a spark, so it reads as "apply a fix", not "run". */
    @JvmField
    val Fix = IconLoader.getIcon("/icons/fix.svg", MondooIcons::class.java)
}
