// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.mondoo.intellij.binary.CnspecBinaryService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/**
 * Runs cnspec against a configured target and streams the output.
 *
 * Output goes to a console in the Mondoo tool window rather than a terminal: the
 * Terminal plugin is not bundled in every IntelliJ-based IDE, and depending on it
 * would narrow where this works. A console also gives clickable file references for
 * free.
 */
@Service(Service.Level.PROJECT)
class CnspecRunService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CnspecRunService::class.java)

    /** `cnspec scan` against [target]. */
    fun scan(target: TargetConfiguration) = run(target, listOf("scan"), "Scan: ${target.name}")

    /** `cnspec run -c <mql>` against [target]. */
    fun runQuery(target: TargetConfiguration, mql: String) =
        run(target, listOf("run", "-c", mql), "Query: ${target.name}")

    private fun run(target: TargetConfiguration, verb: List<String>, title: String) {
        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            CnspecBinaryService.getInstance().notifyMissing(project)
            return
        }

        val inventory = try {
            writeInventory(target)
        } catch (e: Exception) {
            log.warn("could not write the inventory file", e)
            return
        }

        val command = GeneralCommandLine(binary.toString())
            .withParameters(verb + listOf("--inventory-file", inventory.toString()))
            .withWorkDirectory(project.basePath)

        // Secrets go in the environment, never in the arguments above: a command
        // line is visible in the process table to anything running on the machine.
        CredentialEnvironment.forTarget(target).forEach { (k, v) -> command.withEnvironment(k, v) }

        val console = showConsole(title)
        val handler = OSProcessHandler(command)
        ProcessTerminatedListener.attach(handler, project)
        console.attachToProcess(handler)

        handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                deleteInventory(inventory)
            }
        })
        handler.startNotify()
    }

    /**
     * Writes the inventory to a private, short-lived file.
     *
     * Mode 0600 in a directory only this user can enter, because the document holds
     * connection detail and, for some providers, key paths. It is deleted as soon as
     * the process ends; a sweep on startup catches anything a crash left behind.
     */
    private fun writeInventory(target: TargetConfiguration): Path {
        val dir = Files.createTempDirectory(INVENTORY_DIR_PREFIX)
        restrictToOwner(dir)
        val file = dir.resolve("inventory.yml")
        Files.writeString(file, InventoryBuilder.build(target))
        restrictToOwner(file)
        return file
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOfNotNull(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE.takeIf { Files.isDirectory(path) },
                ),
            )
        }.onFailure {
            // Windows has no POSIX permissions; the temp directory is per-user there.
            log.debug("could not set POSIX permissions on $path: ${it.message}")
        }
    }

    private fun deleteInventory(file: Path) {
        runCatching {
            Files.deleteIfExists(file)
            Files.deleteIfExists(file.parent)
        }
    }

    /** Removes inventory directories a crash left behind. */
    fun sweepStaleInventories() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        runCatching {
            Files.list(tmp).use { stream ->
                stream.filter { it.fileName.toString().startsWith(INVENTORY_DIR_PREFIX) }
                    .filter {
                        val age = System.currentTimeMillis() - Files.getLastModifiedTime(it).toMillis()
                        age > TimeUnit.MINUTES.toMillis(STALE_AFTER_MINUTES)
                    }
                    .forEach { dir ->
                        runCatching {
                            Files.list(dir).use { it.forEach(Files::deleteIfExists) }
                            Files.deleteIfExists(dir)
                        }
                    }
            }
        }
    }

    private fun showConsole(title: String): ConsoleView {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(this, console)

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Mondoo")
        toolWindow?.let { window ->
            val content = ContentFactory.getInstance().createContent(console.component, title, false)
            content.setDisposer(console)
            window.contentManager.addContent(content)
            window.contentManager.setSelectedContent(content)
            window.activate(null)
        }
        return console
    }

    override fun dispose() = Unit

    companion object {
        private const val INVENTORY_DIR_PREFIX = "mondoo-intellij-inv-"
        private const val STALE_AFTER_MINUTES = 30L

        @JvmStatic
        fun getInstance(project: Project): CnspecRunService = project.service()
    }
}
