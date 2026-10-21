package dev.shayxo.composedoc.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.model.DocumentLimits
import dev.shayxo.composedoc.model.plainText
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val RICH_MIME = "application/x-composedoc-fragment"
private const val READ_BUFFER = 8192
private const val MAX_PLAIN_BYTES = 400_000
private const val CLIPBOARD_QUEUE = 4
private const val MAX_CLIP_ITEMS = 16
private val clipboardMain = Handler(Looper.getMainLooper())
private val clipboardIo = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(CLIPBOARD_QUEUE))

internal class AndroidSelectionActions(private val host: AndroidEditorView) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        listOf(
            android.R.id.copy to "Copy",
            android.R.id.cut to "Cut",
            android.R.id.paste to "Paste",
            android.R.id.pasteAsPlainText to "Paste plain",
            android.R.id.selectAll to "Select all",
        )
            .forEachIndexed { index, (id, label) -> menu.add(0, id, index, label) }
        return true
    }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
        host.contextAction(item.itemId).also { if (it) mode.finish() }
    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

internal fun AndroidEditorView.contextAction(id: Int): Boolean = if (id == android.R.id.selectAll) {
    model.dispatch(EditorIntent.SelectAll)
    true
} else {
    val capture = model.clipboardCapture()
    if (capture == null) false else capturedContextAction(id, capture)
}

private fun AndroidEditorView.capturedContextAction(id: Int, capture: ClipboardCapture): Boolean {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return when (id) {
        android.R.id.copy, android.R.id.cut -> {
            if (capture.selection.collapsed) false else publishSelection(manager, capture, id == android.R.id.cut)
        }
        android.R.id.paste, android.R.id.pasteAsPlainText -> {
            val clip = manager.primaryClip ?: return false
            pasteSelection(clip, capture, id == android.R.id.pasteAsPlainText)
        }
        else -> false
    }
}

private fun AndroidEditorView.current(capture: ClipboardCapture): Boolean {
    val now = model.clipboardCapture() ?: return false
    return isAttachedToWindow &&
        now.basis.owner === capture.basis.owner &&
        now.basis.revision == capture.basis.revision &&
        now.sessionRevision == capture.sessionRevision &&
        now.selection == capture.selection &&
        now.basis.document == capture.basis.document
}

private fun AndroidEditorView.publishSelection(
    manager: ClipboardManager,
    capture: ClipboardCapture,
    cut: Boolean,
): Boolean {
    val codec = fragmentCodec
    val publish = publishRichClipboard
    onNativeEvent("Clipboard copy pending; wait for publication")
    return clipboardWork {
        val fragment = RichFragment.capture(capture)
        val plain = fragment.document.plainText()
        var warning = ""
        val uri = runCatching {
            if (codec != null && publish != null) publish(codec.encode(fragment)) else null
        }.getOrElse {
            warning = "Rich copy unavailable: ${it.message}; plain text published"
            null
        }
        check(!cut || uri != null || (codec == null && publish == null)) {
            "Rich Cut unavailable; nothing removed. Clear the clipboard cache/retry, or use Copy for a plain fallback."
        }
        clipboardMain.post {
            if (!current(capture)) {
                onNativeEvent("Clipboard selection changed; operation cancelled")
                return@post
            }
            val clip = if (uri != null) {
                ClipData(
                    ClipDescription("ComposeDoc rich selection", arrayOf("text/plain", RICH_MIME)),
                    ClipData.Item(plain),
                )
                    .apply { addItem(ClipData.Item(uri)) }
            } else {
                ClipData.newPlainText("ComposeDoc selected text", plain)
            }
            runCatching { manager.setPrimaryClip(clip) }.fold(
                onSuccess = {
                    if (cut &&
                        !model.dispatch(EditorIntent.ApplyClipboard(capture, null, cut = true))
                    ) {
                        onNativeEvent("Cut basis changed; text retained")
                    } else {
                        onNativeEvent(
                            warning.ifEmpty {
                                if (uri !=
                                    null
                                ) {
                                    "Rich clipboard published"
                                } else {
                                    "Plain clipboard published; rich transport not configured"
                                }
                            },
                        )
                    }
                },
                onFailure = { onNativeEvent("Clipboard publication failed; text retained: ${it.message}") },
            )
        }
    }
}

private fun AndroidEditorView.pasteSelection(clip: ClipData, capture: ClipboardCapture, plainOnly: Boolean): Boolean {
    val codec = fragmentCodec
    onNativeEvent("Clipboard paste pending; captured selection is protected")
    return clipboardWork {
        require(clip.itemCount > 0) { "Clipboard is empty" }
        val text = clip.getItemAt(0).text
        require(text == null || text.length <= DocumentLimits.MAX_UNITS) { "Clipboard text exceeds its unit limit" }
        var warning = ""
        val rich = if (!plainOnly && clip.description.hasMimeType(RICH_MIME) && codec != null) {
            runCatching {
                require(clip.itemCount <= MAX_CLIP_ITEMS) { "Too many clipboard resources" }
                val uri =
                    (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
                        ?: error("Missing rich resource")
                codec.decode(readClipboardUri(uri, codec.maximumEncodedBytes))
            }.getOrElse {
                warning = "Rich paste unavailable: ${it.message}; using plain text"
                null
            }
        } else {
            null
        }
        if (rich !=
            null
        ) {
            check(androidBodyLimitation(rich.document) == null) {
                "Unsupported rich structure. Use explicit Paste plain to import only its text."
            }
        }
        val plain = if (rich == null) {
            text?.toString() ?: clip.getItemAt(0).uri?.let {
                readClipboardUri(it, MAX_PLAIN_BYTES).toString(Charsets.UTF_8)
            }
        } else {
            null
        }
        require(plain == null || plain.length <= DocumentLimits.MAX_UNITS) { "Clipboard text exceeds its unit limit" }
        clipboardMain.post {
            if (!current(capture)) {
                onNativeEvent("Paste basis changed; incoming content was not applied")
                return@post
            }
            val applied = model.dispatch(EditorIntent.ApplyClipboard(capture, rich, plain))
            onNativeEvent(pasteMessage(applied, rich != null, warning))
        }
    }
}

private fun pasteMessage(applied: Boolean, rich: Boolean, warning: String) = when {
    !applied -> "Paste rejected; document retained"
    warning.isNotEmpty() -> warning
    rich -> "Rich paste applied"
    else -> "Plain paste applied"
}

private fun AndroidEditorView.readClipboardUri(uri: Uri, limit: Int): ByteArray {
    require(uri.scheme == "content") { "Clipboard resource must be a content URI" }
    return checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - bytes.size()))
            if (n < 0) break
            bytes.write(buffer, 0, n)
            require(bytes.size() <= limit) { "Clipboard resource exceeds its byte limit" }
        }
        bytes.toByteArray()
    }
}

private fun AndroidEditorView.clipboardWork(work: () -> Unit): Boolean = try {
    clipboardIo.execute {
        runCatching(work).onFailure {
            clipboardMain.post { onNativeEvent("Clipboard failed; document retained: ${it.message}") }
        }
    }
    true
} catch (_: java.util.concurrent.RejectedExecutionException) {
    onNativeEvent("Clipboard is busy; retry after the current transfer")
    false
}
