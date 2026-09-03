// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.lsp

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns a scan-completion message into a notification and releases whatever is
 * waiting on the scan.
 *
 * The server answers `xgrep.scanWorkspace` immediately and reports the outcome
 * later via `window/showMessage`, so the progress indicator has nothing to wait on
 * except this.
 *
 * Per project, not a singleton. Each project runs its own server and its own scan,
 * and a shared waiter slot meant the second project to start a scan overwrote the
 * first project's callback — leaving that progress bar to sit until its own
 * half-hour deadline while someone else's completion released the wrong one.
 */
@Service(Service.Level.PROJECT)
internal class XgrepScanNotifier(private val project: Project) {

    private val waiter = AtomicReference<((String) -> Unit)?>(null)

    /** Registers a one-shot callback fired by the next scan-completion message. */
    fun awaitNext(onComplete: (String) -> Unit) {
        waiter.set(onComplete)
    }

    fun cancelWait() {
        waiter.set(null)
    }

    fun onScanCompleted(message: String) {
        waiter.getAndSet(null)?.invoke(message)
        if (project.isDisposed) return

        val type = if (XgrepScanMessages.isFailure(message)) {
            NotificationType.ERROR
        } else {
            NotificationType.INFORMATION
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Mondoo")
            .createNotification(message, type)
        notification.addAction(
            NotificationAction.createSimpleExpiring("Show findings") {
                ToolWindowManager.getInstance(project).getToolWindow("Mondoo")?.activate(null)
            },
        )
        notification.notify(project)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): XgrepScanNotifier = project.service()
    }
}
