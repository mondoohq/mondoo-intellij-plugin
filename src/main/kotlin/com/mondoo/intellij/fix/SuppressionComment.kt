// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

/**
 * Builds the `nogrep` suppression comment for a finding.
 *
 * Ported from `COMMENT_STYLES` / `buildSuppressionEdit` / `sanitizeSuppressionReason`
 * in vscode-mondoo/src/providers/xgrepSuppressionProvider.ts. A suppression written
 * here must be understood byte-for-byte by `xgrep scan` and by CI, so the two
 * parser constraints are preserved exactly:
 *
 * 1. The reason goes **before** the `nogrep:` keyword, because rule ids run to the
 *    end of the line.
 * 2. HTML uses the bare directive inside a block comment: a trailing `-->` would be
 *    parsed as part of a rule id.
 *
 * The style table is keyed by **file extension**, not by IntelliJ `Language`.
 * `LanguageCommenters` returns nothing for `PlainTextLanguage`, which is exactly
 * what a `.py` file resolves to in Android Studio and a `.rb` file in GoLand — the
 * IDEs where this must keep working.
 *
 * Pure: no platform types.
 */
object SuppressionComment {

    sealed interface Style {
        data class Line(val prefix: String) : Style
        data class Block(val open: String, val close: String) : Style
    }

    private val STYLES: Map<String, Style> = buildMap {
        val slash = Style.Line("//")
        val hash = Style.Line("#")
        listOf(
            "go", "java", "js", "cjs", "mjs", "jsx", "ts", "mts", "cts", "tsx",
            "rs", "c", "h", "cc", "cpp", "cxx", "c++", "hpp", "hh", "hxx",
            "cs", "kt", "kts", "scala", "sc", "php", "phtml",
        )
            .forEach { put(it, slash) }
        listOf("py", "pyi", "rb", "rake", "gemspec", "sh", "bash", "zsh", "ksh", "yaml", "yml")
            .forEach { put(it, hash) }
        put("lua", Style.Line("--"))
        put("html", Style.Block("<!--", "-->"))
        put("htm", Style.Block("<!--", "-->"))
        // .json is deliberately absent: JSON has no comments, so findings there
        // cannot be suppressed inline.
    }

    /** The comment style for [fileName], or null when suppression is impossible. */
    fun styleFor(fileName: String): Style? {
        val lower = fileName.lowercase()
        if (!lower.contains('.')) return null
        return STYLES[lower.substringAfterLast('.')]
    }

    fun isSupported(fileName: String): Boolean = styleFor(fileName) != null

    /**
     * Strips anything that would corrupt the directive: newlines, the `nogrep`
     * keyword itself, and a comment terminator.
     */
    fun sanitizeReason(reason: String): String =
        reason
            .replace(Regex("""[\r\n]+"""), " ")
            .replace(Regex("""\*/|-->"""), " ")
            .replace(Regex("""\bnogrep\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * The suppression line to insert above the finding.
     *
     * @param indent copied from the flagged line so the comment lines up.
     * @param reason optional justification; recorded before the keyword.
     */
    fun buildLine(fileName: String, ruleId: String, indent: String, reason: String? = null): String? {
        val style = styleFor(fileName) ?: return null
        val cleanReason = reason?.let(::sanitizeReason).orEmpty()

        return when (style) {
            is Style.Line -> {
                val body = if (cleanReason.isEmpty()) {
                    "nogrep: $ruleId"
                } else {
                    "$cleanReason nogrep: $ruleId"
                }
                "$indent${style.prefix} $body"
            }
            // In HTML the directive suppresses every rule on the line, and no rule
            // id may follow, because `-->` would be swallowed into it.
            is Style.Block -> "$indent${style.open} nogrep ${style.close}"
        }
    }

    /**
     * Extends an existing suppression comment with another rule id, producing
     * `nogrep: rule-one, rule-two`. Returns null when [existingLine] is not a
     * suppression comment, or already covers [ruleId].
     */
    fun appendRule(existingLine: String, ruleId: String): String? {
        val match = DIRECTIVE.find(existingLine) ?: return null
        val ids = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ruleId in ids) return null
        val replacement = "nogrep: " + (ids + ruleId).joinToString(", ")
        return existingLine.replaceRange(match.range, replacement)
    }

    /** Matches `nogrep: a, b` and captures the id list. */
    private val DIRECTIVE = Regex("""nogrep:\s*([^\r\n]*)""")
}
