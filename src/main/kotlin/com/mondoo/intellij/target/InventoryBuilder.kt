// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

/**
 * Builds the inventory document cnspec reads with `--inventory-file`.
 *
 * Connection details go in a file rather than on the command line for one reason:
 * a command line is visible to every process on the machine through the process
 * table, and to any shell history or log that captures it. An inventory file can be
 * mode 0600 and short-lived — which is why an SSH password belongs here rather than
 * in the environment. cnspec does not read one from the environment at all; its
 * connection package reads only DOCKER_CONTEXT, SSH_AUTH_SOCK, MONDOO_SSH_SCP and
 * WINRM_DISABLE_HTTPS, so a password handed over that way is silently ignored and the
 * connection quietly falls back to the agent. Verified against cnspec, not assumed.
 *
 * Pure: builds a string, touches no filesystem, unit-tested without cnspec.
 */
object InventoryBuilder {

    /**
     * The inventory YAML for [target].
     *
     * Hand-built rather than serialised through a YAML library: the document is
     * small and fixed in shape, and every value that reaches it is quoted here, so
     * a host name containing a quote or a newline cannot alter the structure.
     */
    fun build(target: TargetConfiguration, secrets: Map<String, String> = emptyMap()): String {
        val connection = connectionLines(target, secrets).joinToString("\n") { "          $it" }
        return buildString {
            appendLine("apiVersion: v7")
            appendLine("kind: Inventory")
            appendLine("metadata:")
            appendLine("  name: intellij-mondoo")
            appendLine("spec:")
            appendLine("  assets:")
            appendLine("    - id: ${quote("${target.type.id}-target")}")
            appendLine("      connections:")
            appendLine(connection)
        }
    }

    private fun connectionLines(target: TargetConfiguration, secrets: Map<String, String>): List<String> = buildList {
        add("- type: ${quote(target.type.id)}")

        when (target.type) {
            TargetType.LOCAL -> Unit

            TargetType.SSH -> {
                val parsed = SshTarget.parse(target.value("host"))
                val user = target.value("user").ifBlank { parsed.user }
                val port = target.value("port").ifBlank { parsed.port }

                add("  host: ${quote(parsed.host)}")
                if (port.isNotBlank()) add("  port: ${port.toIntOrNull() ?: 22}")

                val keyFile = target.value("keyFile")
                val password = secrets["password"].orEmpty()
                when {
                    keyFile.isNotBlank() -> {
                        // cnspec loads the key from this path itself, so the key
                        // material never passes through the plugin.
                        //
                        // A passphrase-protected key is not supported: cnspec rejects
                        // a private_key credential carrying a `password` field with
                        // "no authentication method defined", so there is nothing to
                        // send. Checked against cnspec rather than guessed.
                        add("  credentials:")
                        add("    - type: private_key")
                        add("      private_key_path: ${quote(keyFile)}")
                        if (user.isNotBlank()) add("      user: ${quote(user)}")
                    }
                    password.isNotEmpty() -> {
                        // The password lives in the IDE password safe and reaches
                        // cnspec only through this file, which is written 0600 in a
                        // directory only this user can enter and deleted when the
                        // process exits. It is never an argument and never an
                        // environment variable.
                        add("  credentials:")
                        add("    - type: password")
                        if (user.isNotBlank()) add("      user: ${quote(user)}")
                        add("      password: ${quote(password)}")
                    }
                    user.isNotBlank() -> {
                        // No key and no password: defer to the agent. A private_key
                        // credential with no key would fail as "no authentication
                        // method defined".
                        add("  credentials:")
                        add("    - type: ssh_agent")
                        add("      user: ${quote(user)}")
                    }
                }
            }

            TargetType.DOCKER -> {
                val value = target.value("target")
                // An inventory cannot sniff container-vs-image the way the CLI does
                // at connect time, so infer: an image reference carries a tag or a
                // registry path, a container name or id does not.
                val kind = if (value.contains(':') || value.contains('/')) "docker-image" else "docker-container"
                add("  type: ${quote(kind)}")
                if (value.isNotBlank()) add("  host: ${quote(value)}")
            }

            TargetType.KUBERNETES -> {
                val options = buildList {
                    target.value("path").takeIf { it.isNotBlank() }?.let { add("path" to it) }
                    target.value("namespaces").takeIf { it.isNotBlank() }?.let { add("namespaces" to it) }
                }
                if (options.isNotEmpty()) {
                    add("  options:")
                    options.forEach { (k, v) -> add("    $k: ${quote(v)}") }
                }
            }
        }
    }

    /**
     * Double-quotes a scalar and escapes what would otherwise end it.
     *
     * Values here come from user input. Without this, a host of `a"\nfoo: bar` would
     * inject a key into the document.
     */
    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

/** `[user@]host[:port]`, as people actually type it. */
data class SshTarget(val user: String, val host: String, val port: String) {
    companion object {
        fun parse(raw: String): SshTarget {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return SshTarget("", "", "")

            // Split on the LAST '@': a password or user may itself contain one.
            val at = trimmed.lastIndexOf('@')
            val user = if (at >= 0) trimmed.substring(0, at) else ""
            val rest = if (at >= 0) trimmed.substring(at + 1) else trimmed

            // Bracketed IPv6 carries its port outside the brackets: [::1]:22.
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close > 0) {
                    val host = rest.substring(1, close)
                    val tail = rest.substring(close + 1)
                    val port = tail.removePrefix(":").takeIf { tail.startsWith(":") && it.all(Char::isDigit) }
                    return SshTarget(user, host, port.orEmpty())
                }
            }

            // A bare address with more than one colon is IPv6, and its trailing
            // group is an address segment rather than a port — "2001:db8::1" ends
            // in ":1", which reads as a port if you only look at the last colon.
            if (rest.count { it == ':' } > 1) return SshTarget(user, rest, "")

            val colon = rest.lastIndexOf(':')
            val maybePort = if (colon >= 0) rest.substring(colon + 1) else ""
            return if (colon >= 0 && maybePort.isNotEmpty() && maybePort.all(Char::isDigit)) {
                SshTarget(user, rest.substring(0, colon), maybePort)
            } else {
                SshTarget(user, rest, "")
            }
        }
    }
}
