package dev.shayxo.composedoc.ui

import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText

/** No Editable or native history: every query and mutation goes through the published owner. */
internal class AndroidEditorInputConnection(
    private val host: AndroidEditorView,
    private val generation: Int,
    internal val sequence: Long,
) : BaseInputConnection(host, false) {
    private val session =
        NativeTextInputSession(host.model, { host.accepts(this, generation) }, { host.onNativeEvent(it) })
    private var closed = false
    internal val available: Boolean get() = !closed
    private var mutationDepth = 0
    internal val mutating: Boolean get() = mutationDepth > 0
    internal val active: Boolean get() = session.active
    private val current: Boolean get() = Looper.myLooper() == Looper.getMainLooper() &&
        !closed &&
        host.admit(this, generation) &&
        session.active
    var extractedToken: Int? = null
    init {
        session.onNotify = { host.notifyInput(this) }
    }
    private fun expectedReplacement(text: String): String {
        val f = host.model.snapshot()
        val range =
            f.marked?.let { dev.shayxo.composedoc.model.Selection(it.first, it.last + 1) } ?: f.selection
        return f.visible.text.replaceRange(range.start, range.end, text)
    }
    private fun mutate(expected: (() -> String)? = null, operation: () -> Boolean): Boolean {
        if (!current) {
            host.onNativeEvent(
                "Rejected native mutation generation=$generation active=${session.active} " +
                    "main=${Looper.myLooper() == Looper.getMainLooper()}",
            )
            return false
        }
        val predicted = expected?.invoke()
        mutationDepth++
        val accepted = try {
            operation()
        } finally {
            mutationDepth--
        }
        val diverged = predicted != null && host.model.snapshot().visible.text != predicted
        if (accepted && diverged && host.model.compositionCaptureToken() == null) {
            host.invalidateAfterExternalEdit(this)
        }
        return accepted
    }
    override fun getEditable(): android.text.Editable? = null
    override fun beginBatchEdit() = current && session.beginBatch()
    override fun endBatchEdit() = current && session.endBatch()
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = text != null &&
        mutate({
            expectedReplacement(text.toString())
        }) { session.setComposingText(text.toString(), newCursorPosition) }
    override fun setComposingRegion(start: Int, end: Int) = mutate { session.setComposingRegion(start, end) }
    override fun commitText(text: CharSequence?, newCursorPosition: Int) = text != null &&
        mutate({ expectedReplacement(text.toString()) }) { session.commitText(text.toString(), newCursorPosition) }
    override fun finishComposingText() = mutate({
        host.model.snapshot().visible.text
    }) { session.finishComposingText() }
    override fun setSelection(start: Int, end: Int) = mutate { session.setSelection(start, end) }
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        mutate { session.deleteSurrounding(beforeLength, afterLength) }
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
        mutate { session.deleteSurrounding(beforeLength, afterLength, true) }
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = if (current) session.textBefore(n) else null
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = if (current) session.textAfter(n) else null
    override fun getSelectedText(flags: Int): CharSequence? = if (current) session.selectedText() else null
    override fun getCursorCapsMode(reqModes: Int): Int = if (current) {
        TextUtils.getCapsMode(session.allText(), host.model.snapshot().selection.start, reqModes)
    } else {
        0
    }
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (!current) return null
        if (flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) extractedToken = request?.token
        return extracted()
    }
    fun extracted(): ExtractedText = ExtractedText().apply {
        val frame = host.model.snapshot()
        text = frame.visible.text
        startOffset = 0
        partialStartOffset = -1
        partialEndOffset = -1
        selectionStart = frame.selection.anchor
        selectionEnd = frame.selection.focus
    }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        if (!current || beforeLength < 0 || afterLength < 0) return null
        val s = host.model.snapshot().selection
        val before = session.textBefore(beforeLength)
        val selected = session.selectedText()
        val after = session.textAfter(afterLength)
        return if (before != null && selected != null && after != null) {
            val origin = s.start - before.length
            SurroundingText(before + selected + after, s.anchor - origin, s.focus - origin, origin)
        } else {
            null
        }
    }
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        if (!current ||
            cursorUpdateMode and
            (InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR).inv() !=
            0
        ) {
            return false
        }
        host.cursorMonitoring = cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR != 0
        host.notifyCursor()
        return true
    }
    override fun sendKeyEvent(event: KeyEvent): Boolean = mutate { host.handleKey(event) }
    override fun performEditorAction(actionCode: Int): Boolean {
        if (!current) return false
        return when (actionCode) {
            EditorInfo.IME_ACTION_DONE -> {
                session.finishComposingText()
                host.hideKeyboard()
                true
            }
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED -> {
                host.enter()
                true
            }
            else -> false
        }
    }
    override fun performContextMenuAction(id: Int) = current && host.contextAction(id)
    override fun commitCompletion(text: CompletionInfo?): Boolean = text != null &&
        mutate({ expectedReplacement(text.text.toString()) }) { session.commitText(text.text.toString(), 1) }
    override fun commitCorrection(correctionInfo: CorrectionInfo?) = false
    override fun performPrivateCommand(action: String?, data: Bundle?) = false
    override fun closeConnection() {
        val draft = if (session.active) host.model.captureRecoveryDraft() else null
        host.onNativeEvent("closeInputConnection generation=$generation active=${session.active}")
        retire()
        draft?.let { host.onUnfinishedInput(it) }
    }
    fun published() {
        session.published()
    }
    fun retire() {
        closed = true
        mutationDepth++
        try {
            session.close()
        } finally {
            mutationDepth--
        }
    }
}
