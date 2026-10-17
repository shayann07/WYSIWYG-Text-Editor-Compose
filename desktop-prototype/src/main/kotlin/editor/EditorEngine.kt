package editor

import java.util.ArrayDeque

enum class UnitKind { TEXT, PARAGRAPH_BREAK }
enum class FormatKey { BOLD, ITALIC }
enum class TypingSide { LEFT, RIGHT }
enum class WrapAffinity { DOWNSTREAM, UPSTREAM }
enum class SegmentOrigin { RETAINED, REPLACEMENT }
data class OverlaySegment(val dcStart: Int, val dcEnd: Int, val units: List<TextUnit>, val origin: SegmentOrigin)
data class TextStyleFlags @JvmOverloads constructor(val bold: Boolean = false, val italic: Boolean = false) {
    fun get(key: FormatKey) = if (key == FormatKey.BOLD) bold else italic
    fun patch(key: FormatKey, value: Boolean) = if (key == FormatKey.BOLD) copy(bold = value) else copy(italic = value)
    val bits get() = (if (bold) 1 else 0) or (if (italic) 2 else 0)
    companion object { fun fromBits(bits: Int): TextStyleFlags { require(bits in 0..3); return TextStyleFlags(bits and 1 != 0, bits and 2 != 0) } }
}
data class TextUnit @JvmOverloads constructor(val id: Long, val char: Char, val kind: UnitKind = UnitKind.TEXT, val style: TextStyleFlags = TextStyleFlags(), val paragraphStyle: TextStyleFlags = TextStyleFlags())
data class Document @JvmOverloads constructor(val units: List<TextUnit>, val startStyle: TextStyleFlags = TextStyleFlags()) {
    val text: String get() = units.joinToString("") { it.char.toString() }
    fun same(other: Document) = startStyle == other.startStyle && units.size == other.units.size && units.indices.all {
        units[it].char == other.units[it].char && units[it].kind == other.units[it].kind && units[it].style == other.units[it].style && units[it].paragraphStyle == other.units[it].paragraphStyle
    }
    fun splice(a: Int, b: Int, inserted: List<TextUnit>) = Document(units.take(a) + inserted + units.drop(b), startStyle)
}
data class Selection @JvmOverloads constructor(val anchor: Int, val focus: Int, val primary: Boolean = true, val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM) {
    val start get() = minOf(anchor, focus); val end get() = maxOf(anchor, focus); val collapsed get() = anchor == focus
}
data class EditorFrame(val visible: Document, val committed: Document, val selection: Selection, val marked: IntRange?, val documentRevision: Long, val sessionRevision: Long, val undoCount: Int, val redoCount: Int, val waiting: Boolean, val notice: String, val typingStyle: TextStyleFlags)
private data class Saved(val document: Document, val selection: Selection, val intent: TextStyleFlags?, val side: TypingSide)
private data class History(val id: Long, val before: Saved, var after: Saved, val kind: String, var time: Long)
private data class Composition(
    val before: Saved,
    var segments: List<OverlaySegment>,
    var epochBaseSegments: List<OverlaySegment>,
    val seed: TextStyleFlags,
    val synthesized: MutableMap<Pair<Int, Char>, TextUnit> = HashMap(),
    var undoSawDirty: Boolean = false
)
data class CapturedRange(val ids: List<Long>, val left: Long?, val right: Long?, val collapsed: Boolean, val backwards: Boolean)
sealed interface EditAction {
    data class Replace(val target: CapturedRange, val text: String, val kind: String = "Typing", val paragraphBreaks: Boolean = false, val style: TextStyleFlags = TextStyleFlags(), val deleteSide: TypingSide? = null) : EditAction
    data class Move(val target: CapturedRange, val primary: Boolean = true, val side: TypingSide = TypingSide.LEFT, val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM) : EditAction
    data class Format(val target: CapturedRange?, val key: FormatKey, val value: Boolean) : EditAction
    data object Undo : EditAction
    data object Redo : EditAction
}

/** Plain-text vertical slice: one owner, Dc + provisional replacement, independent history.
 * Array snapshots are intentional for this prototype, NOT the production persistent index. */
