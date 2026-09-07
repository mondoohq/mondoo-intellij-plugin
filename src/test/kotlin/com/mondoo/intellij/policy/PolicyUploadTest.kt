// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyUploadTest {

    /** Captured verbatim from a real upload against a Mondoo space. */
    private val realSuccess = """
        RULE ID                 LEVEL    FILE                                  LINE  MESSAGE
        policy-missing-require  warning  intellij-plugin-upload-test.mql.yaml  2     does not define any required providers
        → valid policy bundle
        → successfully uploaded 1 policy
          policy: IntelliJ plugin upload test (safe to delete) 1.0.0
            //policy.api.mondoo.app/spaces/amazing-dhawan-655469/policies/intellij-plugin-upload-test
          space: SAST - Secrets
            //captain.api.mondoo.app/spaces/amazing-dhawan-655469
    """.trimIndent()

    @Test
    fun `reads a real successful upload`() {
        val result = assertInstanceOf(
            UploadResult.Uploaded::class.java,
            PolicyUpload.interpret(realSuccess, ""),
        )
        assertEquals("1 policy to SAST - Secrets", result.summary)
        assertEquals(1, result.lintWarnings)
    }

    @Test
    fun `pluralises more than one`() {
        val result = assertInstanceOf(
            UploadResult.Uploaded::class.java,
            PolicyUpload.interpret("→ successfully uploaded 3 policies\n  space: Production", ""),
        )
        assertEquals("3 policies to Production", result.summary)
    }

    @Test
    fun `an upload with no space named still succeeds`() {
        val result = assertInstanceOf(
            UploadResult.Uploaded::class.java,
            PolicyUpload.interpret("→ successfully uploaded 2 policies", ""),
        )
        assertEquals("2 policies", result.summary)
    }

    /** The case the exit code hides: unauthenticated, and still exit 0. */
    @Test
    fun `missing credentials are a failure, not a silent success`() {
        val result = assertInstanceOf(
            UploadResult.Failed::class.java,
            PolicyUpload.interpret(
                "→ valid policy bundle\nx failed to create upstream client: agent credentials must be set",
                "",
            ),
        )
        assertEquals("failed to create upstream client: agent credentials must be set", result.reason)
    }

    @Test
    fun `a malformed bundle is a failure`() {
        val result = assertInstanceOf(
            UploadResult.Failed::class.java,
            PolicyUpload.interpret(
                "",
                """x could not resolve bundle files error="could not load file: good.mql.yaml"""",
            ),
        )
        assertTrue(result.reason.contains("could not resolve bundle files"))
    }

    /**
     * If cnspec neither succeeds nor prints a failure marker, that is not success.
     * Reporting an upload that did not happen is the worst outcome available.
     */
    @Test
    fun `silence is not success`() {
        val result = assertInstanceOf(
            UploadResult.Failed::class.java,
            PolicyUpload.interpret("→ valid policy bundle", ""),
        )
        assertTrue(result.reason.contains("did not report"))
    }

    @Test
    fun `lint warnings do not prevent a successful upload`() {
        val result = assertInstanceOf(
            UploadResult.Uploaded::class.java,
            PolicyUpload.interpret(
                """
                rule-a  warning  f.mql.yaml  2  something
                rule-b  warning  f.mql.yaml  9  something else
                → successfully uploaded 1 policy
                  space: Dev
                """.trimIndent(),
                "",
            ),
        )
        assertEquals(2, result.lintWarnings)
    }
}
