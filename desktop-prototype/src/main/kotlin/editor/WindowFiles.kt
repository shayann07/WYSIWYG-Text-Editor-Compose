package editor

import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/** Swing shell only. Disk work is off the editing thread; results publish on the EDT. */
class WindowFiles(
    private val window: JFrame,
    private val engine: EditorEngine,
    initialPath: Path?,
    private val refresh: () -> Unit
) {
    var path: Path? = initialPath; private set
    var busy = false; private set
    private val savePoint = SavePoint(engine.snapshot().committed)
    private var diskDocument = engine.snapshot().committed
    val dirty get() = savePoint.dirty(engine.snapshot())
    val name get() = (path?.fileName?.toString() ?: "Untitled") + if (dirty) " *" else ""
    val status get() = if (busy) "Working with file…" else if (dirty) "Unsaved changes" else if (path != null) "Saved" else "New document"

    init {
        window.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { close() }
        })
    }

    private fun error(failure: Throwable) {
        JOptionPane.showMessageDialog(window, failure.cause?.message ?: failure.message ?: "File operation failed.", "Document not saved or opened", JOptionPane.ERROR_MESSAGE)
    }

    private fun <T> work(action: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true; refresh()
        object : SwingWorker<T, Unit>() {
            override fun doInBackground(): T = action()
            override fun done() {
                busy = false
                try { success(get()) } catch (failure: Exception) { error(failure) }
                finally { refresh() }
            }
        }.execute()
    }

    private fun chooser(): JFileChooser = JFileChooser(path?.parent?.toFile()).apply {
        fileFilter = FileNameExtensionFilter("Astra prototype document (*.astra)", "astra")
        isAcceptAllFileFilterUsed = false
    }

    fun open() {
        if (busy) return
        val picker = chooser()
        if (picker.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return
        val selected = picker.selectedFile.toPath().toAbsolutePath().normalize()
        work({ DocumentFiles.read(selected) }) { loaded -> showEditor(initial = loaded, initialPath = selected) }
    }

    fun save(asNew: Boolean = false, closeAfter: Boolean = false) {
        if (busy) return
        // Never persist preedit as accepted text or guess a native completion fence.
        if (engine.snapshot().marked != null || engine.snapshot().waiting) {
            error(IllegalStateException("Finish composing before saving or closing.")); return
        }
        var destination = path
        if (asNew || destination == null) {
            val picker = chooser().apply { selectedFile = (path?.fileName?.toFile() ?: java.io.File("Untitled.astra")) }
            if (picker.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return
            destination = picker.selectedFile.toPath().toAbsolutePath().normalize()
            if (!destination.fileName.toString().endsWith(".astra", true)) destination = destination.resolveSibling(destination.fileName.toString() + ".astra")
            if (Files.exists(destination) && JOptionPane.showConfirmDialog(window, "Replace ${destination.fileName}?", "Replace document", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        }
        val written = try { savePoint.capture(engine.snapshot()) } catch (failure: Exception) { error(failure); return }
        val target = destination!!
        val expected = if (!asNew && target == path) diskDocument else null
        work({ DocumentFiles.write(target, written, expected) }) {
            path = target; diskDocument = written; savePoint.acknowledge(written)
            // Typing while the write runs stays dirty. Never close over those newer edits.
            if (closeAfter && !dirty) window.dispose()
        }
    }

    private fun close() {
        if (busy) { ToolkitFeedback.beep(); return }
        if (!dirty) { window.dispose(); return }
        when (JOptionPane.showOptionDialog(window, "Save changes to ${path?.fileName ?: "Untitled"}?", "Unsaved document", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, arrayOf("Save", "Discard", "Cancel"), "Save")) {
            0 -> save(closeAfter = true)
            1 -> window.dispose()
        }
    }
}

private object ToolkitFeedback { fun beep() { java.awt.Toolkit.getDefaultToolkit().beep() } }
