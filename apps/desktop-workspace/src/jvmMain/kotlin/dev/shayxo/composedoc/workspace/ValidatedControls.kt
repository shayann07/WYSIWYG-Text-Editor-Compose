package dev.shayxo.composedoc.workspace

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.WindowConstants

private const val ERROR_COLOR_RGB = 0xb3261e
private const val FORM_HGAP_PX = 8
private const val FORM_VGAP_PX = 6
private const val DIALOG_GAP_PX = 8
private const val DIALOG_EDGE_PX = 12
private const val DIALOG_BOTTOM_EDGE_PX = 8
private const val COMBO_CLOSED_VALUE_ROOM_PX = 36
private const val COMBO_MIN_HEIGHT_PX = 26

/** The same components remain mounted until valid admission or explicit Cancel. */
fun validatedDialog(
    owner: JFrame,
    title: String,
    name: String,
    fields: List<Pair<String, JComponent>>,
    buttons: List<Pair<String, () -> String?>>,
) {
    val dialog = JDialog(owner, title, true).apply {
        this.name = name
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    }
    val error = JLabel(" ").apply {
        this.name = "validation-error"
        foreground = Color(ERROR_COLOR_RGB)
        accessibleContext.accessibleName = "Validation error"
    }
    val form = JPanel(GridLayout(0, 2, FORM_HGAP_PX, FORM_VGAP_PX)).apply {
        fields.forEach { (label, field) ->
            add(JLabel(label))
            add(field)
        }
    }
    val actions = validatedActions(dialog, error, fields, buttons)
    dialog.contentPane = JPanel(BorderLayout(DIALOG_GAP_PX, DIALOG_GAP_PX)).apply {
        border = javax.swing.border.EmptyBorder(DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_BOTTOM_EDGE_PX, DIALOG_EDGE_PX)
        add(form, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                add(error, BorderLayout.NORTH)
                add(actions, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )
    }
    dialog.rootPane.registerKeyboardAction({
        dialog.dispose()
    }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)
    dialog.pack()
    dialog.setLocationRelativeTo(owner)
    dialog.isVisible = true
}

private fun validatedActions(
    dialog: JDialog,
    error: JLabel,
    fields: List<Pair<String, JComponent>>,
    buttons: List<Pair<String, () -> String?>>,
): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
    buttons.forEach { (label, perform) ->
        add(
            JButton(label).apply {
                this.name = label.lowercase().replace(' ', '-')
                addActionListener {
                    val message = perform()
                    if (message == null) {
                        dialog.dispose()
                    } else {
                        error.text = message
                        error.toolTipText = message
                        dialog.pack()
                        fields.firstOrNull()?.second?.requestFocusInWindow()
                    }
                }
            },
        )
    }
    add(
        JButton("Cancel").apply {
            this.name = "cancel"
            addActionListener { dialog.dispose() }
        },
    )
}

/** Ask the renderer/native UI for its size, plus breathing room for closed values. */
fun fitCombo(combo: JComboBox<*>) {
    val d = combo.preferredSize
    val fm = combo.getFontMetrics(combo.font)
    val width = (0 until combo.itemCount).maxOfOrNull { fm.stringWidth(combo.getItemAt(it).toString()) } ?: 0
    combo.preferredSize =
        Dimension(
            maxOf(d.width, width + combo.insets.left + combo.insets.right + COMBO_CLOSED_VALUE_ROOM_PX),
            maxOf(d.height, COMBO_MIN_HEIGHT_PX),
        )
    combo.minimumSize = combo.preferredSize
}
