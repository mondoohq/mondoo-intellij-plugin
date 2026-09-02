// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

/**
 * A field a target needs in order to connect.
 *
 * [secret] is the important flag: a secret field is never written to settings and
 * never placed on a command line. It lives in the IDE's password safe and reaches
 * cnspec through the environment. See [TargetCredentials] and [InventoryBuilder].
 */
data class TargetField(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val secret: Boolean = false,
    val comment: String = "",
)

/**
 * The target kinds the plugin can configure.
 *
 * cnspec supports 97 providers; these are the ones worth a form. The rest remain
 * reachable from the cnspec CLI, and adding one here is a matter of listing its
 * fields — the machinery below does not change.
 */
enum class TargetType(
    val id: String,
    val title: String,
    val description: String,
    val fields: List<TargetField>,
) {
    LOCAL(
        "local",
        "This machine",
        "Scan the system the IDE is running on",
        emptyList(),
    ),

    SSH(
        "ssh",
        "SSH host",
        "A remote system over SSH",
        listOf(
            TargetField("host", "Host", required = true, comment = "host, user@host, or user@host:port"),
            TargetField("user", "User"),
            TargetField("port", "Port"),
            TargetField(
                "keyFile",
                "Private key file",
                comment = "Leave empty to use your SSH agent",
            ),
            TargetField("password", "Password", secret = true, comment = "Stored in the IDE password safe"),
        ),
    ),

    DOCKER(
        "docker",
        "Docker container or image",
        "A running container, or an image",
        listOf(
            TargetField(
                "target",
                "Container or image",
                required = true,
                comment = "A container name or id, or an image reference such as nginx:latest",
            ),
        ),
    ),

    KUBERNETES(
        "k8s",
        "Kubernetes",
        "A cluster, or local manifests",
        listOf(
            TargetField("path", "Manifest path", comment = "Leave empty to use the current cluster context"),
            TargetField("namespaces", "Namespaces", comment = "Comma-separated; empty means all"),
        ),
    ),

    ;

    companion object {
        fun of(id: String?): TargetType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A configured target.
 *
 * [values] holds non-secret fields only, and is what gets persisted. Secrets are
 * addressed by [name] and the field key, and are read from the password safe at the
 * moment of use.
 */
data class TargetConfiguration(
    val name: String,
    val type: TargetType,
    val values: Map<String, String> = emptyMap(),
) {
    /** Non-secret fields only — enforced, not merely intended. */
    init {
        val secretKeys = type.fields.filter { it.secret }.map { it.key }.toSet()
        require(values.keys.none { it in secretKeys }) {
            "a target configuration must not carry secret fields; they belong in the password safe"
        }
    }

    fun value(key: String): String = values[key].orEmpty()

    /** Required fields that have no value, so the UI can say what is missing. */
    fun missingRequired(): List<TargetField> =
        type.fields.filter { it.required && value(it.key).isBlank() }

    val isComplete: Boolean get() = missingRequired().isEmpty()
}
