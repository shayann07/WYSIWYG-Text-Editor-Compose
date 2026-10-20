package consumer

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.DesktopEditor
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorPlatformServices
import dev.shayxo.composedoc.ui.EditorViewModel
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingWorker
import javax.swing.WindowConstants

internal fun openWindow(model: EditorViewModel, profile: TypographyProfile, title: String) {
    ConsumerWindow(model, profile, title).showEditor()
}

/** Mounted Swing listeners, not native keyboard evidence. */
internal fun checkConsumerToolbar(examples: EditorExamples) {
    val window = ConsumerWindow(examples.restored, examples.profile, "Disposable consumer handler check")
    try {
        window.checkToolbar()
    } finally {
        window.disposeEditor()
    }
}

private class ConsumerWindow(
    private val model: EditorViewModel,
    private val profile: TypographyProfile,
    title: String,
) : JFrame(title) {
    private val editor = DesktopEditor(model, profile, EditorPlatformServices(DocumentFiles, FragmentCodec))
    private val status = JLabel("Independent owner; Save creates a new private copy")
    private var lastSave: Path? = null
    private val buttons = mutableMapOf<String, JButton>()
    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        add(editor, BorderLayout.CENTER)
        add(toolbar(), BorderLayout.NORTH)
        add(status, BorderLayout.SOUTH)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                runCatching { editor.close() }.onSuccess {
                    model.detach()
                    dispose()
                }.onFailure { status.text = it.message }
            }
        })
    }
    fun showEditor() {
        pack()
        setLocationByPlatform(true)
        isVisible = true
        editor.requestEditorFocus()
    }
    private fun toolbar() = JPanel().apply {
        listOf(
            "Bold" to EditorIntent.ToggleFormat(FormatKey.BOLD),
            "Undo" to EditorIntent.Command(EditAction.Undo),
            "Redo" to EditorIntent.Command(EditAction.Redo),
        )
            .forEach { (label, intent) ->
                add(
                    JButton(label).apply {
                        this@ConsumerWindow.buttons[label] = this
                        addActionListener {
                            this@ConsumerWindow.model.dispatch(intent)
                            editor.requestEditorFocus()
                        }
                    },
                )
            }
        add(JButton("Save copy").apply { addActionListener { saveCopy() } })
        add(JButton("Open saved copy").apply { addActionListener { openSavedCopy() } })
    }
    fun checkToolbar() {
        val before = model.snapshot()
        val word = "کتاب".length
        val selection = Selection(word, 0)
        model.dispatch(EditorIntent.Select(selection))
        val expected = before.committed.copy(
            units = before.committed.units.mapIndexed { index, unit ->
                if (index < word) unit.copy(style = unit.style.copy(bold = false)) else unit
            },
        )
        buttons.getValue("Bold").doClick()
        check(model.snapshot().committed.same(expected) && model.snapshot().selection == selection)
        check(model.snapshot().undoCount == before.undoCount + 1)
        buttons.getValue("Undo").doClick()
        check(model.snapshot().committed.same(before.committed))
        buttons.getValue("Redo").doClick()
        check(model.snapshot().committed.same(expected) && model.snapshot().selection == selection)
        buttons.getValue("Undo").doClick()
        check(model.snapshot().committed.same(before.committed) && model.snapshot().undoCount == before.undoCount)
    }
    fun disposeEditor() {
        editor.close()
        dispose()
    }

    private fun saveCopy() {
        if (editor.hasUnfinishedInput) {
            status.text = "Finish/export unfinished input before Save"
            return
        }
        val captured = model.snapshot().committed
        object : SwingWorker<Path, Unit>() {
            override fun doInBackground(): Path {
                val file = Files.createTempDirectory("composedoc-saved-copy-").resolve("document.astra")
                val repository: DocumentRepository = DocumentFiles
                repository.save(file.toString(), captured)
                return file
            }
            override fun done() {
                runCatching { get() }.onSuccess {
                    lastSave = it
                    status.text = "Saved $it"
                }
                    .onFailure { status.text = "Save failed: ${it.message}" }
                editor.requestEditorFocus()
            }
        }.execute()
    }
    private fun openSavedCopy() {
        val file = lastSave ?: return
        object : SwingWorker<Document, Unit>() {
            override fun doInBackground(): Document {
                val repository: DocumentRepository = DocumentFiles
                return repository.load(file.toString())
            }
            override fun done() {
                runCatching {
                    get()
                }.onSuccess { openWindow(EditorViewModel(it), profile, "Independent reopened copy") }
                    .onFailure { status.text = "Open failed: ${it.message}" }
            }
        }.execute()
    }
}
