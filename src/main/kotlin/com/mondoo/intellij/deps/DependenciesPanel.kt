// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.deps

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
    data class Package(val pkg: DependencyPackage) : DepNode
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
        tree.emptyText
            .appendLine("No dependency analysis yet")
            .appendLine("Run Analyze Dependencies to see which packages your code imports.")
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate()
            }
        })

        add(toolbar(), BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        border = JBUI.Borders.empty()

        project.messageBus.connect(this).subscribe(
            DependencyReachabilityService.TOPIC,
            DependencyReachabilityService.Listener { render(it) },
        )
        DependencyReachabilityService.getInstance(project).report()?.let(::render)
    }

    private fun toolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()
        group.add(object : AnAction(
            "Analyze Dependencies",
            "Rebuild the dependency reachability graph",
            AllIcons.Actions.Refresh,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !DependencyReachabilityService.getInstance(project).isRunning()
            }
            override fun actionPerformed(e: AnActionEvent) {
                DependencyReachabilityService.getInstance(project).refresh()
            }
        })
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun render(report: ReachabilityReport) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            root.removeAllChildren()
            report.grouped().forEach { (klass, packages) ->
                val groupNode = DefaultMutableTreeNode(DepNode.Group(klass, packages.size))
                packages.forEach { pkg ->
                    val pkgNode = DefaultMutableTreeNode(DepNode.Package(pkg))
                    pkg.importedBy.forEach { pkgNode.add(DefaultMutableTreeNode(DepNode.Importer(it))) }
                    groupNode.add(pkgNode)
                }
                root.add(groupNode)
            }
            model.reload()
            for (i in 0 until root.childCount) {
                tree.expandPath(TreePath(arrayOf(root, root.getChildAt(i))))
            }
        }
    }

    /** Opens the first-party file that imports the selected package. */
    private fun navigate() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
        val file = (node as? DepNode.Importer)?.file ?: return
        val base = project.basePath ?: return
        val vf = LocalFileSystem.getInstance()
            .findFileByNioFile(Path.of(base).resolve(file).normalize()) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }

    override fun dispose() = Unit

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
                    icon = AllIcons.Nodes.PpLib
                    append(node.pkg.label)
                    if (node.pkg.ecosystem.isNotBlank()) {
                        append("  ${node.pkg.ecosystem}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
