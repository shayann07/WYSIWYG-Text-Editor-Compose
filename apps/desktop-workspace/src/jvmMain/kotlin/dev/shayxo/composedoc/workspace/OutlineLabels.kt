package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.model.OutlineEntry
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

private const val OUTLINE_FONT_SIZE = 19
private const val LEVEL_INDENT_PX = 12
private const val FALLBACK_LIST_WIDTH_PX = 320
private const val TEXT_RESERVED_WIDTH_PX = 58
private const val MIN_WRAP_WIDTH_PX = 80
private const val VERTICAL_PADDING_PX = 20
private const val MIN_ROW_HEIGHT_PX = 44
private const val CURRENT_BACKGROUND_RGB = 0xe8f0fe
private const val CURRENT_BAR_RGB = 0x174ea6
private const val CURRENT_BAR_INSET_PX = 4
private const val CURRENT_BAR_WIDTH_PX = 4
private const val LEVEL_FONT_SIZE = 12
private const val LEVEL_LABEL_X_PX = 10
private const val LEVEL_LABEL_BASELINE_PX = 24
private const val TEXT_TOP_PX = 10f
private const val LTR_TEXT_X_PX = 44f
private const val RTL_TEXT_END_MARGIN_PX = 10f
private const val FOCUS_RECT_SHRINK_PX = 3

/** Explicit font fallback for Swing labels; canonical heading text is never changed. */
object OutlineFonts {
    private val primary = Font.createFont(
        Font.TRUETYPE_FONT,
        java.io.File("tests/suites/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf"),
    ).deriveFont(OUTLINE_FONT_SIZE.toFloat())
    private val logical = Font("Dialog", Font.PLAIN, OUTLINE_FONT_SIZE)
    val choices = java.util.concurrent.ConcurrentHashMap<Int, Font>()
    fun font(cp: Int): Font = choices.computeIfAbsent(cp) { if (primary.canDisplay(cp))primary else logical }
    fun attributed(text: String): AttributedString = AttributedString(text).also { a ->
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val end = i + Character.charCount(cp)
            a.addAttribute(TextAttribute.FONT, font(cp), i, end)
            i = end
        }
    }
}
class OutlineLabelRenderer(private val isCurrent: (Long?) -> Boolean) : ListCellRenderer<OutlineEntry> {
    override fun getListCellRendererComponent(
        list: JList<out OutlineEntry>,
        entry: OutlineEntry,
        index: Int,
        selected: Boolean,
        focused: Boolean,
    ): Component = object : JPanel() {
        val indent = (entry.level - 1) * LEVEL_INDENT_PX
        val available = (list.width.takeIf { it > 0 } ?: FALLBACK_LIST_WIDTH_PX) - indent - TEXT_RESERVED_WIDTH_PX
        val frc = FontRenderContext(null, true, true)
        val lines = ArrayList<TextLayout>()
        init {
            val iterator = OutlineFonts.attributed(entry.label).iterator
            val breaker = LineBreakMeasurer(iterator, frc)
            while (breaker.position <
                iterator.endIndex
            ) {
                lines += breaker.nextLayout(available.coerceAtLeast(MIN_WRAP_WIDTH_PX).toFloat())
            }
            preferredSize =
                Dimension(
                    list.width.takeIf {
                        it > 0
                    } ?: FALLBACK_LIST_WIDTH_PX,
                    (
                        lines.sumOf { (it.ascent + it.descent + it.leading).toDouble() } +
                            VERTICAL_PADDING_PX
                        ).toInt().coerceAtLeast(MIN_ROW_HEIGHT_PX),
                )
            background = if (selected && list.hasFocus()) {
                list.selectionBackground
            } else if (isCurrent(entry.anchor)) {
                Color(CURRENT_BACKGROUND_RGB)
            } else {
                list.background
            }
            getAccessibleContext().accessibleName =
                "${if (isCurrent(entry.anchor))"Current section. " else ""}Heading ${entry.level}: ${entry.label}"
            toolTipText = getAccessibleContext().accessibleName
        }
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val ink = if (selected && list.hasFocus())list.selectionForeground else list.foreground
            if (isCurrent(entry.anchor)) {
                g.color = Color(CURRENT_BAR_RGB)
                g.fillRect(0, CURRENT_BAR_INSET_PX, CURRENT_BAR_WIDTH_PX, height - 2 * CURRENT_BAR_INSET_PX)
            }
            g.color = ink
            g.font = Font("Dialog", Font.BOLD, LEVEL_FONT_SIZE)
            g.drawString("H${entry.level}", LEVEL_LABEL_X_PX + indent, LEVEL_LABEL_BASELINE_PX)
            var y = TEXT_TOP_PX
            for (line in lines) {
                y += line.ascent
                val x = if (line.isLeftToRight)LTR_TEXT_X_PX + indent else width - RTL_TEXT_END_MARGIN_PX - line.advance
                line.draw(g, x, y)
                y += line.descent + line.leading
            }
            if (selected && list.hasFocus()) {
                g.color = ink
                g.drawRect(1, 1, width - FOCUS_RECT_SHRINK_PX, height - FOCUS_RECT_SHRINK_PX)
            }
            g.dispose()
        }
    }
}
