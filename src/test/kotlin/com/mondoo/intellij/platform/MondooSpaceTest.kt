// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MondooSpaceTest {

    private val mrn = "//captain.api.mondoo.app/spaces/friendly-nash-115619"

    @Test
    fun `a space is read from its id, its MRN or a console URL`() {
        assertEquals(mrn, MondooSpace.toMrn("friendly-nash-115619"))
        assertEquals(mrn, MondooSpace.toMrn(" $mrn/ "))
        assertEquals(mrn, MondooSpace.toMrn("https://console.mondoo.com/space/overview?spaceId=friendly-nash-115619"))
        assertEquals("friendly-nash-115619", MondooSpace.id(mrn))
    }

    @Test
    fun `anything else is not a space`() {
        assertNull(MondooSpace.toMrn(""))
        assertNull(MondooSpace.toMrn("Friendly Nash"))
        assertNull(MondooSpace.toMrn("//captain.api.mondoo.app/organizations/youthful-meitner-435985"))
    }
}
