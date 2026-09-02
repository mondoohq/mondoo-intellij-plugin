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

class MondooConfigurable : BoundSearchableConfigurable(
    /* displayName = */ "Mondoo",
    /* helpTopic = */ "com.mondoo.intellij.settings",
    /* _id = */ "com.mondoo.intellij.settings",
) {
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
