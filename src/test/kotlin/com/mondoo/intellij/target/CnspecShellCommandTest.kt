// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnspecShellCommandTest {

    private fun build(
        type: TargetType,
        vararg values: Pair<String, String>,
        hasStoredPassword: Boolean = false,
    ) = CnspecShellCommand.build(
        "cnspec",
        TargetConfiguration("t", type, values.toMap()),
        hasStoredPassword,
    )

    @Test
    fun `local needs nothing but the subcommand`() {
        assertEquals("'cnspec' 'shell' 'local'", build(TargetType.LOCAL))
    }

    @Test
    fun `ssh builds the user host and port form cnspec expects`() {
        assertEquals(
            "'cnspec' 'shell' 'ssh' 'deploy@example.test:2222'",
            build(TargetType.SSH, "host" to "example.test", "user" to "deploy", "port" to "2222"),
        )
    }

    @Test
    fun `a key file is passed by path`() {
        val command = build(
            TargetType.SSH,
            "host" to "example.test",
            "user" to "deploy",
            "keyFile" to "/keys/id_ed25519",
        )
        assertTrue(command!!.contains("'-i' '/keys/id_ed25519'"), command)
    }

    /**
     * The password stays in the safe. `-p <password>` would put it in the process
     * table and the shell's history; `--ask-pass` makes cnspec prompt for it instead.
     */
    @Test
    fun `a stored password asks cnspec to prompt, and is never on the line`() {
        val command = build(
            TargetType.SSH,
            "host" to "example.test",
            "user" to "deploy",
            hasStoredPassword = true,
        )!!
        assertTrue(command.contains("'--ask-pass'"), command)
        // The quoted token, not the substring: "--ask-pass" contains "-p".
        assertFalse(command.contains("'-p'"), command)
        assertFalse(command.contains("--password"), command)
    }

    @Test
    fun `a key wins over a password, so there is nothing to prompt for`() {
        val command = build(
            TargetType.SSH,
            "host" to "example.test",
            "keyFile" to "/keys/id",
            hasStoredPassword = true,
        )!!
        assertFalse(command.contains("--ask-pass"), command)
    }

    /**
     * This string is typed into a real shell, unlike every other cnspec invocation in
     * the plugin, which uses an argv array no shell parses.
     */
    @Test
    fun `a hostile host name cannot run a second command`() {
        val command = build(TargetType.SSH, "host" to "example.test; rm -rf /", "user" to "deploy")!!
        // Inside single quotes the semicolon is a character, not a separator.
        assertTrue(command.contains("'deploy@example.test; rm -rf /'"), command)
        assertFalse(command.contains("; rm -rf / "), command)
    }

    @Test
    fun `a single quote in a value cannot end the quoting`() {
        val command = build(TargetType.DOCKER, "target" to "it's'; whoami; '")!!
        // Every embedded quote is closed, escaped and reopened.
        assertTrue(command.contains("""'it'\''s'\''; whoami; '\'''"""), command)
    }

    @Test
    fun `a backtick stays a character`() {
        val command = build(TargetType.DOCKER, "target" to "\$(whoami)`id`")!!
        assertTrue(command.contains("""'${'$'}(whoami)`id`'"""), command)
    }

    @Test
    fun `docker chooses the container subcommand`() {
        assertEquals("'cnspec' 'shell' 'container' 'abc123'", build(TargetType.DOCKER, "target" to "abc123"))
    }

    @Test
    fun `kubernetes takes an optional manifest path`() {
        assertEquals("'cnspec' 'shell' 'k8s'", build(TargetType.KUBERNETES))
        assertEquals(
            "'cnspec' 'shell' 'k8s' '/manifests'",
            build(TargetType.KUBERNETES, "path" to "/manifests"),
        )
    }

    @Test
    fun `a target with nothing to connect to has no command`() {
        assertNull(build(TargetType.SSH))
        assertNull(build(TargetType.DOCKER))
    }

    @Test
    fun `a binary path with a space is quoted too`() {
        val command = CnspecShellCommand.build(
            "/Applications/My Tools/cnspec",
            TargetConfiguration("t", TargetType.LOCAL),
            false,
        )
        assertEquals("'/Applications/My Tools/cnspec' 'shell' 'local'", command)
    }
}
