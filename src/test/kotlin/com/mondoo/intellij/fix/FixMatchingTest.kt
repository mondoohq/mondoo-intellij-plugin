// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixMatchingTest {

    private val samePath: (String, String) -> Boolean = { a, b -> a == b }

    private fun finding(fp: String, rule: String, line: Int, path: String = "/work/db.py", fixable: Boolean = true) =
        FixFinding(fingerprint = fp, ruleId = rule, startLine = line, absolutePath = path, fixable = fixable)

    @Test
    fun `the exact rule wins`() {
        val cache = listOf(finding("a", "python-sql-injection", 5), finding("b", "python-django-sql-injection", 5))
        val got = FixMatching.match(listOf(FixTarget("/work/db.py", 5, "python-django-sql-injection")), cache, samePath)
        assertEquals(listOf("b"), got.map { it.fingerprint })
    }

    /** What happened in the sandbox: the language server named the Django rule, the scan kept the generic one. */
    @Test
    fun `a rule the scan did not keep falls back to the finding on the same line`() {
        val cache = listOf(finding("a", "python-sql-injection", 5), finding("c", "python-insecure-cookie", 4))
        val got = FixMatching.match(listOf(FixTarget("/work/db.py", 5, "python-django-sql-injection")), cache, samePath)
        assertEquals(listOf("a"), got.map { it.fingerprint })
    }

    @Test
    fun `the fallback prefers a fixable finding`() {
        val cache = listOf(finding("x", "advice-only", 5, fixable = false), finding("y", "python-sql-injection", 5))
        val got = FixMatching.match(listOf(FixTarget("/work/db.py", 5, "something-else")), cache, samePath)
        assertEquals(listOf("y"), got.map { it.fingerprint })
    }

    @Test
    fun `another line or file never matches, and two targets on one finding count once`() {
        val cache = listOf(finding("a", "python-sql-injection", 5))
        assertTrue(
            FixMatching.match(listOf(FixTarget("/work/db.py", 6, "python-sql-injection")), cache, samePath).isEmpty(),
        )
        assertTrue(
            FixMatching.match(listOf(FixTarget("/work/app.py", 5, "python-sql-injection")), cache, samePath).isEmpty(),
        )
        val twice =
            listOf(
                FixTarget("/work/db.py", 5, "python-sql-injection"),
                FixTarget("/work/db.py", 5, "python-django-sql-injection"),
            )
        assertEquals(1, FixMatching.match(twice, cache, samePath).size)
    }
}
