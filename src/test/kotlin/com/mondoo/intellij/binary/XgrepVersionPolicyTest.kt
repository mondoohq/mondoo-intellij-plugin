// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepVersionPolicyTest {

    private val now = 1_800_000_000_000L
    private val ttl = XgrepVersionPolicy.CHECK_TTL_MILLIS

    @Test
    fun `refreshes when never checked`() {
        assertTrue(XgrepVersionPolicy.shouldRefresh(0L, now))
    }

    @Test
    fun `does not refresh inside the ttl`() {
        assertFalse(XgrepVersionPolicy.shouldRefresh(now - ttl / 2, now))
    }

    @Test
    fun `refreshes once the ttl has elapsed`() {
        assertTrue(XgrepVersionPolicy.shouldRefresh(now - ttl, now))
        assertTrue(XgrepVersionPolicy.shouldRefresh(now - ttl - 1, now))
    }

    @Test
    fun `refreshes when the clock moved backwards`() {
        // A stale future timestamp (clock skew, restored backup) would otherwise
        // suppress update checks indefinitely.
        assertTrue(XgrepVersionPolicy.shouldRefresh(now + ttl, now))
    }

    @Test
    fun `prefers the manifest version`() {
        assertEquals("0.58.0", XgrepVersionPolicy.targetVersion("0.58.0", "0.57.0"))
    }

    @Test
    fun `falls back to the cached version when the manifest is unreachable`() {
        assertEquals("0.58.0", XgrepVersionPolicy.targetVersion(null, "0.58.0"))
    }

    @Test
    fun `falls back to the compiled floor with no manifest and no cache`() {
        assertEquals(XgrepVersionPolicy.MINIMUM_VERSION, XgrepVersionPolicy.targetVersion(null, null))
    }

    @Test
    fun `never installs older than the floor`() {
        // A rolled-back or malformed manifest must not downgrade a working install.
        assertEquals(XgrepVersionPolicy.MINIMUM_VERSION, XgrepVersionPolicy.targetVersion("0.1.0", null))
        assertEquals(XgrepVersionPolicy.MINIMUM_VERSION, XgrepVersionPolicy.targetVersion("0.11.1", "0.9.0"))
    }

    @Test
    fun `ignores a malformed manifest version`() {
        assertEquals("0.58.0", XgrepVersionPolicy.targetVersion("not-a-version", "0.58.0"))
        assertEquals("0.58.0", XgrepVersionPolicy.targetVersion("../../etc", "0.58.0"))
    }

    @Test
    fun `needsUpdate compares numerically`() {
        assertTrue(XgrepVersionPolicy.needsUpdate("0.57.0", "0.58.0"))
        assertFalse(XgrepVersionPolicy.needsUpdate("0.58.0", "0.58.0"))
        assertFalse(XgrepVersionPolicy.needsUpdate("0.59.0", "0.58.0"))
        assertTrue(XgrepVersionPolicy.needsUpdate(null, "0.58.0"))
        assertTrue(XgrepVersionPolicy.needsUpdate("", "0.58.0"))
        // Lexical comparison would call 0.9.0 newer than 0.57.0.
        assertTrue(XgrepVersionPolicy.needsUpdate("0.9.0", "0.57.0"))
    }
}
