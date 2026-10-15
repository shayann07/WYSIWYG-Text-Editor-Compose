package editor

import java.util.ArrayDeque

enum class UnitKind { TEXT, PARAGRAPH_BREAK }
enum class FormatKey { BOLD, ITALIC }
enum class TypingSide { LEFT, RIGHT }
data class TextStyleFlags(val bold: Boolean = false, val italic: Boolean = false) {
    fun get(key: FormatKey) = if (key == FormatKey.BOLD) bold else italic
    fun patch(key: FormatKey, value: Boolean) = if (key == FormatKey.BOLD) copy(bold = value) else copy(italic = value)
    val bits get() = (if (bold) 1 else 0) or (if (italic) 2 else 0)
    companion object { fun fromBits(bits: Int): TextStyleFlags { require(bits in 0..3); return TextStyleFlags(bits and 1 != 0, bits and 2 != 0) } }
}
data class TextUnit(val id: Long, val char: Char, val kind: UnitKind = UnitKind.TEXT, val style: TextStyleFlags = TextStyleFlags())
data class Document(val units: List<TextUnit>, val startStyle: TextStyleFlags = TextStyleFlags()) {
    val text: String get() = units.joinToString("") { it.char.toString() }
    fun same(other: Document) = startStyle == other.startStyle && units.size == other.units.size && units.indices.all { units[it].char == other.units[it].char && units[it].kind == other.units[it].kind && units[it].style == other.units[it].style }
    fun splice(a: Int, b: Int, inserted: List<TextUnit>) = Document(units.take(a) + inserted + units.drop(b), startStyle)
}
data class Selection(val anchor: Int, val focus: Int, val primary: Boolean = true) {
    val start get() = minOf(anchor, focus); val end get() = maxOf(anchor, focus); val collapsed get() = anchor == focus
}
data class EditorFrame(val visible: Document, val committed: Document, val selection: Selection, val marked: IntRange?, val documentRevision: Long, val sessionRevision: Long, val undoCount: Int, val redoCount: Int, val waiting: Boolean, val notice: String, val typingStyle: TextStyleFlags)
private data class Saved(val document: Document, val selection: Selection, val intent: TextStyleFlags?, val side: TypingSide)
private data class History(val id: Long, val before: Saved, var after: Saved, val kind: String, var time: Long)
private data class Composition(val before: Saved, var start: Int, var end: Int, var candidate: List<TextUnit>, var baseline: List<TextUnit>, val seed: TextStyleFlags, val synthesized: MutableMap<Pair<Int, Char>, TextUnit> = HashMap(), var undoSawDirty: Boolean = false)
data class CapturedRange(val ids: List<Long>, val left: Long?, val right: Long?, val collapsed: Boolean, val backwards: Boolean)
sealed interface EditAction {
    data class Replace(val target: CapturedRange, val text: String, val kind: String = "Typing", val paragraphBreaks: Boolean = false, val style: TextStyleFlags = TextStyleFlags(), val deleteSide: TypingSide? = null) : EditAction
    data class Move(val target: CapturedRange, val primary: Boolean = true, val side: TypingSide = TypingSide.LEFT) : EditAction
    data class Format(val target: CapturedRange?, val key: FormatKey, val value: Boolean) : EditAction
    data object Undo : EditAction
    data object Redo : EditAction
}

/** Plain-text vertical slice: one owner, Dc + provisional replacement, independent history.
 * Array snapshots are intentional for this prototype, NOT the production persistent index. */
