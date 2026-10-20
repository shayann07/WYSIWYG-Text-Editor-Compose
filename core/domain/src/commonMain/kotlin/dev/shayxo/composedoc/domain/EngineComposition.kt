package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.OverlaySegment
import dev.shayxo.composedoc.model.SegmentOrigin
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.validateStructure

/** Length of the common prefix of [text] and the characters of [base]. */
internal fun commonPrefixLength(text: String, base: List<TextUnit>): Int {
    var prefix = 0
    while (prefix < text.length && prefix < base.size && text[prefix] == base[prefix].char)prefix++
    return prefix
}

/** Length of the common suffix of [text] and [base] that does not overlap a common prefix of length [prefix]. */
internal fun commonSuffixLength(text: String, base: List<TextUnit>, prefix: Int): Int {
    var suffix = 0
    while (suffix < text.length - prefix &&
        suffix < base.size - prefix &&
        text[text.length - 1 - suffix] == base[base.size - 1 - suffix].char
    ) {
        suffix++
    }
    return suffix
}

/** Provisional admission units take identities below this base so they never collide with owner serials. */
private const val PROVISIONAL_UNIT_ID_BASE = -1_000_000L

/** Whether replacing the composition (or selection) with [text] keeps the document structurally valid. */
internal fun EditorEngine.admitsNativeText(text: String): Boolean {
    val session = composition
    val a = session?.segments?.first()?.dcStart ?: selection.start
    val b = session?.segments?.last()?.dcEnd ?: selection.end
    val base = session?.epochBaseSegments?.flatMap { it.units } ?: dc.units.subList(a, b)
    val prefix = commonPrefixLength(text, base)
    val suffix = commonSuffixLength(text, base, prefix)
    val provisional = text.substring(prefix, text.length - suffix).mapIndexed { i, c ->
        TextUnit(PROVISIONAL_UNIT_ID_BASE - i, c)
    }
    val proposed = base.take(prefix) + provisional + base.takeLast(suffix)
    return runCatching {
        validateStructure(dc.splice(a, b, proposed, canonicalRetainedParagraphs = true))
        true
    }.getOrDefault(false)
}

/** Strict normalized native retarget: widens the overlay with retained units to cover [range]. */
internal fun EditorEngine.retargetOverlay(range: Selection) {
    val current = visible()
    require(range.start >= 0 && range.end <= current.units.size)
    require(current.units.subList(range.start, range.end).none { it.kind == UnitKind.IMAGE }) {
        "Native image seam is not editable text"
    }
    val c = checkNotNull(composition) { "No active composition" }
    val sc = c.segments.first().dcStart
    val ec = c.segments.last().dcEnd
    val visibleEnd = sc + c.segments.sumOf { it.units.size }
    val left = minOf(sc, range.start)
    val right = maxOf(visibleEnd, range.end)
    val prefix = (left until sc).map {
        OverlaySegment(it, it + 1, listOf(dc.units[it]), SegmentOrigin.RETAINED)
    }
    val tailCount = right - visibleEnd
    val suffix = (ec until ec + tailCount).map {
        OverlaySegment(it, it + 1, listOf(dc.units[it]), SegmentOrigin.RETAINED)
    }
    c.segments = prefix + c.segments + suffix
    c.epochBaseSegments = c.segments
    c.synthesized.clear()
    c.markedStart = range.start - left
    c.markedEnd = range.end - left
    selection = range
}

internal data class NativeInputOutcome(val selection: Selection, val fullyCommitted: Boolean)

