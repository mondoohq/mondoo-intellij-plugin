// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.mondoo.intellij.mql.MqlFiles
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Every policy bundle in the project, parsed and laid out as a tree.
 *
 * The bundles are found by walking the project's content roots rather than by asking
 * an index: `*.mql.yaml` is a compound suffix and not a file type, indexes need smart
 * mode, and the walk already skips excluded directories — which is the only filtering
 * that matters here. Policy bundles number in the tens, not the thousands.
 *
 * The text comes from the open document when there is one, so the tree reflects what
 * you are looking at rather than what was last written to disk.
 */
@Service(Service.Level.PROJECT)
class PolicyIndexService(private val project: Project) {

    private val log = Logger.getInstance(PolicyIndexService::class.java)
    private val tree = AtomicReference<List<PolicyNode>>(emptyList())
    private val scanning = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)

    /** The current tree. Empty until the first scan finishes. */
    fun tree(): List<PolicyNode> = tree.get()

    /**
     * Rescans off the EDT, then publishes.
     *
     * Coalesced rather than dropped. A save touching several bundles should not start
     * a second walk over the same files — but it must not be discarded either: an edit
     * arriving while a scan is running is precisely the one whose results that scan
     * will not contain. So it marks the index dirty and repeats afterwards.
     *
     * Nothing here goes near the EDT, and that is the point. Scheduling this with
     * `DumbService.smartInvokeLater` looked equivalent and was not: that posts an EDT
     * runnable carrying the modality state it was created with, and on a machine
     * showing anything modal the runnable simply never runs. It failed exactly that
     * way in CI — indexing finished, the project went smart, and the scan never
     * started for the remaining three minutes of the session.
     *
     * It has no smart-mode constraint either, and that is the same lesson learned a
     * second time: `inSmartMode` schedules its constraint through
     * `DumbService.runWhenSmart`, which posts to the EDT. Adding it reintroduced the
     * exact dependency it was meant to remove, and the instrumentation caught it —
     * on all three platforms the scan was requested and then never succeeded, failed
     * or cancelled, because the promise was waiting on a runnable that would never run.
     *
     * Nothing here needs indexes. `ProjectFileIndex.iterateContent` walks content
     * roots from the workspace model rather than querying an index, so it is available
     * during indexing; a `FilenameIndex` lookup would not have been, which is why this
     * walks instead.
     */
    fun refresh() {
        if (project.isDisposed) return
        if (!scanning.compareAndSet(false, true)) {
            dirty.set(true)
            return
        }
        dirty.set(false)

        // Every outcome says something. Three attempts at getting this scheduling
        // right failed in CI and produced no log line at all, because the only path
        // that was reached — cancellation — was the one being swallowed. An index
        // that quietly does nothing is indistinguishable from an index with nothing
        // to find, and that ambiguity cost more than the log lines do.
        log.info("Mondoo: policy index scan requested")

        ReadAction.nonBlocking<List<PolicyTree.Source>> { collectSources() }
            .expireWhen { project.isDisposed }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { sources -> publish(sources) }
            .onError { error ->
                if (error is java.util.concurrent.CancellationException) {
                    log.info("Mondoo: policy index scan cancelled before it produced a result")
                } else {
                    log.warn("Mondoo: policy index scan failed", error)
                }
            }
            .onProcessed {
                scanning.set(false)
                // Something changed while this was running, and it is not in the
                // result just published.
                if (dirty.compareAndSet(true, false)) refresh()
            }
    }

    private fun publish(sources: List<PolicyTree.Source>) {
        // Doubles as the answer to "why is my policy not in the tree", which is the
        // first question in any report about this tab.
        log.info(
            "Mondoo: policy index found ${sources.size} bundle(s), " +
                "${sources.sumOf { it.bundle.policies.size }} policies, " +
                "${sources.sumOf { it.bundle.queries.size }} queries",
        )
        val built = PolicyTree.build(sources)
        tree.set(built)
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(TOPIC).policiesChanged(built)
        }
    }

    /**
     * Collects and parses every bundle. Runs inside the read action above, so it
     * must not block on anything but the filesystem.
     */
    private fun collectSources(): List<PolicyTree.Source> {
        if (project.isDisposed) return emptyList()
        val base = project.basePath?.let(Path::of)
        val sources = mutableListOf<PolicyTree.Source>()
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && MqlFiles.isPolicyBundle(file.name) && file.length <= MAX_BUNDLE_BYTES) {
                textOf(file)?.let { text ->
                    sources += PolicyTree.Source(relativePath(base, file), PolicyBundle.parse(text))
                }
            }
            true
        }
        return sources
    }

    /** The open document's text when the file is being edited, else what is on disk. */
    private fun textOf(file: VirtualFile): String? =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text
            ?: runCatching { LoadTextUtil.loadText(file).toString() }
                .onFailure { log.debug("could not read ${file.name}: ${it.message}") }
                .getOrNull()

    private fun relativePath(base: Path?, file: VirtualFile): String {
        val absolute = runCatching { file.toNioPath() }.getOrNull() ?: return file.name
        // Forward slashes regardless of platform: the tree splits on '/' to nest
        // directories, and on Windows relativize() returns backslashes.
        return runCatching { base?.relativize(absolute)?.toString() }.getOrNull()
            ?.replace('\\', '/')
            ?: file.name
    }

    fun interface Listener {
        fun policiesChanged(tree: List<PolicyNode>)
    }

    companion object {
        /**
         * A hand-written policy bundle is kilobytes. Anything past this is a generated
         * or pasted document that would stall the parser for a tree nobody could read.
         */
        private const val MAX_BUNDLE_BYTES = 4L * 1024 * 1024

        @JvmStatic
        fun getInstance(project: Project): PolicyIndexService = project.service()

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Mondoo policy bundles", Listener::class.java)
    }
}
