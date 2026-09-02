package com.mondoo.intellij.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty

/**
 * Settings that only take effect when the language server restarts.
 *
 * The rules path and parallelism are read at server startup, and the scan scope
 * decides which files are ever synced. Changing any of them without a restart looks
 * like the setting did nothing, so [apply] offers one.
 */
private data class ServerAffectingSettings(
    val rulesPath: String,
    val scanJobs: Int,
    val excludePatterns: List<String>,
    val includePatterns: List<String>,
)

class MondooConfigurable : BoundSearchableConfigurable(
    /* displayName = */ "Mondoo",
    /* helpTopic = */ "com.mondoo.intellij.settings",
    /* _id = */ "com.mondoo.intellij.settings",
) {
    override fun apply() {
        val before = serverAffectingSettings()
        super.apply()
        val after = serverAffectingSettings()
        if (before == after) return

        // Broadening the scope is the case that genuinely needs a restart: files
        // skipped under the old scope were never sent to the server at all, so they
        // have no diagnostics to show until it re-reads them.
        val broadened = com.mondoo.intellij.util.XgrepScanScope(before.includePatterns, before.excludePatterns)
            .broadenedBy(com.mondoo.intellij.util.XgrepScanScope(after.includePatterns, after.excludePatterns))
        val reason = when {
            before.rulesPath != after.rulesPath -> "The rules path changed."
            before.scanJobs != after.scanJobs -> "Scan parallelism changed."
            broadened -> "The scan scope was broadened."
            else -> "Scan settings changed."
        }

        com.intellij.openapi.project.ProjectManager.getInstanceIfCreated()
            ?.openProjects
            ?.filterNot { it.isDisposed }
            ?.forEach { project ->
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("Mondoo")
                    .createNotification(
                        "$reason Reload the scanner to apply it.",
                        com.intellij.notification.NotificationType.INFORMATION,
                    )
                    .addAction(
                        com.intellij.notification.NotificationAction.createSimpleExpiring("Reload now") {
                            reloadScanner(project)
                        },
                    )
                    .notify(project)
            }
    }

    private fun serverAffectingSettings(): ServerAffectingSettings {
        val state = MondooSettings.getInstance().state
        return ServerAffectingSettings(
            rulesPath = state.xgrepRulesPath.orEmpty(),
            scanJobs = state.xgrepScanJobs,
            excludePatterns = state.xgrepExcludePatterns.toList(),
            includePatterns = state.xgrepIncludePatterns.toList(),
        )
    }

    /**
     * Restarts the scanner, reached reflectively.
     *
     * The restart lives in the optional LSP module. A direct reference would pull
     * com.intellij.modules.lsp into the core plugin and break loading wherever that
     * module is absent — the whole point of keeping LSP optional. A no-op when the
     * module did not load, which is correct: there is no server to restart.
     */
    private fun reloadScanner(project: com.intellij.openapi.project.Project) {
        runCatching {
            val actionClass = Class.forName(
                "com.mondoo.intellij.lsp.ReloadRulesAction",
                false,
                javaClass.classLoader,
            )
            val companion = actionClass.getDeclaredField("Companion").get(null)
            companion.javaClass
                .getMethod("restart", com.intellij.openapi.project.Project::class.java)
                .invoke(companion, project)
        }
    }

    override fun createPanel(): DialogPanel {
        val state = MondooSettings.getInstance().state
        return panel {
            group("Code Security (xgrep)") {
                row {
                    checkBox("Enable the xgrep security scanner")
                        .bindSelected(state::xgrepEnabled)
                }
                row {
                    checkBox("Download and update the scanner automatically")
                        .bindSelected(state::xgrepAutoInstall)
                }
                row("xgrep path:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor(),
                    )
                        .bindText(state::xgrepPath.toNonNullableProperty(""))
                        .align(AlignX.FILL)
                }.rowComment("Leave empty to resolve <code>xgrep</code> from PATH or a managed install.")
                row("Custom rules path:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor(),
                    )
                        .bindText(state::xgrepRulesPath.toNonNullableProperty(""))
                        .align(AlignX.FILL)
                }.rowComment("Passed as <code>-f</code>. Empty uses the embedded security and secrets rules.")
                row("Scan parallelism:") {
                    intTextField(range = 0..32).bindIntText(state::xgrepScanJobs)
                }.rowComment("0 lets the scanner size itself to your CPU.")
            }
            group("Scan scope") {
                row("Exclude:") {
                    expandableTextField(
                        { it.split("\n").map(String::trim).filter(String::isNotEmpty).toMutableList() },
                        { it.joinToString("\n") },
                    )
                        .bindText(
                            { state.xgrepExcludePatterns.joinToString("\n") },
                            { text ->
                                state.xgrepExcludePatterns.clear()
                                state.xgrepExcludePatterns.addAll(
                                    text.split("\n").map(String::trim).filter(String::isNotEmpty),
                                )
                            },
                        )
                        .align(AlignX.FILL)
                }.rowComment(
                    "One glob per line. <code>*</code> matches within a path segment, " +
                        "<code>**</code> spans segments. A pattern without <code>/</code> " +
                        "matches any segment at any depth, so <code>vendor</code> excludes " +
                        "every vendor directory.",
                )
                row("Include only:") {
                    expandableTextField(
                        { it.split("\n").map(String::trim).filter(String::isNotEmpty).toMutableList() },
                        { it.joinToString("\n") },
                    )
                        .bindText(
                            { state.xgrepIncludePatterns.joinToString("\n") },
                            { text ->
                                state.xgrepIncludePatterns.clear()
                                state.xgrepIncludePatterns.addAll(
                                    text.split("\n").map(String::trim).filter(String::isNotEmpty),
                                )
                            },
                        )
                        .align(AlignX.FILL)
                }.rowComment("When non-empty, only matching files are scanned. An exclude still wins.")
            }
        }
    }
}
