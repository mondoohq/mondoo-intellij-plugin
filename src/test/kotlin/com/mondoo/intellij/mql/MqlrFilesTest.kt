// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqlrFilesTest {

    @Test
    fun `recognises both the old and the new extension`() {
        assertTrue(MqlrFiles.isSupported("core.lr"))
        assertTrue(MqlrFiles.isSupported("core.mqlr"))
        assertTrue(MqlrFiles.isSupported("providers/os/resources/os.mqlr"))
    }

    @Test
    fun `both report the language id the server knows`() {
        assertEquals("lr", MqlrFiles.languageIdFor("core.lr"))
        assertEquals("lr", MqlrFiles.languageIdFor("core.mqlr"))
        assertEquals("", MqlrFiles.languageIdFor("core.go"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(MqlrFiles.isSupported("Core.LR"))
        assertTrue(MqlrFiles.isSupported("Core.MQLr"))
    }

    /** A dotfile named `.lr` is a file called nothing, not a resource definition. */
    @Test
    fun `an extension with no name is not a resource file`() {
        assertFalse(MqlrFiles.isSupported(".lr"))
        assertFalse(MqlrFiles.isSupported(".mqlr"))
    }

    @Test
    fun `a backup or unrelated suffix is not claimed`() {
        assertFalse(MqlrFiles.isSupported("core.lr.bak"))
        assertFalse(MqlrFiles.isSupported("core.lr.versions"))
        assertFalse(MqlrFiles.isSupported("solr"))
        assertFalse(MqlrFiles.isSupported("colr.txt"))
    }

    /**
     * The two Mondoo language servers must not both claim a file. `.mqlr` is the one
     * that could plausibly be mistaken for MQL's own `.mql`.
     */
    @Test
    fun `does not overlap with the MQL server's files`() {
        listOf("core.lr", "core.mqlr").forEach {
            assertFalse(MqlFiles.isSupported(it), "$it must not be claimed by cnspec")
        }
        listOf("query.mql", "policy.mql.yaml", "policy.mql.yml").forEach {
            assertFalse(MqlrFiles.isSupported(it), "$it must not be claimed by mqlr")
        }
    }
}
