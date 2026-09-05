// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.mondoo.intellij.search.SearchOffsets
import com.mondoo.intellij.search.StructuralReplace
import com.mondoo.intellij.search.XgrepSearchMatch
import com.mondoo.intellij.search.XgrepSearchService
import com.mondoo.intellij.util.MondooDialogs
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
        if (!requireServer(e)) return
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

        val language = askLanguage(project, "Search Code") ?: return

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
}

/** Only offers languages xgrep can actually parse. */
private fun askLanguage(project: Project, title: String): String? {
    val languages = XgrepLanguages.supportedLanguageIds.sorted()
    val current = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ?.let { XgrepLanguages.languageIdFor(it.name) }
        ?.takeIf { it.isNotEmpty() }
    return MondooDialogs.choose(
        project,
        "Which language?",
        title,
        languages,
        initial = current ?: languages.first(),
    )?.let(languages::getOrNull)
}

/**
 * Renders matches in the Find tool window and returns the view, so a caller that has
 * something to offer — Replace All — can put a button on it.
 *
 * Shared by search and replace on purpose: the preview a replace shows must be built
 * the same way as the search it came from, or the ranges highlighted and the ranges
 * rewritten could differ.
 */
private fun showUsages(
    project: Project,
    pattern: String,
    matches: List<XgrepSearchMatch>,
): UsageView? {
    val usages = matches.mapNotNull { match ->
        val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(match.path)) ?: return@mapNotNull null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
        val range = SearchOffsets.rangeIn(document, match) ?: return@mapNotNull null
        UsageInfo2UsageAdapter(UsageInfo(psi, range.first, range.last))
    }

    val presentation = UsageViewPresentation().apply {
        tabText = "xgrep: $pattern"
        toolwindowTitle = "xgrep Search"
        isOpenInNewTab = true
        codeUsagesString = "Structural matches"
    }
    return UsageViewManager.getInstance(project)
        .showUsages(emptyArray(), usages.toTypedArray(), presentation)
}

/** Turns the current pattern into a reusable xgrep rule, opened as YAML. */
class ExportSearchRuleAction : XgrepScanActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        if (!requireServer(e)) return
        val project = e.project ?: return
        val pattern = Messages.showInputDialog(
            project,
            "Structural pattern to export as a rule",
            "Export Search as Rule",
            null,
            "eval(\$X)",
            null,
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
                        Messages.showWarningDialog(
                            project,
                            "The scanner did not return a rule.",
                            "Export Search as Rule",
                        )
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

/**
 * Structural search and replace, previewed before anything is written.
 *
 * The pattern language is the scanner's, so `eval($X)` → `safeEval($X)` rewrites every
 * call regardless of what `$X` is — which is the point, and also why this shows the
 * matches first and puts Replace All behind a button rather than doing it on the way
 * out of a dialog.
 *
 * The whole replace is one undoable command. Multi-file replaces that undo file by
 * file are worse than no replace at all: the state after three undos is one nobody
 * asked for and nobody can name.
 */
class ReplaceCodeAction : XgrepScanActionBase() {

    override fun actionPerformed(e: AnActionEvent) {
        if (!requireServer(e)) return
        val project = e.project ?: return

        val pattern = Messages.showInputDialog(
            project,
            "Structural pattern to find. \$X binds any expression, ... matches anything.",
            "Replace Code",
            null,
            "eval(\$X)",
            null,
        )?.trim().orEmpty()
        if (pattern.isEmpty()) return

        val replacement = Messages.showInputDialog(
            project,
            "Replace matches with. Metavariables from the pattern may be reused.",
            "Replace Code",
            null,
            "safeEval(\$X)",
            null,
        )?.trim().orEmpty()
        if (replacement.isEmpty()) return

        val language = askLanguage(project, "Replace Code") ?: return

        object : Task.Backgroundable(project, "Searching with xgrep", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val matches = XgrepSearchService.getInstance(project).search(pattern, language, replacement)
                ApplicationManager.getApplication().invokeLater {
                    when {
                        matches == null -> Messages.showWarningDialog(
                            project,
                            "The xgrep scanner did not answer. It may not be running.",
                            "Replace Code",
                        )
                        matches.isEmpty() -> Messages.showInfoMessage(
                            project,
                            "No structural matches for $pattern",
                            "Replace Code",
                        )
                        else -> previewReplacements(project, pattern, replacement, matches)
                    }
                }
            }
        }.queue()
    }

    private fun previewReplacements(
        project: Project,
        pattern: String,
        replacement: String,
        matches: List<XgrepSearchMatch>,
    ) {
        val plan = StructuralReplace.plan(matches)
        if (plan.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "The scanner matched $pattern but returned no replacement text.",
                "Replace Code",
            )
            return
        }

        val view = showUsages(project, "$pattern → $replacement", plan) ?: return
        val skipped = StructuralReplace.skipped(matches)

        view.addButtonToLowerPane(
            { applyAll(project, plan, "$pattern → $replacement", skipped) },
            if (skipped > 0) "Replace All (${plan.size}, $skipped nested skipped)" else "Replace All (${plan.size})",
        )
    }

    /**
     * Applies every replacement in one write command.
     *
     * The plan arrives last-first so that each edit leaves the offsets of the ones
     * still to come untouched; see [StructuralReplace]. Ranges are recomputed against
     * the live document rather than trusted from the scan, because the file may have
     * been edited while the preview was open.
     */
    private fun applyAll(
        project: Project,
        plan: List<XgrepSearchMatch>,
        commandName: String,
        skipped: Int,
    ) {
        val files = plan.mapNotNull { virtualFile(it.path) }.distinct()
        if (!ReadonlyStatusHandler.ensureFilesWritable(project, *files.toTypedArray())) return

        var applied = 0
        var stale = 0
        WriteCommandAction.runWriteCommandAction(project, "Replace $commandName", null, {
            val documents = FileDocumentManager.getInstance()
            plan.forEach { match ->
                val file = virtualFile(match.path) ?: return@forEach
                val document = documents.getDocument(file) ?: return@forEach
                val range = SearchOffsets.rangeIn(document, match)
                if (range == null) {
                    stale++
                    return@forEach
                }
                document.replaceString(range.first, range.last, match.replacement.orEmpty())
                applied++
            }
        })

        val notes = listOfNotNull(
            "$applied replacement(s) applied".takeIf { applied > 0 },
            "$skipped nested match(es) skipped".takeIf { skipped > 0 },
            "$stale match(es) no longer fit the file".takeIf { stale > 0 },
        )
        Messages.showInfoMessage(project, notes.joinToString("\n"), "Replace Code")
    }

    private fun virtualFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))
}
