// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MondooConfigFileTest {

    private val usable = """
        agent_mrn: //agents.api.mondoo.app/spaces/friendly-nash-115619/agents/abc
        api_endpoint: https://us.api.mondoo.com
        mrn: //agents.api.mondoo.app/spaces/friendly-nash-115619/serviceaccounts/xyz
        space_mrn: //captain.api.mondoo.app/spaces/friendly-nash-115619
        private_key: |
          -----BEGIN PRIVATE KEY-----
          MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgnot-a-real-key
          -----END PRIVATE KEY-----
        certificate: |
          -----BEGIN CERTIFICATE-----
          MIIBnot-a-real-certificate
          -----END CERTIFICATE-----
    """.trimIndent()

    @Test
    fun `a complete service account is usable and names its space`() {
        val c = MondooConfigFile.inspect(usable)
        assertTrue(c.usable, c.problems.toString())
        assertEquals("friendly-nash-115619", c.space)
        assertEquals("https://us.api.mondoo.com", c.apiEndpoint)
    }

    /** The shape of the file that made every cnspec scan fail in the sandbox, 2026-10-06. */
    @Test
    fun `empty key and certificate are reported, not discovered by a failing scan`() {
        val broken = usable.lines().filterNot { it.startsWith("  ") }
            .map {
                if (it.startsWith("private_key") ||
                    it.startsWith("certificate")
                ) {
                    it.substringBefore(":") + ": \"\""
                } else {
                    it
                }
            }
            .joinToString("\n")
        val c = MondooConfigFile.inspect(broken)
        assertFalse(c.usable)
        assertEquals(listOf("its private key is missing", "its certificate is missing"), c.problems)
    }

    @Test
    fun `a key that is not PEM, and a file that is not a config`() {
        val c = MondooConfigFile.inspect(usable.replace("-----BEGIN PRIVATE KEY-----", "garbage"))
        assertEquals(listOf("its private key is damaged"), c.problems)
        assertEquals(
            listOf("it is not a Mondoo service account file"),
            MondooConfigFile.inspect("- just\n- a list").problems,
        )
        assertEquals(listOf("it is not a Mondoo service account file"), MondooConfigFile.inspect("{ not yaml").problems)
    }

    @Test
    fun `problems never echo key material`() {
        val c = MondooConfigFile.inspect(usable.replace("-----BEGIN PRIVATE KEY-----", "SECRETVALUE"))
        assertFalse(c.problems.joinToString().contains("SECRETVALUE"))
    }
}
