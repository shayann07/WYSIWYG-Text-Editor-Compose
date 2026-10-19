package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import java.awt.Color
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

data class WritingCounts(val words: Int, val units: Int, val selected: Int)
fun writingCounts(frame: EditorFrame): WritingCounts {
    val text = frame.committed.text
    var words = 0
    var inside = false
    for (cp in text.codePoints().toArray()) {
        val space = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
        // Joiners/marks alone do not create words; within a run they remain part of that run.
        val content = Character.isLetterOrDigit(cp) || Character.getType(cp) in listOf(Character.OTHER_SYMBOL.toInt(), Character.MATH_SYMBOL.toInt())
        if (space) {
            inside = false
        } else if (content && !inside) {
            words++
            inside = true
        }
    }
    return WritingCounts(words, text.length, frame.selection.end - frame.selection.start)
}
class WritingControls(private val surface: () -> EditorSurface, private val busy: () -> Boolean) {
    val text = JPanel(WrappingControls()).apply {
        isOpaque = false
        name = "text-controls"
    }
    val spacing = JPanel(WrappingControls()).apply {
        isOpaque = false
        name = "spacing-controls"
    }
    val family = JComboBox(arrayOf("Mixed", "Preview font", "System", "Noto Nastaliq", "Gulzar", "Naskh")).apply { name = "stored-font" }
    val size = JComboBox(arrayOf("Mixed", "Preview size", "12", "16", "18", "22", "26", "32", "40", "48", "64", "96")).apply {
        name = "stored-size"
        isEditable = true
        preferredSize = Dimension(104, 26)
    }
    val color = JComboBox(arrayOf("Mixed", "Default ink", "Black", "Blue", "Red", "Green", "Custom…")).apply { name = "stored-color" }
    val underline = JButton("Underline")
    val strike = JButton("Strike")
    val before = JComboBox(arrayOf("Mixed", "0", "4", "8", "12", "16", "24", "32", "48", "72", "100", "200")).apply {
        name = "space-before"
        isEditable = true
        preferredSize = Dimension(70, 26)
    }
    val after = JComboBox(arrayOf("Mixed", "0", "4", "8", "12", "16", "24", "32", "48", "72", "100", "200")).apply {
        name = "space-after"
        isEditable = true
        preferredSize = Dimension(70, 26)
    }
    val line = JComboBox(arrayOf("Mixed", "Natural", "1.25×", "1.5×", "2×", "3×")).apply { name = "line-spacing" }
    private var refresh = false
    private val colors = listOf(-1, 0, 0x174ea6, 0xb3261e, 0x146c43)
    init {
        listOf(family, size, color, before, after, line).forEach(::fitCombo)
        text.add(JLabel("Text"))
        text.add(family)
        text.add(JLabel("Size"))
        text.add(size)
        text.add(underline)
        text.add(strike)
        text.add(color)
        spacing.add(JLabel("Space before"))
        spacing.add(before)
        spacing.add(JLabel("After"))
        spacing.add(after)
        spacing.add(JLabel("Line"))
        spacing.add(line)
        family.toolTipText = "Stored font overrides Preview; missing candidates fall back to System without rewriting the file"
        size.toolTipText = "Stored size 8–96 sp; Preview size resets to window profile"
        color.toolTipText = "Stored RGB text color; Default ink clears override"
        before.toolTipText = "Paragraph space before in document dp (0–200)"
        after.toolTipText = "Paragraph space after in document dp (0–200)"
        line.toolTipText = "Natural metrics, or multiplier of measured natural line advance; never compress glyph boxes"
        fun edit(block: () -> Unit) {
            if (!refresh && surface().admissionAllowed && !busy()) {
                block()
                surface().requestFocusInWindow()
            }
        }
        family.addActionListener { edit { if (family.selectedIndex > 0) surface().viewModel.dispatch(EditorIntent.SetTextStyle(TextStylePatch(font = StoredFont.entries[family.selectedIndex - 1]))) } }
        size.addActionListener {
            edit {
                val v = if (size.selectedItem == "Preview size") 0 else size.selectedItem.toString().toIntOrNull()
                if (v != null && (v == 0 || v in 8..96)) surface().viewModel.dispatch(EditorIntent.SetTextStyle(TextStylePatch(size = v))) else refresh()
            }
        }
        underline.isFocusable = false
        strike.isFocusable = false
        underline.addActionListener { edit { surface().viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.UNDERLINE)) } }
        strike.addActionListener { edit { surface().viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.STRIKE)) } }
        color.addActionListener {
            edit {
                val index = color.selectedIndex
                val rgb = if (index in 1..5) {
                    colors[index - 1]
                } else if (index == 6) {
                    JColorChooser.showDialog(text, "Text color", Color.BLACK)?.rgb?.and(0xffffff)
                } else {
                    null
                }
                if (rgb != null) surface().viewModel.dispatch(EditorIntent.SetTextStyle(TextStylePatch(color = rgb))) else refresh()
            }
        }
        before.addActionListener {
            edit {
                val v = before.selectedItem.toString().toIntOrNull()
                if (v != null && v in 0..200) surface().viewModel.dispatch(EditorIntent.SetParagraphProperties(before = v)) else refresh()
            }
        }
        after.addActionListener {
            edit {
                val v = after.selectedItem.toString().toIntOrNull()
                if (v != null && v in 0..200) surface().viewModel.dispatch(EditorIntent.SetParagraphProperties(after = v)) else refresh()
            }
        }
        line.addActionListener { edit { if (line.selectedIndex > 0) surface().viewModel.dispatch(EditorIntent.SetParagraphProperties(line = listOf(0, 1250, 1500, 2000, 3000)[line.selectedIndex - 1])) } }
    }
    fun refresh() {
        refresh = true
        try {
            val e = surface().viewModel
            family.selectedIndex = e.textStyleState { it.font }?.ordinal?.plus(1) ?: 0
            size.selectedItem = e.textStyleState { it.size }?.let { if (it == 0) "Preview size" else it.toString() } ?: "Mixed"
            val rgb = e.textStyleState { it.color }
            color.selectedIndex = if (rgb == null) 0 else colors.indexOf(rgb).let { if (it < 0) 6 else it + 1 }
            color.toolTipText = if (rgb != null && rgb >= 0) "Stored RGB #%06X; Default ink clears override".format(rgb) else "Default ink or mixed values"
            underline.text = when (e.formatState(FormatKey.UNDERLINE)) {
                true -> "Underline on"
                false -> "Underline"
                null -> "Underline mixed"
            }
            strike.text = when (e.formatState(FormatKey.STRIKE)) {
                true -> "Strike on"
                false -> "Strike"
                null -> "Strike mixed"
            }
            before.selectedItem = e.paragraphState { it.spaceBefore }?.toString() ?: "Mixed"
            after.selectedItem = e.paragraphState { it.spaceAfter }?.toString() ?: "Mixed"
            line.selectedIndex = e.paragraphState { it.linePermille }?.let { listOf(0, 1250, 1500, 2000, 3000).indexOf(it) + 1 } ?: 0
            (text.components + spacing.components).filter { it !is JLabel }.forEach { it.isEnabled = surface().admissionAllowed && !busy() }
        } finally {
            refresh = false
        }
    }
}
