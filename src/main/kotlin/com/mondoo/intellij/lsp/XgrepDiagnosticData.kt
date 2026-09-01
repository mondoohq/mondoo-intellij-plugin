package com.mondoo.intellij.lsp

/**
 * The custom `data` payload xgrep attaches to every diagnostic.
 *
 * Captured from `xgrep lsp` on 2026-09-01 (see docs/adr/0001, appendix):
 *
 * ```json
 * "data": {
 *   "ruleId": "js-express-command-injection",
 *   "cwe": ["CWE-78: Improper Neutralization of Special Elements used in an OS Command..."],
 *   "owasp": ["A03:2021", "A05:2025"],
 *   "references": ["https://cheatsheetseries.owasp.org/..."],
 *   "hasFix": false,
 *   "fixKind": "assisted"
 * }
 * ```
 *
 * Decoding is deliberately total: older xgrep builds omit `data` entirely, and
 * newer ones may add fields. A malformed or partial payload must degrade to a
 * finding without extras, never throw inside `createAnnotation`.
 */
data class XgrepDiagnosticData(
    val ruleId: String? = null,
    val cwe: List<String> = emptyList(),
    val owasp: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val hasFix: Boolean = false,
    /** One of `deterministic`, `assisted`, `advisory`, or null on older builds. */
    val fixKind: String? = null,
) {
    /** True when a rule ships a mechanical fix the server can apply itself. */
    val hasDeterministicFix: Boolean get() = hasFix && fixKind == FIX_KIND_DETERMINISTIC

    /** True when the fix needs a model to author it against xgrep's fix contract. */
    val needsAssistedFix: Boolean get() = fixKind == FIX_KIND_ASSISTED

    companion object {
        const val FIX_KIND_DETERMINISTIC = "deterministic"
        const val FIX_KIND_ASSISTED = "assisted"
        const val FIX_KIND_ADVISORY = "advisory"

        /**
         * Decodes a decoded-JSON map. Pure — no Gson, no platform types — so the
         * whole contract is unit-testable without an IDE.
         */
        fun fromMap(map: Map<*, *>?): XgrepDiagnosticData? {
            if (map == null) return null
            return XgrepDiagnosticData(
                ruleId = map["ruleId"] as? String,
                cwe = stringList(map["cwe"]),
                owasp = stringList(map["owasp"]),
                references = stringList(map["references"]),
                hasFix = map["hasFix"] as? Boolean ?: false,
                fixKind = map["fixKind"] as? String,
            )
        }

        private fun stringList(value: Any?): List<String> =
            (value as? Iterable<*>)?.filterIsInstance<String>() ?: emptyList()
    }
}
