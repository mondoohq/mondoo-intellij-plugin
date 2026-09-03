// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.deps

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.mondoo.intellij.binary.XgrepBinaryService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers "which of these dependencies does my code actually use?".
 *
 * Runs entirely offline — it reads manifests and resolves imports from source, with
 * no package manager, no network and no account. That matters, because the related
 * question ("is this dependency vulnerable?") needs an advisory feed and therefore
 * a Mondoo Platform login, which this deliberately does not require.
 *
 * A CLI invocation rather than an LSP command: the scanner exposes the reachability
 * graph only on the command line, and it is a whole-project analysis rather than
 * per-file editor state.
 */
@Service(Service.Level.PROJECT)
class DependencyReachabilityService(private val project: Project) {

    private val log = Logger.getInstance(DependencyReachabilityService::class.java)
    private val latest = AtomicReference<ReachabilityReport?>(null)
    private val running = AtomicBoolean(false)

    /** The most recent report, or null if none has been produced. */
    fun report(): ReachabilityReport? = latest.get()

    fun isRunning(): Boolean = running.get()

    /** Rebuilds the graph. Cancellable; the graph build is the dominant cost. */
    fun refresh() {
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
        if (binary == null) {
            notify("The xgrep scanner is not installed. Run Set Up Scanner.", NotificationType.WARNING)
            return
        }
        val projectPath = project.basePath ?: return
        if (!running.compareAndSet(false, true)) return

        object : Task.Backgroundable(project, "Analyzing dependency reachability", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val command = GeneralCommandLine(binary.toString())
                    .withParameters("deps", "reachability", projectPath, "--json")
                    .withWorkDirectory(projectPath)

                val output = CapturingProcessHandler(command).runProcess(TIMEOUT_MS, true)
                if (indicator.isCanceled) return

                if (output.exitCode != 0) {
                    log.warn("deps reachability failed: ${output.stderr.take(500)}")
                    notify(
                        "Could not analyze dependencies: " +
                            output.stderr.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(300),
                        NotificationType.ERROR,
                    )
                    return
                }

                val report = ReachabilityReport.parse(output.stdout)
                if (report == null) {
                    // A parse failure means the scanner's output shape moved; say so
                    // rather than showing an empty view that reads as "no dependencies".
                    notify(
                        "The dependency report could not be read. The scanner may be newer than this plugin.",
                        NotificationType.ERROR,
                    )
                    return
                }

                latest.set(report)
                project.messageBus.syncPublisher(TOPIC).reachabilityChanged(report)
                log.info("Mondoo: dependency reachability — ${report.total} package(s)")
            }

            override fun onFinished() {
                running.set(false)
            }
        }.queue()
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Mondoo")
            .createNotification(content, type).notify(project)
    }

    fun interface Listener {
        fun reachabilityChanged(report: ReachabilityReport)
    }

    companion object {
        private const val TIMEOUT_MS = 10 * 60 * 1000

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Mondoo dependency reachability", Listener::class.java)

        @JvmStatic
        fun getInstance(project: Project): DependencyReachabilityService = project.service()
    }
}
