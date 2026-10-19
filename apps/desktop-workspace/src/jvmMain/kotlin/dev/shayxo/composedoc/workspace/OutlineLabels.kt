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
/** Explicit font fallback for Swing labels; canonical heading text is never changed. */
object OutlineFonts {
    private val primary = Font.createFont(Font.TRUETYPE_FONT, java.io.File("tests/suites/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf")).deriveFont(19f)
    private val logical = Font("Dialog", Font.PLAIN, 19)
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
    override fun getListCellRendererComponent(list: JList<out OutlineEntry>, entry: OutlineEntry, index: Int, selected: Boolean, focused: Boolean): Component = object : JPanel() {
        val indent = (entry.level - 1) * 12
        val available = (list.width.takeIf { it > 0 } ?: 320) - indent - 58
        val frc = FontRenderContext(null, true, true)
        val lines = ArrayList<TextLayout>()
        init {
            val iterator = OutlineFonts.attributed(entry.label).iterator
            val breaker = LineBreakMeasurer(iterator, frc)
            while (breaker.position < iterator.endIndex)lines += breaker.nextLayout(available.coerceAtLeast(80).toFloat())
            preferredSize = Dimension(list.width.takeIf { it > 0 } ?: 320, (lines.sumOf { (it.ascent + it.descent + it.leading).toDouble() } + 20).toInt().coerceAtLeast(44))
            background = if (selected && list.hasFocus()) {
                list.selectionBackground
            } else if (isCurrent(entry.anchor)) {
                Color(0xe8f0fe)
            } else {
                list.background
            }
            getAccessibleContext().accessibleName = "${if (isCurrent(entry.anchor))"Current section. " else ""}Heading ${entry.level}: ${entry.label}"
            toolTipText = getAccessibleContext().accessibleName
        }
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val ink = if (selected && list.hasFocus())list.selectionForeground else list.foreground
            if (isCurrent(entry.anchor)) {
                g.color = Color(0x174ea6)
                g.fillRect(0, 4, 4, height - 8)
            }
            g.color = ink
            g.font = Font("Dialog", Font.BOLD, 12)
            g.drawString("H${entry.level}", 10 + indent, 24)
            var y = 10f
            for (line in lines) {
                y += line.ascent
                val x = if (line.isLeftToRight)44f + indent else width - 10f - line.advance
                line.draw(g, x, y)
                y += line.descent + line.leading
            }
            if (selected && list.hasFocus()) {
                g.color = ink
                g.drawRect(1, 1, width - 3, height - 3)
            }
            g.dispose()
        }
    }
}
