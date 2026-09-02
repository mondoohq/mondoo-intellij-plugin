// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mql

/**
 * Which files the cnspec language server should see.
 *
 * Policy bundles are ordinary YAML by extension, so they cannot be recognised by
 * file type alone — `*.mql.yaml` is the convention that distinguishes a Mondoo
 * bundle from any other YAML in the tree. Matching on the compound suffix is what
 * keeps the server off unrelated YAML, which would otherwise get diagnostics from
 * a policy linter that knows nothing about it.
 *
 * Keyed by file name for the same reason as the xgrep language table: an IDE
 * without the YAML plugin resolves these to plain text, and a `Language`-keyed
 * lookup would silently stop working there.
 *
 * Pure: unit-tested without an IDE.
 */
object MqlFiles {

    /** A standalone MQL query file. */
    private const val MQL_EXTENSION = "mql"

    /** Policy bundle suffixes, longest first so `.mql.yaml` wins over `.yaml`. */
    private val BUNDLE_SUFFIXES = listOf(".mql.yaml", ".mql.yml")

    /** The LSP language id to report for a file, or empty when unsupported. */
    fun languageIdFor(fileName: String): String {
        val lower = fileName.lowercase()
        if (BUNDLE_SUFFIXES.any { lower.endsWith(it) }) return "yaml"
        if (lower.substringAfterLast('.', "") == MQL_EXTENSION) return "mql"
        return ""
    }

    fun isSupported(fileName: String): Boolean = languageIdFor(fileName).isNotEmpty()

    /** True for a policy bundle, as opposed to a standalone query. */
    fun isPolicyBundle(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return BUNDLE_SUFFIXES.any { lower.endsWith(it) }
    }
}
