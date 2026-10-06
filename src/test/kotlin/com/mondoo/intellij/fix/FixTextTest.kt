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
}
