// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

/**
 * The `cnspec shell` invocation for a target.
 *
 * `cnspec shell` accepts no `--inventory-file` — see mondoohq/cnspec#3614 — so unlike
 * every other cnspec call in this plugin the target is expressed as a subcommand and
 * arguments rather than a file.
 *
 * [arguments] is what the terminal runs: an argv list, which the terminal launches as
 * the tab's process without a shell parsing it. So a host name containing a semicolon
 * is one argument containing a semicolon, and quoting is not what makes that safe.
 * [display] renders the same thing as a paste-able line, where the quoting does matter.
 *
 * **No password is ever placed on it.** `cnspec shell ssh` offers `-p <password>`, and
 * using it would put the secret in the process table. `--ask-pass` makes cnspec prompt
 * inside the terminal instead, so the password is typed by the person who owns it and
 * stored nowhere.
 *
 * Pure: no platform types, unit-tested without cnspec.
 */
object CnspecShellCommand {

    /**
     * The command to type, or null when the target cannot be expressed as one.
     *
     * @param hasStoredPassword whether the password safe holds a password for this
     *   target. Only its existence is used — the value is deliberately not a
     *   parameter, because there is nowhere on this command line it could safely go.
     */
    fun arguments(binary: String, target: TargetConfiguration, hasStoredPassword: Boolean): List<String>? {
        val arguments = argumentsFor(target, hasStoredPassword) ?: return null
        return listOf(binary, "shell") + arguments
    }

    /**
     * The same command as one line, for showing a person.
     *
     * Only for display — notifications, documentation, a copy-to-clipboard. The
     * terminal is handed [arguments] directly, so this quoting is a readability
     * concern rather than a safety one. It is still correct quoting, because a string
     * shown as "the command that ran" should be one somebody can paste.
     */
    fun display(binary: String, target: TargetConfiguration, hasStoredPassword: Boolean): String? =
        arguments(binary, target, hasStoredPassword)?.joinToString(" ") { quote(it) }

    private fun argumentsFor(target: TargetConfiguration, hasStoredPassword: Boolean): List<String>? =
        when (target.type) {
            TargetType.LOCAL -> listOf("local")

            TargetType.SSH -> buildList {
                val parsed = SshTarget.parse(target.value("host"))
                if (parsed.host.isBlank()) return null

                val user = target.value("user").ifBlank { parsed.user }
                val port = target.value("port").ifBlank { parsed.port }

                add("ssh")
                add(
                    buildString {
                        if (user.isNotBlank()) append(user).append('@')
                        append(parsed.host)
                        if (port.isNotBlank()) append(':').append(port)
                    },
                )

                target.value("keyFile").takeIf { it.isNotBlank() }?.let {
                    add("-i")
                    add(it)
                }
                // The password itself stays in the safe; cnspec asks for it.
                if (hasStoredPassword && target.value("keyFile").isBlank()) add("--ask-pass")
            }

            TargetType.DOCKER -> {
                val value = target.value("target")
                if (value.isBlank()) null else listOf("container", value)
            }

            TargetType.KUBERNETES -> buildList {
                add("k8s")
                // The manifest path is positional; a cluster connection takes none.
                target.value("path").takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }

    /**
     * Wraps a token in single quotes, which a POSIX shell does not look inside.
     *
     * The only character that can end a single-quoted string is a single quote, so it
     * is closed, escaped and reopened — the standard `'\''` dance. Nothing else needs
     * escaping, which is exactly why single quotes are used rather than double.
     */
    private fun quote(token: String): String = "'" + token.replace("'", "'\\''") + "'"
}
