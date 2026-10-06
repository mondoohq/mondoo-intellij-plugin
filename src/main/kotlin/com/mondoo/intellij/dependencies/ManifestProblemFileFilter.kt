// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile

/**
 * Lets the Project view show a manifest with vulnerable dependencies in red.
 *
 * The IDE only marks a file as having problems when some plugin says its kind of
 * file is checked: source files qualify through the language server's own filter,
 * but `requirements.txt` and `package.json` qualified through nothing, so
 * [ManifestHighlighter]'s reports were dropped without a trace.
 */
class ManifestProblemFileFilter : Condition<VirtualFile> {
    override fun value(file: VirtualFile): Boolean = ManifestLocator.supports(file.name)
}
