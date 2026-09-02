// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MondooSkillsTest {

    @Test
    fun `builds the documented marketplace command`() {
        assertEquals(
            listOf("plugin", "marketplace", "add", "mondoohq/skills"),
            MondooSkills.marketplaceAddArgs(),
        )
    }

    @Test
    fun `builds the documented install command`() {
        val triage = MondooSkills.ALL.first { it.id == "xgrep-triage" }
        assertEquals(
            listOf("plugin", "install", "xgrep-triage@mondoohq/skills"),
            MondooSkills.installArgs(triage),
        )
    }

    @Test
    fun `offers exactly the skills the repository publishes`() {
        assertEquals(
            setOf(
                "xgrep-triage", "xgrep-inspect", "xgrep-rule-creator", "xgrep-remediate",
                "xgrep-fix", "secure-coding", "mondoo-mql",
            ),
            MondooSkills.ALL.map { it.id }.toSet(),
        )
    }

    @Test
    fun `every skill has a title and description for the chooser`() {
        MondooSkills.ALL.forEach {
            assertTrue(it.title.isNotBlank(), it.id)
            assertTrue(it.description.isNotBlank(), it.id)
        }
    }

    @Test
    fun `slash commands register the marketplace before installing`() {
        // Installing first fails with "Marketplace not found", so order matters.
        val lines = MondooSkills.slashCommands(listOf(MondooSkills.ALL.first())).lines()
        assertTrue(lines.first().startsWith("/plugin marketplace add mondoohq/skills"), lines.first())
        assertTrue(lines[1].startsWith("/plugin install "), lines[1])
    }

    @Test
    fun `slash commands cover every requested skill`() {
        val text = MondooSkills.slashCommands(MondooSkills.ALL)
        MondooSkills.ALL.forEach { assertTrue(text.contains("${it.id}@mondoohq/skills"), it.id) }
    }
}
