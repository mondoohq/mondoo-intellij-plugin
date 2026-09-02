package com.mondoo.intellij.lsp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepScanMessagesTest {

    @Test
    fun `recognises workspace and changed-files results`() {
        assertTrue(XgrepScanMessages.isScanResult("xgrep: workspace scan found 12 findings in 4 files"))
        assertTrue(XgrepScanMessages.isScanResult("xgrep: changed-files scan found 0 findings"))
    }

    @Test
    fun `recognises failures and reports them as such`() {
        val failed = "xgrep: workspace scan failed: rule parse error"
        assertTrue(XgrepScanMessages.isScanResult(failed))
        assertTrue(XgrepScanMessages.isFailure(failed))
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
