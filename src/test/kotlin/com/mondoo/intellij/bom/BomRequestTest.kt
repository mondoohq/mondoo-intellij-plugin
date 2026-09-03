// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.bom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BomRequestTest {

    private fun args(request: BomRequest, out: String? = "/tmp/out.json") =
        request.arguments("/work/project", out)

    @Test
    fun `builds the documented software-bom command`() {
        val a = args(BomRequest(setOf(BomContent.SCA), BomFormat.CYCLONEDX_JSON))
        assertEquals(
            listOf(
                "sbom",
                "/work/project",
                "--include",
                "sca",
                "--format",
                "cyclonedx-json",
                "--output",
                "/tmp/out.json",
            ),
            a,
        )
    }

    @Test
    fun `merges several content kinds into one include flag`() {
        val a = args(BomRequest(setOf(BomContent.SCA, BomContent.AIBOM), BomFormat.CYCLONEDX_JSON))
        val include = a[a.indexOf("--include") + 1]
        assertEquals("sca,aibom", include)
    }

    @Test
    fun `include order is stable regardless of set order`() {
        // A command that changes shape run to run is a command nobody can diff.
        val one = BomRequest(setOf(BomContent.AIBOM, BomContent.SCA), BomFormat.CYCLONEDX_JSON)
        val two = BomRequest(setOf(BomContent.SCA, BomContent.AIBOM), BomFormat.CYCLONEDX_JSON)
        assertEquals(args(one), args(two))
    }

    @Test
    fun `crypto and AI bills are CycloneDX JSON only`() {
        // The scanner rejects any other format for these, so the request must not
        // offer one.
        listOf(BomContent.CBOM, BomContent.AIBOM).forEach { kind ->
            val request = BomRequest(setOf(kind), BomFormat.SPDX_JSON)
            assertTrue(request.requiresCycloneDxJson, kind.name)
            assertEquals(listOf(BomFormat.CYCLONEDX_JSON), request.availableFormats())
            assertEquals(BomFormat.CYCLONEDX_JSON, request.effectiveFormat())
            val a = args(request)
            assertEquals("cyclonedx-json", a[a.indexOf("--format") + 1])
        }
    }

    @Test
    fun `a software bom supports every format`() {
        val request = BomRequest(setOf(BomContent.SCA), BomFormat.SPDX_TAG_VALUE)
        assertFalse(request.requiresCycloneDxJson)
        assertEquals(BomFormat.entries.size, request.availableFormats().size)
        val a = args(request)
        assertEquals("spdx-tag-value", a[a.indexOf("--format") + 1])
    }

    @Test
    fun `optional flags are omitted unless asked for`() {
        val plain = args(BomRequest(setOf(BomContent.SCA), BomFormat.JSON))
        assertFalse(plain.contains("--include-dev"))
        assertFalse(plain.contains("--direct-only"))
        assertFalse(plain.contains("--exclude-dir"))
    }

    @Test
    fun `optional flags are passed when asked for`() {
        val a = args(
            BomRequest(
                setOf(BomContent.SCA),
                BomFormat.JSON,
                includeDev = true,
                directOnly = true,
                excludeDirs = listOf("vendor", "", "  ", "testdata"),
            ),
        )
        assertTrue(a.contains("--include-dev"))
        assertTrue(a.contains("--direct-only"))
        // Blank entries would become an empty argument the scanner rejects.
        assertEquals(2, a.count { it == "--exclude-dir" })
        assertTrue(a.contains("vendor") && a.contains("testdata"))
    }

    @Test
    fun `omits the output flag when writing to stdout`() {
        assertFalse(args(BomRequest(setOf(BomContent.SCA), BomFormat.TABLE), out = null).contains("--output"))
    }

    @Test
    fun `names the file after the project and content`() {
        assertEquals(
            "my-app.sbom.cdx.json",
            BomRequest(setOf(BomContent.SCA), BomFormat.CYCLONEDX_JSON).defaultFileName("my-app"),
        )
        assertEquals(
            "my-app.cbom.cdx.json",
            BomRequest(setOf(BomContent.CBOM), BomFormat.CYCLONEDX_JSON).defaultFileName("my-app"),
        )
        assertEquals(
            "my-app.bom.cdx.json",
            BomRequest(setOf(BomContent.SCA, BomContent.CBOM), BomFormat.CYCLONEDX_JSON).defaultFileName("my-app"),
        )
    }

    @Test
    fun `file name survives a project name with path characters`() {
        val name = BomRequest(setOf(BomContent.SCA), BomFormat.SPDX_JSON).defaultFileName("../weird name/v2")
        assertFalse(name.contains("/"))
        assertEquals("..-weird-name-v2.sbom.spdx.json", name)
        assertEquals(
            "project.sbom.spdx.json",
            BomRequest(setOf(BomContent.SCA), BomFormat.SPDX_JSON).defaultFileName(""),
        )
    }

    @Test
    fun `an empty content set is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BomRequest(emptySet(), BomFormat.CYCLONEDX_JSON)
        }
    }
}
