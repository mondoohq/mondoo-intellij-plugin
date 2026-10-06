// Copyright Mondoo, Inc. 2026
// SPDX-License-Identifier: Apache-2.0

package com.mondoo.intellij.dependencies

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * An icon per package ecosystem for the Dependencies tab.
 *
 * The IDE's own icon for the ecosystem's language when it has one (the npm row gets
 * the JavaScript icon, pypi the Python one), so it matches the rest of the IDE and
 * the theme. Where the IDE does not know the language — IntelliJ IDEA without the
 * Python plugin — a small badge in the ecosystem's colour, so every row still says
 * at a glance where the package comes from.
 */
object EcosystemIcons {

    private data class Ecosystem(val extension: String, val badge: String, val color: Color)

    private val known = mapOf(
        "npm" to Ecosystem("js", "js", Color(0xCB3837)),
        "pypi" to Ecosystem("py", "py", Color(0x3775A9)),
        "golang" to Ecosystem("go", "go", Color(0x00ADD8)),
        "maven" to Ecosystem("java", "mvn", Color(0xC71A36)),
        "gradle" to Ecosystem("java", "gr", Color(0x02303A)),
        "cargo" to Ecosystem("rs", "rs", Color(0xB7410E)),
        "gem" to Ecosystem("rb", "rb", Color(0xCC342D)),
        "nuget" to Ecosystem("cs", "nu", Color(0x004880)),
        "composer" to Ecosystem("php", "php", Color(0x777BB4)),
        "pub" to Ecosystem("dart", "dt", Color(0x0175C2)),
        "hex" to Ecosystem("ex", "ex", Color(0x6E4A7E)),
        "swift" to Ecosystem("swift", "sw", Color(0xF05138)),
        "cocoapods" to Ecosystem("swift", "pod", Color(0xEE3322)),
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Icon>()

    fun forEcosystem(ecosystem: String): Icon = cache.getOrPut(ecosystem.lowercase()) {
        val e = known[ecosystem.lowercase()] ?: return@getOrPut AllIcons.Nodes.PpLib
        val type = FileTypeManager.getInstance().getFileTypeByExtension(e.extension)
        if (type is UnknownFileType || type is PlainTextFileType || type.icon == null) {
            Badge(e.badge, e.color)
        } else {
            type.icon!!
        }
    }

    /** A rounded square in the ecosystem's colour with a short label. */
    private class Badge(private val text: String, private val color: Color) : Icon {
        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val inset = JBUI.scale(1)
                val size = iconWidth - 2 * inset
                g2.color = color
                g2.fillRoundRect(x + inset, y + inset, size, size, JBUI.scale(4), JBUI.scale(4))
                g2.font =
                    JBFont.small().deriveFont(
                        java.awt.Font.BOLD,
                        JBUI.scaleFontSize(
                            if (text.length >
                                2
                            ) {
                                6.5f
                            } else {
                                8f
                            },
                        ).toFloat(),
                    )
                val m = g2.fontMetrics
                g2.color = JBColor.WHITE
                g2.drawString(
                    text,
                    x + (iconWidth - m.stringWidth(text)) / 2,
                    y + (iconHeight + m.ascent - m.descent) / 2,
                )
            } finally {
                g2.dispose()
            }
        }
    }
}
