package com.mondoo.intellij.util

/**
 * Maps a file name to the language id xgrep expects.
 *
 * Deliberately keyed by file name / extension rather than by IntelliJ
 * [com.intellij.lang.Language]: GoLand has no Python language, Android Studio has
 * no Go language, and a `.py` file in either resolves to `PlainTextLanguage`.
 * Keying off `Language` would silently stop scanning in exactly the IDEs this
 * plugin exists to support.
 *
 * The platform's own default `getLanguageId()` is also extension-based, but it
 * falls through to the raw extension for anything it does not know — emitting
 * `cs`, `kt`, `rs`, `sh`, `yml`, none of which are xgrep language ids. Hence
 * this explicit table.
 *
 * Ported from `XGREP_LANGUAGES` in vscode-mondoo/src/services/xgrepService.ts,
 * re-keyed from VS Code language ids to extensions.
 *
 * Pure: no platform types, fully unit-testable.
 */
object XgrepLanguages {

    private val BY_EXTENSION: Map<String, String> = mapOf(
        "py" to "python",
        "pyi" to "python",
        "go" to "go",
        "java" to "java",
        "js" to "javascript",
        "cjs" to "javascript",
        "mjs" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript",
        "mts" to "typescript",
        "cts" to "typescript",
        "tsx" to "typescriptreact",
        "rb" to "ruby",
        "rake" to "ruby",
        "gemspec" to "ruby",
        "rs" to "rust",
        "c" to "c",
        "h" to "c",
        "cc" to "cpp",
        "cpp" to "cpp",
        "cxx" to "cpp",
        "c++" to "cpp",
        "hpp" to "cpp",
        "hh" to "cpp",
        "hxx" to "cpp",
        "cs" to "csharp",
        "kt" to "kotlin",
        "kts" to "kotlin",
        "scala" to "scala",
        "sc" to "scala",
        "php" to "php",
        "phtml" to "php",
        "lua" to "lua",
        "sh" to "shellscript",
        "bash" to "shellscript",
        "zsh" to "shellscript",
        "ksh" to "shellscript",
        "html" to "html",
        "htm" to "html",
        "json" to "json",
        "yaml" to "yaml",
        "yml" to "yaml",
    )

    private val BY_FILE_NAME: Map<String, String> = mapOf(
        "rakefile" to "ruby",
        "gemfile" to "ruby",
    )

    /** The xgrep language id for [fileName], or an empty string when unsupported. */
    fun languageIdFor(fileName: String): String {
        val lower = fileName.lowercase()
        BY_FILE_NAME[lower]?.let { return it }
        if (!lower.contains('.')) return ""
        val extension = lower.substringAfterLast('.')
        return BY_EXTENSION[extension] ?: ""
    }

    fun isSupported(fileName: String): Boolean = languageIdFor(fileName).isNotEmpty()

    /** Every distinct xgrep language id this plugin can produce. */
    val supportedLanguageIds: Set<String>
        get() = (BY_EXTENSION.values + BY_FILE_NAME.values).toSet()
}
