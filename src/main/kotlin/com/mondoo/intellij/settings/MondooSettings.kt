// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level settings. Keys mirror the VS Code extension's `mondoo.*`
 * setting names so the user documentation can stay shared between the two.
 */
@State(
    name = "MondooSettings",
    storages = [Storage("mondoo.xml")],
    category = SettingsCategory.TOOLS,
)
class MondooSettings : SimplePersistentStateComponent<MondooState>(MondooState()) {
    /**
     * The effective scan scope.
     *
     * Read through here from day one so that moving the patterns to project level
     * later — they are `resource`-scoped in VS Code — is a change in one place
     * rather than a refactor.
     */
    fun scanScope(): com.mondoo.intellij.util.XgrepScanScope = com.mondoo.intellij.util.XgrepScanScope(
        includePatterns = state.xgrepIncludePatterns.toList(),
        excludePatterns = state.xgrepExcludePatterns.toList(),
    )

    companion object {
        @JvmStatic
        fun getInstance(): MondooSettings = service()
    }
}

class MondooState : BaseState() {
    /** mondoo.xgrepEnabled */
    var xgrepEnabled: Boolean by property(true)

    /** mondoo.xgrepAutoInstall */
    var xgrepAutoInstall: Boolean by property(true)

    /** mondoo.xgrepPath — empty means auto-discover. */
    var xgrepPath: String? by string("")

    /** mondoo.xgrepRulesPath — empty means the embedded security+secrets corpus. */
    var xgrepRulesPath: String? by string("")

    /**
     * mondoo.xgrepScanJobs — how many files on-demand scans process in parallel.
     *
     * 0 uses the scanner's own default, which is `min(NumCPU / 2, 4)`: never more
     * than four workers and never more than half the machine's cores. That is
     * already an editor-appropriate cap, so raising this is the unusual choice, not
     * lowering it.
     */
    var xgrepScanJobs: Int by property(0)

    /**
     * mondoo.xgrepFixAgent — the coding agent `xgrep fix serve` hands assisted fixes
     * to: `claude`, `codex`, or a command line with a `{prompt}` placeholder. Empty
     * leaves the choice to xgrep (XGREP_AGENT, its config, then claude), so the IDE
     * and the terminal agree unless the user says otherwise.
     */
    var xgrepFixAgent: String? by string("")

    /**
     * mondoo.xgrepScanUncommitted — in a git repository, whether workspace scans and
     * the Fix tab's scan include files not committed yet. `xgrep scan` covers
     * committed files only by default; in an IDE the file you just created is
     * usually the one you are working on, so this is on. `.gitignore`d files are
     * never scanned either way.
     */
    var xgrepScanUncommitted: Boolean by property(true)

    /** mondoo.xgrepExcludePatterns — globs never scanned. */
    val xgrepExcludePatterns: MutableList<String> by list()

    /** mondoo.xgrepIncludePatterns — when non-empty, only these are scanned. */
    val xgrepIncludePatterns: MutableList<String> by list()

    /**
     * mondoo.configPath — the Mondoo configuration (service account) xgrep and cnspec
     * use, passed as MONDOO_CONFIG_PATH. Empty uses their default: MONDOO_CONFIG_PATH
     * from the environment, else ~/.config/mondoo/mondoo.yml.
     */
    var mondooConfigPath: String? by string("")

    // --- Infrastructure security (cnspec) ---

    /** mondoo.cnspecEnabled — MQL language support. */
    var cnspecEnabled: Boolean by property(true)

    /** mondoo.cnspecPath — empty means auto-discover. Never auto-installed. */
    var cnspecPath: String? by string("")

    // --- LR resource definitions (mqlr) ---

    /** mondoo.mqlrEnabled — language support for `.lr` and `.mqlr` files. */
    var mqlrEnabled: Boolean by property(true)

    /** mondoo.mqlrPath — empty means auto-discover. Never auto-installed. */
    var mqlrPath: String? by string("")

    // --- Managed-install bookkeeping. Not user-editable; see XgrepBinaryService. ---

    /** Last version resolved from the release manifest. */
    var resolvedVersion: String? by string(null)

    /** When [resolvedVersion] was last refreshed, epoch millis. 0 means never. */
    var resolvedCheckedAt: Long by property(0L)

    /**
     * The version the user last agreed to download. Consent is per version, as in
     * the VS Code extension: agreeing to one release is not agreement to every
     * future one.
     */
    var installConsentVersion: String? by string(null)
}
