// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The array under [name], or nothing.
 *
 * The scanners are written in Go, where an empty list is often a nil slice and
 * serializes as `null`, not `[]`: a project with no dependencies comes back as
 * `"packages": null`. Gson's getAsJsonArray throws on a JSON null, which once
 * turned "no dependencies" into "the report could not be read".
 */
internal fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