internal fun EditorEngine.applyInputMethod(
    text: String,
    committedCount: Int,
    caret: Int?,
    markedRange: Selection,
    outcome: NativeInputOutcome? = null,
) {
    val postSelection = outcome?.selection
    val fullyCommitted = outcome?.fullyCommitted ?: (committedCount == text.length)
    val idle = composition == null && text.isEmpty()
    val unchangedIdle = idle && committedCount == 0 && selection.collapsed
    if (unchangedIdle && postSelection == null) return
    dispatch("IME text: committed=$committedCount composed=${text.length - committedCount}") {
        require(nativeStructureAdmission(text)) { "Native image seam is not editable text" }
        val existing = composition != null
        val session = composition ?: startComposition()
        val newSegments = reconcileOverlay(session.epochBaseSegments, text, session.seed, session.synthesized) {
            ++serial
        }
        if (committedCount > 0) {
            commitAcceptedPrefix(session, text, committedCount, newSegments)
        } else {
            session.segments = newSegments
        }
        session.updateNativeMark(markedRange, text.length, committedCount)
        val sc = session.segments.first().dcStart
        val remUnits = session.segments.sumOf { it.units.size }
        selection = Selection(sc + (caret ?: remUnits), sc + (caret ?: remUnits))
        applyNativeSelection(postSelection)
        val kind = if (existing || text.length != committedCount) "Composition" else "Typing"
        settleInputMethodText(session, sc, kind, fullyCommitted, postSelection)
    }
}

private fun Composition.updateNativeMark(range: Selection, textSize: Int, committedCount: Int) {
    markedStart = (range.start - committedCount).coerceAtLeast(0)
    markedEnd = if (range.start == 0 && range.end == textSize) {
        null
    } else {
        (range.end - committedCount).coerceAtLeast(0)
    }
}

private fun EditorEngine.applyNativeSelection(value: Selection?) {
    value?.let {
        require(it.start >= 0 && it.end <= visible().units.size)
        selection = it
    }
}

private fun EditorEngine.startComposition(beforeState: Saved = saved()): Composition {
    val initialSegments = if (selection.start == selection.end) {
        listOf(OverlaySegment(selection.start, selection.start, emptyList(), SegmentOrigin.REPLACEMENT))
    } else {
        (selection.start until selection.end).map { i ->
            OverlaySegment(i, i + 1, listOf(dc.units[i]), SegmentOrigin.RETAINED)
        }
    }
    return Composition(
        before = beforeState,
        segments = initialSegments,
        epochBaseSegments = initialSegments,
        seed = replacementStyle(selection),
    ).also { composition = it }
}

private fun EditorEngine.settleInputMethodText(
    session: Composition,
    sc: Int,
    kind: String,
    fullyCommitted: Boolean,
    postSelection: Selection?,
) {
    if (fullyCommitted) {
        val ec = session.segments.last().dcEnd
        if (ec > sc) {
            dc = dc.splice(sc, ec, emptyList())
        }
        selection = postSelection ?: Selection(sc, sc)
        finish(session, kind)
    } else if (pending.isEmpty()) {
        settlementRequestedAt = null
        if (!settlementTimedOut) notice = "Composing…"
    } else {
        if (settlementRequestedAt == null) settlementRequestedAt = now()
        notice = "Waiting for the input method to finish…"
    }
}

/** Which epoch base segments the replaced visible span [aMid, bMid) touches, with retained edge units. */
private class OverlayTouch(val first: Int, val last: Int, val left: List<TextUnit>, val right: List<TextUnit>)

private val untouched = OverlayTouch(-1, -1, emptyList(), emptyList())

/** Reconciles a complete preedit against the epoch base, reusing synthesized units by position and character. */
private fun reconcileOverlay(
    baseSegs: List<OverlaySegment>,
    text: String,
    seed: TextStyleFlags,
    synthesized: MutableMap<Pair<Int, Char>, TextUnit>,
    nextId: () -> Long,
): List<OverlaySegment> {
    val baselineUnits = baseSegs.flatMap { it.units }
    val p = commonPrefixLength(text, baselineUnits)
    val s = commonSuffixLength(text, baselineUnits, p)
    if (p == baselineUnits.size && s == 0 && text.length == baselineUnits.size) return baseSegs
    val midText = text.substring(p, text.length - s)
    var candidateLink = seed.link
    val midUnits = midText.mapIndexed { idx, ch ->
        if (ch.isWhitespace())candidateLink = null
        synthesized.getOrPut((p + idx) to ch) {
            TextUnit(nextId(), ch, UnitKind.TEXT, seed.copy(link = candidateLink))
        }
    }
    val aMid = p
    val bMid = baselineUnits.size - s
    val touch = if (aMid == bMid) touchedAtCaret(baseSegs, aMid) else touchedAcross(baseSegs, aMid, bMid)
    val segList = if (touch.first == -1) {
        insertedOverlay(baseSegs, aMid, midUnits)
    } else {
        replacedOverlay(baseSegs, touch, midUnits)
    }
    if (segList.isEmpty()) {
        val fallbackDc = if (touch.first in baseSegs.indices) {
            baseSegs[touch.first].dcStart
        } else if (baseSegs.isNotEmpty()) {
            baseSegs[0].dcStart
        } else {
            0
        }
        segList.add(OverlaySegment(fallbackDc, fallbackDc, emptyList(), SegmentOrigin.REPLACEMENT))
    }
    return segList
}

