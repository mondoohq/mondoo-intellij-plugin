// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import com.mondoo.intellij.settings.MondooConfigurable
import com.mondoo.intellij.settings.MondooSettings
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/** What the status bar and tool window show about the scanner. */
sealed interface XgrepStatus {
    data object Disabled : XgrepStatus
    data object Resolving : XgrepStatus
    data class Downloading(val version: String, val percent: Int) : XgrepStatus
    data class Ready(val binary: Path, val version: String?) : XgrepStatus
    data class Unavailable(val reason: String) : XgrepStatus
}

/**
 * Resolves, installs and updates the xgrep binary.
 *
 * Resolution order, ported from `findBinary` in
 * vscode-mondoo/src/services/xgrepService.ts:
 *
 *  1. the `xgrepPath` setting, when it points at an executable
 *  2. a plugin-managed install under the IDE system directory
 *  3. `xgrep` on PATH
 *  4. common install locations, including Go's bin dirs
 *
 * Unlike the VS Code extension this never shells out to npm: JetBrains users on
 * GoLand, Rider or CLion have no reason to have Node installed. Releases come
 * from `latest.json` and are verified against the SHA-256 it publishes.
 */
@Service(Service.Level.APP)
class XgrepBinaryService {

    private val log = Logger.getInstance(XgrepBinaryService::class.java)
    private val installer = XgrepInstaller(managedRoot(), PlatformXgrepDownloader(), executableName())
    private val state = AtomicReference<XgrepStatus>(XgrepStatus.Resolving)
    private val installInFlight = AtomicBoolean(false)
    private val cached = AtomicReference<Resolution?>(null)

    val status: XgrepStatus get() = state.get()

    /**
     * The resolved binary, or null when xgrep cannot be found. Never installs.
     *
     * Cached, because this is called from action `update()`, from the status-bar
     * widget and from the editor banner provider — all of which run whenever a menu
     * opens, a toolbar refreshes or a file is shown. Resolving means scanning `PATH`,
     * stat-ing a list of common directories and listing the managed install root, and
     * doing that on every UI refresh is real, repeated I/O for an answer that changes
     * only when a setting changes or an install completes.
     *
     * [invalidate] is called from both of those points.
     */
    fun resolvedBinaryOrNull(): Path? {
        cached.get()?.let { hit ->
            if (hit.stillValid()) return hit.binary
        }
        return resolveUncached().also { cached.set(Resolution(it)) }
    }

    /** Drops the cached resolution. */
    fun invalidate() {
        cached.set(null)
    }

    private class Resolution(val binary: Path?) {
        private val at = System.nanoTime()

        /**
         * A present binary is re-checked only occasionally; an absent one is
         * re-checked promptly, so "install it now" takes effect without a restart.
         */
        fun stillValid(): Boolean {
            val age = System.nanoTime() - at
            return if (binary == null) age < ABSENT_TTL_NANOS else age < PRESENT_TTL_NANOS
        }
    }

    private fun resolveUncached(): Path? {
        val settings = MondooSettings.getInstance().state
        if (!settings.xgrepEnabled) {
            state.set(XgrepStatus.Disabled)
            return null
        }

        val configured = settings.xgrepPath.orEmpty()
        if (configured.isNotBlank()) {
            val path = Path.of(configured)
            if (path.isRegularFile() && path.isExecutable()) return ready(path, null)
            log.warn("Configured xgrepPath is not an executable file: $configured")
        }

        managedBinary()?.let { (binary, version) -> return ready(binary, version) }

        PathEnvironmentVariableUtil.findInPath(executableName())?.let { return ready(it.toPath(), null) }

        commonBinDirs()
            .map { it.resolve(executableName()) }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }
            ?.let { return ready(it, null) }

