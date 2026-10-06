// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.findings

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
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

private const val GROUP_MODE_KEY = "mondoo.xgrep.findings.groupMode"

/**
 * The Mondoo tool window.
 *
 * A `ContentManager` with tabs from the start, so the cnspec and bill-of-materials
 * pillars can slot in later without moving anything.
 *
 * This exists alongside the platform Problems view rather than instead of it.
 * Problems covers the current file for free, but it cannot show workspace-scan
 * results for files nobody has opened (the daemon has no highlighting session for
 * them), it cannot group by severity and rule, and it interleaves every other
 * inspection so its count would never match the status bar.
 */
internal class XgrepFindingsToolWindowFactory :
    ToolWindowFactory,
    DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        val findings = XgrepFindingsPanel(project)
        Disposer.register(toolWindow.disposable, findings)
        toolWindow.contentManager.addContent(
            factory.createContent(findings, "Code Security", false).also { it.isCloseable = false },
        )

        val dependencies = com.mondoo.intellij.dependencies.DependenciesPanel(project)
        Disposer.register(toolWindow.disposable, dependencies)
        toolWindow.contentManager.addContent(
            factory.createContent(dependencies, "Dependencies", false).also { it.isCloseable = false },
        )

        val policies = com.mondoo.intellij.policy.PolicyTreePanel(project)
        Disposer.register(toolWindow.disposable, policies)
        toolWindow.contentManager.addContent(
            factory.createContent(policies, "Policies", false).also { it.isCloseable = false },
        )

        // The fix session starts `xgrep fix serve`, so it waits until the tab is
        // actually looked at rather than starting with the tool window.
        val fix = com.mondoo.intellij.fix.XgrepFixPanel(project)
        Disposer.register(toolWindow.disposable, fix)
        val fixContent = factory.createContent(fix, FIX_TAB, false).also {
            it.isCloseable = false
            it.icon = com.mondoo.intellij.MondooIcons.Fix
            it.putUserData(com.intellij.openapi.wm.ToolWindow.SHOW_CONTENT_ICON, true)
        }
        toolWindow.contentManager.addContent(fixContent)
        toolWindow.contentManager.addContentManagerListener(
            object : com.intellij.ui.content.ContentManagerListener {
                override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                    if (event.content === fixContent &&
                        event.operation == com.intellij.ui.content.ContentManagerEvent.ContentOperation.add
                    ) {
                        fix.onShown()
                    }
                }
            },
        )
    }

    companion object {
        const val FIX_TAB = "Fix"

        /**
         * Shows the Fix tab and hands its panel to [then], creating the tool window
         * content first if it has not been opened yet.
         */
        fun showFixTab(project: Project, then: (com.mondoo.intellij.fix.XgrepFixPanel) -> Unit = {}) {
            val window =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Mondoo") ?: return
            window.activate {
                val content = window.contentManager.findContent(FIX_TAB) ?: return@activate
                window.contentManager.setSelectedContent(content)
                (content.component as? com.mondoo.intellij.fix.XgrepFixPanel)?.let(then)
            }
        }
    }
}

