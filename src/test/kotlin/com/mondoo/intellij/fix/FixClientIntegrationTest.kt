// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * [FixClient] against a real `xgrep fix serve`: the Connect framing, the event
 * stream, and that closing the stream really stops the agent.
 *
 * Skipped unless an xgrep with `fix serve` is available: `XGREP_BIN`, else `xgrep`
 * on PATH. The fake agents are shell scripts, so it is skipped on Windows too.
 */
class FixClientIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private var server: Process? = null

    @AfterEach
    fun stop() {
        server?.let {
            runCatching { it.outputStream.close() }
            if (!it.waitFor(15, TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    @Test
    fun `preview, run and agent output end to end`() {
        val agent = script(
            "agent.sh",
            "echo 'agent: rewriting run.js'\n" +
                "printf 'function g(x) {\\n  return JSON.parse(x);\\n}\\n' > run.js\n",
        )
        val client = start(agent)

        assertTrue(client.serverInfo().agent.available)
        assertEquals(false, client.listFindings().cachePresent)
        // No service account in the test, so no dependency lookup and nothing to warn about.
        assertEquals(emptyList<String>(), client.rescan())
        assertEquals(2, client.listFindings().findings.size)

        val findings = client.listFindings().findings
        val det = findings.first { it.ruleId == "js-bad-suffix" }
        val asst = findings.first { it.ruleId == "js-eval-input" }
        assertEquals(FixTier.DETERMINISTIC, det.tier)
        assertEquals(FixTier.ASSISTED, asst.tier)

        val preview = client.preview(det.fingerprint)
        assertTrue(preview.accepted)
        assertTrue(preview.patchedContent.contains("endsWith"))

        val triaged = client.setTriage(asst.fingerprint, TriageStatus.TRUE_POSITIVE, "input reaches eval")
        assertEquals(TriageStatus.TRUE_POSITIVE, triaged!!.triage!!.status)

        val events = mutableListOf<RunFixEvent>()
        client.runFix(listOf(det.fingerprint, asst.fingerprint)).use { stream ->
            while (true) events += stream.next() ?: break
        }
        val outcomes = events.filterIsInstance<RunFixEvent.OutcomeReported>().map { it.outcome }
        assertEquals(listOf("applied", "applied"), outcomes.map { it.status })
        assertTrue(
            events.filterIsInstance<RunFixEvent.AgentActivity>().joinToString("\n") { it.activity.text }
                .contains("agent: rewriting run.js"),
        )
        assertEquals(RunFixEvent.Done(2, 0, 0, false), events.last())
        assertTrue(dir.resolve("app.js").readText().contains("endsWith"))
    }

    @Test
    fun `closing the stream stops the agent and frees the session`() {
        val agent = script("slow.sh", "sleep 120 &\necho $! > child.pid\necho started\nwait\n")
        val client = start(agent)
        client.rescan()
        val asst = client.listFindings().findings.first { it.ruleId == "js-eval-input" }

        val stream = client.runFix(listOf(asst.fingerprint))
        while (true) {
            val event = stream.next() ?: break
            if (event is RunFixEvent.AgentActivity && event.activity.text.contains("started")) break
        }
        val pid = dir.resolve("child.pid").readText().trim().toLong()
        assertTrue(ProcessHandle.of(pid).isPresent, "the agent's child should be running")

        stream.cancel()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
            check(System.nanoTime() < deadline) { "the agent's child survived the cancelled run" }
            Thread.sleep(100)
        }
        // The session takes a new run once the cancelled one unwinds.
        val retryUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            val ok = runCatching {
                client.runFix(emptyList()).use { s ->
                    @Suppress("ControlFlowWithEmptyBody")
                    while (s.next() != null) {
                    }
                }
            }
            if (ok.isSuccess) break
            check(System.nanoTime() < retryUntil) { "session still busy: ${ok.exceptionOrNull()?.message}" }
            Thread.sleep(200)
        }
    }

    private fun start(agent: Path): FixClient {
        val binary = xgrepWithFixServe()
        assumeTrue(binary != null, "no xgrep with `fix serve`; set XGREP_BIN to run this test")
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"), "the fake agents are shell scripts")
        fixture()
        val token = "integration-test-token"
        val pb = ProcessBuilder(binary, "fix", "serve", "--rules", "rules", "--agent", agent.toString())
            .directory(dir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.environment()[FixServer.TOKEN_ENV] = token
        val process = pb.start().also { server = it }
        val hs = FixHandshake.parse(process.inputStream.bufferedReader().readLine().orEmpty())
        assertNotNull(hs, "no handshake from xgrep fix serve")
        return FixClient("http://${hs!!.addr}", token)
    }

    private fun xgrepWithFixServe(): String? {
        val candidates = listOfNotNull(System.getenv("XGREP_BIN"), "xgrep")
        return candidates.firstOrNull { bin ->
            runCatching {
                val p = ProcessBuilder(bin, "fix", "serve", "--help").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0 && out.contains("xgrep.fix.v1")
            }.getOrDefault(false)
        }
    }

    private fun fixture() {
        Files.createDirectories(dir.resolve("rules"))
        dir.resolve("rules/suffix.xgrep.yaml").writeText(
            """
            rules:
              - id: js-bad-suffix
                languages: [javascript]
                severity: ERROR
                message: "broken suffix check"
                metadata: { category: security, confidence: HIGH }
                fix-regex:
                  - regex: 'indexOf\(([^)]*)\) !== -1'
                    replacement: 'endsWith($1)'
                patterns:
                  - pattern-regex: 'indexOf\(.*\) !== -1'
            """.trimIndent() + "\n",
        )
        dir.resolve("rules/eval.xgrep.yaml").writeText(
            """
            rules:
              - id: js-eval-input
                languages: [javascript]
                severity: ERROR
                message: "eval of caller input"
                metadata: { category: security, confidence: HIGH }
                fix-hint: "Parse the input with JSON.parse instead of evaluating it."
                pattern: eval(${'$'}X)
            """.trimIndent() + "\n",
        )
        dir.resolve("app.js").writeText("function f(name) {\n  return name.indexOf(\".png\") !== -1;\n}\n")
        dir.resolve("run.js").writeText("function g(x) {\n  return eval(x);\n}\n")
    }

    private fun script(name: String, body: String): Path {
        val path = dir.resolve(name)
        path.writeText("#!/bin/sh\n$body")
        path.toFile().setExecutable(true)
        return path
    }
}
