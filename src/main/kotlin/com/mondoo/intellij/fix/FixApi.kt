// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The `xgrep.fix.v1` messages the plugin reads, decoded from the server's JSON.
 *
 * Mirrors `proto/xgrep/fix/v1/fix.proto` in the xgrep repository; that file is the
 * source of truth. Protobuf JSON leaves default values out — false, 0, "" and empty
 * lists are simply absent — so every field here has a default, and decoding never
 * fails on a missing key. Field names are the lowerCamelCase protobuf JSON uses.
 *
 * Pure: Gson only, no platform types.
 */
enum class FixTier(val wire: String, val label: String) {
    NONE("FIX_TIER_UNSPECIFIED", "No fix"),
    DETERMINISTIC("FIX_TIER_DETERMINISTIC", "Deterministic"),
    ASSISTED("FIX_TIER_ASSISTED", "Agent-assisted"),
    ADVISORY("FIX_TIER_ADVISORY", "Advisory"),
    ECOSYSTEM("FIX_TIER_ECOSYSTEM", "Dependency upgrade"),
    ;

    companion object {
        fun fromWire(value: String?): FixTier = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

enum class TriageStatus(val wire: String, val label: String) {
    UNREVIEWED("TRIAGE_STATUS_UNSPECIFIED", "Unreviewed"),
    TRUE_POSITIVE("TRIAGE_STATUS_TRUE_POSITIVE", "True positive"),
    FALSE_POSITIVE("TRIAGE_STATUS_FALSE_POSITIVE", "False positive"),
    NEEDS_REVIEW("TRIAGE_STATUS_NEEDS_REVIEW", "Needs review"),
    ;

    companion object {
        fun fromWire(value: String?): TriageStatus = entries.firstOrNull { it.wire == value } ?: UNREVIEWED
    }
}

data class Triage(
    val status: TriageStatus,
    val rationale: String = "",
    val reviewedBy: String = "",
    val reviewedAt: String = "",
)

data class Contract(
    val strategy: String = "",
    val canonicalFix: String = "",
    val recognizedSanitizers: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val function: String = "",
)

/** What a fix run did with a finding. [status] and [reason] use `xgrep fix --json`'s words. */
data class Outcome(
    val fingerprint: String,
    val ruleId: String = "",
    val path: String = "",
    val status: String = "",
    val reason: String = "",
    val detail: String = "",
    val tier: FixTier = FixTier.NONE,
    val diff: String = "",
) {
    val applied: Boolean get() = status == "applied"
    val rejected: Boolean get() = status == "rejected"
}

data class FixFinding(
    val fingerprint: String,
    val ruleId: String = "",
    val title: String = "",
    val message: String = "",
    val severity: String = "",
    val confidence: String = "",
    val path: String = "",
    val absolutePath: String = "",
    /** 1-based, as xgrep reports it. */
    val startLine: Int = 0,
    val startColumn: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val lines: String = "",
    val tier: FixTier = FixTier.NONE,
    val fixable: Boolean = false,
    val stale: Boolean = false,
    val triage: Triage? = null,
    val hint: String = "",
    val contract: Contract? = null,
    val outcome: Outcome? = null,
) {
    /** Most severe first, as the terminal UI sorts. */
    val severityRank: Int
        get() = when (severity.uppercase()) {
            "CRITICAL" -> 4
            "ERROR", "HIGH" -> 3
            "WARNING", "MEDIUM" -> 2
            "INFO", "LOW" -> 1
            else -> 0
        }
}

data class AgentInfo(val name: String = "", val commandLine: String = "", val available: Boolean = false)

data class ServerInfo(
    val version: String = "",
    val commit: String = "",
    val projectRoot: String = "",
    val agent: AgentInfo = AgentInfo(),
)

data class FindingList(val findings: List<FixFinding>, val cachePresent: Boolean)

data class Preview(
    val accepted: Boolean = false,
    val reason: String = "",
    val detail: String = "",
    val diff: String = "",
    val originalContent: String = "",
    val patchedContent: String = "",
)

data class PullRequestResult(
    val noun: String = "pull request",
    val branch: String = "",
    val title: String = "",
    val body: String = "",
    val pushed: Boolean = false,
    val opened: Boolean = false,
    val url: String = "",
    val pushError: String = "",
)

/** One message of the RunFix stream. */
sealed interface RunFixEvent {
    data class Progress(val message: String, val completed: Int, val total: Int) : RunFixEvent
    data class AgentStarted(val agent: AgentInfo, val findingCount: Int) : RunFixEvent
    data class AgentOutput(val text: String) : RunFixEvent
    data class FilesChanged(val paths: List<String>) : RunFixEvent
    data class OutcomeReported(val outcome: Outcome) : RunFixEvent
    data class Done(val applied: Int, val rejected: Int, val skipped: Int, val cancelled: Boolean) : RunFixEvent

    /** A message from a newer server this client does not know. Ignored, not an error. */
    data object Unknown : RunFixEvent
}

object FixApi {

    fun serverInfo(obj: JsonObject) = ServerInfo(
        version = obj.str("version"),
        commit = obj.str("commit"),
        projectRoot = obj.str("projectRoot"),
        agent = obj.obj("agent")?.let(::agent) ?: AgentInfo(),
    )

    fun findingList(obj: JsonObject) = FindingList(
        findings = obj.arr("findings").mapNotNull { (it as? JsonObject)?.let(::finding) },
        cachePresent = obj.bool("cachePresent"),
    )

    fun finding(obj: JsonObject): FixFinding {
        val start = obj.obj("start")
        val end = obj.obj("end")
        return FixFinding(
            fingerprint = obj.str("fingerprint"),
            ruleId = obj.str("ruleId"),
            title = obj.str("title"),
            message = obj.str("message"),
            severity = obj.str("severity"),
            confidence = obj.str("confidence"),
            path = obj.str("path"),
            absolutePath = obj.str("absolutePath"),
            startLine = start?.int("line") ?: 0,
            startColumn = start?.int("column") ?: 0,
            endLine = end?.int("line") ?: 0,
            endColumn = end?.int("column") ?: 0,
            lines = obj.str("lines"),
            tier = FixTier.fromWire(obj.str("tier")),
            fixable = obj.bool("fixable"),
            stale = obj.bool("stale"),
            triage = obj.obj("triage")?.let {
                Triage(
                    status = TriageStatus.fromWire(it.str("status")),
                    rationale = it.str("rationale"),
                    reviewedBy = it.str("reviewedBy"),
                    reviewedAt = it.str("reviewedAt"),
                )
            },
            hint = obj.str("hint"),
            contract = obj.obj("contract")?.let {
                Contract(
                    strategy = it.str("strategy"),
                    canonicalFix = it.str("canonicalFix"),
                    recognizedSanitizers = it.strings("recognizedSanitizers"),
                    acceptanceCriteria = it.strings("acceptanceCriteria"),
                    function = it.str("function"),
                )
            },
            outcome = obj.obj("outcome")?.let(::outcome),
        )
    }

    fun outcome(obj: JsonObject) = Outcome(
        fingerprint = obj.str("fingerprint"),
        ruleId = obj.str("ruleId"),
        path = obj.str("path"),
        status = obj.str("status"),
        reason = obj.str("reason"),
        detail = obj.str("detail"),
        tier = FixTier.fromWire(obj.str("tier")),
        diff = obj.str("diff"),
    )

    fun preview(obj: JsonObject) = Preview(
        accepted = obj.bool("accepted"),
        reason = obj.str("reason"),
        detail = obj.str("detail"),
        diff = obj.str("diff"),
        originalContent = obj.str("originalContent"),
        patchedContent = obj.str("patchedContent"),
    )

    fun pullRequest(obj: JsonObject) = PullRequestResult(
        noun = obj.str("noun").ifBlank { "pull request" },
        branch = obj.str("branch"),
        title = obj.str("title"),
        body = obj.str("body"),
        pushed = obj.bool("pushed"),
        opened = obj.bool("opened"),
        url = obj.str("url"),
        pushError = obj.str("pushError"),
    )

    fun runFixEvent(obj: JsonObject): RunFixEvent {
        obj.obj("progress")?.let {
            return RunFixEvent.Progress(it.str("message"), it.int("completed"), it.int("total"))
        }
        obj.obj("agentStarted")?.let {
            return RunFixEvent.AgentStarted(it.obj("agent")?.let(::agent) ?: AgentInfo(), it.int("findingCount"))
        }
        obj.obj("agentOutput")?.let { return RunFixEvent.AgentOutput(it.str("text")) }
        obj.obj("filesChanged")?.let { return RunFixEvent.FilesChanged(it.strings("paths")) }
        obj.obj("outcome")?.let { return RunFixEvent.OutcomeReported(outcome(it)) }
        obj.obj("done")?.let {
            return RunFixEvent.Done(it.int("applied"), it.int("rejected"), it.int("skipped"), it.bool("cancelled"))
        }
        return RunFixEvent.Unknown
    }

    private fun agent(obj: JsonObject) = AgentInfo(
        name = obj.str("name"),
        commandLine = obj.str("commandLine"),
        available = obj.bool("available"),
    )

    // --- requests ---------------------------------------------------------------

    fun fingerprintRequest(fingerprint: String) = JsonObject().apply { addProperty("fingerprint", fingerprint) }

    fun setTriageRequest(fingerprint: String, status: TriageStatus, rationale: String) = JsonObject().apply {
        addProperty("fingerprint", fingerprint)
        addProperty("status", status.wire)
        if (rationale.isNotBlank()) addProperty("rationale", rationale)
    }

    fun runFixRequest(fingerprints: Collection<String>) = JsonObject().apply {
        add("fingerprints", JsonArray().apply { fingerprints.forEach { add(it) } })
    }

    // --- tolerant accessors: protobuf JSON omits defaults --------------------------

    private fun JsonObject.field(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.str(name: String): String =
        field(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.bool(name: String): Boolean =
        field(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    // Gson reads every JSON number as a double-backed primitive; asInt truncates.
    private fun JsonObject.int(name: String): Int =
        field(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0

    private fun JsonObject.obj(name: String): JsonObject? = field(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(name: String): List<JsonElement> =
        field(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun JsonObject.strings(name: String): List<String> =
        arr(name).mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
}
