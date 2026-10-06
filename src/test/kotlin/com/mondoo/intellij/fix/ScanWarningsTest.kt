// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanWarningsTest {

    /** The warning xgrep logged for the organization service account, 2026-10-06. */
    private val missingSpace = "dependency vulnerability scan failed; continuing with code findings " +
        "error=\"scan sbom: rpc error: code = Unknown desc = resource mrn is invalid: missing space\""

    @Test
    fun `a missing space is explained and actionable`() {
        assertTrue(ScanWarnings.needsSpace(missingSpace))
        assertTrue(ScanWarnings.explain(missingSpace).contains("choose the space"))
    }

    @Test
    fun `other lookup failures keep their reason`() {
        assertEquals(
            "Dependency vulnerabilities were not checked: scan sbom: deadline exceeded.",
            ScanWarnings.explain(
                "dependency vulnerability scan failed; continuing error=\"scan sbom: deadline exceeded\"",
            ),
        )
        assertEquals("something new", ScanWarnings.explain("something new"))
    }
}
