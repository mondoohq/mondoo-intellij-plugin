// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.history.LocalHistory
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The IDE side of one project's fix session: what the Fix tab shows, and the runs it
 * starts.
 *
 * Every decision about fixing is xgrep's, made in `xgrep fix serve` ([FixServer]).
 * This holds the latest answers so the tab can redraw without asking again, and does
 * the IDE's part around a run:
 * - save open documents before xgrep reads the files;
 * - put a Local History label in front of the run, so the whole run can be undone;
 * - reload what xgrep wrote;
 * - stream the agent's run to the Fix tab's run log.
 *
 * Listeners on [TOPIC] are told when anything changes; read the state from them on
 * the EDT.
 */
@Service(Service.Level.PROJECT)
class FixSession(private val project: Project) : Disposable {

    init {
        watchCache()
    }

    /**
     * Reloads when `.xgrep/findings.json` changes, whoever wrote it: a scan from this
     * tab, `xgrep scan` in a terminal, or an agent. Without this the tab needed a
     * Refresh button next to Scan, and the difference was not obvious.
     */
    private fun watchCache() {
        val reload = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.POOLED_THREAD, this)
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                override fun after(events: List<com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                    if (!loaded || busy.get()) return
                    if (events.none {
                            it.path.endsWith(CACHE_SUFFIX) &&
                                project.basePath?.let(it.path::startsWith) == true
                        }
                    ) {
                        return
                    }
                    reload.cancelAllRequests()
                    reload.addRequest({ refresh() }, CACHE_RELOAD_DELAY_MS)
                }
            },
        )
    }

    private val log = logger<FixSession>()

    @Volatile var findings: List<FixFinding> = emptyList()
        private set

    /** False until the project has been scanned into a findings cache. */
    @Volatile var cachePresent: Boolean = true
        private set

    @Volatile var serverInfo: ServerInfo? = null
        private set

    /** Why the session cannot work right now, for the user; null when it can. */
    @Volatile var problem: String? = null
        private set

    /** A short line on what the session is doing or just did. */
    @Volatile var status: String = ""
        private set

    @Volatile var loaded: Boolean = false
        private set

    private val busy = AtomicBoolean(false)
    val isBusy: Boolean get() = busy.get()

    private val previews = ConcurrentHashMap<String, Preview>()

    /** File content from before the last run, by absolute path, for before/after diffs. */
    private val beforeRun = ConcurrentHashMap<String, String>()

    fun cachedPreview(fingerprint: String): Preview? = previews[fingerprint]

    fun contentBeforeRun(absolutePath: String): String? = beforeRun[absolutePath]

    fun finding(fingerprint: String): FixFinding? = findings.firstOrNull { it.fingerprint == fingerprint }

    /** Reloads the findings, scanning first when [rescan] or when there is no cache. */
    fun refresh(rescan: Boolean = false, then: (() -> Unit)? = null) {
        val title = if (rescan) "Scanning for findings" else "Loading findings"
        background(title) { client ->
            if (rescan) {
                setStatus("Scanning the project...")
                client.rescan()
                previews.clear()
            }
            reload(client)
            setStatus(if (rescan) "Scan complete: ${findings.size} findings" else "")
            then?.let { ApplicationManager.getApplication().invokeLater(it) }
        }
    }

    fun preview(fingerprint: String, onDone: (Preview?) -> Unit) {
        previews[fingerprint]?.let { return onDone(it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { FixServer.getInstance(project).client().preview(fingerprint) }
                .onFailure { log.info("preview failed for $fingerprint: ${it.message}") }
                .getOrNull()
            result?.let { previews[fingerprint] = it }
            ApplicationManager.getApplication().invokeLater({ onDone(result) }, project.disposed)
        }
    }

    fun setTriage(fingerprints: Collection<String>, status: TriageStatus, rationale: String = "") {
        if (fingerprints.isEmpty()) return
        background("Recording triage verdict") { client ->
            fingerprints.forEach { client.setTriage(it, status, rationale) }
            reload(client)
            setStatus("Marked ${fingerprints.size} finding(s) ${status.label.lowercase()}")
        }
    }

    fun graphContext(fingerprint: String, onDone: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = runCatching { FixServer.getInstance(project).client().graphContext(fingerprint) }
                .getOrElse { "Graph context is not available: ${it.message}" }
            ApplicationManager.getApplication().invokeLater({ onDone(text) }, project.disposed)
        }
    }

    /**
     * Fixes the findings: deterministic ones through the harness, assisted ones with
     * the coding agent, all in xgrep. Cancelling the progress stops the agent.
     */
    fun run(fingerprints: Collection<String>) {
        if (fingerprints.isEmpty()) return
        if (!busy.compareAndSet(false, true)) {
            notify("A fix run is already in progress.", NotificationType.WARNING)
            return
        }
        val targets = fingerprints.toList()

        // xgrep reads and writes the files on disk, so unsaved edits must land first,
        // and the label lets the user roll the whole run back from Local History.
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
            LocalHistory.getInstance().putSystemLabel(project, "Before xgrep fix")
        }
        snapshotBefore(targets)
        val console = FixConsole.getInstance(project)
        console.startRun(targets.size)

        object : Task.Backgroundable(project, "Fixing findings with xgrep", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val client = FixServer.getInstance(project).client()
                val stream = client.runFix(targets)
                // The stream blocks in a read; watch the indicator from the side and
                // close the connection on cancel, which stops the agent server-side.
                val watcher = ApplicationManager.getApplication().executeOnPooledThread {
                    runCatching {
                        while (!indicator.isCanceled) Thread.sleep(CANCEL_POLL_MS)
                        stream.cancel()
                    }
                }
                var done: RunFixEvent.Done? = null
                try {
                    while (true) {
                        val event = stream.next() ?: break
                        when (event) {
                            is RunFixEvent.Progress -> {
                                console.progress(event.message)
                                indicator.text = event.message
                                if (event.total > 0) indicator.fraction = event.completed.toDouble() / event.total
                            }
                            is RunFixEvent.AgentStarted -> {
                                indicator.text = "Waiting for ${event.agent.name} (${event.findingCount} finding(s))"
                                console.agentStarted(event.agent, event.findingCount)
                            }
                            is RunFixEvent.AgentActivity -> {
                                console.activity(event.activity)
                                if (event.activity.kind == ActivityKind.TOOL) {
                                    indicator.text2 = "${event.activity.tool} ${event.activity.target}".trim()
                                }
                            }
                            is RunFixEvent.FilesChanged -> reloadFiles(event.paths)
                            is RunFixEvent.OutcomeReported -> {
                                console.outcome(event.outcome)
                                applyOutcome(event.outcome)
                            }
                            is RunFixEvent.Done -> {
                                done = event
                                console.done(event)
                            }
                            RunFixEvent.Unknown -> Unit
                        }
                    }
                } finally {
                    stream.close()
                    watcher.cancel(true)
                }
                runCatching { reload(client) }
                summarize(done, indicator.isCanceled)
            }

            override fun onThrowable(error: Throwable) {
                val message = (error as? FixServerUnavailable)?.message ?: "The fix run failed: ${error.message}"
                notify(message, NotificationType.ERROR)
                console.error(message)
            }

            override fun onFinished() {
                busy.set(false)
                changed()
            }
        }.queue()
        changed()
    }

    /**
     * Fixes (or, with [run] false, only shows) findings the editor or the Code Security
     * tab knows about — the Alt+Enter and right-click paths.
     *
     * Those views get findings from the language server, the session from the findings
     * cache, so they are matched by file, line and rule. One the cache does not have
     * yet means the code changed since the last scan, so the session scans first.
     *
     * The Fix tab opens straight away, before any of that, so what happens next is
     * visible rather than somewhere else.
     */
    fun fixFindings(targets: List<FixTarget>, run: Boolean = true) {
        if (targets.isEmpty()) return
        com.mondoo.intellij.findings.XgrepFindingsToolWindowFactory.showFixTab(project)

        fun matches() = FixMatching.match(targets, findings, ::samePath)
        background("Finding the findings to fix") { client ->
            if (!loaded) reload(client)
            var found = matches()
            if (found.size < targets.size) {
                setStatus("Scanning so the fix starts from current code...")
                client.rescan()
                previews.clear()
                reload(client)
                setStatus("")
                found = matches()
            }
            val requested = if (found.size < targets.size) " (requested: $targets)" else ""
            log.info(
                "fix request for ${targets.size} finding(s): matched ${found.size} in the findings cache$requested",
            )
            // Checked either way, so the Fix tab shows exactly what was sent to it; a
            // run unchecks each finding as its outcome lands. Held here rather than
            // applied to the panel directly, so it lands whenever the tab next draws,
            // however that is ordered against the reloads above.
            if (found.isNotEmpty()) {
                pendingFocus =
                    PendingFocus(found.first().fingerprint, found.filter { it.fixable }.map { it.fingerprint })
            }
            ApplicationManager.getApplication().invokeLater({
                com.mondoo.intellij.findings.XgrepFindingsToolWindowFactory.showFixTab(project) { panel ->
                    panel.applyPendingFocus()
                }
                if (!run) return@invokeLater
                val fixable = found.filter { it.fixable }
                when {
                    found.isEmpty() -> notify(
                        "xgrep fix: the current scan no longer reports ${if (targets.size == 1) "this finding" else "these findings"}.",
                        NotificationType.INFORMATION,
                    )
                    fixable.isEmpty() -> notify(
                        "xgrep fix: nothing to fix here (${found.joinToString { FixDetails.badge(it) }}).",
                        NotificationType.INFORMATION,
                    )
                    else -> run(fixable.map { it.fingerprint })
                }
            }, project.disposed)
        }
    }

    /** What the Fix tab should select and check the next time it draws. */
    data class PendingFocus(val select: String, val check: List<String>)

    @Volatile var pendingFocus: PendingFocus? = null

    /** Hands the pending focus to the caller once, if there is one. */
    fun takePendingFocus(): PendingFocus? = pendingFocus.also { pendingFocus = null }

    private fun samePath(a: String, b: String): Boolean =
        runCatching { Path.of(a).toRealPath() == Path.of(b).toRealPath() }.getOrDefault(a == b)

    fun createPullRequest() {
        background("Opening a pull request") { client ->
            setStatus("Writing the pull request with the coding agent...")
            val pr = client.createPullRequest()
            setStatus("")
            val noun = pr.noun
            when {
                pr.opened -> notify(
                    "Opened ${noun.replaceFirstChar(Char::titlecase)}: ${pr.title}",
                    NotificationType.INFORMATION,
                    NotificationAction.createSimpleExpiring("Open in Browser") {
                        com.intellij.ide.BrowserUtil.browse(pr.url)
                    },
                )
                pr.pushed -> notify(
                    "Pushed branch ${pr.branch}. Open the $noun from your git host.",
                    NotificationType.INFORMATION,
                )
                else -> notify(
                    "Committed the fixes on branch ${pr.branch}, but the push failed: " +
                        pr.pushError.lineSequence().firstOrNull().orEmpty(),
                    NotificationType.WARNING,
                )
            }
        }
    }

    // --- internals ---------------------------------------------------------------

    private fun reload(client: FixClient) {
        if (serverInfo == null) serverInfo = runCatching { client.serverInfo() }.getOrNull()
        val list = client.listFindings()
        findings = list.findings.sortedByDescending { it.severityRank }
        cachePresent = list.cachePresent
        problem = null
        loaded = true
        changed()
    }

    private fun applyOutcome(outcome: Outcome) {
        findings = findings.map {
            if (it.fingerprint == outcome.fingerprint) it.copy(outcome = outcome, fixable = false) else it
        }
        previews.remove(outcome.fingerprint)
        changed()
    }

    private fun snapshotBefore(fingerprints: List<String>) {
        beforeRun.clear()
        fingerprints.mapNotNull { finding(it)?.absolutePath }.distinct().forEach { path ->
            runCatching { Files.readString(Path.of(path)) }.onSuccess { beforeRun[path] = it }
        }
    }

    private fun reloadFiles(paths: List<String>) {
        val files = paths.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(it)) }
        if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(true, false, false, *files.toTypedArray())
    }

    private fun summarize(done: RunFixEvent.Done?, cancelled: Boolean) {
        if (done == null) {
            if (cancelled) setStatus("Fix run cancelled")
            return
        }
        val summary = buildString {
            append("${done.applied} fixed")
            if (done.rejected > 0) append(", ${done.rejected} rejected")
            if (done.skipped > 0) append(", ${done.skipped} skipped")
            if (done.cancelled || cancelled) append(" (cancelled)")
        }
        setStatus("Fix run: $summary")
        if (done.applied == 0) {
            notify(
                "xgrep fix: $summary.",
                if (done.rejected >
                    0
                ) {
                    NotificationType.WARNING
                } else {
                    NotificationType.INFORMATION
                },
            )
            return
        }
        notify(
            "xgrep fix: $summary. Review the changes, then open a pull request.",
            NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Create Pull Request") { createPullRequest() },
            NotificationAction.createSimpleExpiring("Review Changes") { showChanges() },
        )
    }

    private fun showChanges() {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(com.intellij.openapi.wm.ToolWindowId.COMMIT)
            ?.activate(null)
            ?: com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Version Control")?.activate(null)
    }

    /**
     * Runs [work] with a client on a background task, turning failures into the
     * session's [problem] rather than an exception dialog.
     */
    private fun background(title: String, work: (FixClient) -> Unit) {
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    work(FixServer.getInstance(project).client())
                } catch (e: FixServerUnavailable) {
                    problem = e.message
                    loaded = true
                    changed()
                } catch (e: ConnectException) {
                    if (e.code == "unavailable") FixServer.getInstance(project).restart()
                    setStatus("")
                    notify("xgrep: ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun setStatus(text: String) {
        status = text
        changed()
    }

    private fun changed() {
        if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).sessionChanged()
    }

    private fun notify(content: String, type: NotificationType, vararg actions: NotificationAction) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(content, type)
            .also { n -> actions.forEach(n::addAction) }
            .notify(project)
    }

    override fun dispose() = Unit

    fun interface Listener {
        fun sessionChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Mondoo xgrep fix session", Listener::class.java)

        private const val CANCEL_POLL_MS = 200L
        private const val CACHE_SUFFIX = "/.xgrep/findings.json"
        private const val CACHE_RELOAD_DELAY_MS = 500

        fun getInstance(project: Project): FixSession = project.service()
    }
}

/** A finding as the editor knows it: file, 1-based line and rule. */
data class FixTarget(val absolutePath: String, val line: Int, val ruleId: String)
