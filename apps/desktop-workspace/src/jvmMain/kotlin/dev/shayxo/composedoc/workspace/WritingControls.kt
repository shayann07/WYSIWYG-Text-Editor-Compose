package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.EditorViewModel
import java.awt.Color
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

private const val SIZE_COMBO_WIDTH_PX = 104
private const val SPACE_COMBO_WIDTH_PX = 70
private const val COMBO_HEIGHT_PX = 26
private const val MIN_STORED_SIZE_SP = 8
private const val MAX_STORED_SIZE_SP = 96
private const val MAX_PARAGRAPH_SPACE_DP = 200
private const val DEFAULT_INK = -1
private const val BLACK_RGB = 0
private const val BLUE_RGB = 0x174ea6
private const val RED_RGB = 0xb3261e
private const val GREEN_RGB = 0x146c43
private const val RGB_MASK = 0xffffff
private const val CUSTOM_COLOR_INDEX = 6
private const val NATURAL_LINE_PERMILLE = 0
private const val LINE_1_25_PERMILLE = 1250
private const val LINE_1_5_PERMILLE = 1500
private const val LINE_2_PERMILLE = 2000
private const val LINE_3_PERMILLE = 3000
private val LINE_PERMILLE_CHOICES =
    listOf(NATURAL_LINE_PERMILLE, LINE_1_25_PERMILLE, LINE_1_5_PERMILLE, LINE_2_PERMILLE, LINE_3_PERMILLE)

