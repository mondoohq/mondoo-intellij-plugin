// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
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

/**
 * The text of [diagnostic], read through the platform rather than from lsp4j.
 *
 * `Diagnostic.getMessage()` is not binary-stable across the IDEs this plugin claims:
 * 2026.1 bundles lsp4j 0.24, where it returns `String`, and 2026.3 bundles lsp4j 1.0,
 * where it returns `Either<String, MarkupContent>`. A direct call compiled against
 * one is a `NoSuchMethodError` on the other, and the Marketplace verifier rejects the
 * release for it. [LspDiagnosticsSupport.getMessage] returns `String` in both and does
 * the unwrapping itself, so going through it keeps a single build working everywhere.
 */
internal fun messageOf(diagnostic: Diagnostic): String = platformDiagnostics.getMessage(diagnostic)

private val platformDiagnostics = LspDiagnosticsSupport()

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
