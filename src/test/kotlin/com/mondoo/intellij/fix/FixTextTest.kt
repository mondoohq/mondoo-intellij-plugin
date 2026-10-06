// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FixTextTest {

    /** The message Claude wrote after fixing db.py in the sandbox, 2026-10-06. */
    @Test
    fun `agent markdown reads cleanly in a console`() {
        val md = """
            I fixed the SQL injection in `db.py:5`.

            **Change (one line):**
            ```python
            return conn.cursor().execute("SELECT * FROM users WHERE id = ?", (uid,))
            ```
            ## Why
            The query now uses a `?` placeholder.
        """.trimIndent()
        assertEquals(
            """
            I fixed the SQL injection in db.py:5.

            Change (one line):
                return conn.cursor().execute("SELECT * FROM users WHERE id = ?", (uid,))
            Why
            The query now uses a ? placeholder.
            """.trimIndent(),
            FixText.plain(md),
        )
    }

    @Test
    fun `finished and summary lines`() {
        assertEquals(
            "agent finished in 5.4s · $0.03",
            FixText.finished(AgentActivity(ActivityKind.FINISHED, durationMs = 5382, costUsd = 0.0269)),
        )
        assertEquals("agent finished", FixText.finished(AgentActivity(ActivityKind.FINISHED)))
        assertEquals("Done: 1 fixed, 1 not fixed (cancelled)", FixText.summary(RunFixEvent.Done(1, 1, 0, true)))
    }

    /** One dependency upgrade clears many advisories; the log names them on one line. */
    @Test
    fun `findings of one outcome line`() {
        assertEquals("CVE-1", FixText.findings(listOf("CVE-1")))
        assertEquals("CVE-1 and CVE-2", FixText.findings(listOf("CVE-1", "CVE-2")))
        assertEquals(
            "10 findings (CVE-0, CVE-1 and 8 more)",
            FixText.findings((0 until 10).map { "CVE-$it" }),
        )
    }

    @Test
    fun `denied tool calls decode`() {
        assertEquals(ActivityKind.DENIED, ActivityKind.fromWire("KIND_DENIED"))
    }
}
