package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.UnfinishedEntry
import dev.shayxo.composedoc.data.UnfinishedKind
import dev.shayxo.composedoc.data.UnfinishedRecord
import dev.shayxo.composedoc.data.UnfinishedStore
import dev.shayxo.composedoc.data.cloneDocument
import dev.shayxo.composedoc.model.Document
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

private const val UNFINISHED_MIN_WIDTH_PX = 700
private const val UNFINISHED_MIN_HEIGHT_PX = 380
private const val UNFINISHED_WIDTH_PX = 860
private const val UNFINISHED_HEIGHT_PX = 420
private const val LIST_FONT_SIZE = 15
private const val NOTE_ROWS = 3
private const val NOTE_COLUMNS = 55
private const val PREVIEW_ROWS = 18
private const val PREVIEW_COLUMNS = 54
private const val PREVIEW_FIELD_LIMIT = 2048
private const val EXPORT_ID_PREFIX_LENGTH = 8
private const val LAYOUT_GAP_PX = 8
private const val EDGE_PX = 12
private const val OFFSCREEN_COORDINATE_PX = -16000

/** Metadata-only list; decode at most one selected draft for inspection/open. Never resumes native input. */
fun showUnfinishedRecovery(
    directory: Path,
    fontProfile: String,
    paged: Boolean,
    onlyIfNeeded: Boolean = false,
    offscreen: Boolean = false,
): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    return UnfinishedRecoveryWindow(directory, fontProfile, paged, onlyIfNeeded, offscreen).window
}

