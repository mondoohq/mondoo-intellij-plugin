// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText

/**
 * Exercises the real download → verify → extract → atomic-install path against a
 * fake transport and a temp directory. No network, no real xgrep binary.
 */
class XgrepInstallerTest {

    /** Serves in-memory archives, and records what was requested. */
    private class FakeDownloader(
        private val archives: Map<String, ByteArray> = emptyMap(),
        private val texts: Map<String, String> = emptyMap(),
    ) : XgrepDownloader {
        val requested = mutableListOf<String>()

        override fun fetchText(url: String, timeout: Duration): String {
            requested += url
            return texts[url] ?: throw java.io.IOException("no stub for $url")
        }

        override fun <T> openStream(url: String, timeout: Duration, sink: (InputStream) -> T): T {
            requested += url
            val bytes = archives[url] ?: throw java.io.IOException("no stub for $url")
            return bytes.inputStream().use(sink)
        }
    }

    private fun zipWith(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(url: String, bytes: ByteArray, hash: String = sha256(bytes)) =
        ReleaseFile(url, bytes.size.toLong(), "windows", hash)

    private val url = "https://releases.mondoo.com/xgrep/0.57.0/xgrep_0.57.0_windows_amd64.zip"

    @Test
    fun `installs a verified archive into a version directory`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "#!/bin/sh\necho scanner\n", "README.md" to "docs"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))

        val binary = installer.install(release(url, zip), "0.57.0")

        assertTrue(Files.isRegularFile(binary))
        assertEquals("xgrep", binary.fileName.toString())
        assertTrue(binary.startsWith(root.resolve("0.57.0")))
        assertTrue(binary.readText().contains("echo scanner"))
        assertTrue(binary.toFile().canExecute(), "installed binary must be executable")
    }

    /**
     * The Windows path, which every other test misses because they all take the
     * default executable name. XgrepBinaryService passes `xgrep.exe` there, and a
     * mismatch here makes the managed install impossible on Windows rather than
     * merely wrong.
     */
    @Test
    fun `installs a windows release whose archive contains xgrep_exe`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep.exe" to "scanner", "README.md" to "docs"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)), "xgrep.exe")

        val binary = installer.install(release(url, zip), "0.57.0")

        assertEquals("xgrep.exe", binary.fileName.toString())
        assertEquals(binary, installer.installedBinary("0.57.0"))
    }

    @Test
    fun `does not mistake a unix binary for the windows one`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "scanner"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)), "xgrep.exe")

        val error = assertThrows(XgrepInstallException::class.java) {
            installer.install(release(url, zip), "0.57.0")
        }
        assertTrue(error.message!!.contains("xgrep.exe"), error.message)
    }

    /**
     * Stands in for the case this message exists for: a running `xgrep.exe` that
     * Windows will not let the installer delete.
     *
     * Two blocks, applied together, because the platforms disagree about which one
     * bites. POSIX will not unlink from a directory it cannot write but is perfectly
     * happy to delete an open file; Windows is the other way round. Applying only the
     * permission change passed on a Mac and failed on the Windows runner — the first
     * thing that job caught, and a fair warning about testing a Windows behaviour
     * from a Mac.
     */
    @Test
    fun `explains itself when the existing install cannot be replaced`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "scanner"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))
        installer.install(release(url, zip), "0.57.0")

        val target = root.resolve("0.57.0")
        val held = target.resolve("held.txt")
        Files.writeString(held, "x")

        val open = Files.newByteChannel(held, StandardOpenOption.READ, StandardOpenOption.WRITE)
        target.toFile().setWritable(false)
        try {
            val error = assertThrows(XgrepInstallException::class.java) {
                installer.install(release(url, zip), "0.57.0")
            }
            assertTrue(
                error.message!!.contains("still running"),
                "should name the likely cause, said: ${error.message}",
            )
        } finally {
            target.toFile().setWritable(true)
            open.close()
        }
    }

    @Test
    fun `rejects a checksum mismatch and leaves nothing behind`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "payload"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))
        val tampered = release(url, zip, hash = "0".repeat(64))

        val error = assertThrows(XgrepInstallException::class.java) {
            installer.install(tampered, "0.57.0")
        }

        assertTrue(error.message!!.contains("Checksum mismatch"), error.message)
        assertFalse(Files.exists(root.resolve("0.57.0")), "must not install an unverified archive")
        // No staging or partial-download leftovers.
        assertEquals(emptyList<String>(), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun `fails when the archive contains no xgrep binary`(@TempDir root: Path) {
        val zip = zipWith(mapOf("README.md" to "no binary here"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))

        val error = assertThrows(XgrepInstallException::class.java) {
            installer.install(release(url, zip), "0.57.0")
        }
        assertTrue(error.message!!.contains("No 'xgrep' found"), error.message)
        assertFalse(Files.exists(root.resolve("0.57.0")))
    }

    @Test
    fun `refuses a malformed version rather than creating a path from it`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "x"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))

        assertThrows(XgrepInstallException::class.java) {
            installer.install(release(url, zip), "../../escape")
        }
    }

    @Test
    fun `reinstalling the same version replaces the previous tree`(@TempDir root: Path) {
        val first = zipWith(mapOf("xgrep" to "v1"))
        val second = zipWith(mapOf("xgrep" to "v2"))
        XgrepInstaller(root, FakeDownloader(archives = mapOf(url to first)))
            .install(release(url, first), "0.57.0")
        val binary = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to second)))
            .install(release(url, second), "0.57.0")

        assertEquals("v2", binary.readText())
    }

    @Test
    fun `lists installed versions newest first and prunes the rest`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "x"))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))
        listOf("0.9.0", "0.57.0", "0.11.1").forEach { installer.install(release(url, zip), it) }

        // Numeric ordering: 0.57.0 > 0.11.1 > 0.9.0.
        assertEquals(listOf("0.57.0", "0.11.1", "0.9.0"), installer.installedVersions())

        installer.pruneOtherVersions("0.57.0")
        assertEquals(listOf("0.57.0"), installer.installedVersions())
    }

    @Test
    fun `reports no installed binary for a version that is absent`(@TempDir root: Path) {
        val installer = XgrepInstaller(root, FakeDownloader())
        assertNull(installer.installedBinary("0.57.0"))
        assertEquals(emptyList<String>(), installer.installedVersions())
    }

    @Test
    fun `ignores non-version directories left in the install root`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("scratch"))
        Files.createDirectories(root.resolve("0.57.0-rc1"))
        assertEquals(emptyList<String>(), XgrepInstaller(root, FakeDownloader()).installedVersions())
    }

    @Test
    fun `fetchManifest returns null instead of throwing when unreachable`(@TempDir root: Path) {
        // A captive portal or offline machine must not break a working install.
        assertNull(XgrepInstaller(root, FakeDownloader()).fetchManifest())
    }

    @Test
    fun `fetchManifest parses the real latest json shape`(@TempDir root: Path) {
        val json = """
            {
              "name": "xgrep",
              "version": "0.57.0",
              "files": [
                {"filename": "https://releases.mondoo.com/xgrep/0.57.0/checksums.txt",
                 "size": 1273, "modtime": 1788138197, "platform": "", "hash": ""},
                {"filename": "$url", "size": 46881025, "modtime": 1788138228,
                 "platform": "windows", "hash": "94d1bbea"}
              ]
            }
        """.trimIndent()
        val installer = XgrepInstaller(root, FakeDownloader(texts = mapOf(XgrepInstaller.LATEST_JSON_URL to json)))

        val manifest = installer.fetchManifest()!!
        assertEquals("0.57.0", manifest.version)
        assertEquals(2, manifest.files.size)
        assertEquals("94d1bbea", ArtifactSelector.select(manifest, "windows", "amd64")!!.hash)
    }

    @Test
    fun `reports download progress`(@TempDir root: Path) {
        val zip = zipWith(mapOf("xgrep" to "x".repeat(200_000)))
        val installer = XgrepInstaller(root, FakeDownloader(archives = mapOf(url to zip)))
        val seen = mutableListOf<Long>()

        installer.install(release(url, zip), "0.57.0") { copied, _ -> seen += copied }

        assertTrue(seen.isNotEmpty(), "expected progress callbacks")
        assertEquals(zip.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen, "progress must be monotonic")
    }
}
