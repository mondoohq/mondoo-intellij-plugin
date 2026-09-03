// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.util

/**
 * Glob matching for the include/exclude scan-scope settings.
 *
 * Semantics ported verbatim from `globToRegExp`/`isPathExcluded` in
 * vscode-mondoo/src/utils/globUtils.ts, so a pattern behaves identically in both
 * editors and in the user documentation:
 *
 * - `*` matches within one path segment, `**` spans segments, `?` matches one character.
 * - A pattern containing `/` matches against the workspace-relative path.
 * - A pattern without `/` matches any single path segment, at any depth.
 *
 * Pure: no platform types.
 */
object GlobMatcher {

    fun matches(pattern: String, relativePath: String): Boolean {
        val path = relativePath.replace('\\', '/')
        return if (pattern.contains('/')) {
            toRegex(pattern).matches(path)
        } else {
            // Segment pattern: match any single segment anywhere in the path.
            val regex = toRegex(pattern)
            path.split('/').any { regex.matches(it) }
        }
    }

    private fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' ->
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        // `**` spans segments; swallow a following slash so that
                        // `src/generated/**` also matches `src/generated`.
                        sb.append(".*")
                        i++
                        if (i + 1 < pattern.length && pattern[i + 1] == '/') i++
                    } else {
                        sb.append("[^/]*")
                    }

                '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}

/**
 * The effective scan scope: which workspace-relative paths xgrep should see.
 *
 * IntelliJ's LSP API has no client middleware, so this is applied in two places
 * rather than one: as an early return in `fileOpened` (so out-of-scope files are
 * never synced) and as a diagnostics filter (so already-published findings for
 * newly-excluded files disappear).
 */
data class XgrepScanScope(val includePatterns: List<String>, val excludePatterns: List<String>) {
    fun isScanned(relativePath: String): Boolean {
        if (excludePatterns.any { GlobMatcher.matches(it, relativePath) }) return false
        if (includePatterns.isEmpty()) return true
        return includePatterns.any { GlobMatcher.matches(it, relativePath) }
    }

    /**
     * True when [next] scans strictly more than this scope does, which is the case
     * that needs a language-server restart: files skipped under the old scope were
     * never sent to the server and have no diagnostics to show.
     */
    fun broadenedBy(next: XgrepScanScope): Boolean {
        val excludesDropped = excludePatterns.any { it !in next.excludePatterns }
        val includesDropped = includePatterns.isNotEmpty() &&
            (next.includePatterns.isEmpty() || includePatterns.any { it !in next.includePatterns })
        return excludesDropped || includesDropped
    }
}
