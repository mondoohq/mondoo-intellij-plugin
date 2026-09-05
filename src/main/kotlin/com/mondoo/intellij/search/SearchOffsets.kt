// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.search

import com.intellij.openapi.editor.Document

/**
 * Turns a scanner match into a range in the document the editor currently holds.
 *
 * Shared by the search view and the replace, and it has to be: showing a match at one
 * range and rewriting a different one is how a replace corrupts a file.
 *
 * Every offset is clamped. The document is what the editor holds now; the match
 * describes what the scanner read moments ago, and a file edited or reverted in
 * between can be shorter than the match claims. The platform's offset methods throw
 * rather than clamp, and an unguarded `getLineStartOffset` past the end takes down the
 * whole operation rather than the one stale hit.
 */
internal object SearchOffsets {

    /** The match's span in [document], or null when it cannot land in one. */
    fun rangeIn(document: Document, match: XgrepSearchMatch): IntRange? {
        if (match.line >= document.lineCount) return null

        val lineStart = document.getLineStartOffset(match.line)
        val start = (lineStart + match.column).coerceIn(lineStart, document.getLineEndOffset(match.line))

        val endLine = match.endLine.coerceIn(match.line, document.lineCount - 1)
        val endLimit = document.getLineEndOffset(endLine).coerceAtLeast(start)
        val end = (document.getLineStartOffset(endLine) + match.endColumn).coerceIn(start, endLimit)

        return start..end
    }
}
