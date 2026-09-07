// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.mondoo.intellij.target.CnspecOutcome

/** What `cnspec policy upload` did. */
sealed interface UploadResult {
    /** [summary] names what went where, e.g. "1 policy to SAST - Secrets". */
    data class Uploaded(val summary: String, val lintWarnings: Int) : UploadResult

    data class Failed(val reason: String) : UploadResult
}

/**
 * Reads the outcome of `cnspec policy upload`.
 *
 * Exit code says nothing here either — see [CnspecOutcome]. Success and failure both
 * exit 0, so the output is the signal, and this is the fourth command in the plugin
 * that needs that treatment.
 *
 * Captured from a real upload against a Mondoo space on 2026-09-05:
 *
 * ```
 * → valid policy bundle
 * → successfully uploaded 1 policy
 *   policy: IntelliJ plugin upload test (safe to delete) 1.0.0
 *     //policy.api.mondoo.app/spaces/<space>/policies/<uid>
 *   space: SAST - Secrets
 *     //captain.api.mondoo.app/spaces/<space>
 * ```
 *
 * Upload lints first — that is what `--no-lint` turns off — so lint findings appear
 * above all this even on success. They are counted rather than parsed: the console has
 * the detail, and a policy that uploaded with warnings still uploaded.
 *
 * Pure: unit-tested without cnspec.
 */
object PolicyUpload {

    private val UPLOADED = Regex("""successfully uploaded (\d+) polic(?:y|ies)""")
    private val SPACE = Regex("""^space:\s*(.+)$""")

    fun interpret(stdout: String, stderr: String): UploadResult {
        CnspecOutcome.failureReason(stdout, stderr)?.let { return UploadResult.Failed(it) }

        val lines = (stdout + "\n" + stderr).lines().map(String::trim)
        val count = lines.firstNotNullOfOrNull { UPLOADED.find(it)?.groupValues?.get(1) }
            ?: return UploadResult.Failed("cnspec did not report an upload")

        val space = lines.firstNotNullOfOrNull { SPACE.find(it)?.groupValues?.get(1)?.trim() }
        val policies = if (count == "1") "1 policy" else "$count policies"

        return UploadResult.Uploaded(
            summary = if (space.isNullOrBlank()) policies else "$policies to $space",
            lintWarnings = lines.count { it.contains("  warning  ") },
        )
    }
}
