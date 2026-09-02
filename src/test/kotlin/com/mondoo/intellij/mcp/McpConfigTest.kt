// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mcp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpConfigTest {

    private val binary = "/usr/local/bin/xgrep"

    private fun serversOf(json: String) =
        JsonParser.parseString(json).asJsonObject.getAsJsonObject("mcpServers")

    @Test
    fun `produces the standard mcpServers shape`() {
        val entry = serversOf(McpConfig.serverEntryJson(binary)).getAsJsonObject("mondoo-xgrep")
        assertEquals(binary, entry["command"].asString)
        assertEquals(listOf("mcp"), entry["args"].asJsonArray.map { it.asString })
    }

    @Test
    fun `an absent config becomes a new one`() {
        assertTrue(serversOf(McpConfig.merge(null, binary)!!).has("mondoo-xgrep"))
        assertTrue(serversOf(McpConfig.merge("", binary)!!).has("mondoo-xgrep"))
    }

    @Test
    fun `merging preserves other servers`() {
        val existing = """
            {"mcpServers": {"other": {"command": "othertool", "args": ["serve"]}}}
        """.trimIndent()
        val servers = serversOf(McpConfig.merge(existing, binary)!!)
        assertTrue(servers.has("other"), "must not drop an unrelated server")
        assertEquals("othertool", servers.getAsJsonObject("other")["command"].asString)
        assertTrue(servers.has("mondoo-xgrep"))
    }

    @Test
    fun `merging preserves unrelated top-level keys`() {
        val existing = """{"someOtherSetting": 42, "mcpServers": {}}"""
        val root = JsonParser.parseString(McpConfig.merge(existing, binary)!!).asJsonObject
        assertEquals(42, root["someOtherSetting"].asInt)
    }

    @Test
    fun `adds the mcpServers object when the config lacks one`() {
        val merged = McpConfig.merge("""{"someOtherSetting": 1}""", binary)!!
        assertTrue(serversOf(merged).has("mondoo-xgrep"))
    }

    @Test
    fun `re-registering replaces rather than duplicates`() {
        val once = McpConfig.merge(null, binary)!!
        val twice = McpConfig.merge(once, "/opt/homebrew/bin/xgrep")!!
        val servers = serversOf(twice)
        assertEquals(1, servers.keySet().count { it == "mondoo-xgrep" })
        assertEquals("/opt/homebrew/bin/xgrep", servers.getAsJsonObject("mondoo-xgrep")["command"].asString)
    }

    @Test
    fun `refuses to rewrite a config it cannot parse`() {
        // Silently clobbering a user's MCP config would be worse than doing nothing,
        // so the caller falls back to the clipboard.
        assertNull(McpConfig.merge("{ this is not json", binary))
        assertNull(McpConfig.merge("[]", binary))
    }

    @Test
    fun `escapes a path containing quotes`() {
        val odd = """/tmp/we"ird/xgrep"""
        val entry = serversOf(McpConfig.serverEntryJson(odd)).getAsJsonObject("mondoo-xgrep")
        assertEquals(odd, entry["command"].asString)
    }
}
