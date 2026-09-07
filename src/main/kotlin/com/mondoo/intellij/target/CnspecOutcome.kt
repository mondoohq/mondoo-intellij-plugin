// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

/**
 * Whether a cnspec invocation actually failed.
 *
 * **cnspec exits 0 when it fails.** Not occasionally — consistently, across every
 * subcommand this plugin drives, each verified against the binary:
 *
 * | Invocation | Failure | Exit |
 * | --- | --- | --- |
 * | `run` against an unreachable host | `x unable to create runtime for asset` | 0 |
 * | `policy init` onto an existing file | `FTL Policy '...' already exists` | 0 |
 * | `policy upload` with no credentials | `x failed to create upstream client` | 0 |
 * | `shell` with an unknown flag | `Error: unknown flag` | 0 |
 *
 * So the exit code says nothing and the output is the only signal. Every caller that
 * needs to know whether cnspec succeeded goes through here, because writing this check
 * four times is how three of them end up subtly different.
 *
 * Two markers, because cnspec uses both: `x` for a runtime failure and `FTL` for a
 * fatal one. Everything else — the arrow-prefixed progress lines, provider install
 * chatter, lint findings — is not failure.
 *
 * Pure: unit-tested without cnspec.
 */
object CnspecOutcome {

    private const val MAX_REASON = 300

    /** The failure cnspec reported, or null when it reported none. */
    fun failureReason(stdout: String, stderr: String): String? =
        (stderr + "\n" + stdout).lines()
            .map(String::trim)
            .firstNotNullOfOrNull(::asFailure)
            ?.take(MAX_REASON)

    private fun asFailure(line: String): String? = when {
        // `x ` rather than `x`: a line merely starting with the letter is not a
        // failure, and cnspec always writes the marker with a space after it.
        line.startsWith("x ") -> line.removePrefix("x ").trim()
        line.startsWith("FTL") -> line.removePrefix("FTL").trim()
        line.startsWith("Error: ") -> line.removePrefix("Error: ").trim()
        else -> null
    }?.takeIf { it.isNotEmpty() }
}
