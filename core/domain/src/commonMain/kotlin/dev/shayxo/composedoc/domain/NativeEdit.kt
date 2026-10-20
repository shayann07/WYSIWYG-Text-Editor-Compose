package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.validateStructure

/** Strict normalized native proposals. Platform clipping belongs in the adapter, never here. */
sealed interface NativeEdit {
    data class Text(val text: String, val commit: Boolean, val selection: Selection) : NativeEdit
    data class Select(val selection: Selection) : NativeEdit
    data class Mark(val range: Selection) : NativeEdit
    data object Finish : NativeEdit
    data class Partial(val count: Int, val selection: Selection? = null) : NativeEdit
    data class DeleteSurrounding(val before: Int, val after: Int) : NativeEdit
}

internal fun EditorEngine.applyNativeEdit(edit: NativeEdit) {
    when (edit) {
        is NativeEdit.Text -> replaceNativeCandidate(edit)
        is NativeEdit.Select -> dispatch("Native selection") {
            require(edit.selection.start >= 0 && edit.selection.end <= visible().units.size)
            selection = edit.selection
        }
        is NativeEdit.Mark -> dispatch("Native mark") { markNativeRange(edit.range) }
        NativeEdit.Finish -> finishNativeInput()
        is NativeEdit.Partial -> partialCommit(edit.count, edit.selection)
        is NativeEdit.DeleteSurrounding -> dispatch("Native surrounding deletion") {
            deleteNativeSurrounding(edit.before, edit.after)
        }
    }
}

private fun EditorEngine.replaceNativeCandidate(edit: NativeEdit.Text) {
    val c = composition
    val raw = c?.segments?.flatMap { it.units }?.joinToString("") { it.char.toString() }
    val start = c?.markedStart ?: 0
    val end = c?.markedEnd ?: raw?.length ?: 0
    val text = if (raw == null) edit.text else raw.take(start) + edit.text + raw.substring(end)
    applyInputMethod(
        text,
        if (edit.commit) text.length else 0,
        null,
        Selection(start, start + edit.text.length),
        outcome = NativeInputOutcome(edit.selection, edit.commit),
    )
}

private fun EditorEngine.finishNativeInput() {
    val c = composition ?: return
    val raw = c.segments.flatMap { it.units }.joinToString("") { it.char.toString() }
    applyInputMethod(raw, raw.length, null, Selection(0, raw.length), NativeInputOutcome(selection, true))
}

private fun EditorEngine.deleteNativeSurrounding(before: Int, after: Int) {
    require(before >= 0 && after >= 0)
    val frame = snapshot()
    val cover = nativeDeletionCover(frame)
    val a = cover.start
    val b = cover.end
    require(before <= a && after <= frame.visible.units.size - b)
    if (before + after == 0) return
    val c = composition
    val overlayStart = if (c == null) 0 else c.segments.first().dcStart
    val overlayEnd = if (c == null) 0 else overlayStart + c.segments.sumOf { it.units.size }
    // A retargeted session can retain unmarked islands outside the Android composing interval.
    // Refuse a deletion touching those islands rather than widening or accepting them silently.
    requireNoRetainedIslands(c, Selection(a, b), Selection(a - before, b + after))
    fun canonical(at: Int) = if (c != null && at >= overlayEnd) {
        at + c.segments.last().dcEnd - overlayEnd
    } else {
        at
    }
    val savedBefore = saved()
    val left = (a - before until a)
    val right = (b until b + after)
    require((left + right).none { frame.visible.units[it].kind == UnitKind.IMAGE })
    dc = dc.splice(canonical(b), canonical(b + after), emptyList())
        .splice(canonical(a - before), canonical(a), emptyList())
    validateStructure(dc)
    fun map(at: Int) = at - (at - b).coerceIn(0, after) - (at - (a - before)).coerceIn(0, before)
    selection = Selection(map(selection.anchor), map(selection.focus), selection.primary, selection.wrapAffinity)
    if (c != null) {
        fun rebase(segment: dev.shayxo.composedoc.model.OverlaySegment) = segment.copy(
            dcStart = segment.dcStart - before,
            dcEnd = segment.dcEnd - before,
        )
        c.segments = c.segments.map(::rebase)
        c.epochBaseSegments = c.epochBaseSegments.map(::rebase)
    } else {
        push(savedBefore, "Native delete", false)
    }
    typingBarrier = true
}

private fun nativeDeletionCover(frame: EditorFrame): Selection {
    val mark = frame.marked
    return if (mark == null) {
        frame.selection
    } else {
        Selection(minOf(frame.selection.start, mark.first), maxOf(frame.selection.end, mark.last + 1))
    }
}

private fun requireNoRetainedIslands(c: Composition?, cover: Selection, deletion: Selection) {
    if (c != null) {
        val start = c.segments.first().dcStart
        val end = start + c.segments.sumOf { it.units.size }
        val leftOutside = cover.start <= start || deletion.start >= end
        val rightOutside = cover.end >= end || deletion.end <= start
        require(leftOutside && rightOutside) { "Deletion crosses retained composition islands" }
    }
}
