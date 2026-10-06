// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Decoding against payloads captured from `xgrep fix serve` on 2026-10-06, with the
 * temporary fixture directory shortened to `/work`.
 */
class FixApiTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private val capturedList = """
        {"findings":[
          {"fingerprint":"53058b647b1f6eed","ruleId":"js-bad-suffix","message":"broken suffix check",
           "severity":"ERROR","confidence":"HIGH","path":"app.js","absolutePath":"/work/app.js",
           "start":{"line":2,"column":15},"end":{"line":2,"column":37},
           "lines":"indexOf(\".png\") !== -1","tier":"FIX_TIER_DETERMINISTIC","fixable":true},
          {"fingerprint":"1ebfe27c08950559","ruleId":"js-eval-input","message":"eval of caller input",
           "severity":"ERROR","confidence":"HIGH","path":"run.js","absolutePath":"/work/run.js",
           "start":{"line":2,"column":10},"end":{"line":2,"column":17},"lines":"eval(x)",
           "tier":"FIX_TIER_ASSISTED","fixable":true,
           "hint":"Parse the input with JSON.parse instead of evaluating it.",
           "contract":{"strategy":"Parse the input with JSON.parse instead of evaluating it.",
             "acceptanceCriteria":["the patched file parses without introducing new syntax errors",
               "finding js-eval-input no longer fires in the changed region",
               "no new finding of equal-or-higher severity appears in the changed region"]}}],
         "cachePresent":true}
    """.trimIndent()

    @Test
    fun `a captured finding list decodes`() {
        val list = FixApi.findingList(obj(capturedList))
        assertTrue(list.cachePresent)
        assertEquals(2, list.findings.size)

        val det = list.findings[0]
        assertEquals("js-bad-suffix", det.ruleId)
        assertEquals(FixTier.DETERMINISTIC, det.tier)
        assertEquals(2, det.startLine)
        assertEquals(15, det.startColumn)
        assertEquals("/work/app.js", det.absolutePath)
        assertTrue(det.fixable)
        assertNull(det.triage)
        assertNull(det.contract)
        assertEquals(3, det.severityRank)

        val asst = list.findings[1]
        assertEquals(FixTier.ASSISTED, asst.tier)
        assertEquals(3, asst.contract!!.acceptanceCriteria.size)
        assertEquals("Parse the input with JSON.parse instead of evaluating it.", asst.hint)
    }

    @Test
    fun `defaults the server leaves out decode as defaults`() {
        // Protobuf JSON omits false, 0, "" and empty lists.
        val f = FixApi.finding(obj("""{"fingerprint":"x"}"""))
        assertFalse(f.fixable)
        assertFalse(f.stale)
        assertEquals(FixTier.NONE, f.tier)
        assertEquals(0, f.startLine)
        assertEquals("", f.path)
        assertFalse(FixApi.findingList(obj("{}")).cachePresent)
        assertTrue(FixApi.findingList(obj("{}")).findings.isEmpty())
    }

    @Test
    fun `an unknown enum value from a newer server degrades instead of failing`() {
        val f = FixApi.finding(
            obj("""{"fingerprint":"x","tier":"FIX_TIER_QUANTUM","triage":{"status":"TRIAGE_STATUS_MAYBE"}}"""),
        )
        assertEquals(FixTier.NONE, f.tier)
        assertEquals(TriageStatus.UNREVIEWED, f.triage!!.status)
    }

    @Test
    fun `triage and outcome decode`() {
        val f = FixApi.finding(
            obj(
                """{"fingerprint":"x","triage":{"status":"TRIAGE_STATUS_FALSE_POSITIVE","rationale":"test code",
                   "reviewedBy":"ide","reviewedAt":"2026-10-06T10:00:00Z"},
                   "outcome":{"fingerprint":"x","status":"rejected","reason":"finding-not-cleared","tier":"FIX_TIER_ASSISTED"}}""",
            ),
        )
        assertEquals(TriageStatus.FALSE_POSITIVE, f.triage!!.status)
        assertEquals("test code", f.triage.rationale)
        assertTrue(f.outcome!!.rejected)
        assertEquals("finding-not-cleared", f.outcome.reason)
    }

    @Test
    fun `captured preview and server info decode`() {
        val p = FixApi.preview(
            obj(
                """{"accepted":true,"diff":"--- a/app.js\n+++ b/app.js\n",
                   "originalContent":"function f(name) {\n  return name.indexOf(\".png\") !== -1;\n}\n",
                   "patchedContent":"function f(name) {\n  return name.endsWith(\".png\");\n}\n"}""",
            ),
        )
        assertTrue(p.accepted)
        assertTrue(p.patchedContent.contains("endsWith"))

        val info = FixApi.serverInfo(
            obj(
                """{"version":"0.1.0","commit":"none","projectRoot":"/work",
                   "agent":{"name":"claude","commandLine":"claude -p <prompt>","available":true}}""",
            ),
        )
        assertEquals("claude", info.agent.name)
        assertTrue(info.agent.available)
    }

    /** The RunFix messages, in the order a captured run sent them. */
    @Test
    fun `captured run events decode in order`() {
        val frames = listOf(
            """{"progress":{"message":"applying fix 1/2: js-bad-suffix","total":2}}""",
            """{"outcome":{"fingerprint":"53058b647b1f6eed","ruleId":"js-bad-suffix","path":"app.js","status":"applied","tier":"FIX_TIER_DETERMINISTIC","diff":"--- a/app.js\n"}}""",
            """{"filesChanged":{"paths":["/work/app.js"]}}""",
            """{"agentStarted":{"agent":{"name":"claude","commandLine":"claude -p <prompt>","available":true},"findingCount":1}}""",
            """{"agentActivity":{"kind":"KIND_TOOL","tool":"Read","target":"run.js"}}""",
            """{"done":{"applied":2}}""",
            """{"somethingNew":{}}""",
        ).map { FixApi.runFixEvent(obj(it)) }

        val progress = frames[0] as RunFixEvent.Progress
        assertEquals(0, progress.completed, "an omitted zero")
        assertEquals(2, progress.total)
        assertTrue((frames[1] as RunFixEvent.OutcomeReported).outcome.applied)
        assertEquals(listOf("/work/app.js"), (frames[2] as RunFixEvent.FilesChanged).paths)
        assertEquals(1, (frames[3] as RunFixEvent.AgentStarted).findingCount)
        assertEquals(
            AgentActivity(ActivityKind.TOOL, tool = "Read", target = "run.js"),
            (frames[4] as RunFixEvent.AgentActivity).activity,
        )
        assertEquals(RunFixEvent.Done(2, 0, 0, false), frames[5])
        assertEquals(RunFixEvent.Unknown, frames[6])
    }

    @Test
    fun `agent activity decodes, with int64 as the string protobuf JSON writes`() {
        val e = FixApi.runFixEvent(
            obj("""{"agentActivity":{"kind":"KIND_FINISHED","durationMs":"5382","costUsd":0.0269}}"""),
        ) as RunFixEvent.AgentActivity
        assertEquals(AgentActivity(ActivityKind.FINISHED, durationMs = 5382, costUsd = 0.0269), e.activity)
        val unknown = FixApi.runFixEvent(obj("""{"agentActivity":{"kind":"KIND_TELEPATHY","text":"hi"}}"""))
        assertEquals(ActivityKind.OUTPUT, (unknown as RunFixEvent.AgentActivity).activity.kind)
    }

    @Test
    fun `requests use the wire names`() {
        assertEquals(
            """{"fingerprint":"x","status":"TRIAGE_STATUS_FALSE_POSITIVE","rationale":"why"}""",
            FixApi.setTriageRequest("x", TriageStatus.FALSE_POSITIVE, "why").toString(),
        )
        assertEquals(
            """{"fingerprint":"x","status":"TRIAGE_STATUS_UNSPECIFIED"}""",
            FixApi.setTriageRequest("x", TriageStatus.UNREVIEWED, " ").toString(),
        )
        assertEquals("""{"fingerprints":["a","b"]}""", FixApi.runFixRequest(listOf("a", "b")).toString())
    }
}
