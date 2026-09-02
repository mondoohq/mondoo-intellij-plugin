// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.mondoo.intellij.search.XgrepSearchMatch
import com.mondoo.intellij.search.XgrepSearchService
import com.mondoo.intellij.util.XgrepLanguages
import java.nio.file.Path

/**
 * Structural code search, rendered in the Find tool window.
 *
 * Results go to a `UsageView` rather than a bespoke sidebar tree. That is where
 * IntelliJ users already expect search results — Structural Search and Replace,
 * the feature `xgrep.search` most resembles, puts them there — and it supplies
 * grouping, a preview pane, occurrence navigation, multiple concurrent tabs and a
 * rerun button for free. It also removes an entire state machine: closing the tab
 * replaces the VS Code extension's clear-search command and its
 * `mondoo.xgrepHasSearch` context key.
 */
class SearchCodeAction : XgrepScanActionBase() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pattern = Messages.showInputDialog(
            project,
            "Structural pattern. \$X binds any expression, ... matches anything.",
            "Search Code",
            null,
            "eval(\$X)",
            null,
        )?.trim().orEmpty()
        if (pattern.isEmpty()) return

        val language = askLanguage(project) ?: return

        object : Task.Backgroundable(project, "Searching with xgrep", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val matches = XgrepSearchService.getInstance(project).search(pattern, language)
                ApplicationManager.getApplication().invokeLater {
                    when {
                        matches == null -> Messages.showWarningDialog(
                            project,
                            "The xgrep scanner did not answer. It may not be running.",
                            "Search Code",
                        )
                        matches.isEmpty() -> Messages.showInfoMessage(
                            project,
                            "No structural matches for $pattern",
                            "Search Code",
                        )
                        else -> showUsages(project, pattern, matches)
                    }
                }
            }
        }.queue()
    }

    /** Only offers languages xgrep can actually parse. */
    private fun askLanguage(project: Project): String? {
        val languages = XgrepLanguages.supportedLanguageIds.sorted()
        val current = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?.let { XgrepLanguages.languageIdFor(it.name) }
            ?.takeIf { it.isNotEmpty() }
        val index = Messages.showChooseDialog(
            project,
            "Which language?",
            "Search Code",
            null,
            languages.toTypedArray(),
            current ?: languages.first(),
        )
        return languages.getOrNull(index)
    }

    private fun showUsages(project: Project, pattern: String, matches: List<XgrepSearchMatch>) {
        val usages = matches.mapNotNull { match ->
            val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(match.path)) ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
                ?: return@mapNotNull null
            if (match.line >= document.lineCount) return@mapNotNull null
            val start = document.getLineStartOffset(match.line) + match.column
            val end = (document.getLineStartOffset(match.endLine) + match.endColumn)
                .coerceIn(start, document.textLength)
            UsageInfo2UsageAdapter(UsageInfo(psi, start, end))
        }

        val presentation = UsageViewPresentation().apply {
            tabText = "xgrep: $pattern"
            toolwindowTitle = "xgrep Search"
            isOpenInNewTab = true
            codeUsagesString = "Structural matches"
        }
        UsageViewManager.getInstance(project)
            .showUsages(emptyArray(), usages.toTypedArray(), presentation)
    }
}

/** Turns the current pattern into a reusable xgrep rule, opened as YAML. */
class ExportSearchRuleAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pattern = Messages.showInputDialog(
            project, "Structural pattern to export as a rule", "Export Search as Rule", null, "eval(\$X)", null,
        )?.trim().orEmpty()
        if (pattern.isEmpty()) return
        val language = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?.let { XgrepLanguages.languageIdFor(it.name) }
            ?.takeIf { it.isNotEmpty() } ?: "python"

        object : Task.Backgroundable(project, "Exporting xgrep rule", true) {
            override fun run(indicator: ProgressIndicator) {
                val yaml = XgrepSearchService.getInstance(project).exportRule(pattern, language)
                ApplicationManager.getApplication().invokeLater {
                    if (yaml.isNullOrBlank()) {
                        Messages.showWarningDialog(project, "The scanner did not return a rule.", "Export Search as Rule")
                        return@invokeLater
                    }
                    // Resolved by extension rather than referencing YAMLFileType: the
                    // YAML plugin is not bundled in every IDE.
                    val type = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                        .getFileTypeByExtension("yaml")
                    val file = LightVirtualFile("xgrep-rule.yaml", type, yaml)
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        }.queue()
    }
}