private fun touchedAtCaret(baseSegs: List<OverlaySegment>, aMid: Int): OverlayTouch {
    var curVis = 0
    for (i in baseSegs.indices) {
        val seg = baseSegs[i]
        val segStart = curVis
        val segEnd = curVis + seg.units.size
        curVis = segEnd
        if (segStart < aMid && aMid < segEnd) {
            return OverlayTouch(
                i,
                i,
                seg.units.subList(0, aMid - segStart),
                seg.units.subList(aMid - segStart, seg.units.size),
            )
        }
    }
    return untouched
}

private fun touchedAcross(baseSegs: List<OverlaySegment>, aMid: Int, bMid: Int): OverlayTouch {
    var firstTouched = -1
    var lastTouched = -1
    var leftUnits = emptyList<TextUnit>()
    var rightUnits = emptyList<TextUnit>()
    var curVis = 0
    for (i in baseSegs.indices) {
        val seg = baseSegs[i]
        val segStart = curVis
        val segEnd = curVis + seg.units.size
        curVis = segEnd
        val touches = if (seg.units.isEmpty()) {
            aMid <= segStart && segStart <= bMid
        } else {
            maxOf(segStart, aMid) < minOf(segEnd, bMid)
        }
        if (touches) {
            if (firstTouched == -1) {
                firstTouched = i
                val lEnd = (aMid - segStart).coerceIn(0, seg.units.size)
                leftUnits = seg.units.subList(0, lEnd)
            }
            lastTouched = i
            val rStart = (bMid - segStart).coerceIn(0, seg.units.size)
            rightUnits = seg.units.subList(rStart, seg.units.size)
        }
    }
    return OverlayTouch(firstTouched, lastTouched, leftUnits, rightUnits)
}

/** Pure insertion: the replacement lands between the base segments around visible offset [aMid]. */
private fun insertedOverlay(
    baseSegs: List<OverlaySegment>,
    aMid: Int,
    midUnits: List<TextUnit>,
): ArrayList<OverlaySegment> {
    val segList = ArrayList<OverlaySegment>()
    var insIdx = 0
    var curVis = 0
    while (insIdx < baseSegs.size && curVis + baseSegs[insIdx].units.size <= aMid) {
        curVis += baseSegs[insIdx].units.size
        insIdx++
    }
    for (i in 0 until insIdx) segList.add(baseSegs[i])
    val dcPos = if (insIdx < baseSegs.size) {
        baseSegs[insIdx].dcStart
    } else if (baseSegs.isNotEmpty()) {
        baseSegs.last().dcEnd
    } else {
        0
    }
    segList.add(OverlaySegment(dcPos, dcPos, midUnits, SegmentOrigin.REPLACEMENT))
    for (i in insIdx until baseSegs.size) segList.add(baseSegs[i])
    return segList
}

