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

    /** mondoo.xgrepScanJobs — 0 lets the server size itself to the CPU. */
    var xgrepScanJobs: Int by property(0)

    /** mondoo.xgrepExcludePatterns — globs never scanned. */
    val xgrepExcludePatterns: MutableList<String> by list()

    /** mondoo.xgrepIncludePatterns — when non-empty, only these are scanned. */
    val xgrepIncludePatterns: MutableList<String> by list()

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
