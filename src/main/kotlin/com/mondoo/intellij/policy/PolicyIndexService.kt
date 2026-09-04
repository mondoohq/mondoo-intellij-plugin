// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
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

    /** The current tree. Empty until the first scan finishes. */
    fun tree(): List<PolicyNode> = tree.get()

    /**
     * Rescans in the background, then publishes.
     *
     * Coalesced: a save touching several bundles, or an impatient Refresh, should not
     * start a second walk over the same files.
     */
    fun refresh() {
        if (!scanning.compareAndSet(false, true)) return

        object : Task.Backgroundable(project, "Reading Mondoo policy bundles", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val built = runCatching {
                    val sources = scan()
                    // Doubles as the answer to "why is my policy not in the tree",
                    // which is the first question in any report about this tab.
                    log.info(
                        "Mondoo: policy index found ${sources.size} bundle(s), " +
                            "${sources.sumOf { it.bundle.policies.size }} policies, " +
                            "${sources.sumOf { it.bundle.queries.size }} queries",
                    )
                    PolicyTree.build(sources)
                }
                    .onFailure { log.warn("could not build the policy tree", it) }
                    .getOrDefault(emptyList())
                tree.set(built)
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(TOPIC).policiesChanged(built)
                }
            }

            override fun onFinished() {
                scanning.set(false)
            }
        }.queue()
    }

    /**
     * Walks the content roots under a cancellable read action.
     *
     * nonBlocking rather than a plain read action: this holds the lock while it reads
     * and parses every bundle, and a plain one would make a write action — anything
     * the user types — wait for the whole walk. This yields instead.
     */
    private fun scan(): List<PolicyTree.Source> =
        ReadAction.nonBlocking<List<PolicyTree.Source>> {
            if (project.isDisposed) return@nonBlocking emptyList()
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
            sources
        }.expireWhen { project.isDisposed }.executeSynchronously()

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
