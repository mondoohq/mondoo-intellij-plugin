// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

/**
 * The `cnspec shell` command line for a target, as text to type into a shell.
 *
 * A different shape from every other cnspec invocation in this plugin, and worth
 * saying why. Everything else builds a `GeneralCommandLine` — an argv array that no
 * shell ever parses — and passes credentials in an inventory file. `cnspec shell`
 * accepts no `--inventory-file`, so the target has to be expressed as a subcommand
 * and arguments instead, and those are typed into a real interactive shell.
 *
 * Two consequences follow, and both are the reason this is a tested unit rather than
 * string concatenation at a call site:
 *
 * **Everything is quoted.** This string reaches a shell, so a host name containing a
 * space, a semicolon or a backtick would otherwise run as a command. Every value is
 * wrapped in single quotes with embedded quotes escaped, which no POSIX shell
 * interprets further.
 *
 * **No password is ever placed on it.** `cnspec shell ssh` offers `-p <password>`,
 * and using it would put the secret in the process table and the shell's history.
 * `--ask-pass` makes cnspec prompt inside the terminal instead, so the password is
 * typed by the person who owns it and stored nowhere.
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
    fun build(binary: String, target: TargetConfiguration, hasStoredPassword: Boolean): String? {
        val arguments = argumentsFor(target, hasStoredPassword) ?: return null
        return (listOf(binary, "shell") + arguments).joinToString(" ") { quote(it) }
    }

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