        state.set(XgrepStatus.Unavailable("xgrep was not found"))
        return null
    }

    /**
     * Ensures a usable xgrep exists, asking before it downloads one.
     *
     * Nothing reaches the network without the user saying so. Automatic download
     * being *enabled* means "you may offer"; it is not standing permission to fetch
     * and run a binary. Consent is per version, as in the VS Code extension: agreeing
     * to 0.57.0 is not agreement to every later release.
     *
     * @param force skip the offer — this is "Set Up Scanner" and the setup banner,
     *   where the user has just asked for the install by name.
     */
    fun ensureInstalled(project: Project?, force: Boolean = false) {
        val settings = MondooSettings.getInstance().state
        if (!settings.xgrepEnabled && !force) return
        if (resolvedBinaryOrNull() != null && !force) {
            checkForUpdate(project)
            return
        }
        if (force) {
            runInstall(project)
            return
        }
        if (!settings.xgrepAutoInstall) {
            state.set(XgrepStatus.Unavailable("xgrep is not installed and automatic download is off"))
            notify(
                project,
                "The xgrep scanner is not installed",
                NotificationType.WARNING,
                NotificationAction.createSimpleExpiring("Download it now") { ensureInstalled(project, force = true) },
                NotificationAction.createSimpleExpiring("Open settings") { openSettings(project) },
            )
            return
        }
        offerInstall(project)
    }

    /**
     * Resolves which version would be installed, then asks.
     *
     * The version lookup is a network call, so it cannot run on the EDT; the offer
     * itself is a notification rather than a modal dialog, because this fires while
     * a project is opening and a dialog there interrupts whatever the user came to
     * do. Declining is remembered only as far as "not this version".
     */
    private fun offerInstall(project: Project?) {
        object : Task.Backgroundable(project, "Checking for the xgrep security scanner", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val settings = MondooSettings.getInstance().state
                val version = resolveTargetVersion(settings)

                if (settings.installConsentVersion == version) {
                    runInstall(project)
                    return
                }

                state.set(XgrepStatus.Unavailable("xgrep is not installed"))
                notify(
                    project,
                    "Install the xgrep security scanner ($version) from releases.mondoo.com? " +
                        "It scans on this machine; your code is never uploaded.",
                    NotificationType.INFORMATION,
                    NotificationAction.createSimpleExpiring("Install") {
                        MondooSettings.getInstance().state.installConsentVersion = version
                        runInstall(project, consentedTo = version)
                    },
                    NotificationAction.createSimpleExpiring("Not now") { },
                    // Turns the offer off for good rather than asking again next
                    // release, which is what "Never" has to mean to be worth clicking.
                    NotificationAction.createSimpleExpiring("Never") {
                        MondooSettings.getInstance().state.xgrepAutoInstall = false
                    },
                )
            }
        }.queue()
    }

    /** Offers an update when the manifest advertises a newer release. */
    fun checkForUpdate(project: Project?) {
        val settings = MondooSettings.getInstance().state
        if (!settings.xgrepAutoInstall) return
        // Only ever update our own managed copy: a user-configured path, a Homebrew
        // install or a self-built binary in ~/go/bin belongs to the user, not to us.
        val managed = managedBinary() ?: return
        if (!XgrepVersionPolicy.shouldRefresh(settings.resolvedCheckedAt, System.currentTimeMillis())) return

        val target = resolveTargetVersion(settings)
        if (!XgrepVersionPolicy.needsUpdate(managed.second, target)) return

        notify(
            project,
            "A newer xgrep is available (${managed.second} → $target)",
            NotificationType.INFORMATION,
            // Clicking Update is consent for that version, and only that one.
            NotificationAction.createSimpleExpiring("Update") {
                MondooSettings.getInstance().state.installConsentVersion = target
                runInstall(project, consentedTo = target)
            },
        )
    }

    /**
     * Downloads and installs. Callers are responsible for having consent — either
     * because the user clicked an install action, or via [offerInstall].
     *
     * @param consentedTo the version the user was shown, when they were shown one.
     *   The manifest is re-read here, so a release published between the offer and
     *   the click would otherwise install something nobody agreed to; that asks
     *   again instead. Null means the user asked for an install by name and takes
     *   whatever is current.
     */
    private fun runInstall(project: Project?, consentedTo: String? = null) {
        if (!installInFlight.compareAndSet(false, true)) return

        object : Task.Backgroundable(project, "Installing the xgrep security scanner", true) {
            override fun run(indicator: ProgressIndicator) {
                val settings = MondooSettings.getInstance().state

                // One manifest fetch, and the version comes from it: asking
                // resolveTargetVersion() first and then fetching again could install a
                // different release than the one the user was shown.
                indicator.text = "Checking the xgrep release manifest"
                val manifest = installer.fetchManifest()
                val release = manifest?.let { ArtifactSelector.select(it, osToken(), archToken()) }
                if (manifest == null || release == null) {
                    val reason = if (manifest == null) {
                        "could not reach the xgrep release manifest"
                    } else {
                        "no xgrep build for ${osToken()}/${archToken()}"
                    }
                    state.set(XgrepStatus.Unavailable(reason))
                    notify(project, "Could not install xgrep: $reason", NotificationType.ERROR)
                    return
                }
                val version = manifest.version
                if (consentedTo != null && version != consentedTo) {
                    log.info(
                        "xgrep moved from $consentedTo to $version between the offer " +
                            "and the install; asking again",
                    )
                    settings.installConsentVersion = null
                    // onFinished() releases the in-flight flag; the new offer queues
                    // its own task and cannot re-enter this one.
                    offerInstall(project)
                    return
                }

                indicator.text = "Downloading xgrep $version"
                state.set(XgrepStatus.Downloading(version, 0))
                val binary = try {
                    installer.install(release, version) { copied, total ->
                        if (total > 0) {
                            indicator.fraction = copied.toDouble() / total
                            state.set(XgrepStatus.Downloading(version, (copied * 100 / total).toInt()))
                        }
                    }
                } catch (e: XgrepInstallException) {
                    log.warn("xgrep install failed", e)
                    state.set(XgrepStatus.Unavailable(e.message ?: "install failed"))
                    notify(project, "Could not install xgrep: ${e.message}", NotificationType.ERROR)
                    return
                }

                settings.resolvedVersion = version
                settings.resolvedCheckedAt = System.currentTimeMillis()
                settings.installConsentVersion = version
                invalidate()
                installer.pruneOtherVersions(version)
                ready(binary, version)

                notify(project, "xgrep $version installed", NotificationType.INFORMATION)
                XgrepInstallListener.notifyInstalled(binary)
            }

            override fun onFinished() {
                installInFlight.set(false)
            }
        }.queue()
    }

    private fun resolveTargetVersion(settings: com.mondoo.intellij.settings.MondooState): String {
        val now = System.currentTimeMillis()
        if (!XgrepVersionPolicy.shouldRefresh(settings.resolvedCheckedAt, now)) {
            return XgrepVersionPolicy.targetVersion(null, settings.resolvedVersion)
        }
        val manifestVersion = installer.fetchManifest()?.version
        if (manifestVersion != null) {
            settings.resolvedVersion = manifestVersion
            settings.resolvedCheckedAt = now
        }
        return XgrepVersionPolicy.targetVersion(manifestVersion, settings.resolvedVersion)
    }

    /** The managed install and its version, newest first, or null when there is none. */
    private fun managedBinary(): Pair<Path, String>? =
        installer.installedVersions().firstNotNullOfOrNull { version ->
            installer.installedBinary(version)?.let { it to version }
        }

    fun managedRoot(): Path = PathManager.getSystemDir().resolve("mondoo").resolve("xgrep")

    private fun ready(binary: Path, version: String?): Path {
        state.set(XgrepStatus.Ready(binary, version))
        return binary
    }

    private fun notify(
        project: Project?,
        content: String,
        type: NotificationType,
        vararg actions: NotificationAction,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(content, type)
        actions.forEach(notification::addAction)
        notification.notify(project)
    }

    private fun openSettings(project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MondooConfigurable::class.java)
    }

    private fun executableName(): String = if (OS.CURRENT == OS.Windows) "xgrep.exe" else "xgrep"

    /** Release-artifact OS token, matching the names in `latest.json`. */
    private fun osToken(): String = when (OS.CURRENT) {
        OS.Windows -> "windows"
        OS.macOS -> "darwin"
        OS.Linux -> "linux"
        else -> "unsupported"
    }

    private fun archToken(): String = when (CpuArch.CURRENT) {
        CpuArch.X86_64 -> "amd64"
        CpuArch.ARM64 -> "arm64"
        else -> "unsupported"
    }

    /**
     * Common install locations, ported from `commonBinDirs`/`goBinDirs` in
     * vscode-mondoo/src/utils/platform.ts.
     */
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
        // Go install locations, for developers who build xgrep themselves.
        System.getenv("GOBIN")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        System.getenv("GOPATH")?.takeIf { it.isNotBlank() }
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { add(Path.of(it, "bin")) }
        add(Path.of(System.getProperty("user.home"), "go", "bin"))
    }

    companion object {
        private val PRESENT_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        private val ABSENT_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(2)

        @JvmStatic
        fun getInstance(): XgrepBinaryService = service()
    }
}