internal class XgrepFindingsPanel(private val project: Project) :
    JPanel(BorderLayout()),
    com.intellij.openapi.Disposable {

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = SimpleTree(model)
    private val refreshPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)

    private var groupMode: GroupMode
        get() = if (PropertiesComponent.getInstance().getValue(GROUP_MODE_KEY) == "file") {
            GroupMode.FILE
        } else {
            GroupMode.SEVERITY
        }
        set(value) = PropertiesComponent.getInstance()
            .setValue(GROUP_MODE_KEY, if (value == GroupMode.FILE) "file" else "severity")

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        // Several findings (or whole rule groups) can be fixed in one go.
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.cellRenderer = FindingsCellRenderer()
        tree.emptyText
            .appendLine("No security findings")
            .appendLine("Findings appear as you open and edit code.")
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelection()
            }
        })

        // Right-click on a finding is where people look for "fix this".
        com.intellij.ui.PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(FixSelectedAction(), ReviewInFixTabAction(), JumpToSourceAction()),
            "MondooFindingsTree",
        )

        add(toolbar().component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        border = JBUI.Borders.empty()

        project.messageBus.connect(this).subscribe(
            XgrepFindingsStore.TOPIC,
            XgrepFindingsStore.Listener { scheduleRefresh() },
        )
        refresh()
    }

    private fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(FixSelectedAction())
        group.add(object : ToggleAction(
            "Group by File",
            "Group findings by file instead of severity",
            AllIcons.Actions.GroupByFile,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = groupMode == GroupMode.FILE
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupMode = if (state) GroupMode.FILE else GroupMode.SEVERITY
                refresh()
            }
        })
        ActionManager.getInstance().getAction("Mondoo.CodeSecurity")?.let { group.add(it) }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar
    }

    /**
     * Coalesces refreshes.
     *
     * A workspace scan publishes diagnostics per file, so a few hundred files means a
     * few hundred notifications, each of which would otherwise rebuild the whole tree
     * on the EDT. Redrawing at most every [REFRESH_COALESCE_MS] keeps the view live
     * without making the UI pay for every publish.
     */
    private fun scheduleRefresh() {
        if (!refreshPending.compareAndSet(false, true)) return
        refreshAlarm.addRequest({
            refreshPending.set(false)
            refresh()
        }, REFRESH_COALESCE_MS)
    }

    private fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val nodes = FindingsTree.build(XgrepFindingsStore.getInstance(project).findings(), groupMode)
            root.removeAllChildren()
            nodes.forEach { root.add(it.toTreeNode()) }
            model.reload()
            expandGroups()
        }
    }

    private fun expandGroups() {
        // Only the top level, so a big scan does not explode into thousands of rows.
        for (i in 0 until root.childCount) {
            tree.expandPath(TreePath(arrayOf(root, root.getChildAt(i))))
        }
    }

    private fun FindingsNode.toTreeNode(): DefaultMutableTreeNode = when (this) {
        is FindingsNode.Group -> DefaultMutableTreeNode(this).also { node ->
            children.forEach { node.add(it.toTreeNode()) }
        }
        is FindingsNode.Leaf -> DefaultMutableTreeNode(this)
    }

    /** The findings under the selection: a selected group stands for all of its findings. */
    private fun selectedFindings(): List<Finding> {
        val out = LinkedHashSet<Finding>()
        fun collect(node: FindingsNode) {
            when (node) {
                is FindingsNode.Leaf -> out += node.finding
                is FindingsNode.Group -> node.children.forEach(::collect)
            }
        }
        tree.selectionPaths.orEmpty().forEach { path ->
            ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FindingsNode)?.let(::collect)
        }
        return out.toList()
    }

    private fun fixTargets(): List<com.mondoo.intellij.fix.FixTarget> {
        val base = project.basePath ?: return emptyList()
        return selectedFindings()
            .filter { it.fixKind == FIX_DETERMINISTIC || it.fixKind == FIX_ASSISTED }
            .map {
                com.mondoo.intellij.fix.FixTarget(
                    absolutePath = Path.of(base).resolve(it.path).normalize().toString(),
                    line = it.line + 1,
                    ruleId = it.ruleId,
                )
            }
    }

    private inner class FixSelectedAction :
        com.intellij.openapi.actionSystem.AnAction(
            "Fix with xgrep",
            "Fix the selected findings: deterministic fixes by xgrep, the rest by your coding agent. " +
                "Opens the Fix tab.",
            com.mondoo.intellij.MondooIcons.Fix,
        ),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val n = fixTargets().size
            e.presentation.isEnabled = n > 0
            e.presentation.text = if (n > 1) "Fix $n Findings with xgrep" else "Fix with xgrep"
        }

        override fun actionPerformed(e: AnActionEvent) {
            com.mondoo.intellij.fix.FixSession.getInstance(project).fixFindings(fixTargets())
        }
    }

    private inner class ReviewInFixTabAction :
        com.intellij.openapi.actionSystem.AnAction(
            "Review in Fix Tab",
            "Open the Fix tab with these findings checked, to preview and triage before fixing",
            null,
        ),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = fixTargets().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            com.mondoo.intellij.fix.FixSession.getInstance(project).fixFindings(fixTargets(), run = false)
        }
    }

    private inner class JumpToSourceAction :
        com.intellij.openapi.actionSystem.AnAction("Jump to Source", null, AllIcons.Actions.EditSource),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled =
                (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject is FindingsNode.Leaf
        }

        override fun actionPerformed(e: AnActionEvent) = navigateToSelection()
    }

    private fun navigateToSelection() {
        val leaf = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FindingsNode.Leaf
            ?: return
        val finding = leaf.finding
        val base = project.basePath ?: return
        val absolute = Path.of(base).resolve(finding.path).normalize()
        val file = LocalFileSystem.getInstance().findFileByNioFile(absolute) ?: return
        OpenFileDescriptor(project, file, finding.line, finding.column).navigate(true)
    }

    override fun dispose() = Unit

    private companion object {
        /** Long enough to absorb a scan's burst, short enough to feel immediate. */
        const val REFRESH_COALESCE_MS = 200

        const val FIX_DETERMINISTIC = "deterministic"
        const val FIX_ASSISTED = "assisted"
    }

    private class FindingsCellRenderer : ColoredTreeCellRenderer() {
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
                is FindingsNode.Group -> {
                    icon = node.severity?.let(::severityIcon) ?: AllIcons.FileTypes.Any_type
                    append(node.label)
                    append("  ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FindingsNode.Leaf -> {
                    val finding = node.finding
                    icon = severityIcon(finding.severity)
                    append(finding.message)
                    append("  ${finding.path}:${finding.line + 1}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    when (finding.fixKind) {
                        FIX_DETERMINISTIC -> append("  auto-fix", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        FIX_ASSISTED -> append("  agent fix", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }

        private fun severityIcon(severity: FindingSeverity) = when (severity) {
            FindingSeverity.HIGH -> AllIcons.General.Error
            FindingSeverity.MEDIUM -> AllIcons.General.Warning
            FindingSeverity.LOW -> AllIcons.General.Information
        }
    }
}
