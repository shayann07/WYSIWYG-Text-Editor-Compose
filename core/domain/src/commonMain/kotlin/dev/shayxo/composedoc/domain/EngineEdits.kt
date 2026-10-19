package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.TypingSide
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.displayedStyles
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.model.validateStructure

/** Consecutive typing within this window coalesces into one undo step. */
private const val TYPING_COALESCE_WINDOW_MS = 750

/** Undo keeps at most this many steps; the oldest is released first. */
private const val MAX_UNDO_STEPS = 1000

/** Applies one owner edit action; returns the history entry it produced, if any. */
internal fun EditorEngine.applyAction(action: EditAction, queued: Boolean = false): Long? = when (action) {
    is EditAction.Replace -> applyReplace(action, queued)
    is EditAction.Structure -> applyStructureAction(action)
    is EditAction.OutlineMove -> applyOutlineMove(action)
    is EditAction.Move -> applyMove(action)
    is EditAction.Paragraph -> applyParagraph(action)
    is EditAction.Format -> applyFormatting(action.target, formatPatch(action.key, action.value))
    is EditAction.FormatAttributes -> applyFormatting(
        action.target,
        TextStylePatch(bold = action.bold, italic = action.italic),
    )
    is EditAction.TextAttributes -> applyFormatting(action.target, action.patch)
    EditAction.Undo -> applyUndo()
    EditAction.Redo -> applyRedo()
}

private fun formatPatch(key: FormatKey, value: Boolean) = when (key) {
    FormatKey.BOLD -> TextStylePatch(bold = value)
    FormatKey.ITALIC -> TextStylePatch(italic = value)
    FormatKey.UNDERLINE -> TextStylePatch(underline = value)
    FormatKey.STRIKE -> TextStylePatch(strike = value)
}

internal fun EditorEngine.resolve(t: CapturedRange): Selection? {
    val u = visible().units
    val rank = u.withIndex().associate { it.value.id to it.index }
    return if (t.collapsed) resolveCaret(t, u.size, rank) else resolveSpan(t, rank)
}

private fun resolveCaret(t: CapturedRange, size: Int, rank: Map<Long, Int>): Selection? {
    val left = if (t.left == null) -1 else rank[t.left]
    val right = if (t.right == null) size else rank[t.right]
    val p = right ?: left?.let { it + 1 }
    return p?.let { Selection(it, it) }
}

private fun resolveSpan(t: CapturedRange, rank: Map<Long, Int>): Selection? {
    val positions = t.ids.mapNotNull { rank[it] }
    val contiguous = positions.size == t.ids.size && positions.zipWithNext().all { it.second == it.first + 1 }
    return when {
        !contiguous -> null
        t.backwards -> Selection(positions.last() + 1, positions.first())
        else -> Selection(positions.first(), positions.last() + 1)
    }
}

internal fun EditorEngine.push(before: Saved, kind: String, coalesce: Boolean): Long? {
    val after = saved()
    if (before.document.same(after.document)) return null
    val previous = undo.lastOrNull()
    val time = now()
    val run = previous?.takeIf { coalesce && continuesTypingRun(it, before, kind, time) }
    return if (run != null) {
        run.after = after
        run.time = time
        redo.clear()
        run.id
    } else {
        appendHistory(before, after, kind, time)
    }
}

private fun EditorEngine.continuesTypingRun(previous: History, before: Saved, kind: String, time: Long): Boolean {
    val sameRun = !typingBarrier && kind == "Typing" && previous.kind == kind
    val adjacent = before.selection.collapsed && before.selection.focus == previous.after.selection.focus
    return sameRun &&
        time - previous.time in 0..TYPING_COALESCE_WINDOW_MS &&
        adjacent &&
        before.document.same(previous.after.document)
}

private fun EditorEngine.appendHistory(before: Saved, after: Saved, kind: String, time: Long): Long {
    val h = History(++historySerial, before, after, kind, time)
    undo += h
    redo.clear()
    if (undo.size > MAX_UNDO_STEPS) {
        undo.removeAt(0)
        notice = "Oldest undo step released"
    }
    typingBarrier = false
    return h.id
}

