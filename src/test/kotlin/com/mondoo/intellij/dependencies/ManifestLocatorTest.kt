// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestLocatorTest {

    /** The demo's requirements.txt, 2026-10-06. */
    private val requirements = """
        # Pinned to old releases on purpose: this is a demo of dependency findings.
        flask==0.12.2
        jinja2==2.10
        PyYAML >= 5.3  # loader
        urllib3==1.24.1
    """.trimIndent()

    @Test
    fun `a requirement is found by normalized name and exact version`() {
        val loc = ManifestLocator.find("requirements.txt", requirements, "pyyaml", "5.3")!!
        assertEquals("PyYAML >= 5.3  # loader", requirements.substring(loc.line))
        assertEquals("5.3", requirements.substring(loc.version))
        assertEquals(
            "0.12.2",
            requirements.substring(ManifestLocator.find("requirements.txt", requirements, "Flask", "0.12.2")!!.version),
        )
    }

    @Test
    fun `another version or an absent package is not found`() {
        assertNull(ManifestLocator.find("requirements.txt", requirements, "flask", "1.0"))
        assertNull(ManifestLocator.find("requirements.txt", requirements, "requests", "2.19.1"))
    }

    @Test
    fun `a package json dependency, with or without a range prefix`() {
        val json = """{ "dependencies": { "express": "4.16.0", "lodash": "^4.17.4" } }"""
        val loc = ManifestLocator.find("package.json", json, "lodash", "4.17.4")!!
        assertEquals("\"lodash\": \"^4.17.4\"", json.substring(loc.line))
        assertEquals("4.17.4", json.substring(loc.version))
        assertNull(ManifestLocator.find("package.json", json, "lodash", "4.17.5"))
    }

    @Test
    fun `which files are understood`() {
        assertTrue(ManifestLocator.supports("requirements.txt"))
        assertTrue(ManifestLocator.supports("requirements-dev.txt"))
        assertTrue(ManifestLocator.supports("package.json"))
        assertFalse(ManifestLocator.supports("pom.xml"))
    }
}
