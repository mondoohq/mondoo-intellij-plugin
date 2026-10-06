// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

/**
 * Turns cnspec failures whose cause is the machine's Mondoo setup, not the scan,
 * into a line saying what to do. Pure, so it is tested without the platform.
 */
object CnspecErrors {

    fun hint(output: String): String? = when {
        // A service account in ~/.config/mondoo/mondoo.yml whose key cannot be
        // loaded: every cnspec command fails before it scans anything.
        output.contains("could not initialize client authentication") ||
            output.contains("AuthKey must be a valid .p8 PEM file") ->
            "cnspec could not use the Mondoo service account it is configured with " +
                "(see the configuration file named above). Fix or replace its key, log in " +
                "again with `cnspec login`, or remove the file to scan without Mondoo Platform."
        output.contains("no Mondoo configuration found") ->
            "cnspec has no Mondoo configuration. Run `cnspec login` to connect it to Mondoo Platform."
        else -> null
    }
}
