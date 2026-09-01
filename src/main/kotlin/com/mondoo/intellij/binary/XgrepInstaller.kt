package com.mondoo.intellij.binary

import com.intellij.util.io.Decompressor
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class XgrepInstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads, verifies and unpacks an xgrep release into a version-keyed directory.
 *
 * Takes its filesystem root and network transport as constructor parameters so the
 * whole thing is unit-testable against a temp dir and a fake downloader — no
 * network, no real binary. That mirrors the vscode-mondoo testing policy.
 *
 * Layout: `<root>/<version>/xgrep`. Version-keyed directories make installs atomic
 * (extract to a staging dir, then `ATOMIC_MOVE`) and make rollback a rename.
 */
class XgrepInstaller(
    private val root: Path,
    private val downloader: XgrepDownloader,
    private val executableName: String = "xgrep",
) {

    fun versionDir(version: String): Path = root.resolve(version)

    /** The installed binary for [version], or null when it is not installed. */
    fun installedBinary(version: String): Path? =
        versionDir(version).takeIf { it.isDirectory() }?.let(::findBinary)

    /** Every installed version, newest first. */
    fun installedVersions(): List<String> {
        if (!root.isDirectory()) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { it.isDirectory() }
                .map { it.name }
                .filter(ArtifactSelector::isValidReleaseVersion)
                .toList()
        }.sortedWith { a, b -> ArtifactSelector.compareVersions(b, a) }
    }

    /**
     * Fetches and parses the release manifest. Returns null when it is unreachable
     * or malformed — callers fall back to a cached or floored version rather than
     * failing, because a network hiccup must not break a working install.
     */
    fun fetchManifest(url: String = LATEST_JSON_URL): ReleaseManifest? =
        runCatching { ReleaseManifest.parse(downloader.fetchText(url)) }.getOrNull()

    /**
     * Downloads [release], verifies its SHA-256, unpacks it, and atomically moves it
     * into place. Returns the installed binary.
     *
     * @throws XgrepInstallException on a hash mismatch, a missing binary in the
     *   archive, or any I/O failure. Nothing is left in [versionDir] on failure.
     */
    fun install(release: ReleaseFile, version: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Path {
        if (!ArtifactSelector.isValidReleaseVersion(version)) {
            throw XgrepInstallException("Refusing to install a malformed version: $version")
        }
        Files.createDirectories(root)

        val archive = Files.createTempFile(root, "xgrep-", if (release.isZip) ".zip" else ".tar.gz")
        val staging = Files.createTempDirectory(root, "xgrep-staging-")
        try {
            val actualHash = downloadVerifying(release, archive, onProgress)
            if (!actualHash.equals(release.hash, ignoreCase = true)) {
                throw XgrepInstallException(
                    "Checksum mismatch for ${release.filename.substringAfterLast('/')}: " +
                        "expected ${release.hash}, got $actualHash",
                )
            }

            extract(archive, staging, release.isZip)
            val staged = findBinary(staging)
                ?: throw XgrepInstallException("No '$executableName' found in ${release.filename}")
            staged.toFile().setExecutable(true, false)

            val target = versionDir(version)
            deleteRecursively(target)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            return findBinary(target)
                ?: throw XgrepInstallException("Installed tree at $target has no '$executableName'")
        } catch (e: XgrepInstallException) {
            deleteRecursively(staging)
            throw e
        } catch (e: Exception) {
            deleteRecursively(staging)
            throw XgrepInstallException("Failed to install xgrep $version: ${e.message}", e)
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /** Removes every installed version except [keep]. Best-effort. */
    fun pruneOtherVersions(keep: String) {
        installedVersions().filter { it != keep }.forEach { deleteRecursively(versionDir(it)) }
    }

    /** Streams the download through a digest so the file is hashed exactly once. */
    private fun downloadVerifying(
        release: ReleaseFile,
        target: Path,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        downloader.openStream(release.filename) { input ->
            DigestInputStream(input, digest).use { hashing ->
                Files.newOutputStream(target).use { out ->
                    copyReporting(hashing, out, release.size, onProgress)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyReporting(
        input: InputStream,
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            copied += read
            onProgress(copied, total)
        }
    }

    private fun extract(archive: Path, into: Path, isZip: Boolean) {
        val decompressor = if (isZip) Decompressor.Zip(archive).withZipExtensions() else Decompressor.Tar(archive)
        decompressor
            // Archives are third-party input: refuse entries that would escape the
            // destination via a symlink (zip-slip and friends).
            .escapingSymlinkPolicy(Decompressor.EscapingSymlinkPolicy.DISALLOW)
            .overwrite(true)
            .extract(into)
    }

    /** Locates the binary anywhere in the tree, so a layout change degrades loudly. */
    private fun findBinary(dir: Path): Path? =
        Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.removeSuffix(".exe") == executableName }
                .findFirst()
                .orElse(null)
        }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        runCatching {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        const val LATEST_JSON_URL: String = "https://releases.mondoo.com/xgrep/latest.json"
    }
}