private fun replacedOverlay(
    baseSegs: List<OverlaySegment>,
    touch: OverlayTouch,
    midUnits: List<TextUnit>,
): ArrayList<OverlaySegment> {
    val segList = ArrayList<OverlaySegment>()
    val firstTouched = touch.first
    val lastTouched = touch.last
    for (i in 0 until firstTouched) segList.add(baseSegs[i])

    val dcStartMid = if (touch.left.isNotEmpty()) baseSegs[firstTouched].dcEnd else baseSegs[firstTouched].dcStart
    val dcEndMid = baseSegs[lastTouched].dcEnd

    if (touch.left.isNotEmpty()) {
        segList.add(
            OverlaySegment(
                baseSegs[firstTouched].dcStart,
                baseSegs[firstTouched].dcEnd,
                touch.left,
                baseSegs[firstTouched].origin,
            ),
        )
    }

    if (midUnits.isNotEmpty() || dcStartMid < dcEndMid) {
        segList.add(OverlaySegment(dcStartMid, dcEndMid, midUnits, SegmentOrigin.REPLACEMENT))
    }

    if (touch.right.isNotEmpty()) {
        segList.add(
            OverlaySegment(
                baseSegs[lastTouched].dcEnd,
                baseSegs[lastTouched].dcEnd,
                touch.right,
                baseSegs[lastTouched].origin,
            ),
        )
    }

    for (i in (lastTouched + 1) until baseSegs.size) segList.add(baseSegs[i])
    return segList
}

/** Commits the accepted prefix into Dc and rebases the remaining overlay as the next epoch. */
private fun EditorEngine.commitAcceptedPrefix(
    session: Composition,
    text: String,
    committedCount: Int,
    newSegments: List<OverlaySegment>,
) {
    if (session.seed.link != null && text.take(committedCount).any { it.isWhitespace() }) {
        session.seed = session.seed.copy(link = null)
        session.synthesized.clear()
    }
    val split = splitCommitted(newSegments, committedCount)
    val sc = newSegments.first().dcStart
    val pCommit = split.consumed.flatMap { it.units }
    dc = dc.splice(sc, split.dCut, pCommit, canonicalRetainedParagraphs = true)
    val remaining = rebasedRemainder(split, sc, pCommit.size)
    session.segments = remaining
    session.epochBaseSegments = remaining
    session.synthesized.clear()
}

private class SplitTail(val units: List<TextUnit>, val origDcEnd: Int, val origin: SegmentOrigin)

private class CommitSplit(
    val consumed: List<OverlaySegment>,
    val remaining: List<OverlaySegment>,
    val tail: SplitTail?,
    val dCut: Int,
)

private fun splitCommitted(segs: List<OverlaySegment>, pCut: Int): CommitSplit {
    val sc = segs.first().dcStart
    var curVis = 0
    val consumedSegments = ArrayList<OverlaySegment>()
    val remainingSegments = ArrayList<OverlaySegment>()
    var splitTail: SplitTail? = null
    var dCut = sc
    val totalOverlayUnits = segs.sumOf { it.units.size }

    for (seg in segs) {
        val segLen = seg.units.size
        val segStart = curVis
        val segEnd = curVis + segLen
        curVis = segEnd
        val consumedWhole = if (segLen == 0) {
            emptySegmentConsumed(segStart, pCut, totalOverlayUnits)
        } else {
            segEnd <= pCut
        }
        if (consumedWhole) {
            consumedSegments.add(seg)
            dCut = seg.dcEnd
        } else if (segLen != 0 && segStart < pCut) {
            val commUnits = seg.units.subList(0, pCut - segStart)
            val remUnits = seg.units.subList(pCut - segStart, seg.units.size)
            val origSpan = seg.dcEnd - seg.dcStart
            val consumedSpan = if (seg.origin == SegmentOrigin.RETAINED) commUnits.size else origSpan
            val segCut = seg.dcStart + consumedSpan
            consumedSegments.add(OverlaySegment(seg.dcStart, segCut, commUnits, seg.origin))
            dCut = segCut
            splitTail = SplitTail(remUnits, seg.dcEnd, seg.origin)
        } else {
            remainingSegments.add(seg)
        }
    }
    return CommitSplit(consumedSegments, remainingSegments, splitTail, dCut)
}

/** An empty segment at the cut is consumed only when nothing remains after the accepted prefix. */
private fun emptySegmentConsumed(segStart: Int, pCut: Int, totalOverlayUnits: Int): Boolean {
    val hasRemainingUnitsAfter = pCut < totalOverlayUnits
    return segStart < pCut || (segStart == pCut && pCut > 0 && !hasRemainingUnitsAfter)
}

