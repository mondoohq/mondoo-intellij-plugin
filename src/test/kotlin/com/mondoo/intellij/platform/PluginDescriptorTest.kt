// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import com.mondoo.intellij.DeclaredActions
import org.junit.jupiter.api.Test

/**
 * Checks the plugin descriptors against the classes they name and against the
 * in-IDE self-check, without starting an IDE.
 *
 * Two failures motivated this and neither the compiler nor any other test could see
 * them: a class named in plugin.xml that had been renamed (a menu item that does
 * nothing), and — the reason it exists — action classes written and committed but
 * never registered, leaving a whole feature with no way to reach it.
 */
class PluginDescriptorTest {

    private val core = descriptor("/META-INF/plugin.xml")
    private val lspModule = descriptor("/META-INF/mondoo-lsp.xml")

    @Test
    fun `every class named in a descriptor exists`() {
        val missing = (core + lspModule)
            .let { CLASS_REF.findAll(it).toList() }
            .map { it.groupValues[1] }
            .distinct()
            .filterNot { fqcn -> runCatching { Class.forName(fqcn, false, javaClass.classLoader) }.isSuccess }

        assertTrue(missing.isEmpty(), "descriptors name classes that do not exist: $missing")
    }

    @Test
    fun `the self-check covers every declared action`() {
        assertEquals(
            actionIds(core).sorted(),
            DeclaredActions.CORE.sorted(),
            "DeclaredActions.CORE has drifted from plugin.xml",
        )
        assertEquals(
            actionIds(lspModule).sorted(),
            DeclaredActions.LSP_MODULE.sorted(),
            "DeclaredActions.LSP_MODULE has drifted from mondoo-lsp.xml",
        )
    }

    /**
     * An action nobody can reach is indistinguishable from an unimplemented feature,
     * which is exactly how the policy and target actions shipped unreachable.
     */
    @Test
    fun `every action class on the classpath is registered`() {
        val registered = CLASS_REF.findAll(core + lspModule).map { it.groupValues[1] }.toSet()
        val unregistered = ACTION_CLASSES.filterNot { it in registered }

        assertTrue(
            unregistered.isEmpty(),
            "these action classes exist but no descriptor registers them: $unregistered",
        )
    }

    private fun actionIds(xml: String) = ACTION_ID.findAll(xml).map { it.groupValues[1] }.toList()

    private fun descriptor(resource: String): String =
        checkNotNull(javaClass.getResourceAsStream(resource)) { "no $resource on the classpath" }
            .bufferedReader().use { it.readText() }

    private companion object {
        val CLASS_REF = Regex("""(?:class|implementation|implementationClass)="(com\.mondoo\.[\w.$]+)"""")
        val ACTION_ID = Regex("""<action\s+id="([\w.]+)"""")

        /**
         * Concrete actions the plugin defines. Kept as a literal list rather than
         * scanned from the jar: classpath scanning at test time pulls in a scanning
         * library for one assertion, and a list that must be edited alongside a new
         * action is the same maintenance either way.
         */
        val ACTION_CLASSES = listOf(
            "com.mondoo.intellij.actions.ScanWorkspaceAction",
            "com.mondoo.intellij.actions.ScanChangedFilesAction",
            "com.mondoo.intellij.actions.ScanChangesSinceAction",
            "com.mondoo.intellij.actions.ClearFindingsAction",
            "com.mondoo.intellij.actions.AnalyzeDependenciesAction",
            "com.mondoo.intellij.actions.GenerateBomAction",
            "com.mondoo.intellij.actions.SearchCodeAction",
            "com.mondoo.intellij.actions.ExportSearchRuleAction",
            "com.mondoo.intellij.actions.SetupScannerAction",
            "com.mondoo.intellij.actions.InstallAiSkillsAction",
            "com.mondoo.intellij.actions.ConfigureMcpAction",
            "com.mondoo.intellij.actions.OpenDemoFileAction",
            "com.mondoo.intellij.actions.OpenDocumentationAction",
            "com.mondoo.intellij.actions.ShowXgrepPathAction",
            "com.mondoo.intellij.policy.LintPolicyAction",
            "com.mondoo.intellij.policy.FormatPolicyAction",
            "com.mondoo.intellij.policy.FormatPolicySortedAction",
            "com.mondoo.intellij.target.ScanTargetAction",
            "com.mondoo.intellij.target.RunQueryAction",
            "com.mondoo.intellij.target.ManageTargetsAction",
            "com.mondoo.intellij.lsp.ReloadRulesAction",
        )
    }
}