private fun EditorEngine.applyFormatting(targetAnchor: CapturedRange?, patchValue: TextStylePatch): Long? {
    val target = targetAnchor?.let { resolve(it) }
    if (target == null) {
        notice = "Formatting target changed. Select it again."
        return null
    }
    typingBarrier = true
    return if (target.collapsed) formatCaret(target, patchValue) else formatRange(target, patchValue)
}

private fun EditorEngine.formatCaret(target: Selection, patchValue: TextStylePatch): Long? {
    if (!selection.collapsed || selection.focus != target.focus) {
        notice = "Formatting caret changed. Select the target again."
    } else {
        intent = patchValue.apply(effectiveTyping())
        notice = "Ready"
    }
    return null
}

private fun EditorEngine.formatRange(target: Selection, patchValue: TextStylePatch): Long? {
    val before = saved()
    val intervals = displayedStyles(dc).filter { it.start < target.end && it.end > target.start }
    val patched = BooleanArray(dc.units.size)
    intervals.forEach { for (i in it.start until it.end) patched[i] = true }
    dc = dc.copy(
        units = dc.units.mapIndexed { i, unit ->
            if (patched[i]) unit.copy(style = patchValue.apply(unit.style)) else unit
        },
    )
    intent = null
    notice = "Ready"
    return push(before, "Format", false).also { typingBarrier = true }
}

private fun EditorEngine.applyReplace(action: EditAction.Replace, queued: Boolean): Long? {
    val target = resolve(action.target)
    if (target == null) {
        notice = "The target changed. Please try again."
        return null
    }
    val raw = dc.text
    val a = if (action.exactUtf16) target.start else Graphemes.snap(raw, target.start, after = target.collapsed)
    val b = if (target.collapsed) {
        a
    } else if (action.exactUtf16) {
        target.end
    } else {
        Graphemes.snap(raw, target.end)
    }
    return if (a == b && action.text.isEmpty()) null else replaceRange(action, queued, a, b)
}

private fun EditorEngine.replaceRange(action: EditAction.Replace, queued: Boolean, a: Int, b: Int): Long? {
    val before = saved()
    val inserted = units(action.text, action.paragraphBreaks, action.style, dc.paragraphAt(a).properties)
    require(!(a == b && action.text.isNotEmpty() && paragraphHoldsImage(dc, a))) {
        "Image is atomic; insert in the neighboring paragraph"
    }
    dc = dc.splice(a, b, inserted)
    validateStructure(dc)
    selection = Selection(a + inserted.size, a + inserted.size)
    intent = action.style
    if (inserted.isEmpty()) {
        val at = selection.focus
        if (isEmptyParagraphAt(dc, at)) {
            dc = dc.withEmptyParagraphStyle(at, action.style)
        } else {
            intent = null
            side = action.deleteSide ?: TypingSide.LEFT
        }
    }
    notice = "Ready"
    return push(before, action.kind, !queued && action.kind == "Typing")
}

private fun paragraphHoldsImage(d: Document, at: Int) =
    d.paragraphAt(at).let { d.units.subList(it.start, it.end).any { u -> u.kind == UnitKind.IMAGE } }

/** True when [at] is a caret inside an empty paragraph of [d]. */
internal fun isEmptyParagraphAt(d: Document, at: Int): Boolean {
    val openBefore = at == 0 || d.units[at - 1].kind == UnitKind.PARAGRAPH_BREAK
    return openBefore && (at == d.units.size || d.units[at].kind == UnitKind.PARAGRAPH_BREAK)
}

/** Records [style] as the typing style of the empty paragraph at [at]. */
internal fun Document.withEmptyParagraphStyle(at: Int, style: TextStyleFlags): Document = if (at == 0) {
    copy(startStyle = style)
} else {
    copy(
        units = units.mapIndexed { i, unit ->
            if (i == at - 1) unit.copy(style = TextStyleFlags(), paragraphStyle = style) else unit
        },
    )
}

