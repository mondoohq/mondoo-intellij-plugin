// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** A row in the dependency tree. */
private sealed interface DepNode {
    data class Group(val reachability: Reachability, val count: Int) : DepNode
    data class Package(val pkg: DependencyPackage, val vulns: PackageVulnerabilities?) : DepNode
    data class Vuln(val vuln: Vulnerability) : DepNode
    data class Importer(val file: String) : DepNode
}

/**
 * Shows which dependencies first-party code actually imports.
 *
 * Grouped by reachability rather than listed flat, because the grouping *is* the
 * answer: "declared but unused" and "imported and reachable" call for completely
 * different actions, and a flat list buries that.
 */
internal class DependenciesPanel(private val project: Project) :
    JPanel(BorderLayout()),
    Disposable {

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = SimpleTree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = DepCellRenderer()
        showPrompt()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })

        com.intellij.ui.PopupHandler.installPopupMenu(tree, popupActions(), "MondooDependenciesTree")
        add(toolbar(), BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        border = JBUI.Borders.empty()

        project.messageBus.connect(this).subscribe(
            DependencyReachabilityService.TOPIC,
            DependencyReachabilityService.Listener { render(it) },
        )
        project.messageBus.connect(this).subscribe(
            DependencyReachabilityService.VULNERABILITIES_TOPIC,
            DependencyReachabilityService.VulnerabilitiesListener {
                DependencyReachabilityService.getInstance(project).report()?.let(::render)
            },
        )
        DependencyReachabilityService.getInstance(project).report()?.let(::render)
        DependencyReachabilityService.getInstance(project).loadVulnerabilities()
    }

    /**
     * Called when the tab is shown: analyzes on the first look, so the tab is not an
     * empty "run Analyze" prompt. Only where an explicit click would have worked —
     * a trusted project and an installed scanner — and quietly otherwise, since
     * nobody asked yet.
     */
    fun onShown() {
        val service = DependencyReachabilityService.getInstance(project)
        if (service.report() != null || service.isRunning()) return
        if (!com.mondoo.intellij.util.ProjectTrust.isTrusted(project)) return
        if (com.mondoo.intellij.binary.XgrepBinaryService.getInstance().resolvedBinaryOrNull() == null) return
        tree.emptyText.clear().appendLine("Analyzing dependencies...")
        service.refresh()
        // A failed analysis publishes nothing; put the prompt back once it stops.
        val alarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)
        fun check() {
            if (service.isRunning()) {
                alarm.addRequest(::check, RUNNING_POLL_MS)
            } else if (service.report() == null) {
                showPrompt()
            }
        }
        alarm.addRequest(::check, RUNNING_POLL_MS)
    }

    private fun showPrompt() {
        tree.emptyText.clear()
            .appendLine("No dependency analysis yet")
            .appendLine("See which packages your code actually imports.")
            .appendLine("Analyze Dependencies", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                DependencyReachabilityService.getInstance(project).refresh()
            }
    }

    private fun toolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()
        group.add(ScanAction())
        // A bill of materials is about these same dependencies, so it is made here.
        com.mondoo.intellij.ui.MondooToolbars.labeled("Mondoo.Bom.Generate")?.let { group.add(it) }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    /**
     * Scan: the dependency graph and the vulnerability lookup together. The same
     * Scan as on the other tabs, because a refresh of the graph alone never shows a
     * vulnerability.
     */
    private inner class ScanAction :
        AnAction("Scan", "Analyze dependencies and check them for known vulnerabilities", AllIcons.Actions.Find) {
        init {
            templatePresentation.putClientProperty(
                com.intellij.openapi.actionSystem.ex.ActionUtil.SHOW_TEXT_IN_TOOLBAR,
                true,
            )
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !DependencyReachabilityService.getInstance(project).isRunning()
        }

        override fun actionPerformed(e: AnActionEvent) = DependencyReachabilityService.getInstance(project).scan()
    }

    private fun selectedVuln(): Vulnerability? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DepNode.Vuln)?.vuln

    private fun selectedPackage(): DepNode.Package? {
        var node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        while (node != null) {
            (node.userObject as? DepNode.Package)?.let { return it }
            node = node.parent as? DefaultMutableTreeNode
        }
        return null
    }

    private fun openAdvisory(v: Vulnerability) = com.intellij.ide.BrowserUtil.browse(
        "https://osv.dev/vulnerability/${v.id}",
    )

    private fun popupActions() = DefaultActionGroup(
        object : AnAction("Open Advisory", "Open the vulnerability's advisory in the browser", AllIcons.General.Web) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedVuln() != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                selectedVuln()?.let(::openAdvisory)
            }
        },
        object : AnAction(
            "Copy Upgrade Command",
            "Copy the package manager command that fixes these vulnerabilities",
            AllIcons.Actions.Copy,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !selectedPackage()?.vulns?.upgradeCommand.isNullOrEmpty()
            }
            override fun actionPerformed(e: AnActionEvent) {
                val cmd = selectedPackage()?.vulns?.upgradeCommand?.takeIf { it.isNotEmpty() } ?: return
                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(
                    java.awt.datatransfer.StringSelection(cmd),
                )
            }
        },
    )

    private fun render(report: ReachabilityReport) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            root.removeAllChildren()
            val vulns = DependencyReachabilityService.getInstance(project).vulnerabilities()
            report.grouped().forEach { (klass, packages) ->
                val groupNode = DefaultMutableTreeNode(DepNode.Group(klass, packages.size))
                // Vulnerable packages first, worst first; then by name.
                packages.map { it to vulns[DependencyVulnerabilities.key(it.ecosystem, it.name, it.version)] }
                    .sortedWith(
                        compareByDescending<Pair<DependencyPackage, PackageVulnerabilities?>> {
                            it.second?.worst?.rank
                                ?: 0
                        },
                    )
                    .forEach { (pkg, pv) ->
                        val pkgNode = DefaultMutableTreeNode(DepNode.Package(pkg, pv))
                        pv?.vulnerabilities?.forEach { pkgNode.add(DefaultMutableTreeNode(DepNode.Vuln(it))) }
                        pkg.importedBy.forEach { pkgNode.add(DefaultMutableTreeNode(DepNode.Importer(it))) }
                        groupNode.add(pkgNode)
                    }
                root.add(groupNode)
            }
            model.reload()
            if (report.total == 0) {
                // Analyzed and found nothing is an answer, not "not analyzed yet".
                tree.emptyText.clear()
                    .appendLine("No dependencies found")
                    .appendLine("This project has no package manifest or lockfile xgrep recognizes,")
                    .appendLine("such as package.json, go.mod, requirements.txt or pom.xml.")
                    .appendLine("Analyze Again", com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                        DependencyReachabilityService.getInstance(project).refresh()
                    }
            }
            for (i in 0 until root.childCount) {
                tree.expandPath(TreePath(arrayOf(root, root.getChildAt(i))))
            }
        }
    }

    /** Opens the first-party file that imports the selected package. */
    private fun navigate() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
        (node as? DepNode.Vuln)?.let { return openAdvisory(it.vuln) }
        val file = (node as? DepNode.Importer)?.file ?: return
        val base = project.basePath ?: return
        val vf = LocalFileSystem.getInstance()
            .findFileByNioFile(Path.of(base).resolve(file).normalize()) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }

    override fun dispose() = Unit

    private companion object {
        const val RUNNING_POLL_MS = 500
    }

    private class DepCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is DepNode.Group -> {
                    icon = iconFor(node.reachability)
                    append(node.reachability.title)
                    append("  ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("   ${node.reachability.explanation}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is DepNode.Package -> {
                    icon = EcosystemIcons.forEcosystem(node.pkg.ecosystem)
                    append(node.pkg.label)
                    if (node.pkg.ecosystem.isNotBlank()) {
                        append("  ${node.pkg.ecosystem}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    node.vulns?.let { v ->
                        val severe = (v.worst?.rank ?: 0) >= VulnSeverity.HIGH.rank
                        val style = if (severe) {
                            SimpleTextAttributes.ERROR_ATTRIBUTES
                        } else {
                            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                        }
                        append("  ${v.counts}", style)
                        if (v.upgradeTo.isNotEmpty()) {
                            append("  upgrade to ${v.upgradeTo}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                }
                is DepNode.Vuln -> {
                    val v = node.vuln
                    icon = when (v.severity) {
                        VulnSeverity.CRITICAL, VulnSeverity.HIGH -> AllIcons.General.Error
                        VulnSeverity.MEDIUM -> AllIcons.General.Warning
                        VulnSeverity.LOW -> AllIcons.General.Information
                    }
                    append(v.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${v.summary}")
                    append("  ${v.severity.label.lowercase()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (v.fixedVersion.isNotEmpty()) {
                        append(" · fixed in ${v.fixedVersion}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is DepNode.Importer -> {
                    icon = AllIcons.FileTypes.Any_type
                    append(node.file, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        /**
         * Unused and orphaned packages are the actionable ones — they are removable —
         * so they get the attention marker rather than the imported ones.
         */
        private fun iconFor(reachability: Reachability) = when (reachability) {
            Reachability.DIRECT_UNUSED, Reachability.TRANSITIVE_ORPHANED, Reachability.IMPORTED_DEAD ->
                AllIcons.General.Warning
            Reachability.UNKNOWN -> AllIcons.General.Information
            else -> AllIcons.Nodes.Module
        }
    }
}
