// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.mondoo.intellij.binary.XgrepBinaryService
import com.mondoo.intellij.settings.MondooSettings
import com.mondoo.intellij.util.ProjectTrust
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The project's `xgrep fix serve`: the fix session's engine.
 *
 * xgrep owns everything about fixing — the verify/apply harness, the coding-agent
 * prompts and invocation, the pull request — so the IDE and the `xgrep fix`
 * terminal UI behave the same. This service only runs the server and hands out a
 * client for it.
 *
 * The server is a child process, one per project, started on first use:
 * - its working directory is the project root;
 * - it listens on loopback and announces its port in one handshake line on stdout;
 * - every request carries a token generated here and passed in the environment;
 * - it exits when its stdin closes, so it never outlives the IDE, even after a crash.
 *
 * A server that died, or whose settings (binary, rules, agent) changed, is replaced
 * on the next [client] call.
 */
@Service(Service.Level.PROJECT)
class FixServer(private val project: Project) : Disposable {

    private val log = logger<FixServer>()
    private val lock = Any()

    @Volatile
    private var running: Running? = null

    /** Set by [dispose], which must not wait for [lock]: a start holds it for up to a minute. */
    @Volatile
    private var disposed = false

    private class Running(val process: Process, val client: FixClient, val config: Config)

    private data class Config(
        val binary: String,
        val rulesPath: String,
        val agent: String,
        val allFiles: Boolean,
        val mondooConfig: String?,
        val spaceMrn: String?,
    )

    /**
     * A client for a live server, starting one when needed. Blocks while the server
     * starts; call off the EDT.
     *
     * @throws FixServerUnavailable when the server cannot run here, with a message
     *   fit to show the user.
     */
    fun client(): FixClient = synchronized(lock) {
        if (disposed) throw FixServerUnavailable("The project is closing.")
        if (!ProjectTrust.isTrusted(project)) {
            throw FixServerUnavailable("Trust this project to fix findings in it.")
        }
        val binary = XgrepBinaryService.getInstance().resolvedBinaryOrNull()
            ?: throw FixServerUnavailable("The xgrep scanner is not installed. Use Set Up Scanner first.")
        val settings = MondooSettings.getInstance().state
        val config = Config(
            binary.toString(),
            settings.xgrepRulesPath.orEmpty(),
            settings.xgrepFixAgent.orEmpty(),
            settings.xgrepScanUncommitted,
            com.mondoo.intellij.settings.MondooEnvironment.configPath(),
            com.mondoo.intellij.settings.MondooEnvironment.spaceMrn(),
        )

        running?.let { current ->
            if (current.process.isAlive && current.config == config) return current.client
            stop(current)
            running = null
        }
        val started = start(config)
        if (disposed) {
            // Disposed while starting: nobody will stop this server but us.
            stop(started)
            throw FixServerUnavailable("The project is closing.")
        }
        running = started
        return started.client
    }

    /** Stops the server; the next [client] call starts a fresh one. */
    fun restart() = synchronized(lock) {
        running?.let(::stop)
        running = null
    }

