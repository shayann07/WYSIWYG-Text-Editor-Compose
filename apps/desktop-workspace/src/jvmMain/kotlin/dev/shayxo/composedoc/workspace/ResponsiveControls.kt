package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.awt.*
import javax.swing.*

/** Flow controls wrap into measured rows instead of disappearing outside short windows. */
class WrappingControls : FlowLayout(LEFT, 4, 2) {
    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val bound = generateSequence(target as Container?) { it.parent }.map { it.width }.firstOrNull { it > 0 } ?: 900
        val insets = target.insets
        val limit = (bound - insets.left - insets.right - hgap * 2).coerceAtLeast(100)
        var rowWidth = 0
        var rowHeight = 0
        var height = 0
        var width = 0
        for (c in target.components) {
            if (c.isVisible) {
                val d = c.preferredSize
                if (rowWidth > 0 && rowWidth + hgap + d.width > limit) {
                    width = maxOf(width, rowWidth)
                    height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += if (rowWidth == 0)d.width else hgap + d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
        }
        Dimension(maxOf(width, rowWidth) + insets.left + insets.right + hgap * 2, height + rowHeight + insets.top + insets.bottom + vgap * 2)
    }
    override fun minimumLayoutSize(target: Container) = preferredLayoutSize(target)
}
class WritingTabs : JTabbedPane() {
    override fun getPreferredSize(): Dimension {
        val original = super.getPreferredSize()
        val active = selectedComponent ?: return original
        return Dimension(original.width, active.preferredSize.height + getFontMetrics(font).height + insets.top + insets.bottom + 12)
    }
}

/** Keep the editing surface reachable when Find and live recovery rows are both expanded. */
class WritingHeaderScroll(header: JComponent, private val window: JFrame) : JScrollPane(header, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER) {
    init {
        border = null
        viewport.background = header.background
        verticalScrollBar.unitIncrement = 24
    }
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val cap = if (window.height <= 0)220 else (window.height * .42).toInt().coerceIn(130, 260)
        return Dimension(size.width, minOf(size.height, cap))
    }
}

/** A viewport is a validate root: child revalidate alone does not resize a BorderLayout NORTH header. */
fun refreshWritingLayout(window: JFrame, reveal: JComponent? = null) {
    window.contentPane.revalidate()
    window.validate()
    SwingUtilities.invokeLater {
        window.contentPane.revalidate()
        window.validate()
        if (reveal?.isShowing == true)reveal.scrollRectToVisible(Rectangle(0, 0, reveal.width, reveal.height))
    }
}
