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

    /** Removes every secret for a target, for when its configuration is deleted. */
    fun forget(target: TargetConfiguration) {
        target.type.fields.filter { it.secret }.forEach { set(target.name, it.key, null) }
    }
}

/**
 * Maps a target's secrets to the environment variables cnspec reads.
 *
 * Ported from `buildCredentialEnv` in the VS Code extension. The mapping is
 * per-provider and deliberately explicit: passing a secret in the wrong variable
 * either fails to authenticate or, worse, leaks it to a provider that logs it.
 *
 * Pure: the lookup is injected, so this is unit-tested without a keychain.
 */
object CredentialEnvironment {

    fun forTarget(
        target: TargetConfiguration,
        lookup: (field: String) -> String? = { TargetCredentials.get(target.name, it) },
    ): Map<String, String> = buildMap {
        when (target.type) {
            TargetType.SSH -> lookup("password")?.let { put("SSH_PASSWORD", it) }

            // These carry no secret fields today. Listed rather than defaulted so
            // that adding a secret to one is a deliberate change here.
            TargetType.LOCAL, TargetType.DOCKER, TargetType.KUBERNETES -> Unit
        }
    }
}
