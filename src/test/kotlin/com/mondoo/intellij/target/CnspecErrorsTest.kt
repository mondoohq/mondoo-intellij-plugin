// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnspecErrorsTest {

    /** The failure from a scan in the sandbox, 2026-10-06. */
    @Test
    fun `a broken service account key gets a hint`() {
        val out = """
            → using service account credentials
            FTL failed to run scan error="could not initialize client authentication: cannot load retrieved key: AuthKey must be a valid .p8 PEM file"
        """.trimIndent()
        val hint = CnspecErrors.hint(out)!!
        assertTrue(hint.contains("cnspec login"))
    }

    @Test
    fun `an ordinary failure gets none`() {
        assertNull(CnspecErrors.hint("FTL failed to connect to target error=\"dial tcp: connection refused\""))
        assertNull(CnspecErrors.hint(""))
    }
}