data class WritingCounts(val words: Int, val units: Int, val selected: Int)
fun writingCounts(frame: EditorFrame): WritingCounts {
    val text = frame.committed.text
    var words = 0
    var inside = false
    for (cp in text.codePoints().toArray()) {
        val space = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
        // Joiners/marks alone do not create words; within a run they remain part of that run.
        val content =
            Character.isLetterOrDigit(cp) ||
                Character.getType(cp) in listOf(Character.OTHER_SYMBOL.toInt(), Character.MATH_SYMBOL.toInt())
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
    val family = JComboBox(arrayOf("Mixed", "Preview font", "System", "Noto Nastaliq", "Gulzar", "Naskh")).apply {
        name =
            "stored-font"
    }
    val size = JComboBox(
        arrayOf("Mixed", "Preview size", "12", "16", "18", "22", "26", "32", "40", "48", "64", "96"),
    ).apply {
        name = "stored-size"
        isEditable = true
        preferredSize = Dimension(SIZE_COMBO_WIDTH_PX, COMBO_HEIGHT_PX)
    }
    val color = JComboBox(arrayOf("Mixed", "Default ink", "Black", "Blue", "Red", "Green", "Custom…")).apply {
        name =
            "stored-color"
    }
    val underline = JButton("Underline")
    val strike = JButton("Strike")
    val before = JComboBox(arrayOf("Mixed", "0", "4", "8", "12", "16", "24", "32", "48", "72", "100", "200")).apply {
        name = "space-before"
        isEditable = true
        preferredSize = Dimension(SPACE_COMBO_WIDTH_PX, COMBO_HEIGHT_PX)
    }
    val after = JComboBox(arrayOf("Mixed", "0", "4", "8", "12", "16", "24", "32", "48", "72", "100", "200")).apply {
        name = "space-after"
        isEditable = true
        preferredSize = Dimension(SPACE_COMBO_WIDTH_PX, COMBO_HEIGHT_PX)
    }
    val line = JComboBox(arrayOf("Mixed", "Natural", "1.25×", "1.5×", "2×", "3×")).apply { name = "line-spacing" }
    private var refresh = false
    private val colors = listOf(DEFAULT_INK, BLACK_RGB, BLUE_RGB, RED_RGB, GREEN_RGB)
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
        family.toolTipText =
            "Stored font overrides Preview; missing candidates fall back to System without rewriting the file"
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
        family.addActionListener {
            edit {
                if (family.selectedIndex >
                    0
                ) {
                    surface().viewModel.dispatch(
                        EditorIntent.SetTextStyle(
                            TextStylePatch(
                                font = StoredFont.entries[
                                    family.selectedIndex -
                                        1,
                                ],
                            ),
                        ),
                    )
                }
            }
        }
        size.addActionListener {
            edit {
                val v = if (size.selectedItem == "Preview size") 0 else size.selectedItem.toString().toIntOrNull()
                if (v != null &&
                    (v == 0 || v in MIN_STORED_SIZE_SP..MAX_STORED_SIZE_SP)
                ) {
                    surface().viewModel.dispatch(EditorIntent.SetTextStyle(TextStylePatch(size = v)))
                } else {
                    refresh()
                }
            }
        }
        underline.isFocusable = false
        strike.isFocusable = false
        underline.addActionListener {
            edit { surface().viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.UNDERLINE)) }
        }
        strike.addActionListener { edit { surface().viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.STRIKE)) } }
        color.addActionListener {
            edit {
                val index = color.selectedIndex
                val rgb = if (index in 1 until CUSTOM_COLOR_INDEX) {
                    colors[index - 1]
                } else if (index == CUSTOM_COLOR_INDEX) {
                    JColorChooser.showDialog(text, "Text color", Color.BLACK)?.rgb?.and(RGB_MASK)
                } else {
                    null
                }
                if (rgb !=
                    null
                ) {
                    surface().viewModel.dispatch(EditorIntent.SetTextStyle(TextStylePatch(color = rgb)))
                } else {
                    refresh()
                }
            }
        }
        before.addActionListener {
            edit {
                val v = before.selectedItem.toString().toIntOrNull()
                if (v != null &&
                    v in 0..MAX_PARAGRAPH_SPACE_DP
                ) {
                    surface().viewModel.dispatch(EditorIntent.SetParagraphProperties(before = v))
                } else {
                    refresh()
                }
            }
        }
        after.addActionListener {
            edit {
                val v = after.selectedItem.toString().toIntOrNull()
                if (v != null &&
                    v in 0..MAX_PARAGRAPH_SPACE_DP
                ) {
                    surface().viewModel.dispatch(EditorIntent.SetParagraphProperties(after = v))
                } else {
                    refresh()
                }
            }
        }
        line.addActionListener {
            edit {
                if (line.selectedIndex >
                    0
                ) {
                    surface().viewModel.dispatch(
                        EditorIntent.SetParagraphProperties(
                            line = LINE_PERMILLE_CHOICES[
                                line.selectedIndex -
                                    1,
                            ],
                        ),
                    )
                }
            }
        }
    }
    fun refresh() {
        refresh = true
        try {
            val e = surface().viewModel
            refreshTextStyle(e)
            refreshParagraph(e)
            refreshEnabled()
        } finally {
            refresh = false
        }
    }

    private fun refreshTextStyle(e: EditorViewModel) {
        family.selectedIndex = e.textStyleState { it.font }?.ordinal?.plus(1) ?: 0
        size.selectedItem =
            e.textStyleState { it.size }?.let { if (it == 0) "Preview size" else it.toString() } ?: "Mixed"
        val rgb = e.textStyleState { it.color }
        color.selectedIndex =
            if (rgb == null) 0 else colors.indexOf(rgb).let { if (it < 0) CUSTOM_COLOR_INDEX else it + 1 }
        color.toolTipText =
            if (rgb != null &&
                rgb >= 0
            ) {
                "Stored RGB #%06X; Default ink clears override".format(rgb)
            } else {
                "Default ink or mixed values"
            }
        underline.text = formatLabel("Underline", e.formatState(FormatKey.UNDERLINE))
        strike.text = formatLabel("Strike", e.formatState(FormatKey.STRIKE))
    }

    private fun formatLabel(name: String, state: Boolean?) = when (state) {
        true -> "$name on"
        false -> name
        null -> "$name mixed"
    }

    private fun refreshParagraph(e: EditorViewModel) {
        before.selectedItem = e.paragraphState { it.spaceBefore }?.toString() ?: "Mixed"
        after.selectedItem = e.paragraphState { it.spaceAfter }?.toString() ?: "Mixed"
        line.selectedIndex =
            e.paragraphState { it.linePermille }?.let { LINE_PERMILLE_CHOICES.indexOf(it) + 1 } ?: 0
    }

    private fun refreshEnabled() {
        (text.components + spacing.components).filter { it !is JLabel }.forEach {
            it.isEnabled =
                surface().admissionAllowed &&
                !busy()
        }
    }
}