class EditorEngine(initial: Document = Document(emptyList()), private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val owner = Thread.currentThread()
    private var serial = 0L; private var historySerial = 0L
    private var dc = Document(initial.units.map { it.copy(id = ++serial) }, initial.startStyle); private var composition: Composition? = null
    private var selection = Selection(0, 0)
    private var intent: TextStyleFlags? = null; private var side = TypingSide.LEFT
    private val undo = ArrayList<History>(); private val redo = ArrayList<History>()
    private val pending = ArrayList<EditAction>(); private val dispatchQueue = ArrayDeque<() -> Unit>()
    private var busy = false; private var documentRevision = 0L; private var sessionRevision = 0L
    private var typingBarrier = false; private var notice = "Ready"
    var onChange: (EditorFrame) -> Unit = {}
    var onSettlementRequested: () -> Unit = {}
    var onEvent: (String) -> Unit = {}
    private fun visible() = composition?.let { dc.splice(it.start, it.end, it.candidate) } ?: dc
    fun snapshot() = EditorFrame(visible(), dc, selection, composition?.let { it.start until it.start + it.candidate.size }, documentRevision, sessionRevision, undo.size, redo.size, pending.isNotEmpty(), notice, effectiveTyping())
    private fun units(text: String, breaks: Boolean = false, style: TextStyleFlags = effectiveTyping()) = text.map { TextUnit(++serial, it, if (breaks && it == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT, style) }
    private fun saved() = Saved(visible(), selection, intent, side)
    private fun inherit(at: Int, from: TypingSide): TextStyleFlags {
        val u = visible().units
        val left = u.getOrNull(at - 1)?.takeIf { it.kind == UnitKind.TEXT }?.style
        val right = u.getOrNull(at)?.takeIf { it.kind == UnitKind.TEXT }?.style
        return (if (from == TypingSide.LEFT) left ?: right else right ?: left)
            ?: u.getOrNull(at - 1)?.style ?: visible().startStyle
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
            try {
                block()
                check(selection.anchor in 0..visible().units.size && selection.focus in 0..visible().units.size)
                check(visible().units.map { it.id }.distinct().size == visible().units.size)
            } catch (e: Exception) {
                dc = savedDc; selection = savedSelection; composition = savedComposition; intent = savedIntent; side = savedSide
                undo.clear(); undo.addAll(savedUndo); redo.clear(); redo.addAll(savedRedo); pending.clear(); pending.addAll(savedPending); typingBarrier = barrier
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
                    if (empty) dc = if (at == 0) dc.copy(startStyle = action.style) else dc.copy(units = dc.units.mapIndexed { i, unit -> if (i == at - 1) unit.copy(style = action.style) else unit })
                    else { intent = null; side = action.deleteSide ?: TypingSide.LEFT }
                }
                notice = "Ready"; return push(before, action.kind, !queued && action.kind == "Typing")
            }
            is EditAction.Move -> { resolve(action.target)?.let { selection = it.copy(primary = action.primary); intent = null; side = action.side } ?: run { notice = "Text changed. Select the position again." }; typingBarrier = true }
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
            } else apply(action)
        }
        if (composition != null && pending.isNotEmpty()) onSettlementRequested()
    }
    fun replace(text: String, kind: String = "Typing", paragraphBreaks: Boolean = false, range: Selection = selection, deleteSide: TypingSide? = null) = perform(EditAction.Replace(capture(range), text, kind, paragraphBreaks, replacementStyle(range), deleteSide))
    fun move(range: Selection, primary: Boolean = true, side: TypingSide = TypingSide.LEFT) = perform(EditAction.Move(capture(range), primary, side))
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
        dispatch("IME text: committed=$committedCount composed=${text.length - committedCount}") {
            val existing = composition != null
            val session = composition ?: Composition(saved(), selection.start, selection.end, emptyList(), dc.units.subList(selection.start, selection.end).toList(), replacementStyle(selection)).also { composition = it }
            // Reconcile against an immutable attribution epoch, not the preceding candidate.
            val baseline = session.baseline
            var prefix = 0; while (prefix < minOf(baseline.size, text.length) && baseline[prefix].char == text[prefix]) prefix++
            var suffix = 0; while (suffix < minOf(baseline.size, text.length) - prefix && baseline[baseline.lastIndex - suffix].char == text[text.lastIndex - suffix]) suffix++
            val replacement = text.mapIndexed { i, char -> when {
                i < prefix -> baseline[i]
                i >= text.length - suffix -> baseline[baseline.size - (text.length - i)]
                else -> session.synthesized.getOrPut(i to char) { TextUnit(++serial, char, style = session.seed) }
            } }
            if (committedCount > 0) {
                dc = dc.splice(session.start, session.end, replacement.take(committedCount))
                session.start += committedCount; session.end = session.start
                session.baseline = replacement.drop(committedCount); session.synthesized.clear()
            }
            session.candidate = replacement.drop(committedCount)
            selection = Selection(session.start + (caret ?: session.candidate.size), session.start + (caret ?: session.candidate.size))
            if (session.candidate.isEmpty()) {
                // An explicit native removal accepts deletion of the previous replacement footprint.
                dc = dc.splice(session.start, session.end, emptyList())
                selection = Selection(session.start, session.start)
                finish(session, if (existing || text.length != committedCount) "Composition" else "Typing")
            } else notice = if (pending.isEmpty()) "Composing…" else "Waiting for the input method to finish…"
        }
    }
    fun inputMethodCaret(offset: Int) {
        val s = composition ?: return; if (offset !in 0..s.candidate.size) return
        dispatch("IME caret") { composition?.let { selection = Selection(it.start + offset, it.start + offset) } }
    }
    private fun finish(session: Composition, kind: String, aborted: Boolean = false) {
        composition = null
        intent = if (aborted && session.before.document.same(dc)) session.before.intent else session.seed
        if (!aborted && !session.before.document.same(dc)) {
            val at = selection.focus
            val empty = (at == 0 || dc.units[at - 1].kind == UnitKind.PARAGRAPH_BREAK) && (at == dc.units.size || dc.units[at].kind == UnitKind.PARAGRAPH_BREAK)
            if (empty) dc = if (at == 0) dc.copy(startStyle = session.seed) else dc.copy(units = dc.units.mapIndexed { i, unit -> if (i == at - 1) unit.copy(style = session.seed) else unit })
        }
        val group = push(session.before, kind, kind == "Typing" && pending.isEmpty())
        // Capture before draining: a preceding queued Undo may remove these identities.
        val settledTarget = capture(Selection(session.before.selection.start, session.start))
        val slots = undo.map { it.id as Long? }.toMutableList()
        if (group == null && session.undoSawDirty) slots += null
        for (action in pending.toList()) {
            if (action == EditAction.Undo) {
                if (slots.isNotEmpty()) { val id = slots.removeAt(slots.lastIndex); if (id != null) { check(undo.lastOrNull()?.id == id); apply(action) } else notice = "Pending action already reverted" }
            } else {
                val executable = if (action is EditAction.Format && action.target == null) {
                    if (aborted || settledTarget.collapsed) null else action.copy(target = settledTarget)
                } else action
                val id = executable?.let { apply(it, queued = true) }
                if (action is EditAction.Replace || action is EditAction.Format) slots += id
            }
        }
        pending.clear(); if (notice.startsWith("Composing") || notice.startsWith("Waiting")) notice = "Ready"
    }
    /** Explicit application abort, exercised by kernel tests; not guessed from AWT unmark. */
    fun abortComposition() { dispatch("Application abort") { composition?.let { s -> selection = if (s.before.document.same(dc)) s.before.selection else Selection(s.start, s.start); finish(s, "Composition", aborted = true) } } }
    fun surroundingCommittedText(): String { val frame = snapshot(); val marked = frame.marked ?: return frame.visible.text; return frame.visible.text.removeRange(marked.first, marked.last + 1) }
}
