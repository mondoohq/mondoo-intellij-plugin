// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.bom

/**
 * What kind of bill of materials to produce.
 *
 * The scanner emits these as one merged document when several are selected, so this
 * is a set rather than a single choice.
 */
enum class BomContent(val flag: String, val title: String, val description: String) {
    SCA("sca", "Software (SBOM)", "Dependencies from manifests and lockfiles"),
    CBOM("cbom", "Cryptography (CBOM)", "Algorithms, keys, protocols and certificates found in source"),
    AIBOM("aibom", "AI (AIBOM)", "AI SDKs, frameworks, runtimes and models found in source"),
}

/**
 * Output format.
 *
 * [requiresCycloneDxJson] on a content set matters: cryptography and AI bills are
 * produced by the scan engine and are only expressible in CycloneDX JSON, so
 * offering SPDX alongside them would produce a command the scanner rejects.
 */
enum class BomFormat(val flag: String, val title: String, val extension: String) {
    CYCLONEDX_JSON("cyclonedx-json", "CycloneDX (JSON)", "cdx.json"),
    CYCLONEDX_XML("cyclonedx-xml", "CycloneDX (XML)", "cdx.xml"),
    SPDX_JSON("spdx-json", "SPDX (JSON)", "spdx.json"),
    SPDX_TAG_VALUE("spdx-tag-value", "SPDX (tag-value)", "spdx"),
    JSON("json", "Raw JSON", "json"),
    TABLE("table", "Table (human-readable)", "txt"),
}

/**
 * A bill-of-materials request, and the command line it becomes.
 *
 * Pure: unit-tested without an IDE or the scanner.
 */
data class BomRequest(
    val content: Set<BomContent>,
    val format: BomFormat,
    val includeDev: Boolean = false,
    val directOnly: Boolean = false,
    val excludeDirs: List<String> = emptyList(),
) {
    init {
        require(content.isNotEmpty()) { "a bill of materials needs at least one content kind" }
    }

    /**
     * Cryptography and AI bills run the scan engine and are only expressible as
     * CycloneDX JSON, so a set containing either constrains the format.
     */
    val requiresCycloneDxJson: Boolean
        get() = content.any { it != BomContent.SCA }

    /** The formats this request can actually be produced in. */
    fun availableFormats(): List<BomFormat> =
        if (requiresCycloneDxJson) listOf(BomFormat.CYCLONEDX_JSON) else BomFormat.entries.toList()

    /** Arguments after the binary: `sbom <path> ...`. */
    fun arguments(projectPath: String, outputPath: String?): List<String> = buildList {
        add("sbom")
        add(projectPath)
        // Ordered as the enum declares, so the command is stable and diffable.
        add("--include")
        add(BomContent.entries.filter { it in content }.joinToString(",") { it.flag })
        add("--format")
        add(effectiveFormat().flag)
        if (includeDev) add("--include-dev")
        if (directOnly) add("--direct-only")
        excludeDirs.filter { it.isNotBlank() }.forEach {
            add("--exclude-dir")
            add(it)
        }
        outputPath?.let {
            add("--output")
            add(it)
        }
    }

    /** The format actually used, after the CycloneDX-JSON constraint. */
    fun effectiveFormat(): BomFormat =
        if (requiresCycloneDxJson) BomFormat.CYCLONEDX_JSON else format

    /** A default file name, e.g. `myproject.sbom.cdx.json`. */
    fun defaultFileName(projectName: String): String {
        val safe = projectName.ifBlank { "project" }.replace(Regex("""[^A-Za-z0-9._-]"""), "-")
        val kind = when {
            content == setOf(BomContent.SCA) -> "sbom"
            content == setOf(BomContent.CBOM) -> "cbom"
            content == setOf(BomContent.AIBOM) -> "aibom"
            else -> "bom"
        }
        return "$safe.$kind.${effectiveFormat().extension}"
    }
}
