package com.mondoo.intellij.lsp

/**
 * Recognises the scan-completion messages xgrep sends over `window/showMessage`.
 *
 * The server reports on-demand scans this way rather than through a response, so
 * this regex is the only signal that a workspace or changed-files scan finished.
 * Ported verbatim from `SCAN_RESULT_RE` in
 * vscode-mondoo/src/services/xgrepService.ts, and re-verified against xgrep 0.57.
 *
 * Pure: unit-tested without an IDE.
 */
object XgrepScanMessages {

    private val SCAN_RESULT = Regex("""^xgrep: (workspace|changed-files) scan (found|failed)""")

    fun isScanResult(message: String): Boolean = SCAN_RESULT.containsMatchIn(message)

    /** True when the message reports a failure rather than a result. */
    fun isFailure(message: String): Boolean =
        SCAN_RESULT.find(message)?.groupValues?.get(2) == "failed"
}
