package com.mondoo.intellij.lsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepDiagnosticDataTest {

    /** Verbatim from the 2026-09-01 capture of xgrep lsp. */
    private val captured = mapOf(
        "ruleId" to "js-express-command-injection",
        "cwe" to listOf("CWE-78: Improper Neutralization of Special Elements used in an OS Command"),
        "owasp" to listOf("A03:2021", "A05:2025"),
        "references" to listOf("https://cheatsheetseries.owasp.org/"),
        "hasFix" to false,
        "fixKind" to "assisted",
    )

    @Test
    fun `decodes the payload xgrep actually sends`() {
        val data = XgrepDiagnosticData.fromMap(captured)!!
        assertEquals("js-express-command-injection", data.ruleId)
        assertEquals(1, data.cwe.size)
        assertEquals(listOf("A03:2021", "A05:2025"), data.owasp)
        assertEquals(listOf("https://cheatsheetseries.owasp.org/"), data.references)
        assertFalse(data.hasFix)
        assertEquals("assisted", data.fixKind)
        assertTrue(data.needsAssistedFix)
        assertFalse(data.hasDeterministicFix)
    }

    @Test
    fun `returns null when the diagnostic carries no data`() {
        // Older xgrep builds publish diagnostics without a data field at all.
        assertNull(XgrepDiagnosticData.fromMap(null))
    }

    @Test
    fun `tolerates a partial payload`() {
        val data = XgrepDiagnosticData.fromMap(mapOf("ruleId" to "py-os-system"))!!
        assertEquals("py-os-system", data.ruleId)
        assertTrue(data.cwe.isEmpty())
        assertFalse(data.hasFix)
        assertNull(data.fixKind)
    }

    @Test
    fun `tolerates wrongly typed fields instead of throwing`() {
        val data = XgrepDiagnosticData.fromMap(
            mapOf(
                "ruleId" to 42,
                "cwe" to "not-a-list",
                "owasp" to listOf("A03:2021", 7, null),
                "hasFix" to "yes",
            ),
        )!!
        assertNull(data.ruleId)
        assertTrue(data.cwe.isEmpty())
        assertEquals(listOf("A03:2021"), data.owasp)
        assertFalse(data.hasFix)
    }

    @Test
    fun `identifies a deterministic fix`() {
        val data = XgrepDiagnosticData.fromMap(
            mapOf("ruleId" to "r", "hasFix" to true, "fixKind" to "deterministic"),
        )!!
        assertTrue(data.hasDeterministicFix)
        assertFalse(data.needsAssistedFix)
    }

    @Test
    fun `an advisory finding offers neither fix path`() {
        val data = XgrepDiagnosticData.fromMap(mapOf("fixKind" to "advisory"))!!
        assertFalse(data.hasDeterministicFix)
        assertFalse(data.needsAssistedFix)
    }

    @Test
    fun `an empty payload decodes to all defaults`() {
        val data = XgrepDiagnosticData.fromMap(emptyMap<String, Any>())!!
        assertEquals(XgrepDiagnosticData(), data)
    }
}
