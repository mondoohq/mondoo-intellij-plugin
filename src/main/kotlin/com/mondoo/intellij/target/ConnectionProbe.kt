// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.google.gson.JsonParser

/** What a connection test concluded. */
sealed interface ConnectionResult {
    /** cnspec reached the target; [detail] is what it reported about it. */
    data class Reachable(val detail: String) : ConnectionResult

    /** It did not; [reason] is the most useful line cnspec produced. */
    data class Unreachable(val reason: String) : ConnectionResult
}

/**
 * Reads the outcome of `cnspec run -c "asset.platform" -j` against a target.
 *
 * The interesting part, and the reason this is a named thing with tests rather than an
 * `exitCode == 0`: **cnspec exits 0 when it cannot reach the target.** Verified against
 * cnspec on 2026-09-05 — an unreachable SSH host prints `x unable to create runtime for
 * asset` and still exits zero. A test keyed on the exit code would report every failure
 * as a success, which is worse than having no test.
 *
 * What does distinguish them is the JSON: a reachable asset answers with a populated
 * array, `[{"asset.platform":"macos"}]`, and an unreachable one answers `[]`.
 *
 * Pure: unit-tested without cnspec.
 */
object ConnectionProbe {

    /** The MQL asked. Cheap, needs no privileges, and every provider answers it. */
    const val QUERY: String = "asset.platform"

    fun interpret(stdout: String, stderr: String, timedOut: Boolean): ConnectionResult {
        if (timedOut) {
            return ConnectionResult.Unreachable("cnspec did not answer in time")
        }

        val answered = runCatching {
            JsonParser.parseString(stdout.trim())
                .takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.takeIf { !it.isEmpty }
        }.getOrNull()

        if (answered == null) {
            return ConnectionResult.Unreachable(reasonFrom(stderr, stdout))
        }

        val detail = runCatching {
            answered.first().asJsonObject.entrySet().first().value.asString
        }.getOrNull()

        return ConnectionResult.Reachable(detail ?: "connected")
    }

    /**
     * The most useful line cnspec produced.
     *
     * Its failures are marked with a leading `x`, which is the line worth showing;
     * everything else is progress chatter about loading providers and inventories.
     */
    private fun reasonFrom(stderr: String, stdout: String): String {
        val lines = (stderr + "\n" + stdout).lines().map(String::trim).filter { it.isNotEmpty() }
        val failure = lines.firstOrNull { it.startsWith("x ") }?.removePrefix("x ")
        return (failure ?: lines.lastOrNull())
            ?.take(300)
            ?: "cnspec reported nothing"
    }
}
