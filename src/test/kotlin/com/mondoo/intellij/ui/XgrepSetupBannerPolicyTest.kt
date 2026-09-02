// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepSetupBannerPolicyTest {

    private fun show(
        enabled: Boolean = true,
        dismissed: Boolean = false,
        fileName: String = "main.go",
        reason: String? = "xgrep was not found",
    ) = XgrepSetupBannerPolicy.shouldShow(enabled, dismissed, fileName, reason)

    @Test
    fun `shows on a scannable file when the scanner is missing`() {
        assertTrue(show())
    }

    @Test
    fun `stays hidden once the scanner is available`() {
        assertFalse(show(reason = null))
    }

    @Test
    fun `stays hidden on files xgrep would ignore`() {
        // Telling someone their scanner is missing while they edit a changelog is
        // noise: the scanner would skip the file anyway.
        listOf("README.md", "notes.txt", "logo.png", "Makefile").forEach {
            assertFalse(show(fileName = it), it)
        }
    }

    @Test
    fun `respects a dismissal`() {
        assertFalse(show(dismissed = true))
    }

    @Test
    fun `stays hidden when the scanner is turned off`() {
        assertFalse(show(enabled = false))
    }

    @Test
    fun `dismissal wins over every other condition`() {
        assertFalse(show(enabled = true, dismissed = true, fileName = "main.py", reason = "missing"))
    }
}
