// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import java.nio.file.Path
import javax.swing.Icon

/**
 * Shows dependency vulnerabilities where the dependency is declared.
 *
 * The scan anchors them at the top of the manifest and the language server does not
 * scan manifests, so without this a vulnerable `requirements.txt` looked clean. Here:
 * - the manifest is marked as having problems, so the Project view shows it red;
 * - in an open manifest, each vulnerable declaration gets an error or warning
 *   underline, a scrollbar mark and a gutter icon listing its vulnerabilities;
 * - [UpgradeDependencyIntention] offers the upgrade on that line (Alt+Enter).
 */
@Service(Service.Level.PROJECT)
class ManifestHighlighter(private val project: Project) : Disposable {

    private val reported = mutableSetOf<VirtualFile>()
    private val log = com.intellij.openapi.diagnostic.logger<ManifestHighlighter>()

    init {
        val bus = project.messageBus.connect(this)
        bus.subscribe(
            DependencyReachabilityService.VULNERABILITIES_TOPIC,
            DependencyReachabilityService.VulnerabilitiesListener { onEdt(::refreshAll) },
        )
        bus.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (ManifestLocator.supports(file.name)) onEdt { highlight(file) }
                }
            },
        )
    }

    /** The vulnerable packages a manifest declares, by the path the scan recorded. */
    fun vulnerablePackages(file: VirtualFile): List<PackageVulnerabilities> =
        DependencyReachabilityService.getInstance(project).vulnerabilities().values
            .filter { pkg -> pkg.manifests.any { manifestFile(it) == file } }

    /**
     * The file a recorded manifest path names, resolved by the IDE. Comparing files
     * rather than path strings is what makes this work through symlinks (macOS
     * `/tmp` is `/private/tmp`) and on Windows separators.
     */
    private fun manifestFile(rel: String): VirtualFile? =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByNioFile(Path.of(it, rel)) }

    /** Marks every manifest with vulnerabilities, and every open one in the editor. EDT. */
    fun refreshAll() {
        if (project.isDisposed) return
        val withVulns = DependencyReachabilityService.getInstance(project).vulnerabilities().values
            .flatMap { it.manifests }.toSet()
            .mapNotNull(::manifestFile)
            .toSet()
        // The problem solver refuses the EDT; the editors need it. Split accordingly.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val wolf = WolfTheProblemSolver.getInstance(project)
            synchronized(reported) {
                (reported - withVulns).forEach { wolf.clearProblemsFromExternalSource(it, this) }
                withVulns.forEach { wolf.reportProblemsFromExternalSource(it, this) }
                log.debug(
                    "Mondoo: ${withVulns.count { wolf.isProblemFile(it) }} of ${withVulns.size} " +
                        "manifest(s) shown with problems: ${withVulns.joinToString { it.name }}",
                )
                reported.clear()
                reported += withVulns
            }
        }
        val open = FileEditorManager.getInstance(project).openFiles.filter { ManifestLocator.supports(it.name) }
        log.debug("Mondoo: refresh: ${withVulns.size} manifest(s) with vulnerabilities, ${open.size} open")
        open.forEach(::highlight)
    }

    /** Re-marks every open editor of [file]. EDT. */
    fun highlight(file: VirtualFile) {
        val packages = vulnerablePackages(file)
        FileEditorManager.getInstance(project).getEditors(file)
            .mapNotNull { (it as? TextEditor)?.editor }
            .forEach { editor -> mark(editor, file, packages) }
    }

    private fun mark(editor: Editor, file: VirtualFile, packages: List<PackageVulnerabilities>) {
        val markup = editor.markupModel
        markup.allHighlighters.filter { it.getUserData(MARK) == true }.forEach(markup::removeHighlighter)
        val text = editor.document.text
        val scheme = EditorColorsManager.getInstance().globalScheme
        var marked = 0
        packages.forEach { pkg ->
            val loc = ManifestLocator.find(file.name, text, pkg.name, pkg.version) ?: return@forEach
            val severe = (pkg.worst?.rank ?: 0) >= VulnSeverity.HIGH.rank
            val attributes = scheme.getAttributes(
                if (severe) CodeInsightColors.ERRORS_ATTRIBUTES else CodeInsightColors.WARNINGS_ATTRIBUTES,
            )
            val tooltip = tooltip(pkg)
            markup.addRangeHighlighter(
                loc.line.first,
                loc.line.last + 1,
                HighlighterLayer.ERROR,
                attributes,
                HighlighterTargetArea.EXACT_RANGE,
            ).apply {
                putUserData(MARK, true)
                errorStripeTooltip = tooltip
                gutterIconRenderer =
                    VulnerabilityGutter(if (severe) AllIcons.General.Error else AllIcons.General.Warning, tooltip)
            }
            marked++
        }
        if (packages.isNotEmpty()) {
            log.info("Mondoo: marked $marked of ${packages.size} vulnerable dependencies in ${file.name}")
        }
    }

    private fun tooltip(pkg: PackageVulnerabilities): String = buildString {
        append("<html><b>${StringUtil.escapeXmlEntities("${pkg.name} ${pkg.version}")}</b>: ${pkg.counts}")
        if (pkg.upgradeTo.isNotEmpty()) {
            append(
                " — upgrade to ${StringUtil.escapeXmlEntities(pkg.upgradeTo)} (Alt+Enter)",
            )
        }
        pkg.vulnerabilities.take(TOOLTIP_MAX).forEach { v ->
            append(
                "<br>${v.severity.label}: ${StringUtil.escapeXmlEntities(
                    v.id,
                )} ${StringUtil.escapeXmlEntities(v.summary)}",
            )
        }
        if (pkg.vulnerabilities.size > TOOLTIP_MAX) append("<br>… and ${pkg.vulnerabilities.size - TOOLTIP_MAX} more")
        append("</html>")
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        block()
    }, project.disposed)

    override fun dispose() = Unit

    private class VulnerabilityGutter(private val icon: Icon, private val tooltip: String) : GutterIconRenderer() {
        override fun getIcon() = icon
        override fun getTooltipText() = tooltip
        override fun equals(other: Any?) = other is VulnerabilityGutter && other.tooltip == tooltip
        override fun hashCode() = tooltip.hashCode()
    }

    companion object {
        private val MARK = Key.create<Boolean>("mondoo.dependency.vulnerability")
        private const val TOOLTIP_MAX = 6

        fun getInstance(project: Project): ManifestHighlighter = project.service()
    }
}
