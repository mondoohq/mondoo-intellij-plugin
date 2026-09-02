// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtifactSelectorTest {

    /** Mirrors the real latest.json for 0.57.0, including the musl and package rows. */
    private val manifest = ReleaseManifest(
        name = "xgrep",
        version = "0.57.0",
        files = listOf(
            file("checksums.txt", ""),
            file("xgrep_0.57.0.cyclonedx.sbom.json", ""),
            file("xgrep_0.57.0_darwin_amd64.tar.gz", "darwin"),
            file("xgrep_0.57.0_darwin_arm64.tar.gz", "darwin"),
            file("xgrep_0.57.0_linux_amd64.deb", "linux"),
            file("xgrep_0.57.0_linux_amd64.rpm", "linux"),
            file("xgrep_0.57.0_linux_amd64.tar.gz", "linux"),
            file("xgrep_0.57.0_linux_arm64.deb", "linux"),
            file("xgrep_0.57.0_linux_arm64.tar.gz", "linux"),
            // The musl rows carry an EMPTY platform field in the real manifest.
            file("xgrep_0.57.0_linux_musl_amd64.tar.gz", ""),
            file("xgrep_0.57.0_linux_musl_arm64.tar.gz", ""),
            file("xgrep_0.57.0_windows_amd64.zip", "windows"),
            file("xgrep_0.57.0_windows_arm64.zip", "windows"),
        ),
    )

    private fun file(name: String, platform: String) =
        ReleaseFile("https://releases.mondoo.com/xgrep/0.57.0/$name", 1, platform, "deadbeef")

    private fun pick(os: String, arch: String) =
        ArtifactSelector.select(manifest, os, arch)?.filename?.substringAfterLast('/')

    @Test
    fun `selects the tarball for each supported os and arch`() {
        assertEquals("xgrep_0.57.0_darwin_arm64.tar.gz", pick("darwin", "arm64"))
        assertEquals("xgrep_0.57.0_darwin_amd64.tar.gz", pick("darwin", "amd64"))
        assertEquals("xgrep_0.57.0_linux_amd64.tar.gz", pick("linux", "amd64"))
        assertEquals("xgrep_0.57.0_linux_arm64.tar.gz", pick("linux", "arm64"))
        assertEquals("xgrep_0.57.0_windows_amd64.zip", pick("windows", "amd64"))
        assertEquals("xgrep_0.57.0_windows_arm64.zip", pick("windows", "arm64"))
    }

    @Test
    fun `never selects a deb rpm or musl build`() {
        val selected = listOf(
            "darwin" to "amd64", "darwin" to "arm64",
            "linux" to "amd64", "linux" to "arm64",
            "windows" to "amd64", "windows" to "arm64",
        ).mapNotNull { (os, arch) -> pick(os, arch) }

        assertEquals(6, selected.size)
        selected.forEach {
            assertFalse(it.endsWith(".deb"), it)
            assertFalse(it.endsWith(".rpm"), it)
            assertFalse(it.contains("musl"), it)
        }
    }

    @Test
    fun `returns null for an unsupported platform`() {
        assertNull(ArtifactSelector.select(manifest, "linux", "riscv64"))
        assertNull(ArtifactSelector.select(manifest, "freebsd", "amd64"))
    }

    @Test
    fun `never selects the checksum or sbom rows`() {
        val selected = listOf("darwin", "linux", "windows").flatMap { os ->
            listOf("amd64", "arm64").mapNotNull { pick(os, it) }
        }
        assertFalse(selected.any { it == "checksums.txt" })
        assertFalse(selected.any { it.endsWith(".sbom.json") })
    }

    @Test
    fun `handles a manifest with no files`() {
        assertNull(ArtifactSelector.select(manifest.copy(files = emptyList()), "darwin", "arm64"))
    }

    @Test
    fun `validates release versions`() {
        assertTrue(ArtifactSelector.isValidReleaseVersion("0.57.0"))
        assertFalse(ArtifactSelector.isValidReleaseVersion("0.57"))
        assertFalse(ArtifactSelector.isValidReleaseVersion("v0.57.0"))
        assertFalse(ArtifactSelector.isValidReleaseVersion("0.57.0-rc1"))
        assertFalse(ArtifactSelector.isValidReleaseVersion("../../etc"))
    }

    @Test
    fun `compares versions numerically, not lexically`() {
        assertTrue(ArtifactSelector.compareVersions("0.57.0", "0.9.0") > 0)
        assertTrue(ArtifactSelector.compareVersions("0.11.1", "0.9.0") > 0)
        assertEquals(0, ArtifactSelector.compareVersions("1.2.3", "1.2.3"))
        assertEquals(0, ArtifactSelector.compareVersions("v1.2.3", "1.2.3"))
        // The locally installed build reports 0.54.0-3-gffb6d1eab.
        assertEquals(0, ArtifactSelector.compareVersions("0.54.0", "0.54.0-3-gffb6d1eab"))
        assertTrue(ArtifactSelector.compareVersions("0.57.0", "0.54.0-3-gffb6d1eab") > 0)
    }

    @Test
    fun `rejects an artifact hosted anywhere but the release host`() {
        // The manifest is data from the network, and what it names gets downloaded and
        // executed. The checksum cannot protect against a tampered manifest, since the
        // same document supplies the hash — so the URL itself must be pinned.
        listOf(
            "http://releases.mondoo.com/xgrep/0.57.0/xgrep_0.57.0_darwin_arm64.tar.gz",
            "https://evil.test/xgrep_0.57.0_darwin_arm64.tar.gz",
            "https://releases.mondoo.com.evil.test/xgrep_0.57.0_darwin_arm64.tar.gz",
            "https://evil-releases.mondoo.com/xgrep_0.57.0_darwin_arm64.tar.gz",
            "file:///tmp/xgrep_0.57.0_darwin_arm64.tar.gz",
            "not a url",
        ).forEach { url ->
            assertFalse(ArtifactSelector.isTrustedDownloadUrl(url), url)
            val hostile = ReleaseManifest("xgrep", "0.57.0", listOf(ReleaseFile(url, 1, "darwin", "abc")))
            assertNull(ArtifactSelector.select(hostile, "darwin", "arm64"), url)
        }
    }

    @Test
    fun `accepts the real release host over https`() {
        assertTrue(
            ArtifactSelector.isTrustedDownloadUrl(
                "https://releases.mondoo.com/xgrep/0.57.0/xgrep_0.57.0_darwin_arm64.tar.gz",
            ),
        )
    }

    @Test
    fun `select returns a usable download url and hash`() {
        val picked = ArtifactSelector.select(manifest, "darwin", "arm64")
        assertNotNull(picked)
        assertTrue(picked!!.filename.startsWith("https://"))
        assertTrue(picked.hash.isNotBlank())
        assertFalse(picked.isZip)
        assertTrue(ArtifactSelector.select(manifest, "windows", "amd64")!!.isZip)
    }
}
