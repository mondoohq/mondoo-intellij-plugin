// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.mql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqlFilesTest {

    @Test
    fun `recognises policy bundles`() {
        listOf("policy.mql.yaml", "policy.mql.yml", "Deeply.Named.mql.yaml").forEach {
            assertEquals("yaml", MqlFiles.languageIdFor(it), it)
            assertTrue(MqlFiles.isPolicyBundle(it), it)
        }
    }

    @Test
    fun `recognises standalone query files`() {
        assertEquals("mql", MqlFiles.languageIdFor("check.mql"))
        assertFalse(MqlFiles.isPolicyBundle("check.mql"))
    }

    @Test
    fun `leaves unrelated yaml alone`() {
        // The whole point of the .mql.yaml convention: a policy linter must not
        // report on a CI workflow or a Kubernetes manifest.
        listOf("build.yaml", "docker-compose.yml", ".github/workflows/ci.yml", "values.yaml")
            .forEach {
                assertEquals("", MqlFiles.languageIdFor(it), it)
                assertFalse(MqlFiles.isSupported(it), it)
            }
    }

    @Test
    fun `ignores files that merely mention mql`() {
        listOf("mql.txt", "notes-about-mql.md", "mqlyaml", "policy.mql.json")
            .forEach { assertFalse(MqlFiles.isSupported(it), it) }
    }

    @Test
    fun `is case insensitive`() {
        assertEquals("yaml", MqlFiles.languageIdFor("Policy.MQL.YAML"))
        assertEquals("mql", MqlFiles.languageIdFor("CHECK.MQL"))
    }

    @Test
    fun `handles names with no extension`() {
        listOf("Makefile", "", "README").forEach { assertFalse(MqlFiles.isSupported(it), it) }
    }
}
