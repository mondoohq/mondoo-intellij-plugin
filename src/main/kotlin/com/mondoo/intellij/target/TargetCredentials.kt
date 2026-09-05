// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Secrets for a target, in the IDE's password safe.
 *
 * Never in settings: a project's `.idea` directory is routinely committed, and a
 * password in `mondoo.xml` would be shared with everyone who clones the repository.
 * The password safe is the OS keychain where one is available.
 *
 * Nor on a command line: `ps` shows the arguments of every process on the machine.
 * Secrets reach cnspec through the environment instead — see [CredentialEnvironment].
 */
object TargetCredentials {

    private fun attributes(targetName: String, field: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Mondoo target", "$targetName/$field"))

    fun get(targetName: String, field: String): String? =
        PasswordSafe.instance.getPassword(attributes(targetName, field))?.takeIf { it.isNotEmpty() }

    fun set(targetName: String, field: String, secret: String?) {
        PasswordSafe.instance.setPassword(attributes(targetName, field), secret?.takeIf { it.isNotEmpty() })
    }

    /** Every stored secret for a target, keyed by field. */
    fun forTarget(target: TargetConfiguration): Map<String, String> =
        target.type.fields.filter { it.secret }
            .mapNotNull { field -> get(target.name, field.key)?.let { field.key to it } }
            .toMap()

    /** Removes every secret for a target, for when its configuration is deleted. */
    fun forget(target: TargetConfiguration) {
        target.type.fields.filter { it.secret }.forEach { set(target.name, it.key, null) }
    }
}

/**
 * Maps a target's secrets to environment variables, where cnspec reads them.
 *
 * Empty today, and that is the correct state rather than an unfinished one.
 *
 * It used to put an SSH password in `SSH_PASSWORD`, which cnspec never reads: its
 * connection package reads only `DOCKER_CONTEXT`, `SSH_AUTH_SOCK`, `MONDOO_SSH_SCP`
 * and `WINRM_DISABLE_HTTPS`. The password was therefore silently discarded and the
 * connection fell back to the SSH agent. SSH passwords now go into the inventory file
 * as a `password` credential — see [InventoryBuilder] — which is 0600, short-lived,
 * and actually read.
 *
 * The seam stays because it is genuinely the right delivery path for the providers
 * that do read the environment — AWS reads `AWS_ACCESS_KEY_ID` and friends, GitHub
 * reads `GITHUB_TOKEN`, Azure reads the `AZURE_*` triple — and those targets are the
 * obvious next ones to support. What it must not do again is invent a variable name
 * by analogy and assume something reads it.
 *
 * Pure: the lookup is injected, so this is unit-tested without a keychain.
 */
object CredentialEnvironment {

    @Suppress("UNUSED_PARAMETER")
    fun forTarget(
        target: TargetConfiguration,
        lookup: (field: String) -> String? = { TargetCredentials.get(target.name, it) },
    ): Map<String, String> = when (target.type) {
        // Listed rather than defaulted, so adding a provider that does read the
        // environment is a deliberate edit here.
        TargetType.LOCAL, TargetType.SSH, TargetType.DOCKER, TargetType.KUBERNETES -> emptyMap()
    }
}
