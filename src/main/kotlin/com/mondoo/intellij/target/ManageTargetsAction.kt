// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.mondoo.intellij.util.MondooDialogs
import com.mondoo.intellij.util.ProjectTrust

/**
 * Adds, edits and removes targets.
 *
 * A prompt sequence rather than a bespoke dialog: the field list is data on
 * [TargetType], so a new provider needs no UI work, and secrets are collected
 * through a masked prompt and written straight to the password safe without ever
 * entering the configuration object.
 */
class ManageTargetsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ProjectTrust.isTrusted(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val store = TargetStore.getInstance(project)
        val existing = store.targets()

        val actions = buildList {
            add("Add a target")
            add("Test a connection")
            if (existing.isNotEmpty()) {
                add("Edit a target")
                add("Remove a target")
            }
        }

        when (actions.getOrNull(MondooDialogs.choose(project, "Targets", "Mondoo Targets", actions) ?: return)) {
            "Add a target" -> addTarget(project, store)
            "Test a connection" -> testConnection(project)
            "Edit a target" -> editTarget(project, store, existing)
            "Remove a target" -> removeTarget(project, store, existing)
            else -> Unit
        }
    }

    /**
     * Re-prompts each field with what is already there.
     *
     * The name is fixed. It is the key the password safe stores secrets under, so
     * renaming would either orphan them or need a migration, and "delete and add" is
     * the honest way to say that.
     */
    private fun editTarget(project: Project, store: TargetStore, existing: List<TargetConfiguration>) {
        val target = MondooDialogs.chooseFrom(project, "Edit which target?", "Edit Target", existing) {
            "${it.name} — ${it.type.title}"
        } ?: return

        val values = target.values.toMutableMap()
        for (field in target.type.fields) {
            val prompt = buildString {
                append(field.label)
                if (field.required) append(" (required)")
                if (field.comment.isNotEmpty()) append("\n").append(field.comment)
            }

            if (field.secret) {
                // Blank keeps what is stored. Showing the current secret back would
                // put it on screen for no reason, and clearing it on every edit would
                // make changing a hostname cost a password.
                val secret = Messages.showPasswordDialog(
                    project,
                    "$prompt\n\nLeave blank to keep the stored value.",
                    "Edit Target",
                    null,
                )
                if (!secret.isNullOrEmpty()) TargetCredentials.set(target.name, field.key, secret)
                continue
            }

            val entered = Messages.showInputDialog(
                project,
                prompt,
                "Edit Target",
                null,
                values[field.key].orEmpty(),
                null,
            ) ?: return // Cancel abandons the whole edit rather than saving it half done.

            if (entered.isBlank()) values.remove(field.key) else values[field.key] = entered.trim()
        }

        val updated = TargetConfiguration(target.name, target.type, values)
        store.save(updated)

        val missing = updated.missingRequired()
        if (missing.isEmpty()) {
            Messages.showInfoMessage(project, "Updated \"${target.name}\".", "Edit Target")
        } else {
            Messages.showWarningDialog(
                project,
                "Updated \"${target.name}\", but it is missing: ${missing.joinToString(", ") { it.label }}.",
                "Edit Target",
            )
        }
    }

    /**
     * Asks cnspec whether it can actually reach a target.
     *
     * Worth having as its own thing: without it the first sign of a wrong host or a
     * bad key is a scan that fails several minutes in, and it is not obvious whether
     * the fault is the target, the credentials or the policy.
     */
    private fun testConnection(project: Project) {
        val target = TargetChooser.choose(project) ?: return

        val missing = target.missingRequired()
        if (missing.isNotEmpty()) {
            Messages.showWarningDialog(
                project,
                "${target.name} is missing: ${missing.joinToString(", ") { it.label }}",
                "Test Connection",
            )
            return
        }

        object : Task.Backgroundable(project, "Testing the connection to ${target.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = CnspecRunService.getInstance(project).testConnection(target)
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is ConnectionResult.Reachable -> Messages.showInfoMessage(
                            project,
                            "Connected to ${target.name}.\n\nIt reports: ${result.detail}",
                            "Test Connection",
                        )
                        is ConnectionResult.Unreachable -> Messages.showWarningDialog(
                            project,
                            "Could not reach ${target.name}.\n\n${result.reason}",
                            "Test Connection",
                        )
                    }
                }
            }
        }.queue()
    }

    private fun addTarget(project: Project, store: TargetStore) {
        val types = TargetType.entries.filter { it != TargetType.LOCAL }
        val type = MondooDialogs.chooseFrom(project, "What kind of target?", "Add Target", types) {
            "${it.title} — ${it.description}"
        } ?: return

        val name = Messages.showInputDialog(
            project,
            "A name for this target:",
            "Add Target",
            null,
            type.title,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return
        if (store.find(name) != null) {
            Messages.showWarningDialog(project, "A target called \"$name\" already exists.", "Add Target")
            return
        }

        val values = mutableMapOf<String, String>()
        for (field in type.fields) {
            val prompt = buildString {
                append(field.label)
                if (field.required) append(" (required)")
                if (field.comment.isNotEmpty()) append("\n").append(field.comment)
            }

            if (field.secret) {
                // Masked, and written to the password safe directly — it never
                // becomes part of the configuration that gets persisted.
                val secret = Messages.showPasswordDialog(project, prompt, "Add Target", null)
                if (!secret.isNullOrEmpty()) TargetCredentials.set(name, field.key, secret)
                continue
            }

            val value = Messages.showInputDialog(project, prompt, "Add Target", null)?.trim().orEmpty()
            if (value.isNotEmpty()) values[field.key] = value
        }

        val target = TargetConfiguration(name, type, values)
        store.save(target)

        val missing = target.missingRequired()
        if (missing.isEmpty()) {
            Messages.showInfoMessage(project, "Added \"$name\".", "Add Target")
        } else {
            Messages.showWarningDialog(
                project,
                "Added \"$name\", but it is missing: ${missing.joinToString(", ") { it.label }}.",
                "Add Target",
            )
        }
    }

    private fun removeTarget(project: Project, store: TargetStore, existing: List<TargetConfiguration>) {
        if (existing.isEmpty()) return
        val target = MondooDialogs.chooseFrom(project, "Remove which target?", "Remove Target", existing) {
            "${it.name} — ${it.type.title}"
        } ?: return
        // Deleting also forgets its secrets, so nothing is orphaned in the safe.
        store.delete(target.name)
        Messages.showInfoMessage(project, "Removed \"${target.name}\".", "Remove Target")
    }
}
