// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

/**
 * Turns xgrep's scan warnings into sentences for the user. Pure, so it is tested
 * without the platform.
 */
object ScanWarnings {

    /** The vulnerability lookup ran with an organization service account and no space. */
    fun needsSpace(warning: String): Boolean = warning.contains("missing space")

    fun explain(warning: String): String = when {
        needsSpace(warning) ->
            "Dependency vulnerabilities were not checked: your service account belongs to an " +
                "organization, so choose the space to use."
        warning.startsWith("dependency vulnerability scan failed") ->
            "Dependency vulnerabilities were not checked: " +
                warning.substringAfter("error=", "").trim('"').ifBlank { "the lookup failed" } + "."
        warning.startsWith("failed to report findings to Mondoo Platform") ->
            "Findings were not reported to Mondoo Platform; check the service account in Connect to Mondoo Platform."
        else -> warning
    }
}
