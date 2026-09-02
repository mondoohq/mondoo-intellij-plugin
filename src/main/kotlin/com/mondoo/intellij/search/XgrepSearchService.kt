// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.search

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.mondoo.intellij.lsp.XgrepScanCoordinator
import org.eclipse.lsp4j.ExecuteCommandParams

/**
 * Structural code search over `xgrep.search` / `xgrep.exportRule`.
 *
 * Unlike the scan commands these answer synchronously — they are queries, so the
 * client renders the results itself rather than the server publishing diagnostics.
 * Verified against xgrep 0.57; see docs/adr/0001.
 */
@Service(Service.Level.PROJECT)
class XgrepSearchService(private val project: Project) {

    private val log = Logger.getInstance(XgrepSearchService::class.java)

    /**
     * Runs a structural search.
     *
     * @return matches, or null when the scanner is unavailable or the request
     *   failed. Null is deliberately distinct from an empty list: "we could not
     *   ask" must never be rendered as "nothing matched".
     */
    fun search(pattern: String, language: String, replacement: String? = null): List<XgrepSearchMatch>? {
        val arguments = listOfNotNull(pattern, language, replacement)
        val raw = execute("xgrep.search", arguments) ?: return null
        return XgrepSearchMatch.parseAll(raw as? List<*>)
    }

    /** Turns the pattern into a reusable rule. Returns the YAML, or null on failure. */
    fun exportRule(pattern: String, language: String, replacement: String? = null): String? =
        execute("xgrep.exportRule", listOfNotNull(pattern, language, replacement)) as? String

    private fun execute(command: String, arguments: List<Any>): Any? {
        val server = XgrepScanCoordinator.getInstance(project).runningServer()
        if (server == null) {
            log.info("$command requested but no xgrep server is running")
            return null
        }
        // Well above the platform's 10s default: search is synchronous server-side
        // and walks the whole tree.
        return server.sendRequestSync(SEARCH_TIMEOUT_MS) {
            it.workspaceService.executeCommand(ExecuteCommandParams(command, arguments))
        }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 120_000

        @JvmStatic
        fun getInstance(project: Project): XgrepSearchService = project.service()
    }
}
