// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

/**
 * The rules around scaffolding a bundle with `cnspec policy init`.
 *
 * Both of these were checked against cnspec rather than assumed, and both are the
 * opposite of what the obvious implementation would do:
 *
 * **It refuses to overwrite.** Given an existing path it prints
 * `FTL Policy '<name>' already exists` and writes nothing. So there is no "overwrite?"
 * prompt to offer — the answer could not be honoured. Asking up front and declining is
 * the only truthful flow.
 *
 * **It exits 0 when it fails.** A permission error prints
 * `FTL Could not write '<name>' error="..."` and still exits zero, so success has to
 * be judged by whether a file appeared, never by the exit code.
 *
 * Pure: unit-tested without cnspec.
 */
object PolicyTemplate {

    /** What cnspec calls the file when given no name. */
    const val DEFAULT_NAME: String = "example-policy.mql.yaml"

    private val ACCEPTED_SUFFIXES = listOf(".mql.yaml", ".mql.yml")

    /** A complaint about [name], or null when it is usable. */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "A file name is required."
            ACCEPTED_SUFFIXES.none { trimmed.lowercase().endsWith(it) } ->
                "The name must end with .mql.yaml or .mql.yml, so the plugin and cnspec both recognise it."
            // The name is passed to cnspec as a single argument and joined to a
            // directory; a path separator would put the file somewhere the user did
            // not choose and did not see.
            trimmed.contains('/') || trimmed.contains('\\') ->
                "Enter a file name, not a path."
            trimmed.removeSuffix(".yaml").removeSuffix(".yml").removeSuffix(".mql").isEmpty() ->
                "The name needs something before .mql.yaml."
            else -> null
        }
    }

    /**
     * The failure cnspec reported, or null when it said nothing useful.
     *
     * Its fatal lines are prefixed `FTL`; everything else is progress chatter about
     * providers and configuration.
     */
    fun failureReason(stdout: String, stderr: String): String? =
        (stderr + "\n" + stdout).lines()
            .map(String::trim)
            .firstOrNull { it.startsWith("FTL") }
            ?.removePrefix("FTL")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(300)
}
