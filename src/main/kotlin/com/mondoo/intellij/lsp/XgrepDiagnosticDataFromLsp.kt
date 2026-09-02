package com.mondoo.intellij.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Diagnostic

/**
 * Adapts lsp4j's `Diagnostic.data` to [XgrepDiagnosticData].
 *
 * Kept apart from [XgrepDiagnosticData] itself so the decoding rules stay free of
 * lsp4j and Gson and can be unit-tested without either.
 *
 * lsp4j deserializes `data` with Gson, so it arrives as a [JsonElement] rather than
 * a typed object. Decoding is total: older xgrep builds omit `data` entirely, and a
 * future one may add fields, neither of which may throw inside the annotator.
 */
internal fun xgrepDataOf(diagnostic: Diagnostic): XgrepDiagnosticData? =
    runCatching {
        when (val raw: Any? = diagnostic.data) {
            null -> null
            is JsonObject -> XgrepDiagnosticData.fromMap(raw.toValueMap())
            is Map<*, *> -> XgrepDiagnosticData.fromMap(raw)
            else -> null
        }
    }.getOrNull()

/** Shallow JSON → Kotlin values; enough for the flat payload xgrep sends. */
private fun JsonObject.toValueMap(): Map<String, Any?> =
    entrySet().associate { (key, value) -> key to value.toValue() }

private fun JsonElement.toValue(): Any? = when {
    isJsonNull -> null
    isJsonArray -> (this as JsonArray).map { it.toValue() }
    isJsonPrimitive -> (this as JsonPrimitive).let {
        when {
            it.isBoolean -> it.asBoolean
            it.isNumber -> it.asNumber
            else -> it.asString
        }
    }
    else -> null
}
