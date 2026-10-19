package dev.shayxo.composedoc.workspace

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

private const val WRAP_HGAP_PX = 4
private const val WRAP_VGAP_PX = 2
private const val FALLBACK_BOUND_WIDTH_PX = 900
private const val MIN_ROW_LIMIT_PX = 100
private const val TAB_EXTRA_HEIGHT_PX = 12
private const val HEADER_SCROLL_UNIT_PX = 24
private const val HEADER_CAP_UNSIZED_PX = 220
private const val HEADER_CAP_WINDOW_FRACTION = .42
private const val HEADER_CAP_MIN_PX = 130
private const val HEADER_CAP_MAX_PX = 260

/** Flow controls wrap into measured rows instead of disappearing outside short windows. */
class WrappingControls : FlowLayout(LEFT, WRAP_HGAP_PX, WRAP_VGAP_PX) {
    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val bound =
            generateSequence(target as Container?) { it.parent }.map { it.width }.firstOrNull { it > 0 }
                ?: FALLBACK_BOUND_WIDTH_PX
        val insets = target.insets
        val limit = (bound - insets.left - insets.right - hgap * 2).coerceAtLeast(MIN_ROW_LIMIT_PX)
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
        Dimension(
            maxOf(width, rowWidth) + insets.left + insets.right + hgap * 2,
            height + rowHeight + insets.top + insets.bottom + vgap * 2,
        )
    }
    override fun minimumLayoutSize(target: Container) = preferredLayoutSize(target)
}
class WritingTabs : JTabbedPane() {
    override fun getPreferredSize(): Dimension {
        val original = super.getPreferredSize()
        val active = selectedComponent ?: return original
        return Dimension(
            original.width,
            active.preferredSize.height + getFontMetrics(font).height + insets.top + insets.bottom +
                TAB_EXTRA_HEIGHT_PX,
        )
    }
}

/** Keep the editing surface reachable when Find and live recovery rows are both expanded. */
class WritingHeaderScroll(header: JComponent, private val window: JFrame) :
    JScrollPane(header, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER) {
    init {
        border = null
        viewport.background = header.background
        verticalScrollBar.unitIncrement = HEADER_SCROLL_UNIT_PX
    }
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val cap = if (window.height <= 0) {
            HEADER_CAP_UNSIZED_PX
        } else {
            (window.height * HEADER_CAP_WINDOW_FRACTION).toInt().coerceIn(HEADER_CAP_MIN_PX, HEADER_CAP_MAX_PX)
        }
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
