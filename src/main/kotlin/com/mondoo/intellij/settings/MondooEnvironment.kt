// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.settings

import com.intellij.execution.configurations.GeneralCommandLine

/**
 * Starts xgrep and cnspec with the Mondoo configuration the user chose.
 *
 * Both read `MONDOO_CONFIG_PATH` (a service account for Mondoo Platform: dependency
 * vulnerabilities, uploads, policy upload) and fall back to
 * `~/.config/mondoo/mondoo.yml`. With no path set here the variable is left as the
 * IDE inherited it, so a `MONDOO_CONFIG_PATH` from the user's environment still works.
 */
object MondooEnvironment {

    const val CONFIG_ENV = "MONDOO_CONFIG_PATH"

    /** The configured path, or null to use the tools' own default. */
    fun configPath(): String? = MondooSettings.getInstance().state.mondooConfigPath?.trim()?.takeIf { it.isNotEmpty() }

    /** The chosen space's MRN, or null to use the service account's own. */
    fun spaceMrn(): String? = MondooSettings.getInstance().state.mondooSpaceMrn?.trim()?.takeIf { it.isNotEmpty() }

    /** A command line for [exe] that carries the configured Mondoo config. */
    fun commandLine(exe: String): GeneralCommandLine = apply(GeneralCommandLine(exe))

    fun apply(command: GeneralCommandLine): GeneralCommandLine {
        configPath()?.let { command.withEnvironment(CONFIG_ENV, it) }
        return command
    }

    /**
     * Names the plugin as the caller in xgrep's anonymous usage telemetry (xgrep
     * ADR-0882): xgrep reports `XGREP_CALLER` as the usage event's caller, so the
     * plugin's runs are counted as IntelliJ usage instead of anonymous direct
     * invocations. Only xgrep reads it; cnspec command lines do not get it.
     */
    const val XGREP_CALLER_ENV = "XGREP_CALLER"
    const val XGREP_CALLER = "intellij"

    /** [commandLine] for an xgrep process: the Mondoo config plus the telemetry caller. */
    fun xgrepCommandLine(exe: String): GeneralCommandLine = withXgrepCaller(commandLine(exe))

    /** Adds the telemetry caller to an xgrep [command] built elsewhere. */
    fun withXgrepCaller(command: GeneralCommandLine): GeneralCommandLine =
        command.withEnvironment(XGREP_CALLER_ENV, XGREP_CALLER)
}
