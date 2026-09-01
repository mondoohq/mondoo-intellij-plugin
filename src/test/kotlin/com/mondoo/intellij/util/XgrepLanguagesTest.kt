package com.mondoo.intellij.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XgrepLanguagesTest {

    @Test
    fun `maps the extensions xgrep scans`() {
        val expected = mapOf(
            "main.py" to "python",
            "types.pyi" to "python",
            "main.go" to "go",
            "App.java" to "java",
            "index.js" to "javascript",
            "bundle.cjs" to "javascript",
            "esm.mjs" to "javascript",
            "View.jsx" to "javascript",
            "index.ts" to "typescript",
            "Component.tsx" to "typescriptreact",
            "app.rb" to "ruby",
            "lib.rs" to "rust",
            "main.c" to "c",
            "header.h" to "c",
            "main.cpp" to "cpp",
            "util.cc" to "cpp",
            "Program.cs" to "csharp",
            "Main.kt" to "kotlin",
            "build.gradle.kts" to "kotlin",
            "App.scala" to "scala",
            "index.php" to "php",
            "init.lua" to "lua",
            "deploy.sh" to "shellscript",
            "profile.bash" to "shellscript",
            "index.html" to "html",
            "package.json" to "json",
            "config.yaml" to "yaml",
            "ci.yml" to "yaml",
        )
        expected.forEach { (fileName, languageId) ->
            assertEquals(languageId, XgrepLanguages.languageIdFor(fileName), fileName)
        }
    }

    @Test
    fun `matches extension-less file names`() {
        assertEquals("ruby", XgrepLanguages.languageIdFor("Rakefile"))
        assertEquals("ruby", XgrepLanguages.languageIdFor("Gemfile"))
    }

    @Test
    fun `is case insensitive`() {
        assertEquals("python", XgrepLanguages.languageIdFor("MAIN.PY"))
        assertEquals("ruby", XgrepLanguages.languageIdFor("rakefile"))
    }

    @Test
    fun `returns empty for unsupported files`() {
        listOf("README.md", "notes.txt", "image.png", "Makefile", "", "archive.tar.bz2")
            .forEach { assertEquals("", XgrepLanguages.languageIdFor(it), it) }
    }

    @Test
    fun `never emits a bare extension as a language id`() {
        // The platform default would send "cs", "kt", "rs", "sh", "yml" verbatim.
        listOf("Program.cs", "Main.kt", "lib.rs", "deploy.sh", "ci.yml").forEach {
            val id = XgrepLanguages.languageIdFor(it)
            assertFalse(id == it.substringAfterLast('.'), "leaked raw extension for $it")
        }
    }

    @Test
    fun `isSupported agrees with languageIdFor`() {
        assertTrue(XgrepLanguages.isSupported("main.go"))
        assertFalse(XgrepLanguages.isSupported("README.md"))
    }

    @Test
    fun `exposes exactly the documented language set`() {
        assertEquals(
            setOf(
                "python", "go", "java", "javascript", "typescript", "typescriptreact",
                "ruby", "rust", "c", "cpp", "csharp", "kotlin", "scala", "php", "lua",
                "shellscript", "html", "json", "yaml",
            ),
            XgrepLanguages.supportedLanguageIds,
        )
    }
}
