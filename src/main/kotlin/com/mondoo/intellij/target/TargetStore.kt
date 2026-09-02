// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.target

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The project's configured targets.
 *
 * Project-level, because a target is usually about the thing the project deploys.
 * Persisted without secrets — [TargetConfiguration] refuses to hold a secret field
 * at all, so this cannot write one even by mistake, and the file is safe to commit.
 */
@State(name = "MondooTargets", storages = [Storage("mondoo-targets.xml")])
class TargetStore : SimplePersistentStateComponent<TargetStoreState>(TargetStoreState()) {

    fun targets(): List<TargetConfiguration> = state.targets.mapNotNull { it.toConfiguration() }

    fun find(name: String): TargetConfiguration? = targets().firstOrNull { it.name == name }

    fun save(target: TargetConfiguration) {
        state.targets.removeAll { it.name == target.name }
        state.targets.add(StoredTarget.from(target))
        state.intIncrementModificationCount()
    }

    /** Removes a target and forgets its secrets, so nothing is orphaned in the safe. */
    fun delete(name: String) {
        find(name)?.let(TargetCredentials::forget)
        state.targets.removeAll { it.name == name }
        state.intIncrementModificationCount()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TargetStore = project.service()
    }
}

class TargetStoreState : BaseState() {
    val targets: MutableList<StoredTarget> by list()
}

/** The persisted shape. Flat, because that is what serialises predictably. */
class StoredTarget : BaseState() {
    var name: String? by string(null)
    var type: String? by string(null)

    /** Non-secret fields, as `key=value`. */
    val values: MutableList<String> by list()

    fun toConfiguration(): TargetConfiguration? {
        val name = name ?: return null
        val type = TargetType.of(this.type) ?: return null
        val parsed = values.mapNotNull { entry ->
            val i = entry.indexOf('=')
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
        }.toMap()

        // Defensive: a hand-edited file could name a secret field. Drop it rather
        // than letting it construct, so a secret in the wrong place is ignored, not
        // used.
        val secretKeys = type.fields.filter { it.secret }.map { it.key }.toSet()
        return runCatching {
            TargetConfiguration(name, type, parsed.filterKeys { it !in secretKeys })
        }.getOrNull()
    }

    companion object {
        fun from(target: TargetConfiguration): StoredTarget = StoredTarget().apply {
            name = target.name
            type = target.type.id
            values.addAll(target.values.map { (k, v) -> "$k=$v" })
        }
    }
}
