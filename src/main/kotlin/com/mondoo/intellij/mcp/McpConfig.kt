package com.mondoo.intellij.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Builds the MCP server entry for `xgrep mcp`.
 *
 * JetBrains exposes **no public API for a plugin to register an external stdio MCP
 * server** with AI Assistant or Junie — configuration is hand-entered JSON in
 * Settings. So the plugin writes the standard config shape that every MCP client
 * understands, and offers it for the clipboard as a fallback.
 *
 * Pure: unit-tested without an IDE.
 */
object McpConfig {

    const val SERVER_NAME: String = "mondoo-xgrep"

    /** The `mcpServers` entry, formatted as the config files expect. */
    fun serverEntryJson(binaryPath: String): String {
        val root = JsonObject().apply {
            add("mcpServers", JsonObject().apply { add(SERVER_NAME, entry(binaryPath)) })
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    /**
     * Merges the entry into existing config text, preserving whatever else is
     * there. Returns null when [existing] is not an object we can safely extend —
     * silently rewriting a user's MCP config would be worse than doing nothing.
     */
    fun merge(existing: String?, binaryPath: String): String? {
        if (existing.isNullOrBlank()) return serverEntryJson(binaryPath)
        val root = runCatching { JsonParser.parseString(existing).asJsonObject }.getOrNull() ?: return null

        val servers = root.getAsJsonObject("mcpServers")
            ?: JsonObject().also { root.add("mcpServers", it) }
        servers.add(SERVER_NAME, entry(binaryPath))
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    private fun entry(binaryPath: String): JsonObject = JsonObject().apply {
        add("command", JsonPrimitive(binaryPath))
        add("args", JsonArray().apply { add("mcp") })
    }
}
