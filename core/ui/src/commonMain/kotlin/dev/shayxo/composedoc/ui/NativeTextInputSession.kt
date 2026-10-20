package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.NativeEdit
import dev.shayxo.composedoc.model.DocumentLimits
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextUnit

/** Android-normalized protocol over one owner; stores no editable text or history. */
class NativeTextInputSession(
    private val model: EditorViewModel,
    private val current: () -> Boolean = { true },
    private val trace: (String) -> Unit = {},
) {
    private var closed = false
    private var depth = 0
    private var notifyPending = false
    var onNotify: () -> Unit = {}
    val active: Boolean get() = !closed && current()

    fun beginBatch(): Boolean {
        trace("beginBatchEdit depth=$depth")
        if (!active || depth == MAX_BATCH_DEPTH) return false
        depth++
        return true
    }

    fun endBatch(): Boolean {
        trace("endBatchEdit depth=$depth")
        if (!active || depth == 0) return false
        depth--
        if (depth == 0 && notifyPending) {
            notifyPending = false
            onNotify()
        }
        return true
    }

    fun published() {
        if (!active) return
        if (depth == 0) onNotify() else notifyPending = true
    }

    fun close() {
        val retiring = active
        closed = true
        depth = 0
        notifyPending = false
        onNotify = {}
        if (retiring) model.dispatch(EditorIntent.AbortComposition)
        trace("closeConnection: abort provisional; accepted content retained")
    }

    fun setComposingText(text: String, newCursorPosition: Int): Boolean = update(text, newCursorPosition, false)
    fun commitText(text: String, newCursorPosition: Int): Boolean = update(text, newCursorPosition, true)

    private fun update(text: String, cursor: Int, commit: Boolean): Boolean {
        if (!active || text.length > DocumentLimits.MAX_UNITS) return false
        val s = model.snapshot()
        val range = s.marked?.let { Selection(it.first, it.last + 1) } ?: s.selection
        val size = s.visible.units.size - (range.end - range.start) + text.length
        return size <= DocumentLimits.MAX_UNITS && replace(text, cursor, commit, range.start, size)
    }

    private fun replace(text: String, cursor: Int, commit: Boolean, start: Int, size: Int): Boolean {
        val s = model.snapshot()
        val proposed = start.toLong() + if (cursor > 0) text.length.toLong() + cursor - 1 else cursor.toLong()
        val at = proposed.coerceIn(0, size.toLong()).toInt()
        trace(
            "${if (commit) "commitText" else "setComposingText"} units=${text.length} " +
                "commit=$commit cursor=$cursor normalized=$at basis=${s.sessionRevision}",
        )
        return active &&
            model.dispatch(EditorIntent.Native(NativeEdit.Text(text, commit, Selection(at, at)), s.sessionRevision))
    }

    fun setComposingRegion(start: Int, end: Int): Boolean {
        if (!active) return false
        val basis = model.snapshot()
        val size = basis.visible.units.size
        val a = minOf(start, end).coerceIn(0, size)
        val b = maxOf(start, end).coerceIn(0, size)
        trace("Android composing region raw=$start,$end normalized=$a,$b")
        return active &&
            if (a ==
                b
            ) {
                model.dispatch(EditorIntent.Native(NativeEdit.Finish, basis.sessionRevision))
            } else {
                model.dispatch(EditorIntent.Native(NativeEdit.Mark(Selection(a, b)), basis.sessionRevision))
            }
    }

    fun setSelection(anchor: Int, focus: Int): Boolean {
        trace("setSelection raw=$anchor,$focus")
        if (!active) return false
        val n = model.snapshot().visible.units.size
        return anchor in 0..n &&
            focus in 0..n &&
            model.dispatch(EditorIntent.Native(NativeEdit.Select(Selection(anchor, focus))))
    }

    fun finishComposingText(): Boolean {
        trace("finishComposingText")
        return active && model.dispatch(EditorIntent.Native(NativeEdit.Finish))
    }

    fun deleteSurrounding(before: Int, after: Int, codePoints: Boolean = false): Boolean {
        if (!active || before < 0 || after < 0) return false
        val s = model.snapshot()
        val a = minOf(s.selection.start, s.marked?.first ?: s.selection.start)
        val b = maxOf(s.selection.end, s.marked?.let { it.last + 1 } ?: s.selection.end)
        val left = if (codePoints) countUnits(a, before, false) else before.coerceAtMost(a)
        val right = if (codePoints) {
            countUnits(b, after, true)
        } else {
            after.coerceAtMost(
                s.visible.units.size - b,
            )
        }
        return if (left != null && right != null) {
            trace(
                "Android delete raw=$before,$after codePoints=$codePoints " +
                    "normalized=$left,$right basis=${s.sessionRevision}",
            )
            applyDeletion(left, right, s.sessionRevision)
        } else {
            false
        }
    }

    private fun applyDeletion(left: Int, right: Int, revision: Long): Boolean {
        val edit = NativeEdit.DeleteSurrounding(left, right)
        return left + right == 0 || (active && model.dispatch(EditorIntent.Native(edit, revision)))
    }

    private fun countUnits(at: Int, requested: Int, forward: Boolean): Int? {
        val units = model.snapshot().visible.units
        var p = at
        var remaining = requested
        while (remaining > 0 && (if (forward) p < units.size else p > 0)) {
            val step = scalarUnits(units, p, forward) ?: return null
            p += if (forward) step else -step
            remaining--
        }
        return kotlin.math.abs(p - at)
    }

    private fun scalarUnits(units: List<TextUnit>, at: Int, forward: Boolean): Int? {
        val c = units[if (forward) at else at - 1].char
        val next = units.getOrNull(if (forward) at + 1 else at - UTF16_PAIR_UNITS)?.char
        val validPair = if (forward) {
            c.isHighSurrogate() && next?.isLowSurrogate() == true
        } else {
            c.isLowSurrogate() && next?.isHighSurrogate() == true
        }
        return when {
            !c.isSurrogate() -> 1
            validPair -> UTF16_PAIR_UNITS
            else -> null
        }
    }

    fun textBefore(length: Int): String? {
        if (!active || length < 0) return null
        val at = model.snapshot().selection.start
        return slice((at.toLong() - length).coerceAtLeast(0).toInt(), at)
    }
    fun textAfter(length: Int): String? {
        if (!active || length < 0) return null
        val s = model.snapshot()
        return slice(
            s.selection.end,
            (s.selection.end.toLong() + length).coerceAtMost(s.visible.units.size.toLong()).toInt(),
        )
    }
    fun selectedText(): String? = if (active) model.snapshot().selection.let { slice(it.start, it.end) } else null
    fun allText(): String? = if (active) slice(0, model.snapshot().visible.units.size) else null
    private fun slice(start: Int, end: Int) = model.snapshot().visible.units.subList(start, end)
        .joinToString("") { it.char.toString() }

    companion object {
        private const val MAX_BATCH_DEPTH = 16
        private const val UTF16_PAIR_UNITS = 2
    }
}
