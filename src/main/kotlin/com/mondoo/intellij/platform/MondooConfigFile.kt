// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * What a Mondoo configuration file (a service account, `mondoo.yml`) says, and what is
 * wrong with it, read locally.
 *
 * xgrep and cnspec only find out a key is unusable when they try to sign a request,
 * and then fail every command with "AuthKey must be a valid .p8 PEM file". Checking
 * the shape up front lets the IDE say "this file has no private key" before anything
 * runs. It does not contact the platform, so a revoked account still reads as fine.
 *
 * Pure: no platform types, tested without an IDE. Never returns key material.
 */
data class MondooConfigFile(
    /** The space the account reports to, e.g. `friendly-nash-115619`. */
    val space: String?,
    val spaceMrn: String?,
    val apiEndpoint: String?,
    /** What makes the file unusable, in words for the user; empty when it looks usable. */
    val problems: List<String>,
) {
    val usable: Boolean get() = problems.isEmpty()

    companion object {
        fun inspect(text: String): MondooConfigFile {
            val map = runCatching {
                Yaml(
                    LoaderOptions().apply {
                        maxAliasesForCollections = 10
                        isAllowDuplicateKeys = false
                    },
                )
                    .load<Any?>(text) as? Map<*, *>
            }.getOrNull()
                ?: return MondooConfigFile(null, null, null, listOf("it is not a Mondoo service account file"))

            fun field(name: String) = (map[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            val spaceMrn = field("space_mrn") ?: field("scope_mrn")
            // Worded for someone who has never opened the file: what is wrong, not
            // which field or encoding.
            val problems = buildList {
                if (field("mrn") == null) add("it does not name a service account")
                if (spaceMrn == null) add("it does not name a space")
                when {
                    field("private_key") == null -> add("its private key is missing")
                    !field("private_key")!!.contains("-----BEGIN") -> add("its private key is damaged")
                }
                when {
                    field("certificate") == null -> add("its certificate is missing")
                    !field("certificate")!!.contains("-----BEGIN") -> add("its certificate is damaged")
                }
            }
            return MondooConfigFile(
                space = spaceMrn?.substringAfterLast('/'),
                spaceMrn = spaceMrn,
                apiEndpoint = field("api_endpoint"),
                problems = problems,
            )
        }
    }
}
