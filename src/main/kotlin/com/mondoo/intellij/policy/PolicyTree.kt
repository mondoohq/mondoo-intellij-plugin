// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.policy

/**
 * The policy tree model: every bundle in the project, laid out by directory.
 *
 * Ported from `buildFileStructureView` / `createPolicyItem` in
 * vscode-mondoo/src/providers/policyTreeProvider.ts, keeping the shape a policy
 * author already knows:
 *
 * ```
 * <directory>/
 *   checks.mql.yaml            2 policies, 5 queries
 *     Policies
 *       SSH Baseline           ssh-baseline
 *         Server hardening     3 checks
 *           Disallow root login
 *           sshd-protocol      (not defined in this file)
 *     Queries
 *       Disallow root login    sshd-permit-root
 * ```
 *
 * A group lists the queries its checks name, resolved through the bundle. An
 * unresolved reference is shown rather than dropped: a check pointing at a uid that
 * does not exist is a bug in the bundle, and the tree is where you would notice it.
 *
 * Pure: no platform types, unit-tested without an IDE.
 */
object PolicyTree {

    /** One bundle, with the workspace-relative path it was found at. */
    data class Source(val path: String, val bundle: PolicyBundle)

    fun build(sources: List<Source>): List<PolicyNode> {
        val withContent = sources.filterNot { it.bundle.isEmpty }
        if (withContent.isEmpty()) return emptyList()
        return directoryChildren(withContent.sortedBy { it.path.lowercase() }, prefix = "")
    }

    /**
     * Groups the sources at one directory level: files here, subdirectories below.
     *
     * Recursive on the path segment rather than building a mutable map of every
     * directory, because the result is immutable and the recursion is the same shape
     * as the tree it produces.
     */
    private fun directoryChildren(sources: List<Source>, prefix: String): List<PolicyNode> {
        val (here, deeper) = sources.partition { !it.path.removePrefix(prefix).contains('/') }

        val directories = deeper
            .groupBy { it.path.removePrefix(prefix).substringBefore('/') }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (name, inside) ->
                PolicyNode.Directory(name, directoryChildren(inside, "$prefix$name/"))
            }

        val files = here.map { source ->
            PolicyNode.File(
                path = source.path,
                name = source.path.substringAfterLast('/'),
                summary = summarise(source.bundle),
                children = fileChildren(source),
            )
        }

        return directories + files
    }

    private fun fileChildren(source: Source): List<PolicyNode> = buildList {
        val bundle = source.bundle
        if (bundle.policies.isNotEmpty()) {
            add(
                PolicyNode.Section(
                    label = "Policies",
                    count = bundle.policies.size,
                    children = bundle.policies
                        .sortedBy { it.displayName.lowercase() }
                        .map { policy -> policyNode(policy, source) },
                ),
            )
        }
        if (bundle.queries.isNotEmpty()) {
            add(
                PolicyNode.Section(
                    label = "Queries",
                    count = bundle.queries.size,
                    children = bundle.queries
                        .sortedBy { it.displayName.lowercase() }
                        .map { PolicyNode.Query(it, source.path) },
                ),
            )
        }
    }

    private fun policyNode(policy: Policy, source: Source) = PolicyNode.PolicyRef(
        policy = policy,
        path = source.path,
        children = policy.groups.map { group ->
            PolicyNode.Group(
                group = group,
                path = source.path,
                // Unsorted on purpose: a group's checks are an ordered list the author
                // wrote, and reordering them here would misrepresent the file.
                children = group.checkUids.map { uid ->
                    source.bundle.queryByUid(uid)
                        ?.let { PolicyNode.Query(it, source.path) }
                        ?: PolicyNode.MissingQuery(uid, source.path)
                },
            )
        },
    )

    private fun summarise(bundle: PolicyBundle): String = listOfNotNull(
        bundle.policies.size.takeIf { it > 0 }?.let { plural(it, "policy", "policies") },
        bundle.queries.size.takeIf { it > 0 }?.let { plural(it, "query", "queries") },
    ).joinToString(", ")

    private fun plural(count: Int, one: String, many: String) =
        "$count ${if (count == 1) one else many}"
}

/** A node in the policy tree. */
sealed interface PolicyNode {

    /** Where double-clicking this node should go, or null when it is not navigable. */
    val target: PolicyTarget? get() = null

    data class Directory(val name: String, val children: List<PolicyNode>) : PolicyNode

    data class File(val path: String, val name: String, val summary: String, val children: List<PolicyNode>) :
        PolicyNode {
        override val target get() = PolicyTarget(path, line = 0)
    }

    /** The "Policies" or "Queries" heading inside a file. */
    data class Section(val label: String, val count: Int, val children: List<PolicyNode>) : PolicyNode

    data class PolicyRef(val policy: Policy, val path: String, val children: List<PolicyNode>) : PolicyNode {
        override val target get() = PolicyTarget(path, policy.line)
    }

    data class Group(val group: PolicyGroup, val path: String, val children: List<PolicyNode>) : PolicyNode {
        override val target get() = PolicyTarget(path, group.line)
    }

    data class Query(val query: PolicyQuery, val path: String) : PolicyNode {
        override val target get() = PolicyTarget(path, query.line)
    }

    /** A check naming a uid no query in the bundle defines. */
    data class MissingQuery(val uid: String, val path: String) : PolicyNode
}

/** A place in a bundle, as a workspace-relative path and a 0-based line. */
data class PolicyTarget(val path: String, val line: Int)
