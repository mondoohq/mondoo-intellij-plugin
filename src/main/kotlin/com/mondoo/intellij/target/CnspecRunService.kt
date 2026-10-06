// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.mondoo.intellij.binary.CnspecBinaryService
import java.nio.charset.StandardCharsets
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

    /**
     * Scans [target] with a bundle from the project, optionally one policy from it.
     *
     * `--policy` names a policy inside the bundle and cnspec requires `--policy-bundle`
     * alongside it, which is why both are passed together rather than the uid alone.
     */
    fun scanBundle(target: TargetConfiguration, bundle: Path, policyUid: String?, label: String) =
        run(
            target,
            buildList {
                add("scan")
                add("--policy-bundle")
                add(bundle.toString())
                policyUid?.let {
                    add("--policy")
                    add(it)
                }
            },
            "$label: ${target.name}",
        )

    /**
     * Checks that cnspec can actually reach [target], without opening a console.
     *
     * A capturing run rather than a streaming one: the answer here is a verdict, not
     * output to read, and the point is to find out before committing to a scan. See
     * [ConnectionProbe] for why the exit code is not the signal.
     *
     * Blocking, and must not be called on the EDT.
     */
    fun testConnection(target: TargetConfiguration): ConnectionResult {
        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull()
            ?: return ConnectionResult.Unreachable("cnspec is not installed")

        val inventory = try {
            writeInventory(target)
        } catch (e: Exception) {
            log.warn("could not write the inventory file", e)
            return ConnectionResult.Unreachable("could not write a temporary inventory file")
        }

        return try {
            val command = GeneralCommandLine(binary.toString())
                .withParameters("run", "--inventory-file", inventory.toString(), "-c", ConnectionProbe.QUERY, "-j")
                .withWorkDirectory(project.basePath)
                .withCharset(StandardCharsets.UTF_8)
            CredentialEnvironment.forTarget(target).forEach { (k, v) -> command.withEnvironment(k, v) }

            val output = CapturingProcessHandler(command).runProcess(CONNECTION_TIMEOUT_MS, true)
            ConnectionProbe.interpret(output.stdout, output.stderr, output.isTimeout)
        } catch (e: ExecutionException) {
            log.warn("could not start cnspec", e)
            ConnectionResult.Unreachable("could not start cnspec: ${e.message}")
        } finally {
            deleteInventory(inventory)
        }
    }

    private fun run(target: TargetConfiguration, verb: List<String>, title: String) {
        // Before writing a new one, not on a startup hook: this is the moment we know
        // a sweep is wanted, and it does not add another activity to every project
        // open for a directory most users never have anything in. Off the EDT and
        // fire-and-forget, because it stats every entry in the temp directory and
        // nothing below depends on it — each run writes its own new directory, and
        // the sweep only touches ones half an hour old.
        ApplicationManager.getApplication().executeOnPooledThread { sweepStaleInventories() }

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

        // Started before the console is shown: a cnspec that cannot launch — deleted
        // between resolution and here, or not executable — should leave no empty
        // console tab behind, and must not leak the inventory file it would have read.
        val handler = try {
            OSProcessHandler(command)
        } catch (e: ExecutionException) {
            log.warn("could not start cnspec", e)
            deleteInventory(inventory)
            notify("Could not start cnspec: ${e.message}", NotificationType.ERROR)
            return
        }

        val console = startRunLog(title, handler)
        ProcessTerminatedListener.attach(handler, project)
        console.attachToProcess(handler)

        val output = StringBuffer()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                if (output.length < OUTPUT_KEEP_CHARS) output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                deleteInventory(inventory)
                // cnspec's own error says what failed but not what to do about it.
                CnspecErrors.hint(output.toString())?.let { hint ->
                    ApplicationManager.getApplication().invokeLater({
                        console.print("\n$hint\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
                    }, project.disposed)
                }
            }
        })
        handler.startNotify()
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(content, type)
            .notify(project)
    }

    /**
     * Writes the inventory to a private, short-lived file.
     *
     * Mode 0600 in a directory only this user can enter, because the document holds
     * connection detail and, for some providers, key paths. It is deleted as soon as
     * the process ends, and [sweepStaleInventories] — run before each new one is
     * written — catches whatever a crash left behind.
     */
    private fun writeInventory(target: TargetConfiguration): Path {
        val dir = Files.createTempDirectory(INVENTORY_DIR_PREFIX)
        restrictToOwner(dir)
        val file = dir.resolve("inventory.yml")
        // The secrets are read here, at the moment of writing, and go straight into a
        // file that is about to be locked down and later deleted. They are never held
        // on the configuration object, which refuses to carry them at all.
        Files.writeString(file, InventoryBuilder.build(target, TargetCredentials.forTarget(target)))
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

    /** Removes inventory directories a crash left behind. Swept before each run. */
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

    private var runLog: ConsoleView? = null
    private var current: OSProcessHandler? = null
    private val runListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** The run log the Policies tab shows, created on first use. EDT only. */
    fun runLogComponent(): javax.swing.JComponent = runLog().component

    /** [listener] runs on the EDT with the run's title whenever a run starts, until [parent] is disposed. */
    fun onRunStarted(parent: Disposable, listener: (String) -> Unit) {
        runListeners += listener
        Disposer.register(parent) { runListeners -= listener }
    }

    /**
     * Clears the run log for a new run and brings the Policies tab forward.
     *
     * One log, reused: runs are frequent while writing a policy, and a tab per run
     * filled the tool window with dead output. A run still going is stopped first,
     * so its last lines cannot land in the new run's log.
     */
    private fun startRunLog(title: String, handler: OSProcessHandler): ConsoleView {
        current?.takeUnless { it.isProcessTerminated }?.let { previous ->
            previous.destroyProcess()
            previous.waitFor(STOP_WAIT_MS)
        }
        current = handler
        val console = runLog()
        console.clear()
        console.print("▶ $title\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
        runListeners.forEach { it(title) }
        ToolWindowManager.getInstance(project).getToolWindow("Mondoo")?.let { window ->
            window.activate {
                window.contentManager.findContent(POLICIES_TAB)?.let { window.contentManager.setSelectedContent(it) }
            }
        }
        return console
    }

    private fun runLog(): ConsoleView = runLog ?: TextConsoleBuilderFactory.getInstance()
        .createBuilder(project).console
        .also {
            runLog = it
            Disposer.register(this, it)
        }

    override fun dispose() = Unit

    companion object {
        private const val INVENTORY_DIR_PREFIX = "mondoo-intellij-inv-"
        private const val STALE_AFTER_MINUTES = 30L

        private const val POLICIES_TAB = "Policies"
        private const val STOP_WAIT_MS = 2_000L
        private const val OUTPUT_KEEP_CHARS = 64 * 1024

        /**
         * Long enough for a cold provider install and an SSH handshake, short enough
         * that a wrong host does not look like a hang.
         */
        private const val CONNECTION_TIMEOUT_MS = 90_000

        @JvmStatic
        fun getInstance(project: Project): CnspecRunService = project.service()
    }
}
