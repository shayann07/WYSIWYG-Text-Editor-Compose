package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.FindSession
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val FIELD_COLUMNS = 18
private const val FIELD_FONT_SIZE = 15
private const val STATUS_FONT_SIZE = 12
private const val COUNT_WIDTH_PX = 82
private const val COUNT_HEIGHT_PX = 26
private const val ROW_HGAP_PX = 6
private const val ROW_VGAP_PX = 2
private const val FIELD_WIDTH_PX = 230
private const val FIELD_HEIGHT_PX = 32
private const val STATUS_SIDE_PX = 6
private const val STATUS_BOTTOM_PX = 3

class FindReplaceUi(
    private val window: JFrame,
    private val surface: () -> EditorSurface,
    private val relayout: () -> Unit,
) {
    val session = FindSession(surface)
    val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        name = "find-panel"
        isVisible = false
    }
    val query = JTextArea(1, FIELD_COLUMNS).apply {
        name = "find-query"
        font = Font("Segoe UI", Font.PLAIN, FIELD_FONT_SIZE)
    }
    val replacement = JTextArea(1, FIELD_COLUMNS).apply {
        name = "find-replacement"
        font = Font("Segoe UI", Font.PLAIN, FIELD_FONT_SIZE)
    }
    val count = JLabel("0 / 0").apply {
        name = "find-count"
        preferredSize = Dimension(COUNT_WIDTH_PX, COUNT_HEIGHT_PX)
    }
    val status = JLabel().apply {
        name = "find-status"
        font = Font("Segoe UI", Font.PLAIN, STATUS_FONT_SIZE)
    }
    private var message = ""
    private var inRefresh = false
    private val replaceOne = JButton("Replace").apply {
        name = "find-replace"
        isFocusable = false
    }
    private val replaceAll = JButton("Replace All").apply {
        name = "find-replace-all"
        isFocusable = false
    }
    private val previous = JButton("Previous").apply { isFocusable = false }
    private val next = JButton("Next").apply { isFocusable = false }
    private val replacementRow =
        JPanel(FlowLayout(FlowLayout.LEFT, ROW_HGAP_PX, ROW_VGAP_PX)).apply { isOpaque = false }
    private fun field(area: JTextArea) = JScrollPane(area).apply {
        preferredSize = Dimension(FIELD_WIDTH_PX, FIELD_HEIGHT_PX)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
    }
    private fun action(
        target: JComponent,
        key: String,
        name: String,
        condition: Int = JComponent.WHEN_FOCUSED,
        perform: () -> Unit,
    ) {
        target.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name)
        target.actionMap.put(
            name,
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    perform()
                }
            },
        )
    }
    init {
        panel.add(
            JPanel(FlowLayout(FlowLayout.LEFT, ROW_HGAP_PX, ROW_VGAP_PX)).apply {
                isOpaque = false
                add(JLabel("Find"))
                add(field(query))
                add(previous)
                add(next)
                add(count)
                add(
                    JButton("Close (Esc)").apply {
                        isFocusable = false
                        addActionListener { close() }
                    },
                )
            },
        )
        replacementRow.add(JLabel("With"))
        replacementRow.add(field(replacement))
        replacementRow.add(replaceOne)
        replacementRow.add(replaceAll)
        panel.add(replacementRow)
        panel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = javax.swing.border.EmptyBorder(0, STATUS_SIDE_PX, STATUS_BOTTOM_PX, STATUS_SIDE_PX)
                add(status)
            },
        )
        previous.addActionListener {
            session.navigate(true)
            message = ""
            refresh()
        }
        next.addActionListener {
            session.navigate(false)
            message = ""
            refresh()
        }
        replaceOne.addActionListener { replace(false) }
        replaceAll.addActionListener { replace(true) }
        query.document.addDocumentListener(object : DocumentListener {
            fun changed() {
                message = ""
                session.search(query.text)
                refresh()
            }
            override fun insertUpdate(e: DocumentEvent?) = changed()
            override fun removeUpdate(e: DocumentEvent?) = changed()
            override fun changedUpdate(e: DocumentEvent?) = changed()
        })
        for (area in listOf(query, replacement)) {
            action(area, "ESCAPE", "find.close") { close() }
            action(area, "ENTER", "find.next") {
                session.navigate(false)
                message = ""
                refresh()
            }
            action(area, "shift ENTER", "find.previous") {
                session.navigate(true)
                message = ""
                refresh()
            }
            action(area, "ctrl ENTER", "find.softBreak") { area.replaceSelection("\n") }
            action(area, "ctrl F", "find.show") { open(false) }
            action(area, "ctrl H", "find.showReplace") { open(true) }
            val undo = javax.swing.undo.UndoManager()
            area.document.addUndoableEditListener(undo)
            action(area, "ctrl Z", "field.undo") { if (undo.canUndo()) undo.undo() }
            action(area, "ctrl Y", "field.redo") { if (undo.canRedo()) undo.redo() }
        }
        action(window.rootPane, "ctrl F", "editor.find", JComponent.WHEN_IN_FOCUSED_WINDOW) { open(false) }
        action(window.rootPane, "ctrl H", "editor.replace", JComponent.WHEN_IN_FOCUSED_WINDOW) { open(true) }
        action(window.rootPane, "ESCAPE", "editor.closeFind", JComponent.WHEN_IN_FOCUSED_WINDOW) {
            if (panel.isVisible) close()
        }
    }
    fun open(withReplace: Boolean) {
        panel.isVisible = true
        replacementRow.isVisible = withReplace
        message = ""
        session.refresh()
        refresh()
        session.reveal()
        relayout()
        refreshWritingLayout(window, panel)
        query.requestFocusInWindow()
        query.selectAll()
    }
    fun close() {
        panel.isVisible = false
        val s = surface()
        s.view.findMatches = emptyList()
        s.view.findDocument = null
        s.view.findReveal = null
        s.view.ensureCaret = true
        relayout()
        s.requestFocusInWindow()
        refreshWritingLayout(window)
    }
    private fun replace(all: Boolean) {
        val amount = if (all) session.matches.size else 1
        val outcome = session.replace(replacement.text, all)
        message = when (outcome) {
            FindReplaceResult.Applied -> "Replaced $amount."
            FindReplaceResult.NoChange -> "No text/style change."
            FindReplaceResult.Busy -> "Finish document composition/recovery first."
            FindReplaceResult.Stale -> "Document changed; results refreshed. Try again."
            FindReplaceResult.Invalid -> "Match boundary is unsupported; nothing replaced."
            FindReplaceResult.TooLarge -> "Exceeds the prototype 100,000 UTF-16-unit file limit; nothing replaced."
        }
        refresh()
        query.requestFocusInWindow()
    }
    fun refresh() {
        if (inRefresh || !panel.isVisible) return
        inRefresh = true
        try {
            session.refresh()
            val s = surface()
            val matches = session.matches
            count.text = "${if (session.active < 0) 0 else session.active + 1} / ${matches.size}"
            val safe = session.admitted() && query.text.isNotEmpty()
            replaceOne.isEnabled = safe && session.current?.editable == true
            replaceAll.isEnabled = safe && matches.isNotEmpty() && matches.all { it.editable }
            previous.isEnabled = safe && matches.isNotEmpty()
            next.isEnabled = previous.isEnabled
            val warning = when {
                session.blocked -> "Finish document composition/recovery first."
                matches.any {
                    !it.editable
                } -> "${matches.count {
                    !it.editable
                }} split a grapheme: no expanded replacement; Replace All disabled."
                else -> "Within hard paragraphs; Ctrl+Enter = soft break."
            }
            status.text = "Exact Unicode · case-sensitive · $warning $message"
            status.toolTipText = status.text
            s.view.findDocument = session.basis?.document
            s.view.findMatches = matches
            s.view.findActive = session.active
        } finally {
            inRefresh = false
        }
    }
}