    private fun start(config: Config): Running {
        val base = project.basePath ?: throw FixServerUnavailable("This project has no directory on disk.")
        requireFixServe(config.binary)
        val token = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })

        val command = com.mondoo.intellij.settings.MondooEnvironment.commandLine(config.binary)
            .withWorkDirectory(base)
            .withCharset(Charsets.UTF_8)
            // The token goes in the environment: a command line is visible to every
            // process on the machine. xgrep unsets it before starting anything.
            .withEnvironment(TOKEN_ENV, token)
        if (config.rulesPath.isNotBlank()) command.addParameters("-f", config.rulesPath)
        command.addParameters("fix", "serve")
        if (config.agent.isNotBlank()) command.addParameters("--agent", config.agent)
        if (config.allFiles) command.addParameter("--all-files")
        config.spaceMrn?.let { command.addParameters("--scope-mrn", it) }

        val process = try {
            command.createProcess()
        } catch (e: Exception) {
            throw FixServerUnavailable("Could not start xgrep: ${e.message}")
        }

        // stderr is the server's JSON log; keep it in idea.log, and remember the
        // start of it so a failed start can say why.
        val stderr = StringBuffer()
        Thread({
            process.errorStream.bufferedReader().forEachLine { line ->
                if (stderr.length < STDERR_KEEP_CHARS) stderr.append(line).append('\n')
                log.debug("xgrep fix serve: $line")
            }
        }, "xgrep fix serve stderr").apply { isDaemon = true }.start()

        val handshake = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().readLine()
        }
        val line = try {
            handshake.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            process.destroyForcibly()
            throw FixServerUnavailable("xgrep fix serve did not start within $HANDSHAKE_TIMEOUT_SECONDS seconds.")
        } catch (e: Exception) {
            process.destroyForcibly()
            throw FixServerUnavailable("xgrep fix serve failed to start: ${e.message}")
        }

        val hs = line?.let(FixHandshake::parse)
        if (hs == null) {
            process.waitFor(2, TimeUnit.SECONDS)
            process.destroyForcibly()
            throw FixServerUnavailable(startFailureMessage(stderr.toString()))
        }
        // Drain stdout so the server can never block on a full pipe.
        Thread({
            runCatching { process.inputStream.transferTo(java.io.OutputStream.nullOutputStream()) }
        }, "xgrep fix serve stdout")
            .apply { isDaemon = true }.start()

        log.info("xgrep fix serve ${hs.version} listening on ${hs.addr} for ${project.name}")
        return Running(process, FixClient("http://${hs.addr}", token), config)
    }

    /**
     * Refuses an xgrep without `fix serve` before starting it.
     *
     * An older xgrep does not reject the unknown subcommand: `fix` takes a path, so
     * it reads "serve" as one and runs the fix orchestration on it. Probing the help
     * text is cheap and needs no version parsing, so it also works for dev builds.
     */
    private fun requireFixServe(binary: String) {
        val probe = runCatching {
            com.intellij.execution.util.ExecUtil.execAndGetOutput(
                GeneralCommandLine(binary, "fix", "serve", "--help").withCharset(Charsets.UTF_8),
                PROBE_TIMEOUT_MS,
            )
        }.getOrNull()
        if (probe == null || probe.exitCode != 0 || !probe.stdout.contains(FIX_SERVE_HELP_MARKER)) {
            throw FixServerUnavailable(
                "This xgrep is too old for fixing in the IDE. Update it to $MIN_XGREP_VERSION or later.",
            )
        }
    }

    private fun startFailureMessage(stderr: String): String =
        if (stderr.isNotBlank()) {
            "xgrep fix serve failed to start: ${stderr.lineSequence().first().take(300)}"
        } else {
            "xgrep fix serve failed to start."
        }

    private fun stop(r: Running) {
        // Closing stdin is the polite stop: the server cancels running work, drains
        // and exits. Force it only if it does not.
        runCatching { r.process.outputStream.close() }
        if (!r.process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) r.process.destroyForcibly()
    }

    override fun dispose() {
        // Runs on the EDT at project close, so it never waits: not for the lock a
        // starting server holds, and not for the process to exit.
        disposed = true
        val current = running.also { running = null } ?: return
        runCatching { current.process.outputStream.close() }
        current.process.onExit().orTimeout(STOP_GRACE_SECONDS, TimeUnit.SECONDS)
            .exceptionally {
                current.process.destroyForcibly()
                null
            }
    }

    companion object {
        const val TOKEN_ENV = "XGREP_FIX_TOKEN"

        /** The first xgrep with `fix serve`. */
        const val MIN_XGREP_VERSION = "0.58.0"

        /** A phrase only `xgrep fix serve --help` prints. */
        private const val FIX_SERVE_HELP_MARKER = "xgrep.fix.v1"

        private const val PROBE_TIMEOUT_MS = 15_000
        private const val TOKEN_BYTES = 32
        private const val HANDSHAKE_TIMEOUT_SECONDS = 30L
        private const val STOP_GRACE_SECONDS = 12L
        private const val STDERR_KEEP_CHARS = 4000

        fun getInstance(project: Project): FixServer = project.service()
    }
}

/** The server cannot run here; [message] says why, for the user. */
class FixServerUnavailable(message: String) : RuntimeException(message)

/** The line `xgrep fix serve` prints once it is listening. Pure. */
data class FixHandshake(val addr: String, val api: String, val version: String) {
    companion object {
        const val API = "xgrep.fix.v1"

        /** Null unless the line is a handshake for the API this plugin speaks, on loopback. */
        fun parse(line: String): FixHandshake? {
            val obj = ConnectProtocol.parseObject(line.trim()) ?: return null
            val hs = FixHandshake(
                addr = obj.get("addr")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                api = obj.get("api")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            )
            if (hs.api != API) return null
            // The token rides on every request; never send it anywhere but loopback.
            val host = hs.addr.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            if (host != "127.0.0.1" && host != "::1") return null
            return hs
        }
    }
}
