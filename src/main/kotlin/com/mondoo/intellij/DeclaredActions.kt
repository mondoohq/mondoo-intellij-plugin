// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij

/**
 * The action ids the plugin descriptors declare.
 *
 * One list, read by two checks that catch the same defect at different moments:
 * `PluginDescriptorTest` compares it against the XML at build time, and
 * [MondooSelfCheck] resolves each id in a running IDE. Adding an action without
 * registering it — or registering one and renaming its class — then fails the fast
 * suite rather than shipping as a menu item that does nothing.
 */
object DeclaredActions {

    /** Registered in `META-INF/plugin.xml`, so present in every IDE. */
    val CORE = listOf(
        "Mondoo.Xgrep.ScanWorkspace",
        "Mondoo.Xgrep.ScanChanged",
        "Mondoo.Xgrep.ScanSince",
        "Mondoo.Xgrep.ClearFindings",
        "Mondoo.Deps.Analyze",
        "Mondoo.Bom.Generate",
        "Mondoo.Xgrep.SearchCode",
        "Mondoo.Xgrep.ReplaceCode",
        "Mondoo.Xgrep.ExportSearchRule",
        "Mondoo.Policy.New",
        "Mondoo.Policy.Lint",
        "Mondoo.Policy.Format",
        "Mondoo.Policy.FormatSorted",
        "Mondoo.Target.Scan",
        "Mondoo.Target.RunQuery",
        "Mondoo.Target.Manage",
        "Mondoo.Xgrep.Setup",
        "Mondoo.Xgrep.InstallSkills",
        "Mondoo.Xgrep.ConfigureMcp",
        "Mondoo.Xgrep.OpenDemo",
        "Mondoo.Xgrep.Documentation",
        "Mondoo.Xgrep.ShowPath",
    )

    /** Registered in `META-INF/mondoo-lsp.xml`, so absent where LSP is. */
    val LSP_MODULE = listOf("Mondoo.Xgrep.ReloadRules", "Mondoo.RestartLanguageServers")

    /** Registered in `META-INF/mondoo-terminal.xml`, so absent without the Terminal. */
    val TERMINAL_MODULE = listOf("Mondoo.Target.Shell")

    /** The group the core actions hang off, and the plugin's only menu entry point. */
    const val GROUP = "Mondoo.CodeSecurity"
}
