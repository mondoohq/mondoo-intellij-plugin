// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.search

/**
 * One structural-search hit from the `xgrep.search` execute-command.
 *
 * The server answers synchronously with an array of
 * `{path, line, col, endLine, endCol, text, replacement?}`. Verified against
 * xgrep on 2026-09-01; see docs/adr/0001, appendix.
 *
 * **Positions arrive 1-based** and are converted here, once, on the way in.
 * IntelliJ documents, offsets and `LogicalPosition` are all 0-based, so leaving
 * the conversion to call sites is how off-by-one highlight ranges happen.
 *
 * Pure: no platform types.
 */
data class XgrepSearchMatch(
    val path: String,
    /** 0-based. */
    val line: Int,
    /** 0-based. */
    val column: Int,
    /** 0-based. */
    val endLine: Int,
    /** 0-based. */
    val endColumn: Int,
    val text: String,
    /** Present only for a replace search; `$metavariables` already substituted. */
    val replacement: String? = null,
) {
    companion object {
        /**
         * Decodes the decoded-JSON array the server returns.
         *
         * Total by construction: a `null` result means the request timed out or
         * failed, which must never be mistaken for "no matches", so callers pass
         * null through as unavailable rather than as an empty list. Individual
         * malformed rows are skipped.
         */
        fun parseAll(rows: List<*>?): List<XgrepSearchMatch> =
            rows.orEmpty().mapNotNull { parse(it as? Map<*, *>) }

        fun parse(row: Map<*, *>?): XgrepSearchMatch? {
            if (row == null) return null
            val path = row["path"] as? String ?: return null
            val line = intOf(row["line"]) ?: return null
            val col = intOf(row["col"]) ?: return null
            // The server always sends endLine/endCol, but degrade to a zero-width
            // range rather than dropping the match if a future build omits them.
            val endLine = intOf(row["endLine"]) ?: line
            val endCol = intOf(row["endCol"]) ?: col
            return XgrepSearchMatch(
                path = path,
                line = zeroBased(line),
                column = zeroBased(col),
                endLine = zeroBased(endLine),
                endColumn = zeroBased(endCol),
                text = row["text"] as? String ?: "",
                replacement = row["replacement"] as? String,
            )
        }

        private fun zeroBased(oneBased: Int): Int = (oneBased - 1).coerceAtLeast(0)

        private fun intOf(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.toInt()
            // Gson decodes JSON numbers as Double.
            is Double -> value.toInt()
            is Number -> value.toInt()
            else -> null
        }
    }
}
