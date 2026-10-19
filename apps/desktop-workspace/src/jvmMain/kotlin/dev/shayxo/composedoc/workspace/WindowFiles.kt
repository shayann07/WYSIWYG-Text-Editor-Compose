package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FileConflict
import dev.shayxo.composedoc.data.FileFingerprint
import dev.shayxo.composedoc.data.SafeDocumentIO
import dev.shayxo.composedoc.data.SavePoint
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.QuarantinedCallback
import dev.shayxo.composedoc.ui.quarantineDiagnostic
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingWorker
import javax.swing.filechooser.FileNameExtensionFilter

private const val CONFLICT_RELOAD_OPTION = 1
private const val CONFLICT_SAVE_COPY_OPTION = 2
private const val CLOSE_PRIMARY_OPTION = 0
private const val CLOSE_DISCARD_OPTION = 1

/** One accepted Save request: destination, captured document and the revisions it was captured at. */
private data class SaveRequest(
    val target: Path,
    val written: Document,
    val asNew: Boolean,
    val closeAfter: Boolean,
    val documentRevision: Long,
    val sessionRevision: Long,
)

/** Swing shell only. Disk work is off the editing thread; results publish on the EDT. */
class WindowFiles(
    val window: JFrame,
    private val viewModel: EditorViewModel,
    initialPath: Path?,
    var promptCloseChoice: ((String) -> Int)? = null,
    var onDiscardComposition: () -> Unit = { viewModel.dispatch(EditorIntent.AbortComposition) },
    private val refresh: () -> Unit,
) {
    var path: Path? = initialPath
        private set
    var busy = false
        private set
    var hasRetainedRecovery: () -> Boolean = { false }
    var discardRetainedRecovery: () -> Unit = {}
    var captureRecoveryDiscard: () -> (() -> Unit) = { { discardRetainedRecovery() } }
    var pendingRecoveryDescription: () -> String = { "Retained recovery work" }
    var openInNewWindow: (Document, Path) -> Unit = { document, source ->
        showEditor(initial = document, initialPath = source)
        Unit
    }
    var onApprovedClose: () -> Unit = {}
    var captureDurableDiscardBeforeClose: (() -> ((() -> Unit) -> Unit))? = null
    private var restoredName: String? = null
    private var unsavedRestore = false
    private var copyLabel = "recovered"
    fun markRestored(name: String) {
        restoredName = name
        copyLabel = "recovered"
        unsavedRestore = true
        refresh()
    }
    fun markUnsavedCopy(name: String) {
        restoredName = name
        copyLabel = "unsaved copy"
        unsavedRestore = true
        refresh()
    }
    var reportFailureForChecks: ((Throwable) -> Unit)? = null
    var draftDestinationPicker: (() -> Path?)? = null
    var inputDestinationPicker: (() -> Path?)? = null
    var saveDestinationPicker: (() -> Path?)? = null
    var beforeSaveWriteForChecks: () -> Unit = {}
    var confirmInputDiscardForChecks: ((Int) -> Boolean)? = null
    private var operationNote: String? = null
    var fingerprint: FileFingerprint? = initialPath?.let { SafeDocumentIO.basis(viewModel.snapshot().committed, it) }
        private set
    var conflict: FileConflict? = null
        private set
    var preserveBeforeReload: (Document, () -> Unit) -> Unit = { _, _ ->
        error(IllegalStateException("Recovery protection unavailable; reload blocked"))
    }
    private val savePoint = SavePoint(viewModel.snapshot().committed)
    private var diskDocument = viewModel.snapshot().committed
    val dirty get() = unsavedRestore || savePoint.dirty(viewModel.snapshot())
    val name get() =
        (path?.fileName?.toString() ?: restoredName?.let { "$it ($copyLabel)" } ?: "Untitled") +
            if (dirty) " *" else ""
    val status get() = (
        if (conflict != null) {
            "External file conflict — Keep editing / Reload / Save a Copy"
        } else if (busy) {
            "Working with file…"
        } else if (dirty) {
            "Unsaved changes"
        } else if (path != null) {
            "Saved"
        } else {
            "New document"
        }
        ) +
        (if (!busy && operationNote != null) " · $operationNote" else "")

    init {
        window.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                close()
            }
        })
    }

    private fun error(failure: Throwable) {
        val cause = generateSequence(failure) { it.cause }.last()
        if (cause is FileConflict) {
            conflict = cause
            refresh()
        }
        val report = reportFailureForChecks
        when {
            report != null -> report(cause)
            cause is FileConflict -> showConflict()
            else -> JOptionPane.showMessageDialog(
                window,
                failure.cause?.message ?: failure.message ?: "File operation failed.",
                "Document not saved or opened",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    fun showConflict() {
        val c = conflict ?: return
        val choice = JOptionPane.showOptionDialog(
            window,
            "$c\nLocal edits: $dirty\nReload protects a local recovery copy before replacement.",
            "External file change",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            arrayOf("Keep editing", "Reload external version", "Save a Copy"),
            "Keep editing",
        )
        when (choice) {
            CONFLICT_RELOAD_OPTION -> reloadExternal()
            CONFLICT_SAVE_COPY_OPTION -> save(asNew = true)
        }
    }
    fun reloadExternal() {
        val source = path?.takeIf {
            !busy && viewModel.snapshot().marked == null && !viewModel.snapshot().waiting
        } ?: return
        val captured = viewModel.snapshot()
        val proceed = {
            if (viewModel.snapshot().documentRevision != captured.documentRevision ||
                viewModel.snapshot().sessionRevision != captured.sessionRevision
            ) {
                error(IllegalStateException("Local work changed while protecting it; reload canceled"))
            } else {
                work({ DocumentFiles.read(source) }) { loaded ->
                    // A fresh owner makes empty history and changed-basis selection explicit.
                    openInNewWindow(loaded, source)
                    operationNote = "External file opened separately; protected local window still needs Save a Copy"
                }
            }
        }
        if (!dirty) {
            proceed()
        } else if (confirmReload()) {
            preserveBeforeReload(captured.committed, proceed)
        }
    }

    private fun confirmReload() = JOptionPane.showConfirmDialog(
        window,
        "Protect local changes and open the external version in a fresh window? This local window stays available.",
        "Reload external version",
        JOptionPane.YES_NO_OPTION,
    ) == JOptionPane.YES_OPTION

    private fun <T> work(action: () -> T, success: (T) -> Unit) {
        if (busy) return
        operationNote = null
        busy = true
        refresh()
        object : SwingWorker<T, Unit>() {
            override fun doInBackground(): T = action()

            // Reason: file-operation boundary; any background or callback failure is reported, never lost.
            @Suppress("TooGenericExceptionCaught")
            override fun done() {
                busy = false
                try {
                    success(get())
                } catch (failure: Exception) {
                    error(failure)
                } finally {
                    refresh()
                }
            }
        }.execute()
    }

    private fun chooser(): JFileChooser = JFileChooser(path?.parent?.toFile()).apply {
        fileFilter = FileNameExtensionFilter("Astra prototype document (*.astra)", "astra")
        isAcceptAllFileFilterUsed = false
    }

    private fun withAstraExtension(target: Path): Path = if (!target.fileName.toString().endsWith(".astra", true)) {
        target.resolveSibling(target.fileName.toString() + ".astra")
    } else {
        target
    }

    fun open() {
        if (busy) return
        val picker = chooser()
        if (picker.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return
        val selected = picker.selectedFile.toPath().toAbsolutePath().normalize()
        work({ DocumentFiles.read(selected) }) { loaded -> openInNewWindow(loaded, selected) }
    }

    fun save(asNew: Boolean = false, closeAfter: Boolean = false) {
        if (busy) return
        // Never persist preedit as accepted text or guess a native completion fence.
        if (viewModel.snapshot().marked != null || viewModel.snapshot().waiting) {
            error(IllegalStateException("Finish composing before saving or closing."))
        } else {
            saveDestination(asNew)?.let { target ->
                captureForSave()?.let { written ->
                    startSave(
                        SaveRequest(
                            target,
                            written,
                            asNew,
                            closeAfter,
                            viewModel.snapshot().documentRevision,
                            viewModel.snapshot().sessionRevision,
                        ),
                    )
                }
            }
        }
    }

    private fun saveDestination(asNew: Boolean): Path? {
        val current = path
        return when {
            !asNew && current != null -> current
            saveDestinationPicker != null -> saveDestinationPicker!!.invoke()
            else -> chooseSaveFile()
        }
    }

    private fun chooseSaveFile(): Path? {
        val picker = chooser().apply { selectedFile = (path?.fileName?.toFile() ?: java.io.File("Untitled.astra")) }
        return if (picker.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) {
            null
        } else {
            withAstraExtension(picker.selectedFile.toPath().toAbsolutePath().normalize())
        }
    }

    /** SavePoint.capture only rejects (IllegalStateException) a frame that is still composing. */
    private fun captureForSave(): Document? = try {
        savePoint.capture(viewModel.snapshot())
    } catch (failure: IllegalStateException) {
        error(failure)
        null
    }

    private fun startSave(request: SaveRequest) {
        val target = request.target
        val known = if (!request.asNew && target == path)fingerprint else null
        // Capture destination bytes before the publication worker, including Save As to an existing file.
        work({
            known ?: FileFingerprint.capture(target).also { actual ->
                val sameFile = !request.asNew && target == path
                if (sameFile && actual.exists && !DocumentFiles.read(target).same(diskDocument)) {
                    throw FileConflict(target, FileFingerprint(true), actual)
                }
            }
        }) { expected ->
            if (replaceApproved(request, expected)) {
                work({
                    beforeSaveWriteForChecks()
                    SafeDocumentIO.write(target, request.written, expected)
                }) { published -> acknowledgeSave(request, published) }
            }
        }
    }

    private fun replaceApproved(request: SaveRequest, expected: FileFingerprint): Boolean {
        val replacesOther = (request.asNew || path == null) && expected.exists && saveDestinationPicker == null
        return !replacesOther ||
            JOptionPane.showConfirmDialog(
                window,
                "Replace ${request.target.fileName}?\n$expected",
                "Replace document",
                JOptionPane.YES_NO_OPTION,
            ) == JOptionPane.YES_OPTION
    }

    private fun acknowledgeSave(request: SaveRequest, published: FileFingerprint) {
        val target = request.target
        fingerprint = published
        conflict = null
        path = target
        diskDocument = request.written
        savePoint.acknowledge(request.written)
        unsavedRestore = false
        operationNote = "Saved ${target.fileName}"
        // Typing while the write runs stays dirty. Never close over newer edits or active preedit.
        if (request.closeAfter && closesCleanly(request)) {
            onApprovedClose()
            window.dispose()
        }
    }

    private fun closesCleanly(request: SaveRequest): Boolean {
        val currentSnap = viewModel.snapshot()
        return !dirty &&
            !hasRetainedRecovery() &&
            currentSnap.marked == null &&
            !currentSnap.waiting &&
            currentSnap.documentRevision == request.documentRevision &&
            currentSnap.sessionRevision == request.sessionRevision
    }

    fun close(choice: Int? = null) {
        if (busy) {
            ToolkitFeedback.beep()
            return
        }
        val snap = viewModel.snapshot()
        val hasPreedit = snap.marked != null || snap.waiting
        val retained = hasRetainedRecovery()
        val discardConfirmed = captureRecoveryDiscard()
        val durableDiscard = captureDurableDiscardBeforeClose?.invoke()
        if (!dirty && !hasPreedit && !retained) {
            onApprovedClose()
            window.dispose()
            return
        }
        val docName = path?.fileName?.toString() ?: "Untitled"
        val option = choice ?: promptCloseChoice?.invoke(docName) ?: askCloseChoice(docName, retained)
        when (option) {
            CLOSE_PRIMARY_OPTION -> if (!retained) save(closeAfter = true)
            CLOSE_DISCARD_OPTION -> {
                val finishDiscard: () -> Unit = { finishDiscard(snap, hasPreedit, discardConfirmed) }
                if (durableDiscard != null && (hasPreedit || retained)) {
                    durableDiscard.invoke(finishDiscard)
                } else {
                    finishDiscard()
                }
            }
        }
    }

    private fun askCloseChoice(docName: String, retained: Boolean): Int = JOptionPane.showOptionDialog(
        window,
        if (retained) {
            "Pending: ${pendingRecoveryDescription()}. Keep editing to inspect/export. " +
                "Discard All and Close loses all these entries and any unsaved document changes. " +
                "Unverified input is never applied automatically."
        } else {
            "Save changes to $docName?"
        },
        "Unsaved document",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.QUESTION_MESSAGE,
        null,
        if (retained) {
            arrayOf("Keep Editing", "Discard All and Close", "Cancel")
        } else {
            arrayOf("Save", "Discard", "Cancel")
        },
        if (retained) "Keep Editing" else "Save",
    )

    private fun finishDiscard(snap: EditorFrame, hasPreedit: Boolean, discardConfirmed: () -> Unit) {
        val current = viewModel.snapshot()
        if (current.documentRevision != snap.documentRevision || current.sessionRevision != snap.sessionRevision) {
            operationNote = "Input changed while closing. Review your work and close again."
            refresh()
            return
        }
        if (hasPreedit) {
            onDiscardComposition()
        }
        discardConfirmed()
        if (hasRetainedRecovery()) {
            operationNote = "New recovery work remains. Inspect it before closing."
            refresh()
            return
        }
        onApprovedClose()
        window.dispose()
    }
    fun confirmQuarantineDiscard(count: Int): Boolean = confirmInputDiscardForChecks?.invoke(count) ?: (
        JOptionPane.showConfirmDialog(
            window,
            "Discard these $count unverified input callback(s)? " +
                "Their text has not been accepted into the document. Export first if needed. " +
                "New arrivals will be kept.",
            "Discard unverified input",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        ) == JOptionPane.YES_OPTION
        )

    fun exportQuarantinedInput(entries: List<QuarantinedCallback>, destination: Path? = null) {
        if (busy || entries.isEmpty()) return
        operationNote = null
        refresh()
        val payload = quarantineDiagnostic(entries.toList()).toByteArray(Charsets.UTF_8)
        val target = (destination ?: pickInputDestination())?.toAbsolutePath()?.normalize() ?: return
        if (isMainDocument(target)) {
            error(
                IllegalArgumentException(
                    "Choose a separate diagnostic file. The main document cannot be overwritten.",
                ),
            )
        } else {
            writeDiagnostic(target, payload)
        }
    }

    private fun pickInputDestination(): Path? {
        val custom = inputDestinationPicker
        if (custom != null) return custom.invoke()
        val picker = JFileChooser(path?.parent?.toFile()).apply {
            selectedFile = java.io.File("unverified-input.json")
            fileFilter = FileNameExtensionFilter("Unverified input diagnostic (*.json)", "json")
        }
        return if (picker.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) null else picker.selectedFile.toPath()
    }

    private fun isMainDocument(target: Path): Boolean = target == path?.toAbsolutePath()?.normalize() ||
        (Files.exists(target) && path?.let { Files.exists(it) && Files.isSameFile(target, it) } == true)

    private fun writeDiagnostic(target: Path, payload: ByteArray) {
        // A diagnostic is a new rescue artifact, never a document replacement or persisted replay format.
        work({
            val temp = Files.createTempFile(target.parent, ".unverified-input-", ".tmp")
            try {
                java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(payload)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                // No replacement: a pre-existing file (including an alias of the main file) stays untouched.
                Files.move(temp, target)
            } finally {
                Files.deleteIfExists(temp)
            }
        }) { operationNote = "Unverified input exported: ${target.fileName} (entries retained)" }
    }
    fun exportDraftDocument(document: Document, destination: Path? = null) {
        if (busy) return
        operationNote = null
        refresh()
        val target = (destination ?: pickDraftDestination())?.toAbsolutePath()?.normalize() ?: return
        if (target == path?.toAbsolutePath()?.normalize()) {
            error(IllegalArgumentException("Choose a separate file for the recovery draft."))
        } else {
            work({
                val expected = if (Files.exists(target)) DocumentFiles.read(target) else null
                DocumentFiles.write(target, document, expected)
            }) { operationNote = "Recovery draft exported: ${target.fileName}" }
        }
    }

    private fun pickDraftDestination(): Path? {
        val custom = draftDestinationPicker
        return if (custom != null) custom.invoke() else chooseDraftFile()
    }

    private fun chooseDraftFile(): Path? {
        val picker = chooser().apply { selectedFile = java.io.File("recovered-draft.astra") }
        if (picker.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return null
        val target = withAstraExtension(picker.selectedFile.toPath().toAbsolutePath().normalize())
        val replaceRefused = Files.exists(target) &&
            JOptionPane.showConfirmDialog(
                window,
                "Replace ${target.fileName}?",
                "Export recovery draft",
                JOptionPane.YES_NO_OPTION,
            ) != JOptionPane.YES_OPTION
        return if (replaceRefused) null else target
    }
}

private object ToolkitFeedback {
    fun beep() {
        java.awt.Toolkit.getDefaultToolkit().beep()
    }
}
