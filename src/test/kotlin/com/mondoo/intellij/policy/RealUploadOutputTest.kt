// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The interpreter against streams captured byte for byte from a real upload to a real
 * Mondoo space, rather than from output written by hand.
 *
 * This caught something the hand-written fixtures could not: cnspec writes
 * `successfully uploaded 1 policy` to **stderr**, while the lint table and the policy
 * and space lines go to **stdout**. The hand-written test put the success line on
 * stdout and passed anyway, because the interpreter merges the two streams — so it was
 * asserting the wrong shape and getting the right answer. A parser tested only against
 * my idea of a format is tested against the wrong thing.
 *
 * Held as line lists so the captured text stays exact without exceeding the line
 * limit. The config path is redacted; nothing else is edited.
 */
class RealUploadOutputTest {

    private val stdout = listOf(
        " RULE ID                 LEVEL    FILE                                  LINE  MESSAGE                                                                                                                                          ",
        " policy-missing-require  warning  intellij-plugin-upload-test.mql.yaml  2     policy 'intellij-plugin-upload-test' does not define any required providers, please add a `require` statement (e.g. `require: [{provider: os}]`) ",
        "  policy: IntelliJ plugin upload test (safe to delete) 1.0.0",
        "    //policy.api.mondoo.app/spaces/amazing-dhawan-655469/policies/intellij-plugin-upload-test",
        "  space: SAST - Secrets",
        "    //captain.api.mondoo.app/spaces/amazing-dhawan-655469",
        "",
    ).joinToString("\n")

    private val stderr = listOf(
        "\u2192 loaded configuration from /redacted/service-account.json using source --config",
        "\u2192 valid policy bundle",
        "\u2192 successfully uploaded 1 policy",
        "",
    ).joinToString("\n")

    @Test
    fun `interprets a genuine successful upload`() {
        val result = assertInstanceOf(UploadResult.Uploaded::class.java, PolicyUpload.interpret(stdout, stderr))
        assertEquals("1 policy to SAST - Secrets", result.summary)
        assertEquals(1, result.lintWarnings)
    }

    /** The detail that surprised me, pinned so a future refactor cannot lose it. */
    @Test
    fun `the success line arrives on stderr, not stdout`() {
        assertTrue(stderr.contains("successfully uploaded 1 policy"))
        assertFalse(stdout.contains("successfully uploaded"))
    }

    /** Reading only stdout would report a failure for an upload that succeeded. */
    @Test
    fun `stdout alone is not enough to see the upload`() {
        assertInstanceOf(UploadResult.Failed::class.java, PolicyUpload.interpret(stdout, ""))
    }
}
