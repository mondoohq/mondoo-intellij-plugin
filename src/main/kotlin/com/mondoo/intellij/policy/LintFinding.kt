// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.google.gson.JsonParser

/**
 * A policy-lint finding, parsed from `cnspec policy lint -o sarif`.
 *
 * The linter is complementary to the language server, not a duplicate of it. On a
 * bundle with a compile error and missing tags the server reported one diagnostic
 * and the linter reported seven: the server checks the MQL, the linter also checks
 * policy hygiene — required tags, asset filters, unused queries. Both are worth
 * showing, which is why this exists alongside the LSP client.
 *
 * Positions are SARIF's 1-based line/column; [line] and [column] are converted to
 * the 0-based values IntelliJ uses, once, here.
 *
 * Pure: unit-tested without an IDE or cnspec.
 */
data class LintFinding(
    /** Path as the linter reported it, relative to the bundle's directory. */
    val path: String,
    /** 0-based. */
    val line: Int,
    /** 0-based. */
    val column: Int,
    val ruleId: String,
    val message: String,
    val severity: LintSeverity,
)

enum class LintSeverity {
    ERROR,
    WARNING,
    INFO,
    ;

    companion object {
        /** SARIF levels: error, warning, note, none. */
        fun of(level: String?): LintSeverity = when (level?.lowercase()) {
            "error" -> ERROR
            "warning" -> WARNING
            else -> INFO
        }
    }
}

object LintReport {

    /**
     * Parses a SARIF document.
     *
     * Total: cnspec exits non-zero when it finds problems but still writes valid
     * SARIF, so the exit code cannot be used to decide whether to parse. Malformed
     * output yields null rather than throwing, and one bad result is skipped rather
     * than losing the rest.
     */
    fun parse(sarif: String): List<LintFinding>? = runCatching {
        val root = JsonParser.parseString(sarif).asJsonObject
        val runs = root.getAsJsonArray("runs") ?: return emptyList()

        runs.flatMap { run ->
            run.asJsonObject.getAsJsonArray("results").orEmpty().mapNotNull { element ->
                runCatching {
                    val result = element.asJsonObject

                    // A result with no location cannot be attached to a document, so
                    // it is dropped rather than shown at the top of an arbitrary file.
                    val location = result.getAsJsonArray("locations")
                        ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("physicalLocation")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null

                    // A location with no region is fine — the finding is about the
                    // file as a whole, so it lands at the start of it.
                    val region = location["region"]?.takeIf { it.isJsonObject }?.asJsonObject

                    LintFinding(
                        path = location["artifactLocation"]?.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("uri")?.asString.orEmpty(),
                        // SARIF is 1-based; the IDE is 0-based. Convert once, here.
                        line = ((region?.get("startLine")?.asInt ?: 1) - 1).coerceAtLeast(0),
                        column = ((region?.get("startColumn")?.asInt ?: 1) - 1).coerceAtLeast(0),
                        ruleId = result["ruleId"]?.asString.orEmpty(),
                        message = result.getAsJsonObject("message")?.get("text")?.asString.orEmpty(),
                        severity = LintSeverity.of(result["level"]?.asString),
                    )
                }.getOrNull()
            }
        }
    }.getOrNull()

    private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
        this?.toList() ?: emptyList()
}
