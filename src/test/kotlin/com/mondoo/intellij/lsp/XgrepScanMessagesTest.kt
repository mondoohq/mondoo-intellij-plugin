// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.lsp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepScanMessagesTest {

    /**
     * Captured verbatim from xgrep 0.57 on 2026-09-02. These are the exact strings
     * the regex has to keep matching; re-capture them when xgrep is bumped.
     */
    private val capturedSuccess = "xgrep: workspace scan found 2 finding(s) in 1 file(s)"
    private val capturedFailure =
        "xgrep: changed-files scan failed: listing changed files " +
            "(is /tmp/probe a git repository?): exit status 128"

    @Test
    fun `recognises the messages xgrep actually sends`() {
        assertTrue(XgrepScanMessages.isScanResult(capturedSuccess))
        assertTrue(XgrepScanMessages.isScanResult(capturedFailure))
        assertFalse(XgrepScanMessages.isFailure(capturedSuccess))
        assertTrue(XgrepScanMessages.isFailure(capturedFailure))
    }

    @Test
    fun `recognises both scan kinds`() {
        assertTrue(XgrepScanMessages.isScanResult("xgrep: workspace scan found 12 finding(s) in 4 file(s)"))
        assertTrue(XgrepScanMessages.isScanResult("xgrep: changed-files scan found 0 finding(s)"))
    }

    @Test
    fun `a successful scan is not a failure`() {
        assertFalse(XgrepScanMessages.isFailure("xgrep: workspace scan found 3 findings"))
    }

    @Test
    fun `ignores unrelated server messages`() {
        // These must reach the platform's own message handling untouched.
        listOf(
            "xgrep: loaded 412 rules",
            "Something else entirely",
            "",
            "scan found 3 findings",
        ).forEach { assertFalse(XgrepScanMessages.isScanResult(it), it) }
    }

    @Test
    fun `a non-result message is never treated as a failure`() {
        assertFalse(XgrepScanMessages.isFailure("xgrep: loaded 412 rules"))
    }
}
