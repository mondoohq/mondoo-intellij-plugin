// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Verifies that what the plugin declares actually resolves, and logs the result.
 *
 * Off unless `-Dmondoo.selfcheck=true` is set, so it costs a normal session nothing.
 *
 * It exists because a whole class of defect is invisible to both the compiler and
 * the unit suite: an action named in plugin.xml whose class was renamed, a service
 * that throws in its constructor, a tool window whose factory cannot load. Those
 * surface as a dead menu item or a stack trace in a user's log — which is exactly
 * how the status-bar menu bug was found, by someone looking at a screenshot.
 *
 * Reporting through the log rather than through a test framework is deliberate: the
 * platform test framework cannot be put on this project's test classpath without
 * breaking the fast suite, and a check that runs in a real IDE is closer to what
 * users get than a fixture would be.
 */
internal class MondooSelfCheck : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (System.getProperty(ENABLED_PROPERTY) != "true") return

        val failures = mutableListOf<String>()
        checkActions(failures)
        checkServices(project, failures)

        // Instantiating the policy index proves nothing about it: its job is to read
        // the project, so the check makes it do that. Asynchronous, so its own line
        // lands after this one.
        runCatching { com.mondoo.intellij.policy.PolicyIndexService.getInstance(project).refresh() }
            .onFailure { failures += "policy index refresh threw: ${it.javaClass.simpleName}" }

        if (failures.isEmpty()) {
            LOG.info("$MARKER PASS: ${DeclaredActions.CORE.size} actions, all services")
        } else {
            failures.forEach { LOG.warn("$MARKER FAIL: $it") }
            LOG.warn("$MARKER FAILED with ${failures.size} problem(s)")
        }
    }

    private fun checkActions(failures: MutableList<String>) {
        val manager = ActionManager.getInstance()
        DeclaredActions.CORE.forEach { id ->
            if (manager.getAction(id) == null) failures += "action does not resolve: $id"
        }
        if (manager.getAction(DeclaredActions.GROUP) == null) {
            failures += "the ${DeclaredActions.GROUP} group does not resolve"
        }
        // Actions from the optional LSP module are only expected where that module
        // loaded; on an IDE without it their absence is correct, not a failure.
        if (lspModuleLoaded()) {
            DeclaredActions.LSP_MODULE.forEach { id ->
                if (manager.getAction(id) == null) failures += "LSP action does not resolve: $id"
            }
        } else {
            LOG.info("$MARKER: LSP module not loaded here, skipping its actions")
        }

        // Same reasoning for the terminal: absent where the Terminal plugin is
        // disabled, which is a supported state rather than a failure.
        if (moduleLoaded("org.jetbrains.plugins.terminal.TerminalToolWindowManager")) {
            DeclaredActions.TERMINAL_MODULE.forEach { id ->
                if (manager.getAction(id) == null) failures += "terminal action does not resolve: $id"
            }
        } else {
            LOG.info("$MARKER: Terminal not present here, skipping its actions")
        }
    }

    private fun lspModuleLoaded(): Boolean =
        moduleLoaded("com.intellij.platform.lsp.api.LspServerManager")

    private fun moduleLoaded(className: String): Boolean =
        runCatching { Class.forName(className, false, javaClass.classLoader) }.isSuccess

    private fun checkServices(project: Project, failures: MutableList<String>) {
        // A service that throws in its constructor takes its whole feature with it,
        // and nothing else here constructs them.
        check(failures, "MondooSettings") { com.mondoo.intellij.settings.MondooSettings.getInstance() }
        check(failures, "XgrepBinaryService") { com.mondoo.intellij.binary.XgrepBinaryService.getInstance() }
        check(failures, "CnspecBinaryService") { com.mondoo.intellij.binary.CnspecBinaryService.getInstance() }
        check(failures, "MqlrBinaryService") { com.mondoo.intellij.binary.MqlrBinaryService.getInstance() }
        check(failures, "XgrepFindingsStore") { com.mondoo.intellij.findings.XgrepFindingsStore.getInstance(project) }
        check(failures, "XgrepScanCoordinator") { com.mondoo.intellij.lsp.XgrepScanCoordinator.getInstance(project) }
        check(failures, "XgrepScanNotifier") { com.mondoo.intellij.lsp.XgrepScanNotifier.getInstance(project) }
        check(failures, "XgrepSearchService") { com.mondoo.intellij.search.XgrepSearchService.getInstance(project) }
        check(failures, "BomService") { com.mondoo.intellij.bom.BomService.getInstance(project) }
        check(failures, "DependencyReachabilityService") {
            com.mondoo.intellij.deps.DependencyReachabilityService.getInstance(project)
        }
        check(failures, "PolicyLintService") { com.mondoo.intellij.policy.PolicyLintService.getInstance(project) }
        check(failures, "PolicyIndexService") { com.mondoo.intellij.policy.PolicyIndexService.getInstance(project) }
        check(failures, "TargetStore") { com.mondoo.intellij.target.TargetStore.getInstance(project) }
        check(failures, "CnspecRunService") { com.mondoo.intellij.target.CnspecRunService.getInstance(project) }
    }

    private fun check(failures: MutableList<String>, name: String, supplier: () -> Any?) {
        runCatching { supplier() }
            .onFailure { failures += "service does not instantiate: $name (${it.javaClass.simpleName})" }
            .onSuccess { if (it == null) failures += "service resolved to null: $name" }
    }

    internal companion object {
        private const val ENABLED_PROPERTY = "mondoo.selfcheck"
        internal const val MARKER = "Mondoo self-check"
        private val LOG = Logger.getInstance(MondooSelfCheck::class.java)
    }
}
