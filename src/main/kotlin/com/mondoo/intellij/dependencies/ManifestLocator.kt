// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

/**
 * Finds where a manifest declares a package, so a vulnerability found for the
 * package can be shown on its line. The scan anchors dependency findings at the top
 * of the manifest; the editor wants the line that pins the version.
 *
 * Pure text matching, deliberately small: `requirements*.txt` and `package.json`,
 * the manifests the demo and most projects have. Anything else gets the file-level
 * marking only. Tested without the platform.
 */
object ManifestLocator {

    /** Offsets in the manifest text: the whole declaration, and the version within it. */
    data class Location(val line: IntRange, val version: IntRange)

    fun supports(fileName: String): Boolean = isRequirements(fileName) || fileName == "package.json"

    fun find(fileName: String, text: String, name: String, version: String): Location? = when {
        isRequirements(fileName) -> requirements(text, name, version)
        fileName == "package.json" -> packageJson(text, name, version)
        else -> null
    }

    private fun isRequirements(fileName: String) =
        fileName.startsWith("requirements") && (fileName.endsWith(".txt") || fileName.endsWith(".in"))

    /** `flask==0.12.2`, `Flask >= 0.12.2  # comment`; names compare per PEP 503. */
    private fun requirements(text: String, name: String, version: String): Location? {
        val want = normalize(name)
        var offset = 0
        for (line in text.split('\n')) {
            val m = Regex(
                """^\s*([A-Za-z0-9][A-Za-z0-9._-]*)(\[[^\]]*])?\s*(==|>=|<=|~=|!=|>|<|===)\s*([^\s;#,]+)""",
            ).find(line)
            if (m != null && normalize(m.groupValues[1]) == want && m.groupValues[4] == version) {
                val v = m.groups[4]!!.range
                return Location(offset until offset + line.trimEnd('\r').length, offset + v.first..offset + v.last)
            }
            offset += line.length + 1
        }
        return null
    }

    /** `"pg": "7.1.0"` or `"lodash": "^4.17.4"`; the version range covers the number only. */
    private fun packageJson(text: String, name: String, version: String): Location? {
        val m =
            Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"[~^=v]*(${Regex.escape(version)})\"").find(text) ?: return null
        val v = m.groups[1]!!.range
        return Location(m.range, v)
    }

    private fun normalize(name: String) = name.lowercase().replace(Regex("[-_.]+"), "-")
}
