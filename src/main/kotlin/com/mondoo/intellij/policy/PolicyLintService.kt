// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.mondoo.intellij.binary.CnspecBinaryService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs `cnspec policy lint` and holds the results.
 *
 * Complementary to the language server rather than a duplicate: on a bundle with a
 * compile error and missing tags, the server reported one diagnostic and the linter
 * seven. The server checks the MQL; the linter also checks policy hygiene — required
 * tags, asset filters, unused queries. Both are worth having, so both run.
 *
 * Lint runs on demand and on save rather than per keystroke: it shells out and
 * re-parses the whole bundle, which is too heavy to do while typing, and the server
 * already covers the typing case.
 */
@Service(Service.Level.PROJECT)
class PolicyLintService(private val project: Project) {

    private val log = Logger.getInstance(PolicyLintService::class.java)

    /** Findings by absolute bundle path. */
    private val byBundle = ConcurrentHashMap<String, List<LintFinding>>()

    fun findingsFor(bundlePath: String): List<LintFinding> = byBundle[bundlePath].orEmpty()

    fun allFindings(): List<LintFinding> = byBundle.values.flatten()

    /**
     * Lints one bundle. Returns null when cnspec is unavailable or its output could
     * not be read — distinct from an empty list, which means the bundle is clean.
     */
    fun lint(bundle: Path): List<LintFinding>? {
        val binary = CnspecBinaryService.getInstance().resolvedBinaryOrNull() ?: return null

        val command = GeneralCommandLine(binary.toString())
            .withParameters("policy", "lint", bundle.fileName.toString(), "-o", "sarif")
            // From the bundle's directory, so the paths it reports are relative to it.
            .withWorkDirectory(bundle.parent?.toString())

        val output = CapturingProcessHandler(command).runProcess(LINT_TIMEOUT_MS, true)

        // cnspec exits non-zero when it finds problems but still writes valid SARIF,
        // so the exit code says nothing about whether there is a report to read.
        val findings = LintReport.parse(output.stdout)
        if (findings == null) {
            log.warn("could not parse lint output for $bundle: ${output.stderr.take(300)}")
            return null
        }

        byBundle[bundle.toString()] = findings
        publishToFindingsView(bundle, findings)
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(TOPIC).lintFindingsChanged(bundle.toString(), findings)
        }
        return findings
    }

    /**
     * Mirrors lint findings into the shared findings view.
     *
     * The store takes a URI and a list, deliberately without caring what produced
     * them, so policy-lint findings sit in the same tool window as code findings
     * rather than needing a second view that behaves almost the same.
     *
     * Paths are made project-relative to match what the code scanner publishes; the
     * linter reports them relative to the bundle's own directory.
     */
    private fun publishToFindingsView(bundle: Path, findings: List<LintFinding>) {
        val base = project.basePath?.let(Path::of)
        val relative = runCatching { base?.relativize(bundle)?.toString() }.getOrNull()
            ?: bundle.fileName.toString()

        com.mondoo.intellij.findings.XgrepFindingsStore.getInstance(project).update(
            bundle.toUri().toString(),
            findings.map {
                com.mondoo.intellij.findings.Finding(
                    path = relative,
                    line = it.line,
                    column = it.column,
                    ruleId = it.ruleId.ifBlank { "cnspec-lint" },
                    message = it.message,
                    severity = when (it.severity) {
                        LintSeverity.ERROR -> com.mondoo.intellij.findings.FindingSeverity.HIGH
                        LintSeverity.WARNING -> com.mondoo.intellij.findings.FindingSeverity.MEDIUM
                        LintSeverity.INFO -> com.mondoo.intellij.findings.FindingSeverity.LOW
                    },
                )
            },
        )
    }

    fun clear() {
        byBundle.clear()
    }

    fun interface Listener {
        fun lintFindingsChanged(bundlePath: String, findings: List<LintFinding>)
    }

    companion object {
        private const val LINT_TIMEOUT_MS = 60_000

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Mondoo policy lint", Listener::class.java)

        @JvmStatic
        fun getInstance(project: Project): PolicyLintService = project.service()
    }
}
