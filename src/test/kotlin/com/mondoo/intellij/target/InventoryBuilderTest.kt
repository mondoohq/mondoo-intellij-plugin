// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InventoryBuilderTest {

    private fun build(type: TargetType, vararg values: Pair<String, String>) =
        InventoryBuilder.build(TargetConfiguration("t", type, values.toMap()))

    private fun buildWithSecrets(
        type: TargetType,
        secrets: Map<String, String>,
        vararg values: Pair<String, String>,
    ) = InventoryBuilder.build(TargetConfiguration("t", type, values.toMap()), secrets)

    /**
     * The bug this covers: a password-authenticated SSH target never used its
     * password. The inventory only ever emitted `private_key` or `ssh_agent`, and the
     * password was put in an `SSH_PASSWORD` environment variable that cnspec does not
     * read — so the connection silently fell back to the agent and failed wherever the
     * agent did not happen to hold the key.
     */
    @Test
    fun `an ssh password becomes a password credential`() {
        val yaml = buildWithSecrets(
            TargetType.SSH,
            secrets = mapOf("password" to "s3cr3t"),
            "host" to "example.test",
            "user" to "deploy",
        )
        assertTrue(yaml.contains("- type: password"), yaml)
        assertTrue(yaml.contains("""user: "deploy""""), yaml)
        assertTrue(yaml.contains("""password: "s3cr3t""""), yaml)
        assertFalse(yaml.contains("ssh_agent"), "the agent fallback must not also be emitted")
    }

    /** A key is the stronger credential, so it wins when both are configured. */
    @Test
    fun `a key file takes precedence over a password`() {
        val yaml = buildWithSecrets(
            TargetType.SSH,
            secrets = mapOf("password" to "s3cr3t"),
            "host" to "example.test",
            "user" to "deploy",
            "keyFile" to "/keys/id_ed25519",
        )
        assertTrue(yaml.contains("- type: private_key"), yaml)
        assertFalse(yaml.contains("s3cr3t"), "the password must not be written when a key is used")
    }

    @Test
    fun `no password still falls back to the agent`() {
        val yaml = build(TargetType.SSH, "host" to "example.test", "user" to "deploy")
        assertTrue(yaml.contains("- type: ssh_agent"), yaml)
    }

    /** A password with a quote or newline must not be able to restructure the file. */
    @Test
    fun `a hostile password is escaped, not injected`() {
        val yaml = buildWithSecrets(
            TargetType.SSH,
            secrets = mapOf("password" to "a\"b\nuser: root"),
            "host" to "example.test",
            "user" to "deploy",
        )
        // One line, with the quote and the newline both neutralised, so the injected
        // `user: root` stays inside the scalar instead of becoming a second field.
        assertTrue(yaml.contains("""password: "a\"b\nuser: root""""), yaml)
        assertEquals(1, yaml.lines().count { it.trim().startsWith("user:") }, yaml)
    }

    @Test
    fun `local needs no connection detail`() {
        val yaml = build(TargetType.LOCAL)
        assertTrue(yaml.contains("apiVersion: v7"))
        assertTrue(yaml.contains("kind: Inventory"))
        assertTrue(yaml.contains("- type: \"local\""))
    }

    @Test
    fun `ssh with a key file uses a private key credential`() {
        val yaml = build(TargetType.SSH, "host" to "example.test", "user" to "deploy", "keyFile" to "/keys/id_ed25519")
        assertTrue(yaml.contains("host: \"example.test\""))
        assertTrue(yaml.contains("type: private_key"))
        assertTrue(yaml.contains("private_key_path: \"/keys/id_ed25519\""))
        assertTrue(yaml.contains("user: \"deploy\""))
    }

    @Test
    fun `ssh without a key file defers to the agent`() {
        // A private_key credential carrying no key fails as "no authentication
        // method defined", so the agent credential is the correct fallback.
        val yaml = build(TargetType.SSH, "host" to "deploy@example.test")
        assertTrue(yaml.contains("type: ssh_agent"), yaml)
        assertTrue(yaml.contains("user: \"deploy\""))
        assertFalse(yaml.contains("private_key"))
    }

    @Test
    fun `ssh with neither user nor key emits no credentials`() {
        val yaml = build(TargetType.SSH, "host" to "example.test")
        assertFalse(yaml.contains("credentials"))
    }

    @Test
    fun `explicit user and port beat the ones in the host string`() {
        val yaml = build(
            TargetType.SSH,
            "host" to "ignored@example.test:2222",
            "user" to "explicit",
            "port" to "2200",
        )
        assertTrue(yaml.contains("user: \"explicit\""))
        assertTrue(yaml.contains("port: 2200"))
    }

    @Test
    fun `docker distinguishes an image from a container`() {
        assertTrue(build(TargetType.DOCKER, "target" to "nginx:latest").contains("docker-image"))
        assertTrue(build(TargetType.DOCKER, "target" to "ghcr.io/org/img").contains("docker-image"))
        assertTrue(build(TargetType.DOCKER, "target" to "sleepy_hopper").contains("docker-container"))
    }

    @Test
    fun `kubernetes omits options that were not set`() {
        assertFalse(build(TargetType.KUBERNETES).contains("options"))
        val yaml = build(TargetType.KUBERNETES, "path" to "/manifests", "namespaces" to "kube-system")
        assertTrue(yaml.contains("path: \"/manifests\""))
        assertTrue(yaml.contains("namespaces: \"kube-system\""))
    }

    @Test
    fun `a hostile value cannot inject structure into the document`() {
        // Without quoting and escaping, this would add a key to the connection.
        val yaml = build(TargetType.SSH, "host" to "evil\"\nfoo: bar\nbaz: \"x")
        assertFalse(yaml.lines().any { it.trim().startsWith("foo:") }, yaml)
        assertFalse(yaml.lines().any { it.trim().startsWith("baz:") }, yaml)
        assertTrue(yaml.contains("\\n"), "newlines must be escaped, not emitted")
    }

    @Test
    fun `a backslash in a path survives escaping`() {
        val yaml = build(TargetType.SSH, "host" to "h", "keyFile" to """C:\keys\id""")
        assertTrue(yaml.contains("""private_key_path: "C:\\keys\\id""""), yaml)
    }

    @Test
    fun `a non-numeric port falls back rather than emitting rubbish`() {
        val yaml = build(TargetType.SSH, "host" to "h", "port" to "not-a-port")
        assertTrue(yaml.contains("port: 22"), yaml)
    }

    @Test
    fun `secret fields are rejected from a configuration outright`() {
        // Secrets belong in the password safe; making this a construction error means
        // no code path can accidentally persist one.
        assertThrows(IllegalArgumentException::class.java) {
            TargetConfiguration("t", TargetType.SSH, mapOf("password" to "hunter2"))
        }
    }

    @Test
    fun `missing required fields are reported`() {
        val incomplete = TargetConfiguration("t", TargetType.SSH)
        assertFalse(incomplete.isComplete)
        assertEquals(listOf("host"), incomplete.missingRequired().map { it.key })
        assertTrue(TargetConfiguration("t", TargetType.LOCAL).isComplete)
    }
}

class SshTargetTest {

    @Test
    fun `parses the forms people type`() {
        assertEquals(SshTarget("", "example.test", ""), SshTarget.parse("example.test"))
        assertEquals(SshTarget("deploy", "example.test", ""), SshTarget.parse("deploy@example.test"))
        assertEquals(SshTarget("deploy", "example.test", "2222"), SshTarget.parse("deploy@example.test:2222"))
        assertEquals(SshTarget("", "example.test", "2222"), SshTarget.parse("example.test:2222"))
    }

    @Test
    fun `splits on the last at sign`() {
        // A user name can contain '@' — an email-style login, for instance.
        assertEquals(SshTarget("a@b.test", "host", ""), SshTarget.parse("a@b.test@host"))
    }

    @Test
    fun `does not mistake an IPv6 address for a port`() {
        // "2001:db8::1" ends in ":1", which reads as a port if you only look at the
        // last colon.
        assertEquals("2001:db8::1", SshTarget.parse("2001:db8::1").host)
        assertEquals("", SshTarget.parse("2001:db8::1").port)
        assertEquals("::1", SshTarget.parse("::1").host)
    }

    @Test
    fun `reads a port from bracketed IPv6`() {
        assertEquals(SshTarget("", "2001:db8::1", "2222"), SshTarget.parse("[2001:db8::1]:2222"))
        assertEquals(SshTarget("", "2001:db8::1", ""), SshTarget.parse("[2001:db8::1]"))
        assertEquals(SshTarget("deploy", "::1", "22"), SshTarget.parse("deploy@[::1]:22"))
    }

    @Test
    fun `handles empty and whitespace input`() {
        assertEquals(SshTarget("", "", ""), SshTarget.parse(""))
        assertEquals(SshTarget("", "host", ""), SshTarget.parse("  host  "))
    }
}
