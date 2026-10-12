// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.settings

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * xgrep reports `XGREP_CALLER` as the caller of its anonymous usage event (xgrep
 * ADR-0882). Every xgrep the plugin starts carries it, so IntelliJ usage is counted
 * as such instead of as anonymous direct invocations.
 */
class XgrepCallerTest {

    @Test
    fun `the caller tag is intellij`() {
        assertEquals("XGREP_CALLER", MondooEnvironment.XGREP_CALLER_ENV)
        assertEquals("intellij", MondooEnvironment.XGREP_CALLER)
    }

    @Test
    fun `withXgrepCaller adds the caller and keeps what the command already had`() {
        val command = GeneralCommandLine("xgrep", "version").withEnvironment("MONDOO_CONFIG_PATH", "/x/mondoo.yml")
        MondooEnvironment.withXgrepCaller(command)
        assertEquals("intellij", command.environment["XGREP_CALLER"])
        assertEquals("/x/mondoo.yml", command.environment["MONDOO_CONFIG_PATH"])
    }

    @Test
    fun `every place that starts xgrep adds the caller`() {
        // Language server, fix serve and its probe, SBOM, dependency reachability,
        // login and the Tool Paths version read. (The MCP config is written for
        // external agents, which start xgrep themselves: not the IDE's runs.)
        val root = Path.of("src/main/kotlin/com/mondoo/intellij")
        val starters = listOf(
            "lsp/XgrepLspServerSupportProvider.kt",
            "fix/FixServer.kt",
            "bom/BomService.kt",
            "dependencies/DependencyReachabilityService.kt",
            "platform/MondooPlatform.kt",
            "actions/ShowXgrepPathAction.kt",
        )
        val missing = starters.filterNot { file ->
            val text = Files.readString(root.resolve(file))
            "xgrepCommandLine(" in text || "withXgrepCaller(" in text || "XGREP_CALLER_ENV" in text
        }
        assertEquals(emptyList<String>(), missing, "files that start xgrep without XGREP_CALLER")
    }
}
