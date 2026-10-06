// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.settings.MondooEnvironment
import com.mondoo.intellij.settings.MondooSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Mondoo Platform connection: which service account xgrep and cnspec use, whether
 * it looks usable, and registering a new one from a registration token.
 */
object MondooPlatform {

    /** Where xgrep and cnspec look when nothing else says otherwise. */
    val defaultPath: Path get() = Path.of(System.getProperty("user.home"), ".config", "mondoo", "mondoo.yml")

    /** The config the tools will use: the setting, else MONDOO_CONFIG_PATH, else the default. */
    fun effectivePath(): Path =
        MondooEnvironment.configPath()?.let(Path::of)
            ?: System.getenv(MondooEnvironment.CONFIG_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of)
            ?: defaultPath

    sealed interface Status {
        val path: Path

        data class Missing(override val path: Path) : Status
        data class Unusable(override val path: Path, val problems: List<String>) : Status
        data class Connected(override val path: Path, val space: String?, val endpoint: String?) : Status
    }

    fun status(path: Path = effectivePath()): Status {
        if (!Files.isRegularFile(path)) return Status.Missing(path)
        val text = runCatching { Files.readString(path) }.getOrElse {
            return Status.Unusable(path, listOf("cannot be read: ${it.message}"))
        }
        val config = MondooConfigFile.inspect(text)
        return if (config.usable) {
            Status.Connected(path, config.space, config.apiEndpoint)
        } else {
            Status.Unusable(path, config.problems)
        }
    }

    /** One line for the user. */
    fun describe(status: Status): String = when (status) {
        is Status.Connected -> "Connected to space ${status.space ?: "unknown"}"
        is Status.Unusable -> "The service account ${status.problems.joinToString(", ")}"
        is Status.Missing -> "Not connected"
    }

    /**
     * Registers a service account with [token] and writes it to [target], using
     * `xgrep login`. The token goes in the environment, never on the command line,
     * where every local process could read it. Blocks; call off the EDT.
     *
     * @return null on success, else what went wrong, for the user.
     */
    fun register(token: String, target: Path): String? {
        val xgrep = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
            ?: return "The xgrep scanner is not installed. Use Set Up Scanner first."
        runCatching { Files.createDirectories(target.parent) }
        val command = GeneralCommandLine(xgrep.toString(), "login", "--mondoo-config", target.toString())
            .withEnvironment("MONDOO_REGISTRATION_TOKEN", token.trim())
            .withCharset(Charsets.UTF_8)
        val out = runCatching { ExecUtil.execAndGetOutput(command, LOGIN_TIMEOUT_MS) }.getOrElse {
            return "Could not run xgrep login: ${it.message}"
        }
        if (out.isTimeout) return "xgrep login did not finish within ${LOGIN_TIMEOUT_MS / 1000} seconds."
        if (out.exitCode != 0) {
            val why = (out.stderr + "\n" + out.stdout).lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("→") }
            return why ?: "xgrep login failed (exit code ${out.exitCode})."
        }
        return when (val s = status(target)) {
            is Status.Connected -> null
            else -> "xgrep login finished, but ${describe(s).replaceFirstChar(Char::lowercase)}."
        }
    }

    /** Makes [path] the config every xgrep and cnspec the plugin starts uses. */
    fun use(path: Path) {
        val env = System.getenv(MondooEnvironment.CONFIG_ENV)?.trim().orEmpty()
        // The default needs no setting unless the environment would point elsewhere.
        val implicit = if (env.isNotEmpty()) Path.of(env) else defaultPath
        MondooSettings.getInstance().state.mondooConfigPath =
            if (path.normalize() == implicit.normalize()) "" else path.toString()
    }

    private const val LOGIN_TIMEOUT_MS = 60_000
}
