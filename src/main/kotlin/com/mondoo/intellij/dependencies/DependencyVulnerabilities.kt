// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mondoo.intellij.binary.ArtifactSelector
import com.mondoo.intellij.util.arrayOrEmpty

/** One known vulnerability in a package. */
data class Vulnerability(
    /** The advisory id, e.g. CVE-2017-16082. */
    val id: String,
    val aliases: List<String>,
    val severity: VulnSeverity,
    /** What it is, e.g. "Remote Code Execution in pg". */
    val summary: String,
    /** The first version without it, or empty when none is known. */
    val fixedVersion: String,
)

enum class VulnSeverity(val label: String, val rank: Int) {
    CRITICAL("Critical", 4),
    HIGH("High", 3),
    MEDIUM("Medium", 2),
    LOW("Low", 1),
    ;

    companion object {
        /** From the scan's severity words (xgrep reports ERROR/WARNING/INFO as well). */
        fun of(value: String?): VulnSeverity = when (value?.uppercase()) {
            "CRITICAL" -> CRITICAL
            "ERROR", "HIGH" -> HIGH
            "WARNING", "MEDIUM" -> MEDIUM
            else -> LOW
        }
    }
}

/** A package's vulnerabilities, and the upgrade that clears all of them where one is known. */
data class PackageVulnerabilities(
    val ecosystem: String,
    val name: String,
    val version: String,
    /** The manifests the findings point at, relative to the scan root (e.g. `package.json`). */
    val manifests: Set<String>,
    val vulnerabilities: List<Vulnerability>,
    /** The lowest version that fixes every known vulnerability, or empty. */
    val upgradeTo: String,
    /** The package manager command for that upgrade, or empty. */
    val upgradeCommand: String,
) {
    val worst: VulnSeverity? get() = vulnerabilities.maxByOrNull { it.severity.rank }?.severity

    /** "1 critical, 2 high", most severe first. */
    val counts: String
        get() = vulnerabilities.groupingBy { it.severity }.eachCount().entries
            .sortedByDescending { it.key.rank }
            .joinToString(", ") { "${it.value} ${it.key.label.lowercase()}" }
}

/**
 * The dependency vulnerabilities in a findings cache (`.xgrep/findings.json`), keyed
 * like the reachability report's packages so the two can be shown together.
 *
 * They come from the same scan as everything else (the Fix tab's, or `xgrep scan`
 * run anywhere), so there is one scan and one record. Pure: Gson only.
 */
object DependencyVulnerabilities {

    /** ecosystem/name/version, lower-cased where the ecosystem is case-insensitive. */
    fun key(ecosystem: String, name: String, version: String) =
        "${ecosystem.lowercase()}/${name.lowercase()}@$version"

    fun parse(cacheJson: String): Map<String, PackageVulnerabilities> = runCatching {
        val root = JsonParser.parseString(cacheJson).asJsonObject
        // The cache wraps the report; a bare `scan --json` report works too.
        val report = root.obj("report") ?: root
        val byPackage = linkedMapOf<String, MutableList<Pair<Vulnerability, JsonObject?>>>()
        val identity = mutableMapOf<String, Triple<String, String, String>>()
        val manifests = mutableMapOf<String, MutableSet<String>>()
        report.arrayOrEmpty("results").forEach { element ->
            runCatching {
                val r = element.asJsonObject
                val extra = r.obj("extra") ?: return@runCatching
                val fix = extra.obj("fix_info")
                if (fix?.str("kind") != "ecosystem") return@runCatching
                val meta = extra.obj("metadata") ?: return@runCatching
                val name = meta.str("package").ifEmpty { return@runCatching }
                val key = key(meta.str("ecosystem"), name, meta.str("version"))
                val vuln = Vulnerability(
                    id = r.str("check_id"),
                    aliases = meta.arrayOrEmpty("aliases").mapNotNull { a ->
                        a.takeIf { it.isJsonPrimitive }?.asString
                    },
                    severity = VulnSeverity.of(extra.str("severity")),
                    summary = summaryOf(extra.str("message")),
                    fixedVersion = meta.str("fixed_version"),
                )
                byPackage.getOrPut(key) { mutableListOf() } += vuln to fix.obj("ecosystem_plan")
                identity[key] = Triple(meta.str("ecosystem"), name, meta.str("version"))
                r.str("path").takeIf { it.isNotEmpty() }?.let { manifests.getOrPut(key) { mutableSetOf() } += it }
            }
        }
        byPackage.mapValues { (key, entries) ->
            val vulns = entries.map { it.first }.distinctBy { it.id }
                .sortedWith(compareByDescending<Vulnerability> { it.severity.rank }.thenBy { it.id })
            // Every vulnerability's fix has to be in: the highest fixed version.
            val target = entries.filter { it.first.fixedVersion.isNotEmpty() }
                .maxWithOrNull { a, b -> ArtifactSelector.compareVersions(a.first.fixedVersion, b.first.fixedVersion) }
            val (ecosystem, name, version) = identity.getValue(key)
            PackageVulnerabilities(
                ecosystem = ecosystem,
                name = name,
                version = version,
                manifests = manifests[key].orEmpty(),
                vulnerabilities = vulns,
                upgradeTo = target?.first?.fixedVersion.orEmpty(),
                upgradeCommand = target?.second?.arrayOrEmpty("commands").orEmpty()
                    .mapNotNull { c -> c.takeIf { it.isJsonPrimitive }?.asString }.joinToString(" && "),
            )
        }
    }.getOrDefault(emptyMap())

    /** "pg@7.1.0 is affected by CVE-2017-16082: Remote Code Execution in pg" → the part after the id. */
    private fun summaryOf(message: String): String = message.substringAfter(": ", message).trim()

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
