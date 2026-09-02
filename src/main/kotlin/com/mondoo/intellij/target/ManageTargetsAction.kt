// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
            if (existing.isNotEmpty()) add("Remove a target")
        }.toTypedArray()

        when (Messages.showChooseDialog(project, "Targets", "Mondoo Targets", null, actions, actions.first())) {
            0 -> addTarget(project, store)
            1 -> removeTarget(project, store, existing)
            else -> Unit
        }
    }

    private fun addTarget(project: Project, store: TargetStore) {
        val types = TargetType.entries.filter { it != TargetType.LOCAL }
        val labels = types.map { "${it.title} — ${it.description}" }.toTypedArray()
        val typeIndex = Messages.showChooseDialog(
            project, "What kind of target?", "Add Target", null, labels, labels.first(),
        )
        if (typeIndex < 0) return
        val type = types[typeIndex]

        val name = Messages.showInputDialog(
            project, "A name for this target:", "Add Target", null, type.title, null,
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
        val labels = existing.map { "${it.name} — ${it.type.title}" }.toTypedArray()
        val index = Messages.showChooseDialog(
            project, "Remove which target?", "Remove Target", null, labels, labels.first(),
        )
        if (index < 0) return
        val target = existing[index]
        // Deleting also forgets its secrets, so nothing is orphaned in the safe.
        store.delete(target.name)
        Messages.showInfoMessage(project, "Removed \"${target.name}\".", "Remove Target")
    }
}
