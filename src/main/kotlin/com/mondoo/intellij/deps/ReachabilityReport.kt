// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.deps

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * How a dependency relates to first-party code.
 *
 * This is package-and-file level reachability: whether your own code imports the
 * package at all. It is the question that removes most dependency-triage noise —
 * a vulnerability in a package nothing imports is a different problem from one in
 * a package on a live code path.
 *
 * Function-level reachability ("does the vulnerable function actually run") is a
 * further step that needs advisory data naming the affected symbol, which most
 * ecosystems do not publish, so it is not modelled here.
 */
enum class Reachability(val id: String, val title: String, val explanation: String) {
    IMPORTED("imported", "Imported", "First-party code imports this package"),
    IMPORTED_REACHABLE("imported_reachable", "Imported and reachable", "Imported, and on a live code path"),
    IMPORTED_DEAD("imported_dead", "Imported but dead", "Imported, but no live code path reaches it"),
    TRANSITIVE_REACHABLE(
        "transitive_reachable",
        "Transitive, reachable",
        "Pulled in by another dependency, and reachable through it",
    ),
    TRANSITIVE("transitive", "Transitive", "Pulled in by another dependency"),
    TRANSITIVE_ORPHANED(
        "transitive_orphaned",
        "Transitive, orphaned",
        "Pulled in by another dependency that nothing reaches",
    ),
    DIRECT_UNUSED("direct_unused", "Declared but unused", "Declared as a direct dependency, but never imported"),
    DEV_DEPENDENCY("dev_dependency", "Development only", "Declared for development, not shipped"),
    UNKNOWN("unknown", "Unknown", "Imports could not be resolved for this ecosystem"),
    ;

    companion object {
        fun of(id: String?): Reachability =
            entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

data class DependencyPackage(
    val id: String,
    val name: String,
    val version: String,
    val ecosystem: String,
    val purl: String,
    val reachability: Reachability,
    /** First-party files importing this package, empty when nothing does. */
    val importedBy: List<String> = emptyList(),
) {
    val label: String get() = if (version.isBlank()) name else "$name@$version"
}

/**
 * The parsed output of `xgrep deps reachability --json`.
 *
 * Parsing is total: a schema bump, an added field, or a truncated document degrades
 * to null rather than throwing, because this feeds a tool window and a broken parse
 * must not take the IDE down with it.
 *
 * Pure: unit-tested without an IDE or the scanner.
 */
data class ReachabilityReport(
    val packages: List<DependencyPackage>,
    val summary: Map<Reachability, Int>,
) {
    val total: Int get() = packages.size

    /** Packages grouped for display, in the order the enum declares. */
    fun grouped(): List<Pair<Reachability, List<DependencyPackage>>> =
        Reachability.entries
            .map { klass -> klass to packages.filter { it.reachability == klass }.sortedBy { it.name } }
            .filter { it.second.isNotEmpty() }

    companion object {
        fun parse(json: String): ReachabilityReport? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject

            // file -> package edges, folded into each package.
            val importers = mutableMapOf<String, MutableList<String>>()
            root.getAsJsonArray("edges")?.forEach { element ->
                runCatching {
                    val edge = element.asJsonObject
                    val pkg = edge["package"].asString
                    val file = edge["file"].asString
                    importers.getOrPut(pkg) { mutableListOf() }.add(file)
                }
            }

            val packages = root.getAsJsonArray("packages").orEmpty().mapNotNull { element ->
                runCatching {
                    val o = element.asJsonObject
                    val id = o["id"].asString
                    DependencyPackage(
                        id = id,
                        name = o["name"]?.asString.orEmpty(),
                        version = o["version"]?.asString.orEmpty(),
                        ecosystem = o["ecosystem"]?.asString.orEmpty(),
                        purl = o["purl"]?.asString.orEmpty(),
                        reachability = Reachability.of(o["reachability"]?.asString),
                        importedBy = importers[id]?.distinct()?.sorted().orEmpty(),
                    )
                }.getOrNull()
            }

            val summary = root.getAsJsonObject("summary")?.let { s ->
                Reachability.entries.mapNotNull { klass ->
                    s[klass.id]?.asInt?.takeIf { it > 0 }?.let { klass to it }
                }.toMap()
            }.orEmpty()

            ReachabilityReport(packages, summary)
        }.getOrNull()

        private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
            this?.toList() ?: emptyList()
    }
}
