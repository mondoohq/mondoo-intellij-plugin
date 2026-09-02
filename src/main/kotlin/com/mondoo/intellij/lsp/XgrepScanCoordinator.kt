package com.mondoo.intellij.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs xgrep's on-demand scans over `workspace/executeCommand`.
 *
 * The four commands are advertised by the server and verified against xgrep 0.57
 * (docs/adr/0001): `xgrep.scanWorkspace`, `xgrep.scanChanged`, `xgrep.search` and
 * `xgrep.exportRule`.
 *
 * Scans are asynchronous server-side: the request returns immediately and the
 * outcome arrives later as a `window/showMessage`, so progress is tied to that
 * message rather than to the response.
 */
@Service(Service.Level.PROJECT)
class XgrepScanCoordinator(private val project: Project) {

    private val log = Logger.getInstance(XgrepScanCoordinator::class.java)
    private val scanInFlight = AtomicBoolean(false)

    /** The running server for this project, or null when none is up. */
    internal fun runningServer(): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(XgrepLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    fun scanWorkspace() = executeScan("xgrep.scanWorkspace", emptyList(), "Scanning the workspace with xgrep")

    fun scanChanged() =
        executeScan("xgrep.scanChanged", emptyList(), "Scanning changed files with xgrep")

    /** Scans what changed since [ref] — a branch, tag, commit, or `base..head`. */
    fun scanChangedSince(ref: String) =
        executeScan("xgrep.scanChanged", listOf(ref), "Scanning changes since $ref")

    private fun executeScan(command: String, arguments: List<Any>, title: String) {
        val server = runningServer()
        if (server == null) {
            notify("The xgrep scanner is not running", NotificationType.WARNING)
            return
        }
        if (!scanInFlight.compareAndSet(false, true)) {
            notify("An xgrep scan is already running", NotificationType.INFORMATION)
            return
        }

        // Cancellable: a workspace scan of a large repository runs for minutes, and an
        // indeterminate bar with no way out is its own bug.
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val done = CountDownLatch(1)
                XgrepScanNotifier.awaitNext { done.countDown() }
                try {
                    val response = server.sendRequestSync(REQUEST_TIMEOUT_MS) {
                        it.workspaceService.executeCommand(ExecuteCommandParams(command, arguments))
                    }
                    // sendRequestSync returns null on timeout, error or shutdown. That is
                    // "unavailable", never "no findings" — so say so rather than implying
                    // a clean result.
                    if (response == null) {
                        log.warn("$command did not return within ${REQUEST_TIMEOUT_MS}ms")
                    }
                    // The scan completes asynchronously, so wait for the server's
                    // window/showMessage — in short slices, so cancellation is noticed
                    // promptly, and bounded so a lost message cannot hang forever.
                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(SCAN_TIMEOUT_MINUTES)
                    while (true) {
                        if (indicator.isCanceled) {
                            // The server exposes no cancel command, so the scan itself
                            // keeps running; we stop waiting on it. Say so rather than
                            // implying it was stopped.
                            notify(
                                "Stopped waiting for the xgrep scan. It continues in the " +
                                    "background and its findings will still appear.",
                                NotificationType.INFORMATION,
                            )
                            return
                        }
                        if (done.await(CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)) return
                        if (System.nanoTime() >= deadline) {
                            notify("The xgrep scan did not report a result in time", NotificationType.WARNING)
                            return
                        }
                    }
                } finally {
                    XgrepScanNotifier.cancelWait()
                }
            }

            override fun onFinished() {
                scanInFlight.set(false)
            }
        }.queue()
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(content, type)
            .notify(project)
    }

    companion object {
        /**
         * Generous: a workspace scan of a large repository legitimately takes a
         * while to even acknowledge. The platform default of 10s is far too short.
         */
        private const val REQUEST_TIMEOUT_MS = 120_000
        private const val SCAN_TIMEOUT_MINUTES = 30L

        /** How often the wait wakes to notice cancellation. */
        private const val CANCELLATION_POLL_MS = 250L

        @JvmStatic
        fun getInstance(project: Project): XgrepScanCoordinator = project.service()
    }
}
