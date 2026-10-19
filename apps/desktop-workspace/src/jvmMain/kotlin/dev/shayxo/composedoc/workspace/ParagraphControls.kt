package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

private const val CONTROL_GAP_PX = 4

/** Shell consumer only; targeting, inheritance, settlement and history live in document/commands. */
class ParagraphControls(private val surface: () -> EditorSurface, private val fileBusy: () -> Boolean) {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, CONTROL_GAP_PX, 0)).apply {
        isOpaque = false
        name = "paragraph-controls"
    }
    val direction = JComboBox(arrayOf("Mixed", "Auto", "RTL", "LTR")).apply { name = "paragraph-direction" }
    val alignment = JComboBox(arrayOf("Mixed", "Start", "Center", "End")).apply { name = "paragraph-alignment" }
    private var refreshing = false
    init {
        panel.add(JLabel("Direction"))
        panel.add(direction)
        panel.add(JLabel("Align"))
        panel.add(alignment)
        direction.toolTipText = "Paragraph base direction; stored text order is unchanged"
        alignment.toolTipText = "Start/End follow resolved paragraph direction; no justification"
        direction.addActionListener {
            if (!refreshing && direction.selectedIndex > 0 && canEdit()) {
                surface().viewModel.dispatch(
                    EditorIntent.SetParagraphProperties(
                        direction = ParagraphDirection.entries[
                            direction.selectedIndex -
                                1,
                        ],
                    ),
                )
                surface().requestFocusInWindow()
            } else if (!refreshing && direction.selectedIndex == 0) {
                refresh()
            }
        }
        alignment.addActionListener {
            if (!refreshing && alignment.selectedIndex > 0 && canEdit()) {
                surface().viewModel.dispatch(
                    EditorIntent.SetParagraphProperties(
                        alignment = ParagraphAlignment.entries[
                            alignment.selectedIndex -
                                1,
                        ],
                    ),
                )
                surface().requestFocusInWindow()
            } else if (!refreshing && alignment.selectedIndex == 0) {
                refresh()
            }
        }
    }
    private fun canEdit() = surface().admissionAllowed && !fileBusy()

    fun refresh() {
        refreshing = true
        try {
            direction.selectedIndex = surface().viewModel.paragraphDirectionState()?.ordinal?.plus(1) ?: 0
            alignment.selectedIndex = surface().viewModel.paragraphAlignmentState()?.ordinal?.plus(1) ?: 0
            direction.isEnabled = !fileBusy() && surface().admissionAllowed
            alignment.isEnabled = direction.isEnabled
        } finally {
            refreshing = false
        }
    }
}
