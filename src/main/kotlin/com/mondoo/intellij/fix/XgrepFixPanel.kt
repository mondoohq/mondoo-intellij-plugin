// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.fix

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The Fix tab: the `xgrep fix` review session as IDE UI.
 *
 * | terminal UI                         | here                                         |
 * |-------------------------------------|----------------------------------------------|
 * | finding cards, `space` to select    | a checkbox tree, most severe first           |
 * | detail pane                         | details on the right                         |
 * | deterministic preview diff          | an IntelliJ diff of the file with the fix    |
 * | `t` / `f` / `s` / `u`               | Triage actions, toolbar and right-click      |
 * | `x` / enter                         | Fix Checked                                  |
 * | spinner while the agent works       | a cancellable progress, agent output in a console tab |
 * | "create a PR?" prompt               | a notification after the run, or Create Pull Request |
 * | `g`                                 | Graph Context                                |
 *
 * Everything shown comes from [FixSession], which gets it from `xgrep fix serve`.
 * The server starts the first time this tab is shown, not when the tool window opens.
 */
internal class XgrepFixPanel(private val project: Project) :
    JPanel(BorderLayout()),
    Disposable {

    private val session = FixSession.getInstance(project)

    private val root = CheckedTreeNode()

    // Checking a severity group checks its fixable findings; unchecking a finding
    // unchecks its group. A group is never checked just because one child is.
    private val tree = CheckboxTree(
        FixCellRenderer(),
        root,
        com.intellij.ui.CheckboxTreeBase.CheckPolicy(true, true, false, true),
    )
    private val details = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        border = JBUI.Borders.empty(8)
    }
    private val diff: DiffRequestPanel = DiffManager.getInstance().createRequestPanel(project, this, null)
    private val statusLine = JBLabel().apply {
        border = JBUI.Borders.empty(2, 8)
        componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL
    }

    /** The finding the right side currently shows; stale preview answers are dropped. */
    private var shown: String? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "Loading findings..."
        TreeSpeedSearch.installOn(tree, true) { path ->
            when (val obj = (path.lastPathComponent as? CheckedTreeNode)?.userObject) {
                is FixFinding -> "${obj.ruleId} ${obj.path} ${obj.message}"
                is String -> obj
                else -> ""
            }
        }
        tree.addTreeSelectionListener { showSelection() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selected()?.let(::navigate)
            }
        })
        PopupHandler.installPopupMenu(tree, popupActions(), "MondooFixTree")

        val right = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(details)
            secondComponent = diff.component
        }
        val split = OnePixelSplitter(false, 0.42f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = right
        }
        add(toolbar(), BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(statusLine, BorderLayout.SOUTH)
        border = JBUI.Borders.empty()
        diff.setRequest(MessageDiffRequest("Select a finding to see its fix."))

        project.messageBus.connect(this).subscribe(
            FixSession.TOPIC,
            FixSession.Listener {
                ApplicationManager.getApplication().invokeLater({ render() }, project.disposed)
            },
        )
    }

    /** Called when the tab is first selected: starts the session lazily. */
    fun onShown() {
        if (!session.loaded) session.refresh() else render()
    }

    /** Selects the finding with [fingerprint], if it is listed. */
    fun select(fingerprint: String) {
        val node = leaves().firstOrNull { (it.userObject as FixFinding).fingerprint == fingerprint } ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /** Checks the findings with these fingerprints (when fixable), leaving the rest as they are. */
    fun check(fingerprints: Collection<String>) {
        if (fingerprints.isEmpty()) return
        val wanted = fingerprints.toSet()
        leaves().filter { it.isEnabled && (it.userObject as FixFinding).fingerprint in wanted }
            .forEach { tree.setNodeState(it, true) }
    }

    // --- rendering ---------------------------------------------------------------

    private fun render() {
        val checked = checkedFingerprints()
        val selectedFp = selected()?.fingerprint
        root.removeAllChildren()

        session.findings.groupBy { severityLabel(it.severityRank) }
            .toSortedMap(compareBy { SEVERITY_ORDER.indexOf(it) })
            .forEach { (label, group) ->
                val groupNode = CheckedTreeNode(label).apply {
                    isChecked = false
                    isEnabled = group.any { it.fixable }
                }
                group.forEach { f ->
                    groupNode.add(
                        CheckedTreeNode(f).apply {
                            isEnabled = f.fixable
                            isChecked = f.fixable && f.fingerprint in checked
                        },
                    )
                }
                root.add(groupNode)
            }
        (tree.model as DefaultTreeModel).reload()
        for (i in 0 until root.childCount) {
            tree.expandPath(TreePath(arrayOf<Any>(root, root.getChildAt(i))))
        }
        selectedFp?.let(::select)

        tree.emptyText.clear()
        when {
            session.problem != null -> tree.emptyText.appendLine(session.problem!!)
            !session.loaded -> tree.emptyText.appendLine("Loading findings...")
            !session.cachePresent -> {
                tree.emptyText.appendLine("This project has not been scanned for fixing yet.")
                tree.emptyText.appendLine("Scan Project", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    session.refresh(rescan = true)
                }
            }
            else -> tree.emptyText.appendLine("No findings. Scan again after you change code.")
        }
        renderStatus()
        showSelection()
    }

    private fun renderStatus() {
        val agent = session.serverInfo?.agent
        val agentText = when {
            agent == null -> ""
            agent.available -> "Agent: ${agent.name}"
            else -> "Agent: ${agent.name} (not installed — assisted fixes need it)"
        }
        val fixable = session.findings.count { it.fixable }
        val counts = if (session.loaded && session.problem == null) {
            "${session.findings.size} findings, $fixable fixable"
        } else {
            ""
        }
        statusLine.text = listOf(session.status, counts, agentText).filter { it.isNotBlank() }.joinToString("   ·   ")
    }

    private fun showSelection() {
        val f = selected()
        shown = f?.fingerprint
        if (f == null) {
            details.text = ""
            diff.setRequest(MessageDiffRequest("Select a finding to see its fix."))
            return
        }
        details.text = FixDetails.html(f)
        details.caretPosition = 0

        val before = session.contentBeforeRun(f.absolutePath)
        when {
            f.outcome?.applied == true && before != null -> showFileDiff(f, before, "Before fix", "Now")
            f.outcome != null -> diff.setRequest(
                MessageDiffRequest(
                    "${f.outcome.status}${f.outcome.reason.takeIf {
                        it.isNotBlank()
                    }?.let { ": $it" } ?: ""}",
                ),
            )
            f.tier == FixTier.DETERMINISTIC && !f.stale -> {
                val cached = session.cachedPreview(f.fingerprint)
                if (cached != null) {
                    showPreview(f, cached)
                } else {
                    diff.setRequest(MessageDiffRequest("Computing the fix..."))
                    session.preview(f.fingerprint) { p -> if (shown == f.fingerprint) showPreview(f, p) }
                }
            }
            f.tier == FixTier.ASSISTED -> diff.setRequest(
                MessageDiffRequest("The coding agent writes this fix. xgrep re-scans to confirm it."),
            )
            f.tier == FixTier.ADVISORY -> diff.setRequest(MessageDiffRequest("Guidance only: nothing to apply."))
            f.stale -> diff.setRequest(MessageDiffRequest("The file changed since the scan. Scan again to fix it."))
            else -> diff.setRequest(MessageDiffRequest("This rule offers no fix."))
        }
    }

    private fun showPreview(f: FixFinding, p: Preview?) {
        when {
            p == null -> diff.setRequest(MessageDiffRequest("No preview available."))
            p.originalContent.isNotEmpty() -> {
                val title = if (p.accepted) "With the fix" else "With the fix (rejected: ${p.reason})"
                diff.setRequest(diffRequest(f, p.originalContent, p.patchedContent, "Current", title))
            }
            else -> diff.setRequest(MessageDiffRequest("No preview: ${p.reason} ${p.detail}".trim()))
        }
    }

    private fun showFileDiff(f: FixFinding, before: String, leftTitle: String, rightTitle: String) {
        val now = runCatching { Files.readString(Path.of(f.absolutePath)) }.getOrDefault("")
        diff.setRequest(diffRequest(f, before, now, leftTitle, rightTitle))
    }

    private fun diffRequest(
        f: FixFinding,
        left: String,
        right: String,
        leftTitle: String,
        rightTitle: String,
    ): SimpleDiffRequest {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(Path.of(f.absolutePath).fileName.toString())
        val factory = DiffContentFactory.getInstance()
        return SimpleDiffRequest(
            "${f.ruleId} — ${f.path}",
            factory.create(project, left, type),
            factory.create(project, right, type),
            leftTitle,
            rightTitle,
        )
    }

    // --- selection ---------------------------------------------------------------

    private fun selected(): FixFinding? =
        (tree.lastSelectedPathComponent as? CheckedTreeNode)?.userObject as? FixFinding

    private fun selectedAll(): List<FixFinding> =
        tree.selectionPaths.orEmpty().mapNotNull {
            (it.lastPathComponent as? CheckedTreeNode)?.userObject as? FixFinding
        }

    private fun leaves(): List<CheckedTreeNode> = buildList {
        for (i in 0 until root.childCount) {
            val group = root.getChildAt(i) as CheckedTreeNode
            for (j in 0 until group.childCount) add(group.getChildAt(j) as CheckedTreeNode)
        }
    }

    private fun checkedFingerprints(): Set<String> =
        leaves().filter { it.isChecked && it.isEnabled }.map { (it.userObject as FixFinding).fingerprint }.toSet()

    /** What Fix acts on: the checked findings, or else the selected fixable ones. */
    private fun fixTargets(): List<String> =
        checkedFingerprints().ifEmpty { selectedAll().filter { it.fixable }.map { it.fingerprint }.toSet() }.toList()

    private fun navigate(f: FixFinding) {
        val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(f.absolutePath)) ?: return
        OpenFileDescriptor(project, file, (f.startLine - 1).coerceAtLeast(0), (f.startColumn - 1).coerceAtLeast(0))
            .navigate(true)
    }

    // --- actions -----------------------------------------------------------------

    private fun toolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup().apply {
            add(FixCheckedAction())
            add(Separator.create())
            add(action("Refresh", "Reload findings from the last scan", AllIcons.Actions.Refresh) { session.refresh() })
            add(
                action("Scan Project", "Scan the project again and reload", AllIcons.Actions.ForceRefresh) {
                    session.refresh(rescan = true)
                },
            )
            add(Separator.create())
            add(triageGroup())
            add(GraphContextAction())
            add(Separator.create())
            add(
                action(
                    "Create Pull Request",
                    "Commit this session's fixes to a branch and open a pull request",
                    AllIcons.Vcs.Push,
                ) {
                    session.createPullRequest()
                },
            )
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun popupActions() = DefaultActionGroup().apply {
        add(FixCheckedAction())
        add(action("Jump to Source", null, AllIcons.Actions.EditSource) { selected()?.let(::navigate) })
        add(Separator.create())
        addAll(triageActions())
        add(Separator.create())
        add(GraphContextAction())
    }

    private fun triageGroup() = DefaultActionGroup("Triage", true).apply {
        templatePresentation.icon = AllIcons.General.InspectionsEye
        templatePresentation.description = "Record your verdict on the selected findings"
        addAll(triageActions())
    }

    private fun triageActions(): List<AnAction> = listOf(
        TriageAction("Mark True Positive", TriageStatus.TRUE_POSITIVE, AllIcons.General.InspectionsOK),
        TriageAction("Mark False Positive...", TriageStatus.FALSE_POSITIVE, AllIcons.General.Remove),
        TriageAction("Mark Needs Review", TriageStatus.NEEDS_REVIEW, AllIcons.General.QuestionDialog),
        TriageAction("Clear Verdict", TriageStatus.UNREVIEWED, AllIcons.Actions.Rollback),
    )

    private inner class FixCheckedAction :
        AnAction(
            "Fix Checked",
            "Fix the checked findings, or the selected one: deterministic fixes are applied, assisted ones go to the coding agent",
            com.mondoo.intellij.MondooIcons.Fix,
        ),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !session.isBusy && fixTargets().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val targets = fixTargets()
            val assisted = targets.count { session.finding(it)?.tier == FixTier.ASSISTED }
            if (assisted > 0) {
                val agent = session.serverInfo?.agent
                if (agent != null && !agent.available) {
                    Messages.showWarningDialog(
                        project,
                        "$assisted of these findings need the coding agent \"${agent.name}\", " +
                            "which is not installed. Install it, or choose another agent in " +
                            "Settings | Tools | Mondoo.",
                        "Coding Agent Not Found",
                    )
                    return
                }
            }
            session.run(targets)
        }
    }

    private inner class TriageAction(text: String, private val status: TriageStatus, icon: javax.swing.Icon) :
        AnAction(text, null, icon),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedAll().isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val targets = selectedAll().map { it.fingerprint }
            var rationale = ""
            if (status == TriageStatus.FALSE_POSITIVE) {
                // A dismissal without a reason is the one a reviewer cannot audit later.
                rationale = Messages.showInputDialog(
                    project,
                    "Why is this not a real issue? Recorded with the verdict.",
                    "Mark False Positive",
                    null,
                ) ?: return
            }
            session.setTriage(targets, status, rationale)
        }
    }

    private inner class GraphContextAction :
        AnAction(
            "Graph Context",
            "Show the call graph around the function that contains this finding",
            AllIcons.Toolwindows.ToolWindowHierarchy,
        ),
        DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val f = selected() ?: return
            details.text = "<html><body>Loading graph context...</body></html>"
            session.graphContext(f.fingerprint) { text ->
                if (shown == f.fingerprint) {
                    details.text = "<html><body><pre>${StringUtil.escapeXmlEntities(text)}</pre></body></html>"
                    details.caretPosition = 0
                }
            }
        }
    }

    private fun action(text: String, description: String?, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !session.isBusy
            }
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    override fun dispose() = Unit

    private class FixCellRenderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? CheckedTreeNode ?: return
            val r = textRenderer
            when (val obj = node.userObject) {
                is String -> {
                    r.icon = severityIcon(obj)
                    r.append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    r.append("  ${node.childCount}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FixFinding -> {
                    r.icon = outcomeIcon(obj) ?: severityIcon(severityLabel(obj.severityRank))
                    val done = obj.outcome?.applied == true
                    r.append(
                        obj.title.ifBlank { obj.ruleId },
                        if (done) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    )
                    r.append("  ${obj.path}:${obj.startLine}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    r.append("  ${FixDetails.badge(obj)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }

        private fun outcomeIcon(f: FixFinding) = when {
            f.outcome?.applied == true -> AllIcons.RunConfigurations.TestPassed
            f.outcome?.rejected == true -> AllIcons.RunConfigurations.TestFailed
            f.triage?.status == TriageStatus.FALSE_POSITIVE -> AllIcons.RunConfigurations.TestIgnored
            else -> null
        }

        private fun severityIcon(label: String) = when (label) {
            "Critical", "High" -> AllIcons.General.Error
            "Medium" -> AllIcons.General.Warning
            else -> AllIcons.General.Information
        }
    }

    private companion object {
        val SEVERITY_ORDER = listOf("Critical", "High", "Medium", "Low", "Other")

        fun severityLabel(rank: Int) = when (rank) {
            4 -> "Critical"
            3 -> "High"
            2 -> "Medium"
            1 -> "Low"
            else -> "Other"
        }
    }
}
