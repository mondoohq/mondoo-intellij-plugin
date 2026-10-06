// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Findings shaped like a real `xgrep scan` of the fix demo, 2026-10-06 (trimmed). */
class DependencyVulnerabilitiesTest {

    private fun finding(id: String, severity: String, pkg: String, version: String, fixed: String, eco: String, cmd: String) = """
        {"check_id":"$id","path":"package.json","start":{"line":1,"col":1},
         "extra":{"message":"$pkg@$version is affected by $id: Something bad in $pkg","severity":"$severity",
           "metadata":{"aliases":["GHSA-x"],"ecosystem":"$eco","fixed_version":"$fixed","package":"$pkg","version":"$version"},
           "fix_info":{"kind":"ecosystem","ecosystem_plan":{"commands":["$cmd"],"target_version":"$fixed"}}}}
    """

    private val cache = """
        {"schema_version":"1.1","report":{"results":[
          ${finding("CVE-2017-16082", "CRITICAL", "pg", "7.1.0", "7.1.2", "npm", "npm install pg@7.1.2")},
          ${finding("CVE-2019-10744", "CRITICAL", "lodash", "4.17.4", "4.17.12", "npm", "npm install lodash@4.17.12")},
          ${finding("CVE-2020-8203", "WARNING", "lodash", "4.17.4", "4.17.19", "npm", "npm install lodash@4.17.19")},
          ${finding("CVE-2018-16487", "WARNING", "lodash", "4.17.4", "4.17.11", "npm", "npm install lodash@4.17.11")},
          {"check_id":"js-eval","path":"app.js","extra":{"message":"eval","severity":"ERROR","fix_info":{"kind":"assisted"}}}
        ]}}
    """

    @Test
    fun `vulnerabilities are grouped per package, most severe first`() {
        val all = DependencyVulnerabilities.parse(cache)
        assertEquals(setOf("npm/pg@7.1.0", "npm/lodash@4.17.4"), all.keys)
        val lodash = all.getValue("npm/lodash@4.17.4")
        assertEquals(listOf("CVE-2019-10744", "CVE-2018-16487", "CVE-2020-8203"), lodash.vulnerabilities.map { it.id })
        assertEquals(VulnSeverity.CRITICAL, lodash.worst)
        assertEquals("1 critical, 2 medium", lodash.counts)
        assertEquals("Something bad in lodash", lodash.vulnerabilities.first().summary)
    }

    @Test
    fun `the upgrade is the version that fixes all of them, compared as versions`() {
        val lodash = DependencyVulnerabilities.parse(cache).getValue("npm/lodash@4.17.4")
        // 4.17.19 > 4.17.12 > 4.17.11: numerically, not as text.
        assertEquals("4.17.19", lodash.upgradeTo)
        assertEquals("npm install lodash@4.17.19", lodash.upgradeCommand)
    }

    @Test
    fun `code findings and garbage are ignored`() {
        assertTrue(DependencyVulnerabilities.parse("""{"report":{"results":[]}}""").isEmpty())
        assertTrue(DependencyVulnerabilities.parse("not json").isEmpty())
        assertEquals("npm/pg@7.1.0", DependencyVulnerabilities.key("NPM", "PG", "7.1.0"))
    }
}