class EditorEngine @JvmOverloads constructor(initial: Document = Document(emptyList()), private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val owner = Thread.currentThread()
    private var serial = 0L; private var historySerial = 0L
    private var dc = Document(
        initial.units.map { unit ->
            if (unit.kind == UnitKind.PARAGRAPH_BREAK && unit.style != TextStyleFlags() && unit.paragraphStyle == TextStyleFlags()) {
                unit.copy(id = ++serial, style = TextStyleFlags(), paragraphStyle = unit.style)
            } else {
                unit.copy(id = ++serial)
            }
        },
        initial.startStyle
    )
    private var composition: Composition? = null
    private var selection = Selection(0, 0)
    private var intent: TextStyleFlags? = null; private var side = TypingSide.LEFT
    private val undo = ArrayList<History>(); private val redo = ArrayList<History>()
    private val pending = ArrayList<EditAction>(); private val dispatchQueue = ArrayDeque<() -> Unit>()
    private var busy = false; private var documentRevision = 0L; private var sessionRevision = 0L
    private var typingBarrier = false; private var notice = "Ready"
    var settlementRequestedAt: Long? = null; private set
    var settlementTimedOut = false; private set
    var imeEpoch = 0L; private set
    var onChange: (EditorFrame) -> Unit = {}
    var onSettlementRequested: () -> Unit = {}
    var onEvent: (String) -> Unit = {}
    private fun visible() = composition?.let { c ->
        val sc = c.segments.first().dcStart
        val ec = c.segments.last().dcEnd
        dc.splice(sc, ec, c.segments.flatMap { it.units })
    } ?: dc
    fun snapshot() = EditorFrame(
        visible(),
        dc,
        selection,
        composition?.let { c ->
            val sc = c.segments.first().dcStart
            val len = c.segments.sumOf { it.units.size }
            sc until (sc + len)
        },
        documentRevision,
        sessionRevision,
        undo.size,
        redo.size,
        pending.isNotEmpty(),
        notice,
        effectiveTyping()
    )
    private fun units(text: String, breaks: Boolean = false, style: TextStyleFlags = effectiveTyping()) = text.map {
        val isBreak = breaks && it == '\n'
        TextUnit(
            id = ++serial,
            char = it,
            kind = if (isBreak) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
            style = if (isBreak) TextStyleFlags() else style,
            paragraphStyle = if (isBreak) style else TextStyleFlags()
        )
    }
    private fun saved() = Saved(visible(), selection, intent, side)
    private fun inherit(at: Int, from: TypingSide): TextStyleFlags {
        val u = visible().units
        val left = u.getOrNull(at - 1)?.takeIf { it.kind == UnitKind.TEXT }?.style
        val right = u.getOrNull(at)?.takeIf { it.kind == UnitKind.TEXT }?.style
        return (if (from == TypingSide.LEFT) left ?: right else right ?: left)
            ?: u.getOrNull(at - 1)?.takeIf { it.kind == UnitKind.PARAGRAPH_BREAK }?.paragraphStyle
            ?: visible().startStyle
    }
    private fun effectiveTyping() = intent ?: inherit(selection.focus, side)
    private fun replacementStyle(range: Selection): TextStyleFlags = if (range.collapsed) effectiveTyping() else visible().units.subList(range.start, range.end).firstOrNull { it.kind == UnitKind.TEXT }?.style ?: effectiveTyping()
    fun formatState(key: FormatKey): Boolean? {
        if (selection.collapsed) return effectiveTyping().get(key)
        val values = displayedStyles(visible()).filter { it.start < selection.end && it.end > selection.start }.map { it.style.get(key) }.distinct()
        return values.singleOrNull()
    }
    fun toggleFormat(key: FormatKey) {
        val value = formatState(key) != true
        perform(EditAction.Format(if (composition != null && selection.collapsed) null else capture(), key, value))
    }
    private fun dispatch(label: String, block: () -> Unit) {
        check(Thread.currentThread() === owner) { "Editing must run on the owner thread" }
        dispatchQueue.addLast {
            val before = visible()
            val savedDc = dc; val savedSelection = selection
            val savedIntent = intent; val savedSide = side
            val savedComposition = composition?.let { it.copy(synthesized = HashMap(it.synthesized)) }
            val savedUndo = undo.map { it.copy() }; val savedRedo = redo.map { it.copy() }; val savedPending = pending.toList(); val barrier = typingBarrier
            val savedSettlementRequestedAt = settlementRequestedAt; val savedSettlementTimedOut = settlementTimedOut
            try {
                block()
                check(selection.anchor in 0..visible().units.size && selection.focus in 0..visible().units.size)
                check(visible().units.map { it.id }.distinct().size == visible().units.size)
            } catch (e: Exception) {
                dc = savedDc; selection = savedSelection; composition = savedComposition; intent = savedIntent; side = savedSide
                undo.clear(); undo.addAll(savedUndo); redo.clear(); redo.addAll(savedRedo); pending.clear(); pending.addAll(savedPending); typingBarrier = barrier
                settlementRequestedAt = savedSettlementRequestedAt; settlementTimedOut = savedSettlementTimedOut
                notice = "Edit rejected: ${e.message}"
                runCatching { onEvent("Rejected $label: ${e.message}") }; runCatching { onChange(snapshot()) }
                return@addLast
            }
            if (!before.same(visible())) documentRevision++
            sessionRevision++
            runCatching { onEvent(label) }; runCatching { onChange(snapshot()) }.onFailure { runCatching { onEvent("Observer failure: ${it.message}") } }
        }
        if (busy) return
        busy = true
        try { while (dispatchQueue.isNotEmpty()) dispatchQueue.removeFirst().invoke() } finally { busy = false }
    }
    fun capture(range: Selection = selection): CapturedRange {
        val u = visible().units; require(range.start >= 0 && range.end <= u.size)
        return CapturedRange(u.subList(range.start, range.end).map { it.id }, u.getOrNull(range.start - 1)?.id, u.getOrNull(range.end)?.id, range.collapsed, range.anchor > range.focus)
    }
    private fun resolve(t: CapturedRange): Selection? {
        val u = visible().units; val rank = u.withIndex().associate { it.value.id to it.index }
        if (t.collapsed) {
            val left = if (t.left == null) -1 else rank[t.left]; val right = if (t.right == null) u.size else rank[t.right]
            if (left == null && right == null) return null
            val p = right ?: (left!! + 1); return Selection(p, p)
        }
        val positions = t.ids.map { rank[it] ?: return null }
        if (positions.zipWithNext().any { it.second != it.first + 1 }) return null
        return if (t.backwards) Selection(positions.last() + 1, positions.first()) else Selection(positions.first(), positions.last() + 1)
    }
    private fun push(before: Saved, kind: String, coalesce: Boolean): Long? {
        val after = saved(); if (before.document.same(after.document)) return null
        val previous = undo.lastOrNull(); val time = now()
        if (coalesce && !typingBarrier && kind == "Typing" && previous?.kind == kind && time - previous.time in 0..750 && before.selection.collapsed && before.selection.focus == previous.after.selection.focus && before.document.same(previous.after.document)) {
            previous.after = after; previous.time = time; redo.clear(); return previous.id
        }
        val h = History(++historySerial, before, after, kind, time); undo += h; redo.clear()
        if (undo.size > 1000) { undo.removeAt(0); notice = "Oldest undo step released" }
        typingBarrier = false; return h.id
    }
    private fun apply(action: EditAction, queued: Boolean = false): Long? {
        when (action) {
            is EditAction.Replace -> {
                val target = resolve(action.target) ?: run { notice = "The target changed. Please try again."; return null }
                val raw = dc.text
                val a = Graphemes.snap(raw, target.start, after = target.collapsed)
                val b = if (target.collapsed) a else Graphemes.snap(raw, target.end)
                if (a == b && action.text.isEmpty()) return null
                val before = saved(); val inserted = units(action.text, action.paragraphBreaks, action.style)
                dc = dc.splice(a, b, inserted); selection = Selection(a + inserted.size, a + inserted.size)
                intent = action.style
                if (inserted.isEmpty()) {
                    val at = selection.focus
                    val empty = (at == 0 || dc.units[at - 1].kind == UnitKind.PARAGRAPH_BREAK) && (at == dc.units.size || dc.units[at].kind == UnitKind.PARAGRAPH_BREAK)
                    if (empty) {
                        dc = if (at == 0) dc.copy(startStyle = action.style)
                             else dc.copy(units = dc.units.mapIndexed { i, unit ->
                                 if (i == at - 1) unit.copy(style = TextStyleFlags(), paragraphStyle = action.style) else unit
                             })
                    } else { intent = null; side = action.deleteSide ?: TypingSide.LEFT }
                }
                notice = "Ready"; return push(before, action.kind, !queued && action.kind == "Typing")
            }
            is EditAction.Move -> {
                resolve(action.target)?.let {
                    selection = it.copy(primary = action.primary, wrapAffinity = action.wrapAffinity)
                    intent = null; side = action.side
                } ?: run { notice = "Text changed. Select the position again." }
                typingBarrier = true
            }
            is EditAction.Format -> {
                val target = action.target?.let(::resolve) ?: run { notice = "Formatting target changed. Select it again."; return null }
                typingBarrier = true
                if (target.collapsed) { intent = effectiveTyping().patch(action.key, action.value); notice = "Ready"; return null }
                val before = saved()
                val intervals = displayedStyles(dc).filter { it.start < target.end && it.end > target.start }
                val patched = BooleanArray(dc.units.size)
                intervals.forEach { for (i in it.start until it.end) patched[i] = true }
                dc = dc.copy(units = dc.units.mapIndexed { i, unit -> if (patched[i]) unit.copy(style = unit.style.patch(action.key, action.value)) else unit })
                intent = null; notice = "Ready"
                return push(before, "Format", false).also { typingBarrier = true }
            }
            EditAction.Undo -> { val h = undo.removeLastOrNull() ?: return null; dc = h.before.document; selection = h.before.selection; intent = h.before.intent; side = h.before.side; redo += h; typingBarrier = true; notice = "Undone" }
            EditAction.Redo -> { val h = redo.removeLastOrNull() ?: return null; dc = h.after.document; selection = h.after.selection; intent = h.after.intent; side = h.after.side; undo += h; typingBarrier = true; notice = "Redone" }
        }
        return null
    }
    fun perform(action: EditAction) {
        dispatch(action.javaClass.simpleName) {
            if (composition != null) {
                if (action == EditAction.Redo) { notice = "Finish the current composition before Redo."; return@dispatch }
                if (pending.size >= 256) { notice = "Finish composing before another command."; return@dispatch }
                if (action == EditAction.Undo && (!composition!!.before.document.same(visible()) || !composition!!.before.document.same(dc))) composition!!.undoSawDirty = true
                if (action is EditAction.Move && pending.lastOrNull() is EditAction.Move) pending[pending.lastIndex] = action else pending += action
                notice = "Waiting for the input method to finish…"
                settlementTimedOut = false
                if (settlementRequestedAt == null) settlementRequestedAt = now()
            } else apply(action)
        }
        if (composition != null && pending.isNotEmpty()) onSettlementRequested()
    }
    fun checkSettlementWatchdog(timeoutMs: Long = 2_000L): Boolean {
        if (composition == null || pending.isEmpty()) {
            settlementRequestedAt = null
            return false
        }
        val req = settlementRequestedAt ?: return false
        if ((now() - req) < timeoutMs) return false
        dispatch("Settlement watchdog timeout") {
            settlementTimedOut = true
            settlementRequestedAt = null
            pending.clear()
            notice = "Input method did not respond; pending commands cancelled. Press Escape or abort composition to recover."
        }
        return true
    }
    fun cancelPendingOnTeardown() {
        if (pending.isEmpty() && settlementRequestedAt == null && !settlementTimedOut) return
        dispatch("Surface teardown") {
            pending.clear()
            settlementRequestedAt = null
            settlementTimedOut = false
        }
    }
    fun replace(text: String, kind: String = "Typing", paragraphBreaks: Boolean = false, range: Selection = selection, deleteSide: TypingSide? = null) = perform(EditAction.Replace(capture(range), text, kind, paragraphBreaks, replacementStyle(range), deleteSide))
    fun move(range: Selection, primary: Boolean = range.primary, side: TypingSide = TypingSide.LEFT, wrapAffinity: WrapAffinity = range.wrapAffinity) = perform(EditAction.Move(capture(range), primary, side, wrapAffinity))
    fun selectAll() = move(Selection(0, visible().units.size))
    fun delete(backward: Boolean) {
        val s = snapshot(); if (!s.selection.collapsed) { replace("", "Delete", deleteSide = if (backward) TypingSide.LEFT else TypingSide.RIGHT); return }
        val at = s.selection.focus; val stops = Graphemes.stops(s.visible.text)
        val range = if (backward) Selection(stops.lastOrNull { it < at } ?: at, at) else Selection(at, stops.firstOrNull { it > at } ?: at)
        replace("", "Delete", range = range, deleteSide = if (backward) TypingSide.LEFT else TypingSide.RIGHT)
    }
    /** One AWT INPUT_METHOD_TEXT_CHANGED event: accepted prefix + complete replacement preedit. */
    fun inputMethod(text: String, committedCount: Int, caret: Int?) {
        require(committedCount in 0..text.length)
        require(caret == null || caret in 0..text.length - committedCount)
        if (composition == null && text.isEmpty() && committedCount == 0 && selection.collapsed) return
        dispatch("IME text: committed=$committedCount composed=${text.length - committedCount}") {
            val existing = composition != null
            val session = composition ?: run {
                val initialSegments = if (selection.start == selection.end) {
                    listOf(OverlaySegment(selection.start, selection.start, emptyList(), SegmentOrigin.REPLACEMENT))
                } else {
                    (selection.start until selection.end).map { i ->
                        OverlaySegment(i, i + 1, listOf(dc.units[i]), SegmentOrigin.RETAINED)
                    }
                }
                Composition(
                    before = saved(),
                    segments = initialSegments,
                    epochBaseSegments = initialSegments,
                    seed = replacementStyle(selection)
                ).also { composition = it }
            }

            val baseSegs = session.epochBaseSegments
            val B = baseSegs.flatMap { it.units }

            var p = 0
            while (p < text.length && p < B.size && text[p] == B[p].char) p++

            var s = 0
            while (s < text.length - p && s < B.size - p && text[text.length - 1 - s] == B[B.size - 1 - s].char) s++

            val newSegments: List<OverlaySegment>
            if (p == B.size && s == 0 && text.length == B.size) {
                newSegments = baseSegs
            } else {
                val midText = text.substring(p, text.length - s)
                val midUnits = midText.mapIndexed { idx, ch ->
                    session.synthesized.getOrPut((p + idx) to ch) {
                        TextUnit(++serial, ch, UnitKind.TEXT, session.seed)
                    }
                }
                val aMid = p
                val bMid = B.size - s

                val segList = ArrayList<OverlaySegment>()
                var firstTouched = -1
                var lastTouched = -1
                var leftUnits = emptyList<TextUnit>()
                var rightUnits = emptyList<TextUnit>()

                if (aMid == bMid) {
                    var curVis = 0
                    for (i in baseSegs.indices) {
                        val seg = baseSegs[i]
                        val segStart = curVis
                        val segEnd = curVis + seg.units.size
                        curVis = segEnd
                        if (segStart < aMid && aMid < segEnd) {
                            firstTouched = i
                            lastTouched = i
                            leftUnits = seg.units.subList(0, aMid - segStart)
                            rightUnits = seg.units.subList(aMid - segStart, seg.units.size)
                            break
                        }
                    }
                } else {
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
                }

                if (firstTouched == -1) {
                    var insIdx = 0
                    var curVis = 0
                    while (insIdx < baseSegs.size && curVis + baseSegs[insIdx].units.size <= aMid) {
                        curVis += baseSegs[insIdx].units.size
                        insIdx++
                    }
                    for (i in 0 until insIdx) segList.add(baseSegs[i])
                    val dcPos = if (insIdx < baseSegs.size) baseSegs[insIdx].dcStart
                                else if (baseSegs.isNotEmpty()) baseSegs.last().dcEnd
                                else 0
                    segList.add(OverlaySegment(dcPos, dcPos, midUnits, SegmentOrigin.REPLACEMENT))
                    for (i in insIdx until baseSegs.size) segList.add(baseSegs[i])
                } else {
                    for (i in 0 until firstTouched) segList.add(baseSegs[i])

                    val dcStartMid = if (leftUnits.isNotEmpty()) baseSegs[firstTouched].dcEnd else baseSegs[firstTouched].dcStart
                    val dcEndMid = baseSegs[lastTouched].dcEnd

                    if (leftUnits.isNotEmpty()) {
                        segList.add(
                            OverlaySegment(
                                baseSegs[firstTouched].dcStart,
                                baseSegs[firstTouched].dcEnd,
                                leftUnits,
                                baseSegs[firstTouched].origin
                            )
                        )
                    }

                    if (midUnits.isNotEmpty() || dcStartMid < dcEndMid) {
                        segList.add(OverlaySegment(dcStartMid, dcEndMid, midUnits, SegmentOrigin.REPLACEMENT))
                    }

                    if (rightUnits.isNotEmpty()) {
                        segList.add(
                            OverlaySegment(
                                baseSegs[lastTouched].dcEnd,
                                baseSegs[lastTouched].dcEnd,
                                rightUnits,
                                baseSegs[lastTouched].origin
                            )
                        )
                    }

                    for (i in (lastTouched + 1) until baseSegs.size) segList.add(baseSegs[i])
                }

                if (segList.isEmpty()) {
                    val fallbackDc = if (firstTouched in baseSegs.indices) baseSegs[firstTouched].dcStart
                                     else if (baseSegs.isNotEmpty()) baseSegs[0].dcStart
                                     else 0
                    segList.add(OverlaySegment(fallbackDc, fallbackDc, emptyList(), SegmentOrigin.REPLACEMENT))
                }
                newSegments = segList
            }

            if (committedCount > 0) {
                val pCut = committedCount
                val segs = newSegments
                val sc = segs.first().dcStart
                var curVis = 0
                val consumedSegments = ArrayList<OverlaySegment>()
                val remainingSegments = ArrayList<OverlaySegment>()
                data class SplitTail(val units: List<TextUnit>, val origDcEnd: Int, val origin: SegmentOrigin)
                var splitTail: SplitTail? = null
                var dCut = sc
                val totalOverlayUnits = segs.sumOf { it.units.size }

                for (seg in segs) {
                    val segLen = seg.units.size
                    val segStart = curVis
                    val segEnd = curVis + segLen
                    curVis = segEnd

                    if (segLen == 0) {
                        val hasRemainingUnitsAfter = pCut < totalOverlayUnits
                        if (segStart < pCut || (segStart == pCut && committedCount > 0 && !hasRemainingUnitsAfter)) {
                            consumedSegments.add(seg)
                            dCut = seg.dcEnd
                        } else {
                            remainingSegments.add(seg)
                        }
                        continue
                    }

                    if (segEnd <= pCut) {
                        consumedSegments.add(seg)
                        dCut = seg.dcEnd
                    } else if (segStart < pCut) {
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

                val pCommit = consumedSegments.flatMap { it.units }
                dc = dc.splice(sc, dCut, pCommit)
                val delta = pCommit.size - (dCut - sc)
                val newSc = sc + pCommit.size

                val finalRemSegments = ArrayList<OverlaySegment>()
                if (splitTail != null && splitTail.units.isNotEmpty()) {
                    finalRemSegments.add(
                        OverlaySegment(
                            dcStart = newSc,
                            dcEnd = splitTail.origDcEnd + delta,
                            units = splitTail.units,
                            origin = if (splitTail.origin == SegmentOrigin.RETAINED) SegmentOrigin.RETAINED else SegmentOrigin.REPLACEMENT
                        )
                    )
                }
                for (seg in remainingSegments) {
                    finalRemSegments.add(
                        OverlaySegment(
                            dcStart = seg.dcStart + delta,
                            dcEnd = seg.dcEnd + delta,
                            units = seg.units,
                            origin = seg.origin
                        )
                    )
                }
                if (finalRemSegments.isEmpty()) {
                    finalRemSegments.add(OverlaySegment(newSc, newSc, emptyList(), SegmentOrigin.REPLACEMENT))
                }
                session.segments = finalRemSegments
                session.epochBaseSegments = finalRemSegments
                session.synthesized.clear()
            } else {
                session.segments = newSegments
            }

            val sc = session.segments.first().dcStart
            val remUnits = session.segments.sumOf { it.units.size }
            selection = Selection(sc + (caret ?: remUnits), sc + (caret ?: remUnits))

            if (committedCount == text.length) {
                val ec = session.segments.last().dcEnd
                if (ec > sc) {
                    dc = dc.splice(sc, ec, emptyList())
                }
                selection = Selection(sc, sc)
                finish(session, if (existing || text.length != committedCount) "Composition" else "Typing")
            } else if (pending.isEmpty()) {
                settlementRequestedAt = null
                if (!settlementTimedOut) notice = "Composing…"
            } else {
                if (settlementRequestedAt == null) settlementRequestedAt = now()
                notice = "Waiting for the input method to finish…"
            }
        }
    }
    fun inputMethodCaret(offset: Int) {
        val s = composition ?: return
        val remLen = s.segments.sumOf { it.units.size }
        if (offset !in 0..remLen) return
        dispatch("IME caret") {
            composition?.let {
                val sc = it.segments.first().dcStart
                selection = Selection(sc + offset, sc + offset)
            }
        }
    }
    private fun finish(session: Composition, kind: String, aborted: Boolean = false) {
        composition = null
        settlementRequestedAt = null
        settlementTimedOut = false
        imeEpoch++
        intent = if (aborted && session.before.document.same(dc)) session.before.intent else session.seed
        if (!aborted && !session.before.document.same(dc)) {
            val at = selection.focus
            val empty = (at == 0 || dc.units[at - 1].kind == UnitKind.PARAGRAPH_BREAK) && (at == dc.units.size || dc.units[at].kind == UnitKind.PARAGRAPH_BREAK)
            if (empty) {
                dc = if (at == 0) dc.copy(startStyle = session.seed)
                     else dc.copy(units = dc.units.mapIndexed { i, unit ->
                         if (i == at - 1) unit.copy(style = TextStyleFlags(), paragraphStyle = session.seed) else unit
                     })
            }
        }
        val group = push(session.before, kind, kind == "Typing" && pending.isEmpty())
        val sc = session.segments.first().dcStart
        val settledTarget = capture(Selection(session.before.selection.start, sc))
        val slots = undo.map { it.id as Long? }.toMutableList()
        if (group == null && session.undoSawDirty) slots += null
        for (action in pending.toList()) {
            if (action == EditAction.Undo) {
                if (slots.isNotEmpty()) {
                    val id = slots.removeAt(slots.lastIndex)
                    if (id != null) {
                        check(undo.lastOrNull()?.id == id)
                        apply(action)
                    } else notice = "Pending action already reverted"
                }
            } else {
                val executable = if (action is EditAction.Format && action.target == null) {
                    if (aborted || settledTarget.collapsed) null else action.copy(target = settledTarget)
                } else action
                val id = executable?.let { apply(it, queued = true) }
                if (action is EditAction.Replace || action is EditAction.Format) slots += id
            }
        }
        pending.clear()
        if (notice.startsWith("Composing") || notice.startsWith("Waiting")) notice = "Ready"
    }
    /** Explicit application abort, exercised by kernel tests; not guessed from AWT unmark. */
    fun abortComposition() {
        dispatch("Application abort") {
            val s = composition
            if (s != null) {
                selection = if (s.before.document.same(dc)) s.before.selection
                            else Selection(s.segments.first().dcStart, s.segments.first().dcStart)
                finish(s, "Composition", aborted = true)
            } else {
                settlementRequestedAt = null
                settlementTimedOut = false
                pending.clear()
                if (notice.startsWith("Composing") || notice.startsWith("Waiting")) notice = "Ready"
            }
        }
    }
    fun surroundingCommittedText(): String {
        val frame = snapshot()
        val marked = frame.marked ?: return frame.visible.text
        return frame.visible.text.removeRange(marked.first, marked.last + 1)
    }
}
