package dev.shayxo.composedoc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import dev.shayxo.composedoc.domain.ClipboardCapture

internal class AndroidSelectionActions(private val host: AndroidEditorView) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        listOf(
            android.R.id.copy to "Copy",
            android.R.id.cut to "Cut",
            android.R.id.paste to "Paste plain",
            android.R.id.selectAll to "Select all",
        )
            .forEachIndexed { index, (id, label) -> menu.add(0, id, index, label) }
        return true
    }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val result = host.contextAction(item.itemId)
        if (result) mode.finish()
        return result
    }
    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

internal fun AndroidEditorView.contextAction(id: Int): Boolean {
    if (id == android.R.id.selectAll) {
        model.dispatch(EditorIntent.SelectAll)
        return true
    }
    val capture = model.clipboardCapture()
    return capture != null && clipboardAction(id, capture)
}

private fun AndroidEditorView.clipboardAction(id: Int, capture: ClipboardCapture): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return when (id) {
        android.R.id.copy, android.R.id.cut -> publishSelection(clipboard, capture, id == android.R.id.cut)
        android.R.id.paste -> pastePlain(clipboard)
        else -> false
    }
}

private fun AndroidEditorView.publishSelection(
    clipboard: ClipboardManager,
    capture: ClipboardCapture,
    cut: Boolean,
): Boolean {
    val frame = model.snapshot()
    if (frame.selection.collapsed) return false
    val published = try {
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "ComposeDoc selected text",
                frame.committed.text.substring(frame.selection.start, frame.selection.end),
            ),
        )
        true
    } catch (error: SecurityException) {
        onNativeEvent("Clipboard publication refused: ${error.javaClass.simpleName}")
        false
    }
    return published && (!cut || model.dispatch(EditorIntent.ApplyClipboard(capture, null, cut = true)))
}

private fun AndroidEditorView.pastePlain(clipboard: ClipboardManager): Boolean {
    val clip = clipboard.primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    return if (text != null) {
        model.dispatch(EditorIntent.Type(text, "Plain paste", paragraphBreaks = true))
        true
    } else {
        false
    }
}
