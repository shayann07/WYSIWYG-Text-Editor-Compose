package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.EditorFrame
import java.awt.Toolkit
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

/** Characters below this code point are control characters and are escaped in diagnostics. */
private const val FIRST_PRINTABLE_CODE = 32

internal fun EditorSurface.copySelectionToClipboard(cut: Boolean) {
    if (retired || disposed || !admissionAllowed) return
    val captured = viewModel.clipboardCapture()
    if (captured == null) {
        clipboardMessage = "Finish composition before clipboard commands"
        statusChanged(viewModel.snapshot())
    } else if (!captured.selection.collapsed) {
        publishCapture(captured, cut)
        statusChanged(viewModel.snapshot())
    }
}

private fun EditorSurface.publishCapture(captured: ClipboardCapture, cut: Boolean) {
    try {
        RichClipboard.publish(captured, Toolkit.getDefaultToolkit().systemClipboard, codec = services.fragments)
        val success = !cut || viewModel.dispatch(EditorIntent.ApplyClipboard(captured, null, plain = "", cut = true))
        clipboardMessage =
            if (success) (if (cut) "Cut" else "Copied rich + plain text") else "Cut target changed; content kept"
    } catch (ignored: Exception) {
        // Any clipboard or dispatch failure keeps the document unchanged and is reported in the status.
        clipboardMessage = "Clipboard unavailable; content kept"
    }
}

internal fun EditorSurface.pasteFromClipboard(plainOnly: Boolean) {
    val blocked = retired || disposed || !admissionAllowed || clipboardPasteBusy
    if (blocked) return
    val captured = viewModel.clipboardCapture() ?: run {
        clipboardMessage = "Finish composition before pasting"
        statusChanged(viewModel.snapshot())
        return
    }
    val reader = clipboardReaderForChecks ?: { plain: Boolean ->
        RichClipboard.read(Toolkit.getDefaultToolkit().systemClipboard, plain, codec = services.fragments)
    }
    clipboardPasteBusy = true
    clipboardMessage = "Reading clipboard…"
    statusChanged(viewModel.snapshot())
    object : SwingWorker<RichClipboard.Read, Unit>() {
        override fun doInBackground(): RichClipboard.Read {
            check(!SwingUtilities.isEventDispatchThread())
            return reader(plainOnly)
        }
        override fun done() {
            clipboardPasteBusy = false
            if (retired || disposed)return
            try {
                val data = get()
                val success =
                    admissionAllowed && viewModel.dispatch(EditorIntent.ApplyClipboard(captured, data.rich, data.plain))
                clipboardMessage = pasteMessage(success, data)
            } catch (ignored: Exception) {
                // A failed read or refused paste leaves the document unchanged and is reported in the status.
                clipboardMessage = "Clipboard unavailable or invalid; document unchanged"
            }
            statusChanged(viewModel.snapshot())
        }
    }.execute()
}

private fun pasteMessage(success: Boolean, data: RichClipboard.Read): String = if (!success) {
    "Paste refused; target changed or unsupported data"
} else if (data.fallback) {
    "Invalid rich fragment; pasted plain text"
} else if (data.rich != null) {
    "Pasted rich text"
} else {
    "Pasted plain text"
}

private fun quoted(s: String) = "\"" + s.flatMap { c ->
    when (c) {
        '\\' -> "\\\\"
        '"' -> "\\\""
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> if (c.code < FIRST_PRINTABLE_CODE || Character.isSurrogate(c)) {
            "\\u%04x".format(c.code)
        } else {
            c.toString()
        }
    }.toList()
}.joinToString("") + "\""

/** The diagnostics record for [s] and this host's input/recovery state. */
internal fun EditorSurface.diagnosticsJson(s: EditorFrame): String {
    val failuresJson = recoveryFailures.joinToString(",") { quoted(it) }
    val draftStr = retainedPreimage?.draftText ?: ""
    return "{\"text\":${quoted(s.visible.text)},\"anchor\":${s.selection.anchor},\"focus\":${s.selection.focus}," +
        "\"undo\":${s.undoCount},\"redo\":${s.redoCount},\"documentRevision\":${s.documentRevision}," +
        "\"sessionRevision\":${s.sessionRevision},\"composing\":${s.marked != null},\"waiting\":${s.waiting}," +
        "\"focused\":$isFocusOwner,\"keyTypedEvents\":$keyEvents,\"imeTextEvents\":$imeEvents," +
        "\"activeImeEpoch\":$activeImeEpoch,\"recoveryStatus\":${quoted(recoveryStatus.name)}," +
        "\"contextQualified\":$contextQualified,\"setupSucceeded\":$setupSucceeded," +
        "\"nativeQualified\":$nativeQualified,\"admissionAllowed\":$admissionAllowed," +
        "\"quarantinedCallbacks\":${quarantinedCallbacks.size},\"retainedDraft\":${quoted(draftStr)}," +
        "\"recoveryFailures\":[$failuresJson],\"notice\":${quoted(s.notice)}}"
}