private fun EditorEngine.applyStructureAction(action: EditAction.Structure): Long? {
    val target = resolve(action.target)
    if (target == null) {
        notice = "Structural target changed"
        return null
    }
    val before = saved()
    val changed = structureEdit(dc, target, action.anchors, action.command) { ++serial }
    dc = changed.first
    selection = changed.second
    intent = null
    side = TypingSide.LEFT
    validateStructure(dc)
    typingBarrier = true
    notice = "Structure updated"
    return push(before, "Structure", false).also { typingBarrier = true }
}

private fun EditorEngine.applyOutlineMove(action: EditAction.OutlineMove): Long? {
    val p = dc.paragraphs().singleOrNull { it.anchor == action.anchor && it.properties.heading > 0 }
    if (p == null) {
        notice = "Heading target removed"
    } else {
        selection = Selection(p.start, p.start)
        intent = null
        side = TypingSide.LEFT
        typingBarrier = true
    }
    return null
}

private fun EditorEngine.applyMove(action: EditAction.Move): Long? {
    resolve(action.target)?.let {
        selection = it.copy(primary = action.primary, wrapAffinity = action.wrapAffinity)
        intent = null
        side = action.side
    } ?: run { notice = "Text changed. Select the position again." }
    typingBarrier = true
    return null
}

private fun EditorEngine.applyParagraph(action: EditAction.Paragraph): Long? {
    val blocks = dc.paragraphs()
    val targets = blocks.filter { it.anchor in action.anchors }
    if (targets.size != action.anchors.distinct().size) {
        notice = "Paragraph target changed; try again."
        return null
    }
    val before = saved()
    fun patch(p: ParagraphProperties) = p.copy(
        direction = action.direction ?: p.direction,
        alignment = action.alignment ?: p.alignment,
        spaceBefore = action.before ?: p.spaceBefore,
        spaceAfter = action.after ?: p.spaceAfter,
        linePermille = action.line ?: p.linePermille,
    )
    dc = dc.patchParagraphs(targets.associate { it.anchor to patch(it.properties) })
    val id = push(before, "Paragraph", false)
    typingBarrier = true
    notice = "Paragraph properties updated"
    return id
}

/** Applies a canonical probe transaction; accepted inverses never enter local root-snapshot Undo. */
internal fun EditorEngine.applyCanonicalProbeEdits(edits: List<CanonicalProbeEdit>, mappedSelection: Selection) {
    for (edit in edits) dc = canonicalProbeEdit(dc, edit)
    require(mappedSelection.start >= 0 && mappedSelection.end <= dc.units.size)
    selection = mappedSelection
    typingBarrier = true
    notice = "Canonical probe transaction applied"
}

private fun EditorEngine.canonicalProbeEdit(d: Document, edit: CanonicalProbeEdit): Document = when (edit) {
    is CanonicalProbeEdit.Replace -> {
        require(edit.start in 0..d.units.size && edit.end in edit.start..d.units.size)
        d.splice(
            edit.start,
            edit.end,
            units(edit.text, edit.paragraphBreaks, edit.style, d.paragraphAt(edit.start).properties),
        )
    }
    is CanonicalProbeEdit.Attributes -> {
        require(edit.start in 0..d.units.size && edit.end in edit.start..d.units.size)
        d.copy(units = d.units.mapIndexed { i, unit -> probeAttributes(unit, i, edit) })
    }
    is CanonicalProbeEdit.Move -> {
        require(edit.start >= 0 && edit.start < edit.end && edit.end <= d.units.size)
        val moved = d.units.subList(edit.start, edit.end).toList()
        val remaining = d.units.take(edit.start) + d.units.drop(edit.end)
        require(edit.destination in 0..remaining.size)
        d.copy(units = remaining.take(edit.destination) + moved + remaining.drop(edit.destination))
    }
}

private fun probeAttributes(unit: TextUnit, i: Int, edit: CanonicalProbeEdit.Attributes): TextUnit =
    if (i !in edit.start until edit.end) {
        unit
    } else if (unit.kind == UnitKind.PARAGRAPH_BREAK) {
        unit.copy(style = TextStyleFlags(), paragraphStyle = edit.style)
    } else {
        unit.copy(style = edit.style)
    }
