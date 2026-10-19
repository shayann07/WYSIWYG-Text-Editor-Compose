package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.LocalRecoveryEntry
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.WorkspaceStore
import dev.shayxo.composedoc.data.workspacePositionsValid
import dev.shayxo.composedoc.ui.findSurface
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
/** File operations and scans run off EDT. Restore always makes a separate, dirty, untitled window. */
fun showLocalRecovery(
    directory: Path,
    fontProfile: String,
    paged: Boolean,
    onlyIfNeeded: Boolean = false,
    offscreenTest: Boolean = false,
    confirmDiscardForChecks: ((LocalRecoveryEntry) -> Boolean)? = null,
): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    val store = LocalRecoveryStore(directory)
    val window = JFrame("Local recovery copies").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        minimumSize = java.awt.Dimension(660, 330)
    }
    val model = DefaultListModel<LocalRecoveryEntry>()
    val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        name = "local-recovery-list"
    }
    val message = JTextArea("Committed content only. Restore opens a separate unsaved document. Originals and unverified input are never replayed.", 3, 45).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = window.background
    }
    val restore = JButton("Restore Copy").apply { name = "local-recovery-restore" }
    val discard = JButton("Discard Copy…").apply { name = "local-recovery-discard" }
    val refresh = JButton("Refresh")
    val later = JButton("Later — keep copies")
    val bar = JPanel().apply {
        add(restore)
        add(discard)
        add(refresh)
        add(later)
    }
    window.contentPane = JPanel(java.awt.BorderLayout(8, 8)).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(message, java.awt.BorderLayout.NORTH)
        add(JScrollPane(list), java.awt.BorderLayout.CENTER)
        add(bar, java.awt.BorderLayout.SOUTH)
    }
    var busy = false
    fun enabled() {
        restore.isEnabled = !busy && list.selectedValue?.copy != null
        discard.isEnabled = !busy && list.selectedValue != null
        refresh.isEnabled = !busy
    }
    fun <T> work(action: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        enabled()
        object : SwingWorker<T, Unit>() {
            override fun doInBackground(): T = action()
            override fun done() {
                busy = false
                try {
                    success(get())
                } catch (e: Exception) {
                    message.text = "Recovery failed; copies kept. ${e.cause?.message ?: e.message}"
                    if (!window.isVisible) {
                        window.pack()
                        window.isVisible = true
                    }
                }
                enabled()
            }
        }.execute()
    }
    fun scan() = work({ store.scan() }) { entries ->
        model.clear()
        entries.forEach { model.addElement(it) }
        if (entries.isNotEmpty()) list.selectedIndex = 0
        message.text = if (entries.isEmpty()) "No inactive recovery copies. Copies belonging to running windows are hidden." else "Choose a committed-content copy. Restore creates a separate unsaved document; original files are never overwritten. Later keeps all copies."
        if (!onlyIfNeeded || entries.isNotEmpty()) {
            window.pack()
            window.setSize(760, 370)
            if (offscreenTest) window.setLocation(-16000, -16000) else window.setLocationRelativeTo(null)
            window.isVisible = true
        } else {
            window.dispose()
        }
    }
    list.addListSelectionListener {
        enabled()
        list.selectedValue?.warning?.let { warning -> message.text = "${list.selectedValue}\n$warning\nRestore uses the valid fallback only; unpublished/corrupt bytes are not applied." }
    }
    restore.addActionListener {
        val entry = list.selectedValue ?: return@addActionListener
        work({
            val copy = store.restore(entry)
            val metadata = runCatching { WorkspaceStore(directory.resolve("workspace")).entries().firstOrNull { it.recovery == copy.session && workspacePositionsValid(it, copy.document) } }
            Triple(copy, metadata.getOrNull(), metadata.exceptionOrNull()?.message)
        }) { (copy, metadata, warning) ->
            val restored = showEditor(initial = copy.document, fontProfile = metadata?.font ?: fontProfile, paged = metadata?.paged ?: paged, recoveryDirectory = directory, offscreenTest = offscreenTest)
            checkNotNull(windowFiles(restored)).markRestored(entry.toString().substringBefore(" — "))
            if (metadata != null) {
                (restored.rootPane.getClientProperty("editor.zoomPicker") as JComboBox<*>).selectedIndex = listOf(.5f, .75f, 1f).indexOf(metadata.zoom)
                findSurface(restored)!!.let { s ->
                    s.engine.move(metadata.selection)
                    if (metadata.viewportWidth == s.width && metadata.viewportHeight == s.height) {
                        s.view.scroll = metadata.scroll
                        s.view.ensureCaret = false
                    }
                }
            }
            message.text = "Opened a separate unsaved document. Copy kept until explicit discard." + (warning?.let { " Workspace metadata unavailable; default view used: $it" } ?: "")
        }
    }
    discard.addActionListener {
        val entry = list.selectedValue ?: return@addActionListener
        if (confirmDiscardForChecks?.invoke(entry) ?: (JOptionPane.showConfirmDialog(window, "Discard this recovery copy and its fallback?\n$entry\nOriginal documents are unaffected.", "Discard local recovery", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)) {
            work({ store.discard(entry) }) {
                model.removeElement(entry)
                message.text = "Recovery copy discarded. Original files are unaffected."
            }
        }
    }
    refresh.addActionListener { scan() }
    later.addActionListener { window.dispose() }
    scan()
    return window
}
