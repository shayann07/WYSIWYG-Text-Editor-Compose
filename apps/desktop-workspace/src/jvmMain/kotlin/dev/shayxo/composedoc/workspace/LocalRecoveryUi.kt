package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.LocalRecoveryEntry
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.WorkspaceEntry
import dev.shayxo.composedoc.data.WorkspaceStore
import dev.shayxo.composedoc.data.workspacePositionsValid
import dev.shayxo.composedoc.ui.EditorIntent
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

private const val RECOVERY_MIN_WIDTH_PX = 660
private const val RECOVERY_MIN_HEIGHT_PX = 330
private const val RECOVERY_WIDTH_PX = 760
private const val RECOVERY_HEIGHT_PX = 370
private const val MESSAGE_ROWS = 3
private const val MESSAGE_COLUMNS = 45
private const val LAYOUT_GAP_PX = 8
private const val EDGE_PX = 12
private const val OFFSCREEN_COORDINATE_PX = -16000
private const val ZOOM_HALF = .5f
private const val ZOOM_THREE_QUARTERS = .75f
private const val ZOOM_FULL = 1f
private val ZOOM_LEVELS = listOf(ZOOM_HALF, ZOOM_THREE_QUARTERS, ZOOM_FULL)

/** File operations and scans run off EDT. Restore always makes a separate, dirty, untitled window. */
// Reason: the desktop harness consumes this public entry point; grouping parameters would change callers.
@Suppress("LongParameterList")
fun showLocalRecovery(
    directory: Path,
    fontProfile: String,
    paged: Boolean,
    onlyIfNeeded: Boolean = false,
    offscreenTest: Boolean = false,
    confirmDiscardForChecks: ((LocalRecoveryEntry) -> Boolean)? = null,
): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    return LocalRecoveryWindow(
        directory,
        fontProfile,
        paged,
        onlyIfNeeded,
        offscreenTest,
        confirmDiscardForChecks,
    ).window
}

