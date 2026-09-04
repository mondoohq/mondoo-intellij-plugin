// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader

/**
 * A parsed Mondoo policy bundle.
 *
 * Modelled on the `Policy` / `PolicyGroup` / `Query` interfaces in
 * vscode-mondoo/src/models/policy.ts, reduced to what a tree needs: the identity of
 * each thing, how they nest, and the line each is declared on so double-clicking can
 * go there. Everything else in a bundle — docs, authors, scoring, filters, props —
 * is deliberately not modelled, because nothing displays it and every field parsed is
 * a field to keep in step with a format we do not own.
 *
 * Pure: no platform types, unit-tested without an IDE.
 */
data class PolicyBundle(val policies: List<Policy>, val queries: List<PolicyQuery>) {
    val isEmpty: Boolean get() = policies.isEmpty() && queries.isEmpty()

    /** The query a group's check refers to, or null when the reference dangles. */
    fun queryByUid(uid: String): PolicyQuery? = queries.firstOrNull { it.uid == uid }

    companion object {
        val EMPTY = PolicyBundle(emptyList(), emptyList())

        /**
         * Parses a bundle, or returns [EMPTY] for anything it cannot make sense of.
         *
         * Total by construction. This runs over every `*.mql.yaml` in a project,
         * including ones being typed, so a half-written document is the normal case
         * rather than an error: a tree that empties while you type is bad, but an
         * exception escaping into a tree model is worse.
         */
        fun parse(yaml: String): PolicyBundle = runCatching { parseOrThrow(yaml) }.getOrDefault(EMPTY)

        private fun parseOrThrow(yaml: String): PolicyBundle {
            // compose() rather than load(): the Node tree carries source marks, which
            // is the only way to know which line a policy is declared on, and that is
            // what makes the tree navigable.
            val root = Yaml(loaderOptions())
                .compose(StringReader(yaml)) as? MappingNode
                ?: return EMPTY

            return PolicyBundle(
                policies = root.sequence("policies").mapNotNull { it.toPolicy() },
                queries = root.sequence("queries").mapNotNull { it.toQuery() },
            )
        }

        /**
         * Limits on what a bundle may be, set explicitly.
         *
         * This parses whatever `*.mql.yaml` a project contains, and a project can be
         * one somebody just cloned, so the classic YAML denial-of-service shapes are
         * reachable input. SnakeYAML defends against them by default; naming the
         * limits here means the protection is a decision this code made rather than
         * one it happens to inherit, and it fails visibly if a default ever changes.
         *
         * Composing to a node tree is itself the reason there is no deserialization
         * risk: `compose()` never constructs an object, so a `!!SomeClass` tag is
         * text rather than an instruction. `PolicyBundleHostileInputTest` pins that.
         */
        private fun loaderOptions() = LoaderOptions().apply {
            // A bundle written by a person, not a generator.
            codePointLimit = 4 * 1024 * 1024
            // Enough for the deepest legitimate bundle; far short of a stack overflow.
            nestingDepthLimit = 100
            // The billion-laughs defence: expansions multiply, so this must stay small.
            setMaxAliasesForCollections(50)
            allowRecursiveKeys = false
            // Duplicate keys are a bundle bug, not a parse failure. cnspec reports
            // them; refusing to draw the tree would hide the file that has one.
            isAllowDuplicateKeys = true
        }

        private fun Node.toPolicy(): Policy? {
            val map = this as? MappingNode ?: return null
            val uid = map.scalar("uid") ?: return null
            return Policy(
                uid = uid,
                name = map.scalar("name").orEmpty(),
                groups = map.sequence("groups").mapNotNull { it.toGroup() },
                line = map.startMark.line,
            )
        }

        private fun Node.toGroup(): PolicyGroup? {
            val map = this as? MappingNode ?: return null
            return PolicyGroup(
                title = map.scalar("title").orEmpty(),
                // A group references checks and data queries the same way, and the
                // tree shows both as things belonging to the group.
                checkUids = (map.sequence("checks") + map.sequence("queries"))
                    .mapNotNull { (it as? MappingNode)?.scalar("uid") },
                line = map.startMark.line,
            )
        }

        private fun Node.toQuery(): PolicyQuery? {
            val map = this as? MappingNode ?: return null
            val uid = map.scalar("uid") ?: return null
            return PolicyQuery(
                uid = uid,
                title = map.scalar("title").orEmpty(),
                mql = map.scalar("mql").orEmpty(),
                line = map.startMark.line,
            )
        }

        /** The scalar value of [key], or null when absent or not a scalar. */
        private fun MappingNode.scalar(key: String): String? =
            value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }
                ?.let { (it.valueNode as? ScalarNode)?.value }
                ?.takeIf { it.isNotBlank() }

        /** The items of the sequence at [key], or empty for anything else. */
        private fun MappingNode.sequence(key: String): List<Node> =
            value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }
                ?.let { (it.valueNode as? SequenceNode)?.value }
                .orEmpty()
    }
}

/** A check or data query, identified by uid and referenced from a group. */
data class PolicyQuery(
    val uid: String,
    val title: String,
    val mql: String,
    /** 0-based line the query is declared on. */
    val line: Int,
) {
    val displayName: String get() = title.ifBlank { uid }
}

/** A named set of checks within a policy. */
data class PolicyGroup(
    val title: String,
    val checkUids: List<String>,
    /** 0-based line the group is declared on. */
    val line: Int,
) {
    val displayName: String get() = title.ifBlank { "Group" }
}

/** One policy in a bundle. */
data class Policy(
    val uid: String,
    val name: String,
    val groups: List<PolicyGroup>,
    /** 0-based line the policy is declared on. */
    val line: Int,
) {
    val displayName: String get() = name.ifBlank { uid }
}