private class UnfinishedRecoveryWindow(
    private val directory: Path,
    private val fontProfile: String,
    private val paged: Boolean,
    private val onlyIfNeeded: Boolean,
    private val offscreen: Boolean,
) {
    private val store = UnfinishedStore(LocalRecoveryStore(directory))
    val window = JFrame("Unfinished writing recovery — separate from committed copies").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        minimumSize = Dimension(UNFINISHED_MIN_WIDTH_PX, UNFINISHED_MIN_HEIGHT_PX)
    }
    private val model = DefaultListModel<UnfinishedEntry>()
    private val list = JList(model).apply {
        name = "unfinished-list"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        font = Font("Segoe UI", Font.PLAIN, LIST_FONT_SIZE)
    }
    private val note = JTextArea(
        "Unfinished input is not accepted content. " +
            "Restore creates an unsaved document; no OS session or callback is replayed.",
        NOTE_ROWS,
        NOTE_COLUMNS,
    ).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val bar = JPanel(WrappingControls())
    private var busy = false

    init {
        button("Inspect", "unfinished-inspect") { inspect() }
        button("Restore unsaved draft", "unfinished-restore") { restoreDraft() }
        button("Apply to original", "unfinished-apply") {
            note.text = "Apply refused: this restarted owner is not the original runtime/basis. " +
                "Use Restore unsaved draft or Export. Record kept."
        }
        button("Lossless export…", "unfinished-export") { export() }
        button("Discard selected…", "unfinished-discard") { discardSelected() }
        button("Refresh", "unfinished-refresh") { scan() }
        button("Later — keep work", "unfinished-later") { window.dispose() }
        window.contentPane = JPanel(BorderLayout(LAYOUT_GAP_PX, LAYOUT_GAP_PX)).apply {
            border = BorderFactory.createEmptyBorder(EDGE_PX, EDGE_PX, EDGE_PX, EDGE_PX)
            add(note, BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
            add(bar, BorderLayout.SOUTH)
        }
        scan()
    }

    private fun enabled() {
        bar.components.filterIsInstance<JButton>().forEach { it.isEnabled = !busy }
    }

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        if (busy)return
        busy = true
        enabled()
        object : SwingWorker<T, Unit>() {
            override fun doInBackground() = action()

            // Reason: UI resilience boundary; any background or callback failure is shown and the record kept.
            @Suppress("TooGenericExceptionCaught")
            override fun done() {
                busy = false
                try {
                    done(get())
                } catch (e: Exception) {
                    note.text = "Operation failed; record kept. ${e.cause?.message ?: e.message}"
                }
                enabled()
            }
        }.execute()
    }

    private fun selected(): UnfinishedRecord? {
        val entry = list.selectedValue
        if (entry == null) {
            note.text = "Select a recovery entry first."
        } else if (entry.record == null) {
            note.text = "No validated record to restore/export/discard. " +
                "Bytes remain untouched: ${directory.resolve(entry.owner)}. ${entry.warning}. " +
                "Keep a private folder backup for forensic rescue; no content is inferred."
        }
        return entry?.record
    }

    private fun button(label: String, name: String, action: () -> Unit) {
        bar.add(
            JButton(label).apply {
                this.name = name
                addActionListener { action() }
            },
        )
    }

    private fun inspect() {
        val r = selected() ?: return
        work({
            val (base, draft) = store.load(r)
            val text = if (r.kind == UnfinishedKind.QUARANTINE)r.text else draft?.text ?: r.text
            "${r}\nSupporting committed base: ${r.basisHash}\n" +
                "Generation ${r.generation}; basis revision ${r.basisRevision}; " +
                "identity generation ${r.identityGeneration}\n" +
                "Selection ${r.selection}; marked [${r.markedStart},${r.markedEnd})\n" +
                "${r.provenance.take(PREVIEW_FIELD_LIMIT)}\n" +
                "${text.length} raw UTF-16 units; each preview field first 2048 only. " +
                "Lossless export contains all available captured evidence.\n\n" +
                text.take(PREVIEW_FIELD_LIMIT)
        }) { preview ->
            val area = JTextArea(preview, PREVIEW_ROWS, PREVIEW_COLUMNS).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                caretPosition = 0
            }
            JOptionPane.showMessageDialog(
                window,
                JScrollPane(area),
                "Unfinished evidence — bounded preview",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }

    private fun restoreDraft() {
        val r = selected() ?: return
        if (r.kind == UnfinishedKind.QUARANTINE) {
            note.text = "Quarantine is raw unverified evidence. " +
                "Inspect or export it; it cannot safely become a document."
            return
        }
        work({
            store.load(r).second?.let(::cloneDocument) ?: error("No attributed draft; export raw evidence instead")
        }) { draft -> openRestored(r, draft) }
    }

    private fun openRestored(r: UnfinishedRecord, draft: Document) {
        val existing = restoredUnfinished[r.owner + "/" + r.id]?.takeIf { it.isDisplayable }
        if (existing != null) {
            existing.toFront()
            note.text = "This record is already open as a separate unsaved draft."
        } else {
            val editor = showEditor(
                initial = draft,
                fontProfile = fontProfile,
                paged = paged,
                recoveryDirectory = directory.parent,
                offscreenTest = offscreen,
            )
            windowFiles(editor)!!.markRestored(r.name.substringAfterLast('\\').substringAfterLast('/'))
            restoredUnfinished[r.owner + "/" + r.id] = editor
            note.text = "Separate unsaved attributed draft opened. No composition active; " +
                "original and record kept. Save As, then explicitly discard if no longer needed."
        }
    }

    private fun export() {
        val r = selected() ?: return
        val picker = JFileChooser().apply {
            selectedFile = java.io.File("unfinished-${r.id.take(EXPORT_ID_PREFIX_LENGTH)}.astra-recovery")
        }
        if (picker.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
            work({ store.export(r, picker.selectedFile.toPath()) }) {
                note.text = "Lossless evidence exported; record kept. This is not a callback replay file."
            }
        }
    }

    private fun discardSelected() {
        val capturedEntry = list.selectedValue
        val r = selected() ?: return
        val choice = JOptionPane.showConfirmDialog(
            window,
            "Discard only this captured generation?\n$r\nNew arrivals and original documents remain.",
            "Discard unfinished record",
            JOptionPane.YES_NO_OPTION,
        )
        if (choice == JOptionPane.YES_OPTION) {
            work({ store.recovery.acquire(r.owner).use { store.resolve(it, r) } }) {
                model.removeElement(capturedEntry)
                note.text = "Selected generation discarded. Other work retained."
            }
        }
    }

    private fun scan() = work({ store.scan() }) { entries ->
        model.clear()
        entries.forEach(model::addElement)
        if (entries.isNotEmpty())list.selectedIndex = 0
        note.text = if (entries.isEmpty()) {
            "No inactive unfinished records. Live owners are hidden. Later never discards work."
        } else {
            "Select unfinished writing or quarantine. Accepted recovery copies remain separate. " +
                "A failed action preserves the record."
        }
        if (!onlyIfNeeded || entries.isNotEmpty()) {
            window.setSize(UNFINISHED_WIDTH_PX, UNFINISHED_HEIGHT_PX)
            if (offscreen) {
                window.setLocation(OFFSCREEN_COORDINATE_PX, OFFSCREEN_COORDINATE_PX)
            } else {
                window.setLocationRelativeTo(null)
            }
            window.isVisible = true
        } else {
            window.dispose()
        }
    }
}
private val restoredUnfinished = HashMap<String, JFrame>()
