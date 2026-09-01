package com.mondoo.intellij.binary

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.system.OS
import com.mondoo.intellij.settings.MondooSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Resolves the xgrep binary.
 *
 * Milestone 0 covers discovery only. Download / SHA-256 verification / extraction
 * from `https://releases.mondoo.com/xgrep/latest.json` land in milestone 1.
 */
@Service(Service.Level.APP)
class XgrepBinaryService {

    private val log = Logger.getInstance(XgrepBinaryService::class.java)

    /** The resolved binary, or null when xgrep cannot be found. */
    fun resolvedBinaryOrNull(): Path? {
        val configured = MondooSettings.getInstance().state.xgrepPath.orEmpty()
        if (configured.isNotBlank()) {
            val path = Path.of(configured)
            if (path.isRegularFile() && path.isExecutable()) return path
            log.warn("Configured mondoo.xgrepPath does not point at an executable: $configured")
        }

        managedInstall()?.let { return it }

        PathEnvironmentVariableUtil.findInPath(executableName())?.let { return it.toPath() }

        return commonBinDirs()
            .map { it.resolve(executableName()) }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }
    }

    /** Root of the plugin-managed install tree: `<system>/mondoo/xgrep`. */
    fun managedRoot(): Path = PathManager.getSystemDir().resolve("mondoo").resolve("xgrep")

    private fun managedInstall(): Path? {
        val root = managedRoot()
        if (!root.isDirectory()) return null
        return Files.list(root).use { stream ->
            stream.filter { it.isDirectory() }
                .map { it.resolve(executableName()) }
                .filter { it.isRegularFile() && it.isExecutable() }
                // Newest version directory wins; version dirs are semver-named.
                .sorted(compareByDescending { it.parent.fileName.toString() })
                .findFirst()
                .orElse(null)
        }
    }

    private fun executableName(): String = if (OS.CURRENT == OS.Windows) "xgrep.exe" else "xgrep"

    /**
     * Common install locations, ported from `commonBinDirs`/`goBinDirs` in
     * vscode-mondoo/src/utils/platform.ts.
     */
    private fun commonBinDirs(): List<Path> = buildList {
        if (OS.CURRENT == OS.Windows) {
            System.getenv("ProgramData")?.let { add(Path.of(it, "chocolatey", "bin")) }
            System.getenv("LOCALAPPDATA")?.let { add(Path.of(it, "Microsoft", "WinGet", "Links")) }
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
        @JvmStatic
        fun getInstance(): XgrepBinaryService = service()
    }
}