private class LocalRecoveryWindow(
    private val directory: Path,
    private val fontProfile: String,
    private val paged: Boolean,
    private val onlyIfNeeded: Boolean,
    private val offscreenTest: Boolean,
    private val confirmDiscardForChecks: ((LocalRecoveryEntry) -> Boolean)?,
) {
    private val store = LocalRecoveryStore(directory)
    val window = JFrame("Local recovery copies").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        minimumSize = java.awt.Dimension(RECOVERY_MIN_WIDTH_PX, RECOVERY_MIN_HEIGHT_PX)
    }
    private val model = DefaultListModel<LocalRecoveryEntry>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        name = "local-recovery-list"
    }
    private val message = JTextArea(
        "Committed content only. Restore opens a separate unsaved document. " +
            "Originals and unverified input are never replayed.",
        MESSAGE_ROWS,
        MESSAGE_COLUMNS,
    ).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = window.background
    }
    private val restore = JButton("Restore Copy").apply { name = "local-recovery-restore" }
    private val discard = JButton("Discard Copy…").apply { name = "local-recovery-discard" }
    private val refresh = JButton("Refresh")
    private val later = JButton("Later — keep copies")
    private val bar = JPanel().apply {
        add(restore)
        add(discard)
        add(refresh)
        add(later)
    }
    private var busy = false

    init {
        window.contentPane = JPanel(java.awt.BorderLayout(LAYOUT_GAP_PX, LAYOUT_GAP_PX)).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(EDGE_PX, EDGE_PX, EDGE_PX, EDGE_PX)
            add(message, java.awt.BorderLayout.NORTH)
            add(JScrollPane(list), java.awt.BorderLayout.CENTER)
            add(bar, java.awt.BorderLayout.SOUTH)
        }
        list.addListSelectionListener {
            enabled()
            list.selectedValue?.warning?.let { warning ->
                message.text = "${list.selectedValue}\n$warning\n" +
                    "Restore uses the valid fallback only; unpublished/corrupt bytes are not applied."
            }
        }
        restore.addActionListener { restoreSelected() }
        discard.addActionListener { discardSelected() }
        refresh.addActionListener { scan() }
        later.addActionListener { window.dispose() }
        scan()
    }

    private fun enabled() {
        restore.isEnabled = !busy && list.selectedValue?.copy != null
        discard.isEnabled = !busy && list.selectedValue != null
        refresh.isEnabled = !busy
    }

    private fun <T> work(action: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        enabled()
        object : SwingWorker<T, Unit>() {
            override fun doInBackground(): T = action()

            // Reason: UI resilience boundary; any background or callback failure is shown and copies are kept.
            @Suppress("TooGenericExceptionCaught")
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

    private fun scan() = work({ store.scan() }) { entries ->
        model.clear()
        entries.forEach { model.addElement(it) }
        if (entries.isNotEmpty()) list.selectedIndex = 0
        message.text = if (entries.isEmpty()) {
            "No inactive recovery copies. Copies belonging to running windows are hidden."
        } else {
            "Choose a committed-content copy. Restore creates a separate unsaved document; " +
                "original files are never overwritten. Later keeps all copies."
        }
        if (!onlyIfNeeded || entries.isNotEmpty()) {
            window.pack()
            window.setSize(RECOVERY_WIDTH_PX, RECOVERY_HEIGHT_PX)
            if (offscreenTest) {
                window.setLocation(OFFSCREEN_COORDINATE_PX, OFFSCREEN_COORDINATE_PX)
            } else {
                window.setLocationRelativeTo(null)
            }
            window.isVisible = true
        } else {
            window.dispose()
        }
    }

    private fun restoreSelected() {
        val entry = list.selectedValue ?: return
        work({
            val copy = store.restore(entry)
            val metadata = runCatching {
                WorkspaceStore(directory.resolve("workspace")).entries().firstOrNull {
                    it.recovery == copy.session && workspacePositionsValid(it, copy.document)
                }
            }
            Triple(copy, metadata.getOrNull(), metadata.exceptionOrNull()?.message)
        }) { (copy, metadata, warning) ->
            val restored = showEditor(
                initial = copy.document,
                fontProfile = metadata?.font ?: fontProfile,
                paged = metadata?.paged ?: paged,
                recoveryDirectory = directory,
                offscreenTest = offscreenTest,
            )
            checkNotNull(windowFiles(restored)).markRestored(entry.toString().substringBefore(" — "))
            if (metadata != null) applyWorkspaceView(restored, metadata)
            message.text = "Opened a separate unsaved document. Copy kept until explicit discard." +
                (warning?.let { " Workspace metadata unavailable; default view used: $it" } ?: "")
        }
    }

    private fun applyWorkspaceView(restored: JFrame, metadata: WorkspaceEntry) {
        (restored.rootPane.getClientProperty("editor.zoomPicker") as JComboBox<*>).selectedIndex =
            ZOOM_LEVELS.indexOf(metadata.zoom)
        findSurface(restored)!!.let { s ->
            s.viewModel.dispatch(EditorIntent.Select(metadata.selection))
            if (metadata.viewportWidth == s.width && metadata.viewportHeight == s.height) {
                s.view.scroll = metadata.scroll
                s.view.ensureCaret = false
            }
        }
    }

    private fun discardSelected() {
        val entry = list.selectedValue ?: return
        val confirmed = confirmDiscardForChecks?.invoke(entry) ?: (
            JOptionPane.showConfirmDialog(
                window,
                "Discard this recovery copy and its fallback?\n$entry\nOriginal documents are unaffected.",
                "Discard local recovery",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) == JOptionPane.YES_OPTION
            )
        if (confirmed) {
            work({ store.discard(entry) }) {
                model.removeElement(entry)
                message.text = "Recovery copy discarded. Original files are unaffected."
            }
        }
    }
}
