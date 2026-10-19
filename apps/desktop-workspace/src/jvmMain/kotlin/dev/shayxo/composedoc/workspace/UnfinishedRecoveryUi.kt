package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.awt.*
import java.nio.file.Path
import javax.swing.*

/** Metadata-only list; decode at most one selected draft for inspection/open. Never resumes native input. */
fun showUnfinishedRecovery(directory: Path, fontProfile: String, paged: Boolean, onlyIfNeeded: Boolean = false, offscreen: Boolean = false): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    val store = UnfinishedStore(LocalRecoveryStore(directory))
    val window = JFrame("Unfinished writing recovery — separate from committed copies").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        minimumSize = Dimension(700, 380)
    }
    val model = DefaultListModel<UnfinishedEntry>()
    val list = JList(model).apply {
        name = "unfinished-list"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        font = Font("Segoe UI", Font.PLAIN, 15)
    }
    val note = JTextArea("Unfinished input is not accepted content. Restore creates an unsaved document; no OS session or callback is replayed.", 3, 55).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    val bar = JPanel(WrappingControls())
    var busy = false
    fun enabled() {
        bar.components.filterIsInstance<JButton>().forEach { it.isEnabled = !busy }
    }
    fun <T> work(action: () -> T, done: (T) -> Unit) {
        if (busy)return
        busy = true
        enabled()
        object : SwingWorker<T, Unit>() {
            override fun doInBackground() = action()
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
    fun selected(): UnfinishedRecord? {
        val entry = list.selectedValue
        if (entry == null) {
            note.text = "Select a recovery entry first."
        } else if (entry.record == null) {
            note.text = "No validated record to restore/export/discard. Bytes remain untouched: ${directory.resolve(entry.owner)}. ${entry.warning}. Keep a private folder backup for forensic rescue; no content is inferred."
        }
        return entry?.record
    }
    fun button(label: String, name: String, action: () -> Unit) {
        bar.add(
            JButton(label).apply {
                this.name = name
                addActionListener { action() }
            },
        )
    }
    button("Inspect", "unfinished-inspect") {
        val r = selected() ?: return@button
        work({
            val (base, draft) = store.load(r)
            val text = if (r.kind == UnfinishedKind.QUARANTINE)r.text else draft?.text ?: r.text
            "${r}\nSupporting committed base: ${r.basisHash}\nGeneration ${r.generation}; basis revision ${r.basisRevision}; identity generation ${r.identityGeneration}\nSelection ${r.selection}; marked [${r.markedStart},${r.markedEnd})\n${r.provenance.take(2048)}\n${text.length} raw UTF-16 units; each preview field first 2048 only. Lossless export contains all available captured evidence.\n\n" + text.take(2048)
        }) { preview ->
            val area = JTextArea(preview, 18, 54).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                caretPosition = 0
            }
            JOptionPane.showMessageDialog(window, JScrollPane(area), "Unfinished evidence — bounded preview", JOptionPane.INFORMATION_MESSAGE)
        }
    }
    button("Restore unsaved draft", "unfinished-restore") {
        val r = selected() ?: return@button
        if (r.kind == UnfinishedKind.QUARANTINE) {
            note.text = "Quarantine is raw unverified evidence. Inspect or export it; it cannot safely become a document."
            return@button
        }
        work({ store.load(r).second?.let(::cloneDocument) ?: error("No attributed draft; export raw evidence instead") }) { draft ->
            val existing = restoredUnfinished[r.owner + "/" + r.id]?.takeIf { it.isDisplayable }
            if (existing != null) {
                existing.toFront()
                note.text = "This record is already open as a separate unsaved draft."
            } else {
                val editor = showEditor(initial = draft, fontProfile = fontProfile, paged = paged, recoveryDirectory = directory.parent, offscreenTest = offscreen)
                windowFiles(editor)!!.markRestored(r.name.substringAfterLast('\\').substringAfterLast('/'))
                restoredUnfinished[r.owner + "/" + r.id] = editor
                note.text = "Separate unsaved attributed draft opened. No composition active; original and record kept. Save As, then explicitly discard if no longer needed."
            }
        }
    }
    button("Apply to original", "unfinished-apply") { note.text = "Apply refused: this restarted owner is not the original runtime/basis. Use Restore unsaved draft or Export. Record kept." }
    button("Lossless export…", "unfinished-export") {
        val r = selected() ?: return@button
        val picker = JFileChooser().apply { selectedFile = java.io.File("unfinished-${r.id.take(8)}.astra-recovery") }
        if (picker.showSaveDialog(window) == JFileChooser.APPROVE_OPTION)work({ store.export(r, picker.selectedFile.toPath()) }) { note.text = "Lossless evidence exported; record kept. This is not a callback replay file." }
    }
    button("Discard selected…", "unfinished-discard") {
        val capturedEntry = list.selectedValue
        val r = selected() ?: return@button
        if (JOptionPane.showConfirmDialog(window, "Discard only this captured generation?\n$r\nNew arrivals and original documents remain.", "Discard unfinished record", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            work({ store.recovery.acquire(r.owner).use { store.resolve(it, r) } }) {
                model.removeElement(capturedEntry)
                note.text = "Selected generation discarded. Other work retained."
            }
        }
    }
    fun scan() = work({ store.scan() }) { entries ->
        model.clear()
        entries.forEach(model::addElement)
        if (entries.isNotEmpty())list.selectedIndex = 0
        note.text = if (entries.isEmpty())"No inactive unfinished records. Live owners are hidden. Later never discards work." else "Select unfinished writing or quarantine. Accepted recovery copies remain separate. A failed action preserves the record."
        if (!onlyIfNeeded || entries.isNotEmpty()) {
            window.setSize(860, 420)
            if (offscreen)window.setLocation(-16000, -16000) else window.setLocationRelativeTo(null)
            window.isVisible = true
        } else {
            window.dispose()
        }
    }
    button("Refresh", "unfinished-refresh") { scan() }
    button("Later — keep work", "unfinished-later") { window.dispose() }
    window.contentPane = JPanel(BorderLayout(8, 8)).apply {
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(note, BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
        add(bar, BorderLayout.SOUTH)
    }
    scan()
    return window
}
private val restoredUnfinished = HashMap<String, JFrame>()
