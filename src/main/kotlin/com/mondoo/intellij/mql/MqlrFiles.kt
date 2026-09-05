// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mql

/**
 * Which files the MQLr language server should see.
 *
 * LR is the resource-definition language behind MQL's schema — the files that declare
 * what `sshd.config` or `k8s.pod` *are*. Historically `.lr`; `.mqlr` is where Mondoo
 * is moving, and both are recognised so a repository part-way through the rename is
 * not half-supported.
 *
 * Keyed by extension rather than by IntelliJ `Language`, for the same reason as
 * [MqlFiles]: no IDE has an LR file type, so these resolve to plain text and a
 * `Language`-keyed lookup would match nothing.
 *
 * Pure: unit-tested without an IDE.
 */
object MqlrFiles {

    /** `.mqlr` first: it is the one being moved to, and the list reads as a priority. */
    private val EXTENSIONS = listOf("mqlr", "lr")

    /**
     * The LSP language id, or empty when the file is not LR.
     *
     * Both extensions report `lr`, which is the id the server and its TextMate grammar
     * already use. A new id for `.mqlr` would mean the server treating the same
     * language as two, for no gain.
     */
    fun languageIdFor(fileName: String): String =
        if (isSupported(fileName)) LANGUAGE_ID else ""

    fun isSupported(fileName: String): Boolean {
        val lower = fileName.lowercase()
        // Not substringAfterLast on its own: a file literally named ".lr" has no name,
        // and `x.lr.bak` is a backup rather than a resource definition.
        return EXTENSIONS.any { lower.endsWith(".$it") && lower.length > it.length + 1 }
    }

    const val LANGUAGE_ID: String = "lr"

    /** For messages that have to name what is supported. */
    val displayExtensions: String get() = EXTENSIONS.joinToString(", ") { ".$it" }
}
