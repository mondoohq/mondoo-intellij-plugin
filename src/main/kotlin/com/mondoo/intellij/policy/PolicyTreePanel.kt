// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import com.mondoo.intellij.MondooIcons
import com.mondoo.intellij.binary.CnspecBinaryService
import com.mondoo.intellij.mql.MqlFiles
import com.mondoo.intellij.target.CnspecRunService
import com.mondoo.intellij.target.TargetChooser
import com.mondoo.intellij.util.ProjectTrust
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The Policies tab: every bundle in the project, by directory.
 *
 * Ported from vscode-mondoo's policy tree view, minus the parts that were working
 * around VS Code rather than serving the user. Pinning, multi-select bookkeeping and
 * bulk-operation commands are gone: an IntelliJ tree already has multi-selection,
 * Bookmarks and speed search, so reimplementing them here would be worse versions of
 * things the IDE does. Searching is the platform's type-to-find rather than a custom
 * search view with its own state to clear.
 *
 * What is kept is the part that only this plugin can do: the bundle's structure, the
 * resolution of each group's checks to the queries they name, and the ability to run
 * one from where you are reading it.
 */
internal class PolicyTreePanel(private val project: Project) :
    JPanel(BorderLayout()),
    Disposable {

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = SimpleTree(model)

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = PolicyCellRenderer()
        tree.emptyText
            .appendLine("No policy bundles")
            .appendLine("Files named *.mql.yaml in this project appear here.")
        // The platform's type-to-find, in place of the extension's search view: it
        // needs no state, no clear command and no second tree.
        TreeSpeedSearch.installOn(tree, true) { path ->
            (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject?.let(::searchTextOf).orEmpty()
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelection()
            }
        })

        // Right-click. A tree without one is not finished in this IDE, whatever the
        // toolbar offers — and the toolbar is out of reach of where you are pointing.
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(JumpToSourceAction(), RunSelectionAction()),
            "MondooPolicyTree",
        )

        add(toolbar().component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        border = JBUI.Borders.empty()

        val bus = project.messageBus.connect(this)
        bus.subscribe(
            PolicyIndexService.TOPIC,
            PolicyIndexService.Listener { nodes -> render(nodes) },
        )
        // Saving, creating, deleting or renaming a bundle should be visible without
        // reaching for Refresh. Filtered to bundles so that ordinary editing in a
        // project full of other files costs nothing.
        bus.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // event.path rather than event.file: the path is already a string
                    // on the event, while asking for the file resolves it in the VFS
                    // for every event in every batch, most of which are not ours.
                    if (events.any { MqlFiles.isPolicyBundle(it.path.substringAfterLast('/')) }) {
                        PolicyIndexService.getInstance(project).refresh()
                    }
                }
            },
        )
        PolicyIndexService.getInstance(project).refresh()
    }

    private fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(
            object : AnAction("Refresh", "Re-read the policy bundles in this project", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) =
                    PolicyIndexService.getInstance(project).refresh()
            },
        )
        group.add(RunSelectionAction())
        group.addSeparator()
        ActionManager.getInstance().getAction("Mondoo.CodeSecurity")?.let { group.add(it) }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar
    }

    /**
     * Runs whatever is selected: a query on its own, a policy from its bundle, or a
     * whole bundle. One button rather than three, because which of them applies is
     * something the selection already says.
     */
    private inner class RunSelectionAction :
        AnAction(
            "Run",
            "Run the selected query, policy or bundle against a target",
            AllIcons.Actions.Execute,
        ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val node = selectedNode()
            // Same gate as every other execution path in the plugin. Running a bundle
            // executes policy content that came with the project, which is exactly
            // what an untrusted project must not be allowed to do.
            e.presentation.isEnabled = ProjectTrust.isTrusted(project) &&
                node != null &&
                runnableDescription(node) != null
            e.presentation.text = runnableDescription(node) ?: "Run"
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!ProjectTrust.isTrusted(project)) return
            val node = selectedNode() ?: return
            if (CnspecBinaryService.getInstance().resolvedBinaryOrNull() == null) {
                CnspecBinaryService.getInstance().notifyMissing(project)
                return
            }
            val target = TargetChooser.choose(project) ?: return
            val service = CnspecRunService.getInstance(project)

            when (node) {
                is PolicyNode.Query -> service.runQuery(target, node.query.mql)
                is PolicyNode.PolicyRef -> absolutePath(node.path)?.let {
                    service.scanBundle(target, it, node.policy.uid, node.policy.displayName)
                }
                is PolicyNode.File -> absolutePath(node.path)?.let {
                    service.scanBundle(target, it, null, node.name)
                }
                else -> Unit
            }
        }

        private fun runnableDescription(node: PolicyNode?): String? = when (node) {
            is PolicyNode.Query -> "Run Query".takeIf { node.query.mql.isNotBlank() }
            is PolicyNode.PolicyRef -> "Run Policy"
            is PolicyNode.File -> "Run Bundle"
            else -> null
        }
    }

    /** What double-clicking does, as a named action so it can also be right-clicked. */
    private inner class JumpToSourceAction :
        AnAction(
            "Jump to Source",
            "Open the file where this is declared",
            AllIcons.Actions.EditSource,
        ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode()?.target != null
        }
        override fun actionPerformed(e: AnActionEvent) = navigateToSelection()
    }

    private fun selectedNode(): PolicyNode? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? PolicyNode

    private fun render(nodes: List<PolicyNode>) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Saving a bundle rebuilds the tree, and without this you would lose your
            // place every time — the policy you had open would collapse under you.
            val state = if (root.childCount > 0) TreeState.createOn(tree, root) else null

            root.removeAllChildren()
            nodes.forEach { root.add(it.toTreeNode()) }
            model.reload()

            if (state != null) {
                state.applyTo(tree, root)
            } else {
                // First render: directories and files only. Expanding policies too
                // would open every group and check in the project at once.
                expandTo(depth = 2)
            }
        }
    }

    private fun expandTo(depth: Int) {
        var row = 0
        while (row < tree.rowCount) {
            if (tree.getPathForRow(row).pathCount <= depth + 1) tree.expandRow(row)
            row++
        }
    }

    private fun PolicyNode.toTreeNode(): DefaultMutableTreeNode = DefaultMutableTreeNode(this).also { node ->
        childrenOf(this).forEach { node.add(it.toTreeNode()) }
    }

    private fun childrenOf(node: PolicyNode): List<PolicyNode> = when (node) {
        is PolicyNode.Directory -> node.children
        is PolicyNode.File -> node.children
        is PolicyNode.Section -> node.children
        is PolicyNode.PolicyRef -> node.children
        is PolicyNode.Group -> node.children
        is PolicyNode.Query, is PolicyNode.MissingQuery -> emptyList()
    }

    private fun navigateToSelection() {
        val target = selectedNode()?.target ?: return
        val file = virtualFile(target.path) ?: return
        OpenFileDescriptor(project, file, target.line, 0).navigate(true)
    }

    private fun absolutePath(relative: String): Path? =
        project.basePath?.let { Path.of(it).resolve(relative).normalize() }

    private fun virtualFile(relative: String): VirtualFile? =
        absolutePath(relative)?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    override fun dispose() = Unit

    private companion object {
        /** What type-to-find matches against. */
        fun searchTextOf(node: Any?): String = when (node) {
            is PolicyNode.Directory -> node.name
            is PolicyNode.File -> node.name
            is PolicyNode.Section -> node.label
            is PolicyNode.PolicyRef -> "${node.policy.displayName} ${node.policy.uid}"
            is PolicyNode.Group -> node.group.displayName
            is PolicyNode.Query -> "${node.query.displayName} ${node.query.uid}"
            is PolicyNode.MissingQuery -> node.uid
            else -> ""
        }
    }

    private class PolicyCellRenderer : ColoredTreeCellRenderer() {
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
                is PolicyNode.Directory -> {
                    icon = AllIcons.Nodes.Folder
                    append(node.name)
                }
                is PolicyNode.File -> {
                    icon = AllIcons.FileTypes.Any_type
                    append(node.name)
                    append("  ${node.summary}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is PolicyNode.Section -> {
                    icon = AllIcons.Nodes.Folder
                    append(node.label)
                    append("  ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is PolicyNode.PolicyRef -> {
                    icon = MondooIcons.Mondoo
                    append(node.policy.displayName)
                    append("  ${node.policy.uid}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is PolicyNode.Group -> {
                    icon = AllIcons.Nodes.Folder
                    append(node.group.displayName)
                    append(
                        "  ${node.children.size} ${if (node.children.size == 1) "check" else "checks"}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                is PolicyNode.Query -> {
                    icon = AllIcons.Nodes.Method
                    append(node.query.displayName)
                    append("  ${node.query.uid}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is PolicyNode.MissingQuery -> {
                    // Shown, not dropped: a check naming a uid nothing defines is a
                    // bug in the bundle, and this is where you would see it.
                    icon = AllIcons.General.Warning
                    append(node.uid, SimpleTextAttributes.ERROR_ATTRIBUTES)
                    append("  not defined in this file", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}
