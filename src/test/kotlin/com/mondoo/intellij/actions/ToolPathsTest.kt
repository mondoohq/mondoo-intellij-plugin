// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ToolPathsTest {

    @Test
    fun `home is shown as a tilde, and only as a whole path segment`() {
        assertEquals("~/go/bin/mqlr", ToolPaths.display("/Users/chris/go/bin/mqlr", home = "/Users/chris"))
        assertEquals("/usr/local/bin/cnspec", ToolPaths.display("/usr/local/bin/cnspec", home = "/Users/chris"))
        assertEquals("/Users/christina/bin/x", ToolPaths.display("/Users/christina/bin/x", home = "/Users/chris"))
    }

    /** Output captured from each tool's `version` command on 2026-10-06. */
    @Test
    fun `version is read from what the tools print`() {
        assertEquals("0.1.0", ToolPaths.version("xgrep 0.1.0 (commit: none, built: unknown)"))
        assertEquals("14.3.0", ToolPaths.version("cnspec 14.3.0 (12d02f80, 2026-10-06T09:21:10Z)"))
        assertEquals("0.58.0-rc.1", ToolPaths.version("xgrep 0.58.0-rc.1"))
        assertNull(ToolPaths.version("Error: unknown command \"version\" for \"cli\""))
    }
}
