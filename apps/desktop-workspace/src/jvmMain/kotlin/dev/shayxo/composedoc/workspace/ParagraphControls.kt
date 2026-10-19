package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.awt.FlowLayout
import javax.swing.*

/** Shell consumer only; targeting, inheritance, settlement and history live in document/commands. */
class ParagraphControls(private val surface: () -> EditorSurface, private val fileBusy: () -> Boolean) {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
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
            if (!refreshing && direction.selectedIndex > 0 && surface().admissionAllowed && !fileBusy()) {
                surface().engine.setParagraphProperties(direction = ParagraphDirection.entries[direction.selectedIndex - 1])
                surface().requestFocusInWindow()
            } else if (!refreshing && direction.selectedIndex == 0) {
                refresh()
            }
        }
        alignment.addActionListener {
            if (!refreshing && alignment.selectedIndex > 0 && surface().admissionAllowed && !fileBusy()) {
                surface().engine.setParagraphProperties(alignment = ParagraphAlignment.entries[alignment.selectedIndex - 1])
                surface().requestFocusInWindow()
            } else if (!refreshing && alignment.selectedIndex == 0) {
                refresh()
            }
        }
    }
    fun refresh() {
        refreshing = true
        try {
            direction.selectedIndex = surface().engine.paragraphDirectionState()?.ordinal?.plus(1) ?: 0
            alignment.selectedIndex = surface().engine.paragraphAlignmentState()?.ordinal?.plus(1) ?: 0
            direction.isEnabled = !fileBusy() && surface().admissionAllowed
            alignment.isEnabled = direction.isEnabled
        } finally {
            refreshing = false
        }
    }
}
