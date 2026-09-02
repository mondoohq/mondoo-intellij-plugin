// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

/**
 * The xgrep release manifest published at
 * `https://releases.mondoo.com/xgrep/latest.json`.
 *
 * ```json
 * {
 *   "name": "xgrep",
 *   "version": "0.57.0",
 *   "files": [
 *     { "filename": "https://releases.mondoo.com/xgrep/0.57.0/xgrep_0.57.0_darwin_arm64.tar.gz",
 *       "size": 49064404, "modtime": 1788138211, "platform": "darwin", "hash": "7d60fd..." }
 *   ]
 * }
 * ```
 *
 * `filename` is a full URL and `hash` is the SHA-256 of the artifact.
 */
data class ReleaseManifest(
    val name: String,
    val version: String,
    val files: List<ReleaseFile>,
) {
    companion object {
        /**
         * Parses `latest.json`. Total by design: a truncated response, an HTML
         * error page from a captive portal, or an added field must degrade to
         * null (callers fall back to a cached version) rather than throw.
         */
        fun parse(json: String): ReleaseManifest? = runCatching {
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            val version = root["version"]?.asString ?: return null
            val files = root["files"]?.asJsonArray.orEmpty().mapNotNull { element ->
                runCatching {
                    val o = element.asJsonObject
                    ReleaseFile(
                        filename = o["filename"].asString,
                        size = o["size"]?.asLong ?: 0L,
                        platform = o["platform"]?.asString.orEmpty(),
                        hash = o["hash"]?.asString.orEmpty(),
                    )
                }.getOrNull()
            }
            ReleaseManifest(root["name"]?.asString.orEmpty(), version, files)
        }.getOrNull()

        private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
            this?.toList() ?: emptyList()
    }
}

data class ReleaseFile(
    val filename: String,
    val size: Long,
    val platform: String,
    val hash: String,
) {
    val isZip: Boolean get() = filename.endsWith(".zip")
}

/**
 * Picks the artifact for a given OS/arch.
 *
 * Selection is by **filename pattern**, deliberately not by the `platform` field:
 * `platform` is coarse (`darwin`/`linux`/`windows`) and is *empty* for the musl
 * builds, and matching it would also match the `.deb` and `.rpm` packages. The
 * anchored pattern below rejects those, and musl, by construction.
 *
 * Pure: unit-tested without network or filesystem.
 */
object ArtifactSelector {

    private val ARTIFACT = Regex(
        """^xgrep_(?<version>\d+\.\d+\.\d+)_(?<os>darwin|linux|windows)_(?<arch>amd64|arm64)\.(?<ext>tar\.gz|zip)$""",
    )

    private val RELEASE_VERSION = Regex("""^\d+\.\d+\.\d+$""")

    /** Returns the matching artifact, or null when this OS/arch has no build. */
    fun select(manifest: ReleaseManifest, os: String, arch: String): ReleaseFile? =
        manifest.files.firstOrNull { file ->
            val name = file.filename.substringAfterLast('/')
            val match = ARTIFACT.matchEntire(name) ?: return@firstOrNull false
            match.groups["os"]!!.value == os && match.groups["arch"]!!.value == arch
        }

    /**
     * Version strings become directory names, so they are validated even though
     * nothing here reaches a shell.
     */
    fun isValidReleaseVersion(version: String): Boolean = RELEASE_VERSION.matches(version)

    /** Compares two `x.y.z` versions. Ported from `compareSemver` in xgrepService.ts. */
    fun compareVersions(a: String, b: String): Int {
        val left = a.removePrefix("v").substringBefore('-').split('.')
        val right = b.removePrefix("v").substringBefore('-').split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)?.toIntOrNull() ?: 0
            val r = right.getOrNull(i)?.toIntOrNull() ?: 0
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
