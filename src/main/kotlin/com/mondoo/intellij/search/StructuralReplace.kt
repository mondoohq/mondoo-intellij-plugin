// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.search

/**
 * Decides which structural matches can be rewritten, and in what order.
 *
 * Two rules, both of which produce corrupted files if got wrong, and neither of which
 * needs an editor to reason about — so they live here, away from the platform.
 *
 * **Last first.** Applying an edit shifts everything after it. Rewriting a file from
 * the top means every subsequent match's offsets are stale by the length difference of
 * the ones before it. Descending order leaves the untouched region ahead of each edit
 * exactly where the scanner found it.
 *
 * **Overlaps are dropped, not applied.** A pattern can match inside its own match —
 * `f($X)` against `f(f(1))` matches twice, nested. Rewriting both would splice one
 * replacement into text the other already replaced, and the result belongs to neither.
 * The outer match wins, being the one the user is most likely to have meant, and the
 * inner is discarded rather than silently mangled.
 *
 * Pure: no platform types.
 */
object StructuralReplace {

    /**
     * The matches to apply, ordered last-first within each file.
     *
     * Matches without a replacement are excluded: the server only fills that in for a
     * replace search, and a null one means there is nothing to write.
     */
    fun plan(matches: List<XgrepSearchMatch>): List<XgrepSearchMatch> =
        matches
            .filter { it.replacement != null }
            .groupBy { it.path }
            .toSortedMap()
            .values
            .flatMap(::planForFile)

    /**
     * Selects going forwards, applies going backwards.
     *
     * The selection pass runs in document order so that when two matches overlap the
     * one that starts first — the enclosing one — is the one already kept. Reversing
     * afterwards gives the last-first order the edits have to be applied in.
     */
    private fun planForFile(matches: List<XgrepSearchMatch>): List<XgrepSearchMatch> {
        val ascending = matches.sortedWith(
            compareBy<XgrepSearchMatch> { it.line }.thenBy { it.column },
        )

        val kept = mutableListOf<XgrepSearchMatch>()
        for (match in ascending) {
            val previous = kept.lastOrNull()
            if (previous == null || beginsAfter(match, previous)) kept += match
        }
        return kept.asReversed()
    }

    /** True when [match] begins at or after [previous] ends, i.e. they do not overlap. */
    private fun beginsAfter(match: XgrepSearchMatch, previous: XgrepSearchMatch): Boolean =
        match.line > previous.endLine ||
            (match.line == previous.endLine && match.column >= previous.endColumn)

    /** How many matches [plan] refuses to apply, for telling the user. */
    fun skipped(matches: List<XgrepSearchMatch>): Int =
        matches.count { it.replacement != null } - plan(matches).size
}
