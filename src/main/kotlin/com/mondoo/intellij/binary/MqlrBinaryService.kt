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
 * Locates the MQLr binary, which serves LR resource-definition files.
 *
 * Discovery only, like [CnspecBinaryService] and for a sharper reason: mqlr is not a
 * released product at all. It is a developer tool built from the MQL repository with
 * `go install`, so there is no release manifest to fetch and no versioned artifact to
 * verify — the only sensible install is the one the user's own Go toolchain performs.
 * The plugin hands over the command and stays out of the way.
 *
 * Two names, because it is distributed as both: `mqlr` is current, `lr` is what older
 * installs are called, and a developer with either should not be told it is missing.
 */
@Service(Service.Level.APP)
class MqlrBinaryService {

    private val log = Logger.getInstance(MqlrBinaryService::class.java)
    private val cached = AtomicReference<Resolution?>(null)

    /** The resolved binary, or null when mqlr is not installed. */
    fun resolvedBinaryOrNull(): Path? {
        cached.get()?.let { if (it.stillValid()) return it.binary }
        return resolveUncached().also { cached.set(Resolution(it)) }
    }

    fun invalidate() = cached.set(null)

    private fun resolveUncached(): Path? {
        val configured = MondooSettings.getInstance().state.mqlrPath.orEmpty()
        if (configured.isNotBlank()) {
            val path = Path.of(configured)
            if (path.isRegularFile() && path.isExecutable()) return path
            log.warn("Configured mqlrPath is not an executable file: $configured")
        }

        executableNames().forEach { name ->
            PathEnvironmentVariableUtil.findInPath(name)?.let { return it.toPath() }
        }

        return commonBinDirs()
            .flatMap { dir -> executableNames().map { dir.resolve(it) } }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }
    }

    /** Tells the user how to install mqlr, with the command on the clipboard. */
    fun notifyMissing(project: Project?) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(
                "mqlr is not installed",
                "Language support for LR resource files needs the mqlr tool. It builds " +
                    "from source with Go; the command is on your clipboard.",
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Copy install command") {
                    CopyPasteManager.copyTextToClipboard(INSTALL_COMMAND)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring("Get Go") {
                    com.intellij.ide.BrowserUtil.browse("https://go.dev/dl/")
                },
            )
            .notify(project)
    }

    private fun executableNames(): List<String> =
        if (OS.CURRENT == OS.Windows) listOf("mqlr.exe", "lr.exe") else listOf("mqlr", "lr")

    /**
     * Where `go install` puts things, and the usual package-manager locations.
     *
     * Go's bin directories come first here rather than last: mqlr is only ever
     * installed that way, and `lr` is a short enough name that something unrelated
     * could plausibly own it in /usr/local/bin.
     */
    private fun commonBinDirs(): List<Path> = buildList {
        System.getenv("GOBIN")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        System.getenv("GOPATH")?.takeIf { it.isNotBlank() }
            ?.split(File.pathSeparator)?.filter { it.isNotBlank() }
            ?.forEach { add(Path.of(it, "bin")) }
        add(Path.of(System.getProperty("user.home"), "go", "bin"))

        if (OS.CURRENT == OS.Windows) {
            System.getenv("USERPROFILE")?.let { add(Path.of(it, "scoop", "shims")) }
        } else {
            add(Path.of("/usr/local/bin"))
            add(Path.of("/opt/homebrew/bin"))
        }
    }

    private class Resolution(val binary: Path?) {
        private val at = System.nanoTime()
        fun stillValid(): Boolean {
            val age = System.nanoTime() - at
            return if (binary == null) age < ABSENT_TTL else age < PRESENT_TTL
        }
    }

    companion object {
        /** The Go package that provides mqlr. */
        const val INSTALL_COMMAND: String =
            "go install go.mondoo.com/mql/v13/providers-sdk/v1/mqlr@latest"

        private val PRESENT_TTL = TimeUnit.SECONDS.toNanos(30)
        private val ABSENT_TTL = TimeUnit.SECONDS.toNanos(2)

        @JvmStatic
        fun getInstance(): MqlrBinaryService = service()
    }
}
