// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

/**
 * Reads a Mondoo space the way people have it to hand: its ID
 * (`friendly-nash-115619`), its MRN, or a console URL copied from the browser
 * (`…?spaceId=friendly-nash-115619`). Pure, so it is tested without the platform.
 */
object MondooSpace {

    private const val MRN_PREFIX = "//captain.api.mondoo.app/spaces/"
    private val ID = Regex("""[a-z0-9][a-z0-9-]{2,}""")

    /** The space's MRN, or null when [input] is not recognisably a space. */
    fun toMrn(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("//") && "/spaces/" in text) return text.trimEnd('/')
        Regex("""[?&]spaceId=([a-z0-9-]+)""").find(text)?.let { return MRN_PREFIX + it.groupValues[1] }
        Regex("""/spaces/([a-z0-9-]+)""").find(text)?.let { return MRN_PREFIX + it.groupValues[1] }
        return text.takeIf { ID.matches(it) }?.let { MRN_PREFIX + it }
    }

    /** The space's ID from its MRN, for display. */
    fun id(mrn: String): String = mrn.trimEnd('/').substringAfterLast('/')
}
