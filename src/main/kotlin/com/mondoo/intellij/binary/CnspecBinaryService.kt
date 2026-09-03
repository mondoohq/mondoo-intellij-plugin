// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.system.OS
import com.mondoo.intellij.settings.MondooSettings
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Locates the cnspec binary.
 *
 * Discovery only — deliberately no download, unlike [XgrepBinaryService].
 *
 * That asymmetry is Mondoo's documented position and it is not arbitrary. xgrep is a
 * self-contained scanner that runs offline against the tree in front of it, so
 * fetching one is a convenience with no consequences. cnspec connects to
 * infrastructure with credentials, and is normally installed and updated through the
 * system package manager alongside whatever else the machine's security tooling
 * expects. Silently placing a second copy in an IDE directory would fork that, and
 * put an IDE in charge of a binary the platform team thinks it manages.
 *
 * So when cnspec is missing the plugin explains how to install it and stays out of
 * the way.
 */
@Service(Service.Level.APP)
class CnspecBinaryService {

    private val log = Logger.getInstance(CnspecBinaryService::class.java)
    private val cached = AtomicReference<Resolution?>(null)

    /** The resolved binary, or null when cnspec is not installed. */
    fun resolvedBinaryOrNull(): Path? {
        cached.get()?.let { if (it.stillValid()) return it.binary }
        return resolveUncached().also { cached.set(Resolution(it)) }
    }

    fun invalidate() = cached.set(null)

    private fun resolveUncached(): Path? {
        val configured = MondooSettings.getInstance().state.cnspecPath.orEmpty()
        if (configured.isNotBlank()) {
            val path = Path.of(configured)
            if (path.isRegularFile() && path.isExecutable()) return path
            log.warn("Configured cnspecPath is not an executable file: $configured")
        }

        PathEnvironmentVariableUtil.findInPath(executableName())?.let { return it.toPath() }

        return commonBinDirs()
            .map { it.resolve(executableName()) }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }
    }

    /** Tells the user how to install cnspec, with the command on the clipboard. */
    fun notifyMissing(project: Project?) {
        val command = installCommand()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(
                "cnspec is not installed",
                "MQL support needs the cnspec CLI. Install it, then reload the scanner.",
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Copy install command") {
                    CopyPasteManager.copyTextToClipboard(command)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Installation guide") {
                    com.intellij.ide.BrowserUtil.browse("https://mondoo.com/docs/cnspec/cnspec-adv-install/")
                },
            )
            .notify(project)
    }

    /** The one-liner Mondoo publishes for this platform. */
    fun installCommand(): String = when (OS.CURRENT) {
        OS.Windows ->
            "Set-ExecutionPolicy Unrestricted -Scope Process -Force; " +
                "iex ((New-Object System.Net.WebClient).DownloadString('https://install.mondoo.com/ps1/cnspec'))"
        else -> "bash -c \"\$(curl -sSL https://install.mondoo.com/sh)\""
    }

    private fun executableName(): String = if (OS.CURRENT == OS.Windows) "cnspec.exe" else "cnspec"

    private fun commonBinDirs(): List<Path> = buildList {
        if (OS.CURRENT == OS.Windows) {
            // The four ways a CLI usually arrives on Windows.
            System.getenv("ProgramData")?.let { add(Path.of(it, "chocolatey", "bin")) }
            System.getenv("LOCALAPPDATA")?.let { add(Path.of(it, "Microsoft", "WinGet", "Links")) }
            System.getenv("USERPROFILE")?.let { add(Path.of(it, "scoop", "shims")) }
            System.getenv("ProgramFiles")?.let { add(Path.of(it, "Mondoo")) }
        } else {
            add(Path.of("/usr/local/bin"))
            add(Path.of("/opt/homebrew/bin"))
            add(Path.of("/usr/bin"))
            add(Path.of("/bin"))
        }
        System.getenv("GOBIN")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        System.getenv("GOPATH")?.takeIf { it.isNotBlank() }
            ?.split(File.pathSeparator)?.filter { it.isNotBlank() }
            ?.forEach { add(Path.of(it, "bin")) }
        add(Path.of(System.getProperty("user.home"), "go", "bin"))
    }

    private class Resolution(val binary: Path?) {
        private val at = System.nanoTime()
        fun stillValid(): Boolean {
            val age = System.nanoTime() - at
            return if (binary == null) age < ABSENT_TTL else age < PRESENT_TTL
        }
    }

    companion object {
        private val PRESENT_TTL = TimeUnit.SECONDS.toNanos(30)
        private val ABSENT_TTL = TimeUnit.SECONDS.toNanos(2)

        @JvmStatic
        fun getInstance(): CnspecBinaryService = service()
    }
}
