// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepScanScopeTest {

    @Test
    fun `star matches within a single path segment`() {
        assertTrue(GlobMatcher.matches("*.min.js", "app.min.js"))
        assertTrue(GlobMatcher.matches("*.min.js", "src/vendor/app.min.js"))
        assertFalse(GlobMatcher.matches("src/*.js", "src/nested/app.js"))
    }

    @Test
    fun `double star spans segments`() {
        assertTrue(GlobMatcher.matches("src/generated/**", "src/generated/api/client.ts"))
        assertTrue(GlobMatcher.matches("src/generated/**", "src/generated/a.ts"))
        assertFalse(GlobMatcher.matches("src/generated/**", "src/handwritten/a.ts"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        assertTrue(GlobMatcher.matches("v?.js", "v1.js"))
        assertFalse(GlobMatcher.matches("v?.js", "v10.js"))
    }

    @Test
    fun `a pattern without a slash matches any single segment anywhere`() {
        assertTrue(GlobMatcher.matches("vendor", "vendor/lib.go"))
        assertTrue(GlobMatcher.matches("vendor", "third_party/vendor/lib.go"))
        assertFalse(GlobMatcher.matches("vendor", "vendored/lib.go"))
    }

    @Test
    fun `exclude wins over include`() {
        val scope = XgrepScanScope(
            includePatterns = listOf("src/**"),
            excludePatterns = listOf("src/generated/**"),
        )
        assertTrue(scope.isScanned("src/app.ts"))
        assertFalse(scope.isScanned("src/generated/client.ts"))
        assertFalse(scope.isScanned("test/app.ts"))
    }

    @Test
    fun `an empty include list scans everything not excluded`() {
        val scope = XgrepScanScope(includePatterns = emptyList(), excludePatterns = listOf("vendor"))
        assertTrue(scope.isScanned("src/app.ts"))
        assertTrue(scope.isScanned("anything/else.go"))
        assertFalse(scope.isScanned("vendor/lib.go"))
    }

    @Test
    fun `broadening the scope is detected so the server can be restarted`() {
        val before = XgrepScanScope(emptyList(), listOf("vendor", "dist"))
        // Dropping an exclude broadens: previously skipped files must be rescanned.
        assertTrue(before.broadenedBy(XgrepScanScope(emptyList(), listOf("vendor"))))
        // Adding an exclude narrows: no restart needed.
        assertFalse(before.broadenedBy(XgrepScanScope(emptyList(), listOf("vendor", "dist", "out"))))
        // Dropping an include broadens.
        assertTrue(
            XgrepScanScope(listOf("src/**"), emptyList())
                .broadenedBy(XgrepScanScope(emptyList(), emptyList())),
        )
    }
}
