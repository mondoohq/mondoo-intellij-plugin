// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.skills

/**
 * Mondoo's agent skills, published at https://github.com/mondoohq/skills.
 *
 * They are a separate repository registered as a Claude Code plugin marketplace, not
 * files bundled inside the scanner binary. Installation is deliberately two steps:
 * registering the marketplace makes the skills available, and each skill is then
 * installed individually so people pull only what they want.
 *
 * Pure: unit-tested without an IDE.
 */
object MondooSkills {

    const val MARKETPLACE: String = "mondoohq/skills"
    const val REPOSITORY_URL: String = "https://github.com/mondoohq/skills"

    /** A skill, named as the marketplace exposes it. */
    data class Skill(val id: String, val title: String, val description: String)

    val ALL: List<Skill> = listOf(
        Skill("xgrep-triage", "Finding triage", "Decide whether a finding is exploitable here"),
        Skill("xgrep-inspect", "Code inspection", "Navigate code with xgrep's code graph"),
        Skill("xgrep-rule-creator", "Rule authoring", "Write and port xgrep rules"),
        Skill("xgrep-remediate", "Remediation", "Fix a confirmed finding through the verify harness"),
        Skill("xgrep-fix", "Bulk fixes", "Remediate a whole set of findings in one pass"),
        Skill("secure-coding", "Secure coding", "Secure coding guidance across languages"),
        Skill("mondoo-mql", "MQL", "Write MQL queries and Mondoo policies"),
    )

    /** `claude plugin marketplace add mondoohq/skills` */
    fun marketplaceAddArgs(): List<String> = listOf("plugin", "marketplace", "add", MARKETPLACE)

    /** `claude plugin install <id>@mondoohq/skills` */
    fun installArgs(skill: Skill): List<String> =
        listOf("plugin", "install", "${skill.id}@$MARKETPLACE")

    /** The equivalent slash commands, for pasting into an agent session. */
    fun slashCommands(skills: List<Skill>): String =
        (listOf("/plugin marketplace add $MARKETPLACE") + skills.map { "/plugin install ${it.id}@$MARKETPLACE" })
            .joinToString("\n")
}