private fun rebasedRemainder(split: CommitSplit, sc: Int, committedUnits: Int): ArrayList<OverlaySegment> {
    val delta = committedUnits - (split.dCut - sc)
    val newSc = sc + committedUnits
    val finalRemSegments = ArrayList<OverlaySegment>()
    val splitTail = split.tail
    if (splitTail != null && splitTail.units.isNotEmpty()) {
        finalRemSegments.add(
            OverlaySegment(
                dcStart = newSc,
                dcEnd = splitTail.origDcEnd + delta,
                units = splitTail.units,
                origin = if (splitTail.origin == SegmentOrigin.RETAINED) {
                    SegmentOrigin.RETAINED
                } else {
                    SegmentOrigin.REPLACEMENT
                },
            ),
        )
    }
    for (seg in split.remaining) {
        finalRemSegments.add(
            OverlaySegment(
                dcStart = seg.dcStart + delta,
                dcEnd = seg.dcEnd + delta,
                units = seg.units,
                origin = seg.origin,
            ),
        )
    }
    if (finalRemSegments.isEmpty()) {
        finalRemSegments.add(OverlaySegment(newSc, newSc, emptyList(), SegmentOrigin.REPLACEMENT))
    }
    return finalRemSegments
}

/** Ends the composition session, records its history and replays commands queued during it. */
internal fun EditorEngine.finish(session: Composition, kind: String, aborted: Boolean = false) {
    composition = null
    settlementRequestedAt = null
    settlementTimedOut = false
    imeEpoch++
    intent = if (aborted && session.before.document.same(dc)) session.before.intent else session.seed
    if (!aborted && !session.before.document.same(dc)) {
        val at = selection.focus
        if (isEmptyParagraphAt(dc, at)) dc = dc.withEmptyParagraphStyle(at, session.seed)
    }
    val group = push(session.before, kind, kind == "Typing" && pending.isEmpty())
    val sc = session.segments.first().dcStart
    val settledTarget = capture(Selection(session.before.selection.start, sc))
    val slots = undo.map { it.id as Long? }.toMutableList()
    if (group == null && session.undoSawDirty) slots += null
    for (action in pending.toList()) {
        if (action == EditAction.Undo) {
            replayPendingUndo(action, slots)
        } else {
            replayPendingEdit(action, slots, settledTarget, aborted)
        }
    }
    pending.clear()
    if (notice.startsWith("Composing") || notice.startsWith("Waiting")) notice = "Ready"
}

/** A queued Undo reverts only the history slot it was issued against; external history handles its own. */
private fun EditorEngine.replayPendingUndo(action: EditAction, slots: MutableList<Long?>) {
    if (canonicalProbeHistory != null) {
        applyAction(action)
    } else if (slots.isNotEmpty()) {
        val id = slots.removeAt(slots.lastIndex)
        if (id != null) {
            check(undo.lastOrNull()?.id == id)
            applyAction(action)
        } else {
            notice = "Pending action already reverted"
        }
    }
}

private fun EditorEngine.replayPendingEdit(
    action: EditAction,
    slots: MutableList<Long?>,
    settledTarget: CapturedRange,
    aborted: Boolean,
) {
    val executable = if (action is EditAction.TextAttributes && action.target == null) {
        if (aborted) null else action.copy(target = capture())
    } else if (action is EditAction.Format && action.target == null) {
        if (aborted || settledTarget.collapsed) null else action.copy(target = settledTarget)
    } else {
        action
    }
    val id = executable?.let { applyAction(it, queued = true) }
    if (occupiesHistorySlot(action)) slots += id
}

private fun occupiesHistorySlot(action: EditAction) = when (action) {
    is EditAction.Replace,
    is EditAction.Format,
    is EditAction.FormatAttributes,
    is EditAction.TextAttributes,
    is EditAction.Paragraph,
    is EditAction.Structure,
    -> true
    else -> false
}

/** Mark existing raw units without changing accepted content or the logical selection. */
internal fun EditorEngine.markNativeRange(range: Selection) {
    require(range.start >= 0 && range.end <= visible().units.size && !range.collapsed)
    val original = selection
    if (composition == null) {
        val before = saved()
        selection = range
        startComposition(before)
    }
    retargetOverlay(range)
    selection = original
}
