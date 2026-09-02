// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

/**
 * When to check for a new xgrep, and which version to target.
 *
 * Ported from `resolveTargetVersion`/`computeTargetVersion` in
 * vscode-mondoo/src/services/xgrepService.ts (ADR-0016). The npm fallback is
 * dropped: `latest.json` is the only source here.
 *
 * Pure: no clock, no network, no platform types — the current time is a parameter.
 */
object XgrepVersionPolicy {

    /** How long a resolved version stays good before we ask the manifest again. */
    const val CHECK_TTL_MILLIS: Long = 24 * 60 * 60 * 1000

    /**
     * The version this plugin was built against. Acts as a floor so a manifest
     * that is unreachable, malformed, or unexpectedly old can never talk us into
     * installing something older than what we know works.
     */
    const val MINIMUM_VERSION: String = "0.57.0"

    fun shouldRefresh(lastCheckedAtMillis: Long, nowMillis: Long): Boolean =
        lastCheckedAtMillis <= 0L ||
            nowMillis < lastCheckedAtMillis || // clock moved backwards
            nowMillis - lastCheckedAtMillis >= CHECK_TTL_MILLIS

    /**
     * Picks the version to install, floored at [MINIMUM_VERSION].
     *
     * @param manifestVersion the version `latest.json` reports, or null when the
     *   manifest could not be fetched or parsed.
     * @param cachedVersion the last version we successfully resolved, if any.
     */
    fun targetVersion(manifestVersion: String?, cachedVersion: String?): String {
        val candidate = manifestVersion?.takeIf { ArtifactSelector.isValidReleaseVersion(it) }
            ?: cachedVersion?.takeIf { ArtifactSelector.isValidReleaseVersion(it) }
            ?: MINIMUM_VERSION
        return if (ArtifactSelector.compareVersions(candidate, MINIMUM_VERSION) >= 0) {
            candidate
        } else {
            MINIMUM_VERSION
        }
    }

    /** True when [installed] is older than [target]. An unknown install needs one. */
    fun needsUpdate(installed: String?, target: String): Boolean {
        if (installed.isNullOrBlank()) return true
        return ArtifactSelector.compareVersions(installed, target) < 0
    }
}
