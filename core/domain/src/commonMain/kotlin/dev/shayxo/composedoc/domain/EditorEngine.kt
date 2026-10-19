package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.*
import kotlin.jvm.JvmOverloads
data class EditorFrame(val visible: Document, val committed: Document, val selection: Selection, val marked: IntRange?, val documentRevision: Long, val sessionRevision: Long, val undoCount: Int, val redoCount: Int, val waiting: Boolean, val notice: String, val typingStyle: TextStyleFlags, val identityGeneration: Long, val recoveryBasisRevision: Long)

/** Immutable local recovery payload; never a production serialization or collaborative inverse. */
data class RecoveredDraft constructor(
    val basis: Document,
    val document: Document,
    val selection: Selection,
    val basisRevision: Long,
    val basisIdentityGeneration: Long,
    val ownerScope: Any,
)
enum class RecoveryApplyResult { Applied, RecoveryBaseChanged, RecoveryBusy, RecoveryRejected, NoChange }
sealed interface CanonicalProbeEdit {
    data class Replace(val start: Int, val end: Int, val text: String, val style: TextStyleFlags, val paragraphBreaks: Boolean = false) : CanonicalProbeEdit
    data class Attributes(val start: Int, val end: Int, val style: TextStyleFlags) : CanonicalProbeEdit

    /** Destination is measured after removing [start,end); retained units keep their identities. */
    data class Move(val start: Int, val end: Int, val destination: Int) : CanonicalProbeEdit
}
enum class CanonicalProbeOutcome { Applied, DeferredComposition, DeferredOwnerBusy, RejectedBasis, RequiresExternalHistory, Rejected }
data class CanonicalProbeBasis(val documentRevision: Long, val unitIds: List<Long>, val identityGeneration: Long)
private data class Saved(val document: Document, val selection: Selection, val intent: TextStyleFlags?, val side: TypingSide)
private data class History(val id: Long, val before: Saved, var after: Saved, val kind: String, var time: Long)
private data class Composition(
    val before: Saved,
    var segments: List<OverlaySegment>,
    var epochBaseSegments: List<OverlaySegment>,
    var seed: TextStyleFlags,
    val synthesized: MutableMap<Pair<Int, Char>, TextUnit> = HashMap(),
    var undoSawDirty: Boolean = false,
    var markedStart: Int = 0,
    var markedEnd: Int? = null,
)
data class CapturedRange(val ids: List<Long>, val left: Long?, val right: Long?, val collapsed: Boolean, val backwards: Boolean)
sealed interface EditAction {
    data class Replace @JvmOverloads constructor(val target: CapturedRange, val text: String, val kind: String = "Typing", val paragraphBreaks: Boolean = false, val style: TextStyleFlags = TextStyleFlags(), val deleteSide: TypingSide? = null, val exactUtf16: Boolean = false) : EditAction
    data class Move(val target: CapturedRange, val primary: Boolean = true, val side: TypingSide = TypingSide.LEFT, val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM) : EditAction
    data class Format(val target: CapturedRange?, val key: FormatKey, val value: Boolean) : EditAction
    data class FormatAttributes(val target: CapturedRange, val bold: Boolean?, val italic: Boolean?) : EditAction
    data class TextAttributes(val target: CapturedRange?, val patch: TextStylePatch) : EditAction
    data class Paragraph(val anchors: List<Long?>, val direction: ParagraphDirection?, val alignment: ParagraphAlignment?, val before: Int? = null, val after: Int? = null, val line: Int? = null) : EditAction
    data class Structure(val target: CapturedRange, val anchors: List<Long?>, val command: StructureCommand) : EditAction
    data class OutlineMove(val anchor: Long?) : EditAction
    data object Undo : EditAction
    data object Redo : EditAction
}

/** Plain-text vertical slice: one owner, Dc + provisional replacement, independent history.
 * Array snapshots are intentional for this prototype, NOT the production persistent index. */
class EditorEngine @JvmOverloads constructor(initial: Document = Document(emptyList()), private val now: () -> Long = { monotonicMillis() }) {
    private val owner = ownerThreadToken()
    private val recoveryOwnerScope = Any() // Runtime owner only; not a durable DocumentId or authentication credential.
    private var serial = 0L
    private var historySerial = 0L
    private var dc = Document(
        initial.units.map { unit ->
            if (unit.kind == UnitKind.PARAGRAPH_BREAK && unit.style != TextStyleFlags() && unit.paragraphStyle == TextStyleFlags()) {
                unit.copy(id = ++serial, style = TextStyleFlags(), paragraphStyle = unit.style, followingBlockId = Long.MIN_VALUE)
            } else {
                unit.copy(id = ++serial, followingBlockId = Long.MIN_VALUE)
            }
        },
        initial.startStyle,
        initial.startParagraph,
        initial.resources,
        pageSettings = initial.pageSettings,
        running = initial.running.fresh(),
    )
    private var composition: Composition? = null
    private var selection = Selection(0, 0)
    private var intent: TextStyleFlags? = null
    private var side = TypingSide.LEFT
    private val undo = ArrayList<History>()
    private val redo = ArrayList<History>()
    private val pending = ArrayList<EditAction>()
    private val dispatchQueue = ArrayDeque<() -> Unit>()
    private var busy = false
    private var documentRevision = 0L
    private var sessionRevision = 0L
    private var identityGeneration = 0L
    private var recoveryBasisRevision = 0L // Exact committed Dc changes, excluding provisional-only publications.
    private var typingBarrier = false
    private var notice = "Ready"
    var settlementRequestedAt: Long? = null
        private set
    var settlementTimedOut = false
        private set
    var imeEpoch = 0L
        private set
    var onChange: (EditorFrame) -> Unit = {}
    var onSettlementRequested: () -> Unit = {}
    var onEvent: (String) -> Unit = {}
    private var canonicalProbeHistory: ((Boolean) -> Unit)? = null
    fun captureFindBasis(): FindBasis {
        check(ownerThreadToken() === owner)
        return FindBasis(dc, recoveryBasisRevision, recoveryOwnerScope)
    }
    fun replaceFound(basis: FindBasis, matches: List<FindMatch>, text: String, all: Boolean): FindReplaceResult {
        check(ownerThreadToken() === owner)
        if (busy || composition != null || pending.isNotEmpty() || canonicalProbeHistory != null) return FindReplaceResult.Busy
        if (basis.owner !== recoveryOwnerScope || basis.revision != recoveryBasisRevision || basis.document != dc) return FindReplaceResult.Stale
        val raw = dc.text
        val stops = Graphemes.stops(raw).toHashSet()
        if (matches.isEmpty() ||
            matches.any { it.start < 0 || it.end > dc.units.size || it.start >= it.end || it.start !in stops || it.end !in stops } ||
            matches.zipWithNext().any { it.first.end > it.second.start }
        ) {
            return FindReplaceResult.Invalid
        }
        if (matches.any { m -> dc.units.subList(m.start, m.end).any { it.kind != UnitKind.TEXT } }) return FindReplaceResult.Invalid
        val size = dc.units.size.toLong() - matches.sumOf { (it.end - it.start).toLong() } + matches.size.toLong() * text.length
        if (size > DocumentLimits.MAX_UNITS) return FindReplaceResult.TooLarge
        if (matches.all { m -> raw.substring(m.start, m.end) == text && dc.units.subList(m.start, m.end).all { it.style == dc.units[m.start].style } }) return FindReplaceResult.NoChange
        var outcome = FindReplaceResult.Invalid
        dispatch(if (all) "Replace All" else "Replace match", onCommitted = { outcome = FindReplaceResult.Applied }) {
            val before = saved()
            val result = ArrayList<TextUnit>()
            val firstDeletedStyle = HashMap<Int, TextStyleFlags>()
            val paragraphs = IntArray(dc.units.size)
            var paragraph = 0
            dc.units.forEachIndexed { i, unit ->
                paragraphs[i] = paragraph
                if (unit.kind == UnitKind.PARAGRAPH_BREAK) paragraph++
            }
            var cursor = 0
            var caret = 0
            for ((index, match) in matches.withIndex()) {
                result.addAll(dc.units.subList(cursor, match.start))
                val seed = dc.units[match.start].style // Same first-selected-text rule as ordinary noncollapsed Replace.
                firstDeletedStyle.getOrPut(paragraphs[match.start]) { seed }
                result.addAll(units(text, breaks = false, style = seed)) // New LF is always a soft break.
                if (index == 0) caret = result.size
                cursor = match.end
            }
            result.addAll(dc.units.subList(cursor, dc.units.size))
            var startStyle = dc.startStyle
            if (text.isEmpty()) {
                // Preserve ordinary deletion's empty-paragraph typing style, without per-match history or quadratic splices.
                paragraph = 0
                var blockStart = 0
                fun finishBlock(end: Int) {
                    val style = firstDeletedStyle[paragraph]
                    if (end == blockStart && style != null) {
                        if (blockStart == 0) {
                            startStyle = style
                        } else {
                            result[blockStart - 1] = result[blockStart - 1].copy(paragraphStyle = style)
                        }
                    }
                }
                for (i in result.indices) {
                    if (result[i].kind == UnitKind.PARAGRAPH_BREAK) {
                        finishBlock(i)
                        paragraph++
                        blockStart = i + 1
                    }
                }
                finishBlock(result.size)
            }
            dc = dc.copy(units = result, startStyle = startStyle)
            selection = Selection(caret, caret)
            intent = if (text.isEmpty()) null else basis.document.units[matches.first().start].style
            side = TypingSide.LEFT
            push(before, if (all) "ReplaceAll" else "ReplaceMatch", false)
            typingBarrier = true
            notice = if (all) "Replaced ${matches.size} matches" else "Match replaced"
        }
        return outcome
    }
    fun installCanonicalProbeHistory(handler: (Boolean) -> Unit) {
        check(ownerThreadToken() === owner && canonicalProbeHistory == null && composition == null)
        canonicalProbeHistory = handler // One-way for this probe session; never expose stale local roots again.
    }
    fun applyCanonicalProbe(basis: CanonicalProbeBasis, edits: List<CanonicalProbeEdit>, mappedSelection: Selection): CanonicalProbeOutcome {
        check(ownerThreadToken() === owner)
        if (busy) return CanonicalProbeOutcome.DeferredOwnerBusy
        if (composition != null || pending.isNotEmpty()) return CanonicalProbeOutcome.DeferredComposition
        if (canonicalProbeHistory == null) return CanonicalProbeOutcome.RequiresExternalHistory
        if (documentRevision != basis.documentRevision || identityGeneration != basis.identityGeneration || dc.units.map { it.id } != basis.unitIds) return CanonicalProbeOutcome.RejectedBasis
        var applied = false
        dispatch("Canonical probe transaction", onCommitted = { applied = true }) {
            for (edit in edits) {
                when (edit) {
                    is CanonicalProbeEdit.Replace -> {
                        require(edit.start in 0..dc.units.size && edit.end in edit.start..dc.units.size)
                        dc = dc.splice(edit.start, edit.end, units(edit.text, edit.paragraphBreaks, edit.style, dc.paragraphAt(edit.start).properties))
                    }
                    is CanonicalProbeEdit.Attributes -> {
                        require(edit.start in 0..dc.units.size && edit.end in edit.start..dc.units.size)
                        dc = dc.copy(
                            units = dc.units.mapIndexed { i, unit ->
                                if (i !in edit.start until edit.end) {
                                    unit
                                } else if (unit.kind == UnitKind.PARAGRAPH_BREAK) {
                                    unit.copy(style = TextStyleFlags(), paragraphStyle = edit.style)
                                } else {
                                    unit.copy(style = edit.style)
                                }
                            },
                        )
                    }
                    is CanonicalProbeEdit.Move -> {
                        require(edit.start >= 0 && edit.start < edit.end && edit.end <= dc.units.size)
                        val moved = dc.units.subList(edit.start, edit.end).toList()
                        val remaining = dc.units.take(edit.start) + dc.units.drop(edit.end)
                        require(edit.destination in 0..remaining.size)
                        dc = dc.copy(units = remaining.take(edit.destination) + moved + remaining.drop(edit.destination))
                    }
                }
            }
            require(mappedSelection.start >= 0 && mappedSelection.end <= dc.units.size)
            selection = mappedSelection
            typingBarrier = true
            notice = "Canonical probe transaction applied"
            // Accepted remote/authored inverses do not enter local root-snapshot Undo.
            // Existing local history is retained; all history requests use the installed port.
        }
        return if (applied) CanonicalProbeOutcome.Applied else CanonicalProbeOutcome.Rejected
    }
    private fun visible() = composition?.let { c ->
        val sc = c.segments.first().dcStart
        val ec = c.segments.last().dcEnd
        dc.splice(sc, ec, c.segments.flatMap { it.units }, canonicalRetainedParagraphs = true)
    } ?: dc
    fun snapshot() = EditorFrame(
        visible(),
        dc,
        selection,
        composition?.let { c ->
            val sc = c.segments.first().dcStart
            val len = c.segments.sumOf { it.units.size }
            (sc + c.markedStart) until (sc + (c.markedEnd ?: len))
        },
        documentRevision,
        sessionRevision,
        undo.size,
        redo.size,
        pending.isNotEmpty(),
        notice,
        effectiveTyping(), identityGeneration, recoveryBasisRevision,
    )
    fun compositionCaptureToken(): Any? = composition
    fun recoveryCompositionEvidence(): String = composition?.let { c ->
        evidenceJson(
            mapOf(
                "imeEpoch" to imeEpoch,
                "beforeSelection" to c.before.selection.toString(),
                "markedRelativeStart" to c.markedStart,
                "markedRelativeEnd" to c.markedEnd,
                "typingStyle" to effectiveTyping().toString(),
                "segments" to c.segments.map { mapOf("dcStart" to it.dcStart, "dcEnd" to it.dcEnd, "units" to it.units.size, "origin" to it.origin.name) },
            ),
        )
    } ?: "No active native composition"
    fun captureRecoveryDraft(): RecoveredDraft? = composition?.let {
        captureRecoveryReplacement(visible(), selection)
    }
    fun captureRecoveryReplacement(document: Document, selection: Selection): RecoveredDraft {
        check(ownerThreadToken() === owner) { "Recovery capture must run on the owner thread" }
        return RecoveredDraft(dc, document, selection, recoveryBasisRevision, identityGeneration, recoveryOwnerScope)
    }
    fun ownsRecoveryDraft(draft: RecoveredDraft) = draft.ownerScope === recoveryOwnerScope

    /** Apply the captured attributed root against its exact local basis, independent of caret. */
    fun applyRecoveredDraft(draft: RecoveredDraft): RecoveryApplyResult {
        check(ownerThreadToken() === owner) { "Editing must run on the owner thread" }
        if (busy || composition != null || pending.isNotEmpty()) return RecoveryApplyResult.RecoveryBusy
        var result = RecoveryApplyResult.RecoveryRejected
        var staged = false
        dispatch("Apply recovered draft", onCommitted = { if (staged) result = RecoveryApplyResult.Applied }) {
            val ownerMatches = ownsRecoveryDraft(draft)
            if (!ownerMatches || recoveryBasisRevision != draft.basisRevision || identityGeneration != draft.basisIdentityGeneration || dc != draft.basis) {
                result = RecoveryApplyResult.RecoveryBaseChanged
                notice = if (ownerMatches) {
                    "RecoveryBaseChanged: document changed; retained draft was not applied."
                } else {
                    "RecoveryBaseChanged: draft belongs to another editor session; retained draft was not applied."
                }
            } else if (dc.same(draft.document)) {
                result = RecoveryApplyResult.NoChange
                notice = "Recovered draft has no content change; inspect or discard it."
            } else {
                require(draft.selection.start >= 0 && draft.selection.end <= draft.document.units.size)
                require(draft.document.units.map { it.id }.distinct().size == draft.document.units.size)
                val before = saved()
                dc = draft.document
                serial = maxOf(serial, dc.units.maxOfOrNull { it.id } ?: 0L)
                selection = draft.selection
                intent = null
                typingBarrier = true
                push(before, "RecoveredDraft", false)
                notice = "Recovered draft applied"
                staged = true
            }
        }
        return result
    }
    private fun units(text: String, breaks: Boolean = false, style: TextStyleFlags = effectiveTyping(), properties: ParagraphProperties = ParagraphProperties()): List<TextUnit> {
        var link = style.link
        return text.map {
            val isBreak = breaks && (it == '\n' || it == '\u000c')
            if (it.isWhitespace())link = null
            TextUnit(
                id = ++serial,
                char = it,
                kind = if (isBreak)UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                style = if (isBreak)TextStyleFlags() else style.copy(link = link),
                paragraphStyle = if (isBreak)style.copy(link = null) else TextStyleFlags(),
                followingParagraph = if (isBreak)properties.copy(pagination = properties.pagination.copy(breakBefore = false, keepWithNext = false)) else ParagraphProperties(),
                structuralBreak = if (isBreak && it == '\u000c')StructuralBreak.PAGE else StructuralBreak.PARAGRAPH,
            )
        }
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
    private fun effectiveTyping(): TextStyleFlags {
        val seed = intent ?: inherit(selection.focus, side)
        val u = visible().units
        val left = u.getOrNull(selection.focus - 1)?.style?.link
        val right = u.getOrNull(selection.focus)?.style?.link
        return if (left != null && left == right) seed.copy(link = left) else seed.copy(link = null)
    }
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
    private fun dispatch(label: String, onCommitted: (() -> Unit)? = null, block: () -> Unit) {
        check(ownerThreadToken() === owner) { "Editing must run on the owner thread" }
        dispatchQueue.addLast {
            val before = visible()
            val savedDc = dc
            val savedSelection = selection
            val savedIntent = intent
            val savedSide = side
            val savedComposition = composition?.let { it.copy(synthesized = HashMap(it.synthesized)) }
            val savedUndo = undo.map { it.copy() }
            val savedRedo = redo.map { it.copy() }
            val savedPending = pending.toList()
            val barrier = typingBarrier
            val savedSettlementRequestedAt = settlementRequestedAt
            val savedSettlementTimedOut = settlementTimedOut
            try {
                block()
                check(selection.anchor in 0..visible().units.size && selection.focus in 0..visible().units.size)
                check(visible().units.map { it.id }.distinct().size == visible().units.size)
            } catch (e: Exception) {
                dc = savedDc
                selection = savedSelection
                composition = savedComposition
                intent = savedIntent
                side = savedSide
                undo.clear()
                undo.addAll(savedUndo)
                redo.clear()
                redo.addAll(savedRedo)
                pending.clear()
                pending.addAll(savedPending)
                typingBarrier = barrier
                settlementRequestedAt = savedSettlementRequestedAt
                settlementTimedOut = savedSettlementTimedOut
                notice = "Edit rejected: ${e.message}"
                runCatching { onEvent("Rejected $label: ${e.message}") }
                runCatching { onChange(snapshot()) }
                return@addLast
            }
            if (!before.same(visible())) documentRevision++
            if (savedDc != dc) recoveryBasisRevision++
            if (savedDc.units.map { it.id } != dc.units.map { it.id }) identityGeneration++
            sessionRevision++
            onCommitted?.invoke()
            runCatching { onEvent(label) }
            runCatching { onChange(snapshot()) }.onFailure { runCatching { onEvent("Observer failure: ${it.message}") } }
        }
        if (busy) return
        busy = true
        try {
            while (dispatchQueue.isNotEmpty()) dispatchQueue.removeFirst().invoke()
        } finally {
            busy = false
        }
    }
    fun capture(range: Selection = selection): CapturedRange {
        val u = visible().units
        require(range.start >= 0 && range.end <= u.size)
        return CapturedRange(u.subList(range.start, range.end).map { it.id }, u.getOrNull(range.start - 1)?.id, u.getOrNull(range.end)?.id, range.collapsed, range.anchor > range.focus)
    }
    private fun resolve(t: CapturedRange): Selection? {
        val u = visible().units
        val rank = u.withIndex().associate { it.value.id to it.index }
        if (t.collapsed) {
            val left = if (t.left == null) -1 else rank[t.left]
            val right = if (t.right == null) u.size else rank[t.right]
            if (left == null && right == null) return null
            val p = right ?: (left!! + 1)
            return Selection(p, p)
        }
        val positions = t.ids.map { rank[it] ?: return null }
        if (positions.zipWithNext().any { it.second != it.first + 1 }) return null
        return if (t.backwards) Selection(positions.last() + 1, positions.first()) else Selection(positions.first(), positions.last() + 1)
    }
    private fun push(before: Saved, kind: String, coalesce: Boolean): Long? {
        val after = saved()
        if (before.document.same(after.document)) return null
        val previous = undo.lastOrNull()
        val time = now()
        if (coalesce && !typingBarrier && kind == "Typing" && previous?.kind == kind && time - previous.time in 0..750 && before.selection.collapsed && before.selection.focus == previous.after.selection.focus && before.document.same(previous.after.document)) {
            previous.after = after
            previous.time = time
            redo.clear()
            return previous.id
        }
        val h = History(++historySerial, before, after, kind, time)
        undo += h
        redo.clear()
        if (undo.size > 1000) {
            undo.removeAt(0)
            notice = "Oldest undo step released"
        }
        typingBarrier = false
        return h.id
    }
    private fun applyFormatting(targetAnchor: CapturedRange?, patchValue: TextStylePatch): Long? {
        val target = targetAnchor?.let(::resolve) ?: run {
            notice = "Formatting target changed. Select it again."
            return null
        }
        fun patch(style: TextStyleFlags) = patchValue.apply(style)
        typingBarrier = true
        if (target.collapsed) {
            if (!selection.collapsed || selection.focus != target.focus) {
                notice = "Formatting caret changed. Select the target again."
                return null
            }
            intent = patch(effectiveTyping())
            notice = "Ready"
            return null
        }
        val before = saved()
        val intervals = displayedStyles(dc).filter { it.start < target.end && it.end > target.start }
        val patched = BooleanArray(dc.units.size)
        intervals.forEach { for (i in it.start until it.end) patched[i] = true }
        dc = dc.copy(units = dc.units.mapIndexed { i, unit -> if (patched[i]) unit.copy(style = patch(unit.style)) else unit })
        intent = null
        notice = "Ready"
        return push(before, "Format", false).also { typingBarrier = true }
    }
    private fun apply(action: EditAction, queued: Boolean = false): Long? {
        when (action) {
            is EditAction.Replace -> {
                val target = resolve(action.target) ?: run {
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
                if (a == b && action.text.isEmpty()) return null
                val before = saved()
                val inserted = units(action.text, action.paragraphBreaks, action.style, dc.paragraphAt(a).properties)
                require(!(a == b && action.text.isNotEmpty() && dc.paragraphAt(a).let { dc.units.subList(it.start, it.end).any { u -> u.kind == UnitKind.IMAGE } })) { "Image is atomic; insert in the neighboring paragraph" }
                dc = dc.splice(a, b, inserted)
                validateStructure(dc)
                selection = Selection(a + inserted.size, a + inserted.size)
                intent = action.style
                if (inserted.isEmpty()) {
                    val at = selection.focus
                    val empty = (at == 0 || dc.units[at - 1].kind == UnitKind.PARAGRAPH_BREAK) && (at == dc.units.size || dc.units[at].kind == UnitKind.PARAGRAPH_BREAK)
                    if (empty) {
                        dc = if (at == 0) {
                            dc.copy(startStyle = action.style)
                        } else {
                            dc.copy(
                                units = dc.units.mapIndexed { i, unit ->
                                    if (i == at - 1) unit.copy(style = TextStyleFlags(), paragraphStyle = action.style) else unit
                                },
                            )
                        }
                    } else {
                        intent = null
                        side = action.deleteSide ?: TypingSide.LEFT
                    }
                }
                notice = "Ready"
                return push(before, action.kind, !queued && action.kind == "Typing")
            }
            is EditAction.Structure -> {
                val target = resolve(action.target) ?: run {
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
            is EditAction.OutlineMove -> {
                val p = dc.paragraphs().singleOrNull { it.anchor == action.anchor && it.properties.heading > 0 }
                if (p == null) {
                    notice = "Heading target removed"
                    return null
                }
                selection = Selection(p.start, p.start)
                intent = null
                side = TypingSide.LEFT
                typingBarrier = true
            }
            is EditAction.Move -> {
                resolve(action.target)?.let {
                    selection = it.copy(primary = action.primary, wrapAffinity = action.wrapAffinity)
                    intent = null
                    side = action.side
                } ?: run { notice = "Text changed. Select the position again." }
                typingBarrier = true
            }
            is EditAction.Paragraph -> {
                val blocks = dc.paragraphs()
                val targets = blocks.filter { it.anchor in action.anchors }
                if (targets.size != action.anchors.distinct().size) {
                    notice = "Paragraph target changed; try again."
                    return null
                }
                val before = saved()
                val anchors = action.anchors.toSet()
                fun patch(p: ParagraphProperties) = p.copy(direction = action.direction ?: p.direction, alignment = action.alignment ?: p.alignment, spaceBefore = action.before ?: p.spaceBefore, spaceAfter = action.after ?: p.spaceAfter, linePermille = action.line ?: p.linePermille)
                dc = dc.patchParagraphs(targets.associate { it.anchor to patch(it.properties) })
                val id = push(before, "Paragraph", false)
                typingBarrier = true
                notice = "Paragraph properties updated"
                return id
            }
            is EditAction.Format -> return applyFormatting(
                action.target,
                when (action.key) {
                    FormatKey.BOLD -> TextStylePatch(bold = action.value)
                    FormatKey.ITALIC -> TextStylePatch(italic = action.value)
                    FormatKey.UNDERLINE -> TextStylePatch(underline = action.value)
                    FormatKey.STRIKE -> TextStylePatch(strike = action.value)
                },
            )
            is EditAction.FormatAttributes -> return applyFormatting(action.target, TextStylePatch(bold = action.bold, italic = action.italic))
            is EditAction.TextAttributes -> return applyFormatting(action.target, action.patch)
            EditAction.Undo -> {
                canonicalProbeHistory?.let {
                    it(false)
                    notice = "External history requested"
                    return null
                }
                val h = undo.removeLastOrNull() ?: return null
                dc = h.before.document
                selection = h.before.selection
                intent = h.before.intent
                side = h.before.side
                redo += h
                typingBarrier = true
                notice = "Undone"
            }
            EditAction.Redo -> {
                canonicalProbeHistory?.let {
                    it(true)
                    notice = "External history requested"
                    return null
                }
                val h = redo.removeLastOrNull() ?: return null
                dc = h.after.document
                selection = h.after.selection
                intent = h.after.intent
                side = h.after.side
                undo += h
                typingBarrier = true
                notice = "Redone"
            }
        }
        return null
    }
    fun perform(action: EditAction) {
        dispatch(action::class.simpleName ?: "EditAction") {
            if (composition != null) {
                if (action == EditAction.Redo) {
                    notice = "Finish the current composition before Redo."
                    return@dispatch
                }
                if (pending.size >= 256) {
                    notice = "Finish composing before another command."
                    return@dispatch
                }
                if (action == EditAction.Undo && (!composition!!.before.document.same(visible()) || !composition!!.before.document.same(dc))) composition!!.undoSawDirty = true
                if (action is EditAction.Move && pending.lastOrNull() is EditAction.Move) pending[pending.lastIndex] = action else pending += action
                notice = "Waiting for the input method to finish…"
                settlementTimedOut = false
                if (settlementRequestedAt == null) settlementRequestedAt = now()
            } else {
                apply(action)
            }
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
    fun replace(text: String, kind: String = "Typing", paragraphBreaks: Boolean = false, range: Selection = selection, deleteSide: TypingSide? = null) {
        if (text == "\n" && paragraphBreaks && kind == "Enter") {
            structure(StructureCommand.Enter(false))
            return
        }
        perform(EditAction.Replace(capture(range), text, kind, paragraphBreaks, replacementStyle(range), deleteSide))
    }

    /** Protocol ranges are raw UTF-16. Unlike keyboard commands, they do not snap or infer Enter. */
    fun replaceExact(text: String, kind: String, range: Selection, paragraphBreaks: Boolean = false) {
        val atCurrentCaret = range.collapsed && selection.collapsed && range.focus == selection.focus
        val seed = if (atCurrentCaret) {
            effectiveTyping()
        } else if (range.collapsed) {
            inherit(range.focus, TypingSide.LEFT)
        } else {
            visible().units.subList(range.start, range.end).firstOrNull { it.kind == UnitKind.TEXT }?.style ?: inherit(range.start, TypingSide.LEFT)
        }
        perform(EditAction.Replace(capture(range), text, kind, paragraphBreaks, seed, exactUtf16 = true))
    }
    fun move(range: Selection, primary: Boolean = range.primary, side: TypingSide = TypingSide.LEFT, wrapAffinity: WrapAffinity = range.wrapAffinity) = perform(EditAction.Move(capture(range), primary, side, wrapAffinity))
    fun setParagraphProperties(direction: ParagraphDirection? = null, alignment: ParagraphAlignment? = null, before: Int? = null, after: Int? = null, line: Int? = null) {
        if (direction == null && alignment == null && before == null && after == null && line == null) return
        perform(EditAction.Paragraph(visible().targetParagraphs(selection).map { it.anchor }, direction, alignment, before, after, line))
    }
    fun paragraphDirectionState(): ParagraphDirection? = visible().targetParagraphs(selection).map { it.properties.direction }.distinct().singleOrNull()
    fun paragraphAlignmentState(): ParagraphAlignment? = visible().targetParagraphs(selection).map { it.properties.alignment }.distinct().singleOrNull()
    fun setTextStyle(patch: TextStylePatch) = perform(EditAction.TextAttributes(if (composition != null && selection.collapsed) null else capture(), patch))
    fun <T> textStyleState(value: (TextStyleFlags) -> T): T? = if (selection.collapsed) {
        value(effectiveTyping())
    } else {
        displayedStyles(visible()).filter { it.start < selection.end && it.end > selection.start }.map { value(it.style) }.distinct().singleOrNull()
    }
    fun <T> paragraphState(value: (ParagraphProperties) -> T): T? = visible().targetParagraphs(selection).map { value(it.properties) }.distinct().singleOrNull()
    fun clipboardCapture(): ClipboardCapture? {
        if (busy || composition != null || pending.isNotEmpty()) return null
        val stops = Graphemes.stops(dc.text)
        if (selection.start !in stops || selection.end !in stops) return null
        return ClipboardCapture(captureFindBasis(), selection, sessionRevision)
    }
    fun clipboardApply(c: ClipboardCapture, fragment: RichFragment?, plain: String? = null, cut: Boolean = false): Boolean {
        if (busy ||
            composition != null ||
            pending.isNotEmpty() ||
            canonicalProbeHistory != null ||
            c.basis.owner !== recoveryOwnerScope ||
            c.basis.revision != recoveryBasisRevision ||
            c.basis.document != dc ||
            c.selection != selection ||
            c.sessionRevision != sessionRevision
        ) {
            return false
        }
        val a = c.selection.start
        val b = c.selection.end
        if (cut && a == b) return false
        if (plain != null) {
            if (dc.units.size.toLong() - (b - a) + plain.length > DocumentLimits.MAX_UNITS) return false
            replace(plain, if (cut) "Cut" else "Paste plain", paragraphBreaks = true)
            return true
        }
        val original = fragment ?: return false
        val itemIds = original.document.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct().associateWith { freshStructureId() }
        val linkIds = HashMap<String, String>()
        fun remap(style: TextStyleFlags) = style.copy(link = style.link?.let { it.copy(id = linkIds.getOrPut(it.id) { freshStructureId() }) })
        val imported = original.document.patchParagraphs(original.document.paragraphs().associate { p -> p.anchor to p.properties.copy(item = itemIds[p.properties.item] ?: "", parent = itemIds[p.properties.parent] ?: "") })
        val f = original.copy(document = imported.copy(startStyle = remap(imported.startStyle), units = imported.units.map { it.copy(style = remap(it.style), paragraphStyle = remap(it.paragraphStyle)) }))
        validateStructure(f.document)
        if (dc.units.size.toLong() - (b - a) + f.document.units.size > DocumentLimits.MAX_UNITS) return false
        var done = false
        dispatch("Rich paste", onCommitted = { done = true }) {
            val before = saved()
            val destination = dc.paragraphAt(a)
            val sourceBlocks = f.document.paragraphs()
            val inserted = f.document.units.map { it.copy(id = ++serial, followingBlockId = Long.MIN_VALUE) }.toMutableList()
            for (block in sourceBlocks.drop(1)) {
                val i = block.start - 1
                if (!f.complete[block.index]) inserted[i] = inserted[i].copy(followingParagraph = destination.properties)
            }
            val isImageFirst = inserted.firstOrNull()?.kind == UnitKind.IMAGE
            val isImageLast = inserted.lastOrNull()?.kind == UnitKind.IMAGE
            val closedFirst = sourceBlocks.first().properties.let { f.complete.first() && (it.heading > 0 || it.item.isNotEmpty()) }
            val closedLast = sourceBlocks.last().properties.let { f.complete.last() && (it.heading > 0 || it.item.isNotEmpty()) }
            val prefixBreak = (a > destination.start) && (isImageFirst || closedFirst)
            val suffixBlock = dc.paragraphAt(b)
            val suffixBreak = (b < suffixBlock.end) && (isImageLast || closedLast)
            if (prefixBreak)inserted.add(0, TextUnit(++serial, '\n', UnitKind.PARAGRAPH_BREAK, paragraphStyle = f.document.startStyle, followingParagraph = f.document.startParagraph))
            if (suffixBreak)inserted.add(TextUnit(++serial, '\n', UnitKind.PARAGRAPH_BREAK, paragraphStyle = dc.units.getOrNull(b)?.style ?: dc.startStyle, followingParagraph = suffixBlock.properties))
            var root = dc.startParagraph
            var rootIdentity = dc.rootBlockId
            val preserveTail = a == destination.start && b < suffixBlock.end && (isImageFirst || closedFirst)
            val newHeadId = if (preserveTail)++serial else null
            if (preserveTail) {
                val tail = inserted.indexOfLast { it.kind == UnitKind.PARAGRAPH_BREAK }
                require(tail >= 0) { "Closed block paste must retain an editable tail" }
                val typing = if (suffixBlock.start == 0)dc.startStyle else dc.units[suffixBlock.start - 1].paragraphStyle
                inserted[tail] = inserted[tail].copy(followingBlockId = suffixBlock.anchor, followingParagraph = suffixBlock.properties, paragraphStyle = typing)
            }
            val firstProps = if (isImageFirst && a == destination.start) {
                f.document.startParagraph.copy(heading = 0)
            } else if (f.complete.first() && a == destination.start) {
                f.document.startParagraph
            } else {
                destination.properties
            }
            var result = dc.units.take(a) + inserted + dc.units.drop(b)
            if (a == destination.start) {
                if (a == 0) {
                    root = firstProps
                    if (preserveTail)rootIdentity = newHeadId
                } else {
                    result = result.mapIndexed { i, u -> if (i == a - 1) u.copy(followingParagraph = firstProps, followingBlockId = if (preserveTail)newHeadId else u.followingBlockId) else u }
                }
            }
            var rootStyle = dc.startStyle
            if (f.complete.first() && a == destination.start) {
                if (a == 0) rootStyle = f.document.startStyle else result = result.mapIndexed { i, u -> if (i == a - 1) u.copy(paragraphStyle = f.document.startStyle) else u }
            }
            dc = Document(result, rootStyle, root, dc.resources + f.document.resources, rootIdentity, dc.pageSettings, dc.running).repairRemovedImageHeads(before.document, b).repairListParents(before.document)
            validateStructure(dc)
            selection = Selection(a + inserted.size, a + inserted.size)
            intent = inserted.lastOrNull { it.kind == UnitKind.TEXT }?.style ?: f.document.startStyle
            if (inserted.isEmpty()) {
                if (a == 0) {
                    dc = dc.copy(startStyle = f.document.startStyle)
                } else if (dc.units[a - 1].kind == UnitKind.PARAGRAPH_BREAK) {
                    dc = dc.copy(units = dc.units.mapIndexed { i, u -> if (i == a - 1) u.copy(paragraphStyle = f.document.startStyle) else u })
                }
            }
            push(before, "Paste", false)
            typingBarrier = true
            notice = "Rich paste"
        }
        return done
    }
    fun structure(command: StructureCommand) = perform(EditAction.Structure(capture(), visible().targetParagraphs(selection).map { it.anchor }, command))
    fun applyStructure(c: ClipboardCapture, command: StructureCommand): Boolean {
        if (clipboardCapture() != c) return false
        val before = snapshot()
        structure(command)
        return snapshot().committed != before.committed
    }
    fun outline() = snapshot().committed.outlineEntries()
    fun navigateOutline(anchor: Long?): Boolean {
        if (dc.paragraphs().none { it.anchor == anchor && it.properties.heading > 0 })return false
        perform(EditAction.OutlineMove(anchor))
        return true
    }
    fun selectAll() = move(Selection(0, visible().units.size))
    fun delete(backward: Boolean) {
        val s = snapshot()
        if (!s.selection.collapsed) {
            replace("", "Delete", deleteSide = if (backward) TypingSide.LEFT else TypingSide.RIGHT)
            return
        }
        val block = s.visible.paragraphAt(s.selection.focus)
        if (backward && block.properties.item.isNotEmpty() && s.selection.focus == block.start && s.visible.paragraphs().first { it.properties.item == block.properties.item }.anchor == block.anchor) {
            structure(StructureCommand.Outdent)
            return
        }
        val adjacent = if (backward)s.selection.focus - 1 else s.selection.focus
        if (s.visible.units.getOrNull(adjacent)?.kind == UnitKind.IMAGE) {
            move(Selection(adjacent, adjacent + 1))
            return
        }
        val at = s.selection.focus
        val stops = Graphemes.stops(s.visible.text)
        val range = if (backward) Selection(stops.lastOrNull { it < at } ?: at, at) else Selection(at, stops.firstOrNull { it > at } ?: at)
        replace("", "Delete", range = range, deleteSide = if (backward) TypingSide.LEFT else TypingSide.RIGHT)
    }

    /** Strict normalized native retarget; raw UTF-16, never grapheme snapping. No platform clipping. */
    fun retargetComposition(range: Selection) {
        dispatch("Native retarget") {
            val current = visible()
            require(range.start >= 0 && range.end <= current.units.size)
            require(current.units.subList(range.start, range.end).none { it.kind == UnitKind.IMAGE }) { "Native image seam is not editable text" }
            val c = checkNotNull(composition) { "No active composition" }
            val sc = c.segments.first().dcStart
            val ec = c.segments.last().dcEnd
            val visibleEnd = sc + c.segments.sumOf { it.units.size }
            val left = minOf(sc, range.start)
            val right = maxOf(visibleEnd, range.end)
            val prefix = (left until sc).map { OverlaySegment(it, it + 1, listOf(dc.units[it]), SegmentOrigin.RETAINED) }
            val tailCount = right - visibleEnd
            val suffix = (ec until ec + tailCount).map { OverlaySegment(it, it + 1, listOf(dc.units[it]), SegmentOrigin.RETAINED) }
            c.segments = prefix + c.segments + suffix
            c.epochBaseSegments = c.segments
            c.synthesized.clear()
            c.markedStart = range.start - left
            c.markedEnd = range.end - left
            selection = range
        }
    }

    /** Semantic acceptance effect, separate from candidate reconciliation and terminal callbacks. */
    fun partialCommit(count: Int, postSelection: Selection? = null) {
        val c = checkNotNull(composition)
        val raw = c.segments.flatMap { it.units }.joinToString("") { it.char.toString() }
        val end = c.markedEnd ?: raw.length
        require(count in 0..end - c.markedStart)
        if (postSelection != null)require(postSelection.start >= 0 && postSelection.end <= visible().units.size)
        if (count == 0) {
            dispatch("Zero partial acceptance") { if (postSelection != null)selection = postSelection }
            return
        }
        inputMethod(raw.substring(c.markedStart, end), count, null)
        if (postSelection != null)dispatch("Native post selection") { selection = postSelection }
    }

    /** One AWT INPUT_METHOD_TEXT_CHANGED event: accepted prefix + complete replacement preedit. */
    fun nativeStructureAdmission(text: String): Boolean {
        val session = composition
        val a = session?.segments?.first()?.dcStart ?: selection.start
        val b = session?.segments?.last()?.dcEnd ?: selection.end
        val base = session?.epochBaseSegments?.flatMap { it.units } ?: dc.units.subList(a, b)
        var prefix = 0
        while (prefix < text.length && prefix < base.size && text[prefix] == base[prefix].char)prefix++
        var suffix = 0
        while (suffix < text.length - prefix && suffix < base.size - prefix && text[text.length - 1 - suffix] == base[base.size - 1 - suffix].char)suffix++
        val proposed = base.take(prefix) + text.substring(prefix, text.length - suffix).mapIndexed { i, c -> TextUnit(-1_000_000L - i, c) } + base.takeLast(suffix)
        return runCatching {
            validateStructure(dc.splice(a, b, proposed, canonicalRetainedParagraphs = true))
            true
        }.getOrDefault(false)
    }
    fun inputMethod(text: String, committedCount: Int, caret: Int?) {
        require(committedCount in 0..text.length)
        require(caret == null || caret in 0..text.length - committedCount)
        val c = composition
        if (c != null && (c.markedStart != 0 || c.markedEnd != null)) {
            val raw = c.segments.flatMap { it.units }.joinToString("") { it.char.toString() }
            val end = c.markedEnd ?: raw.length
            val combined = raw.take(c.markedStart) + text + raw.substring(end)
            val accepted = if (committedCount == 0)0 else c.markedStart + committedCount
            applyInputMethod(combined, accepted, if (caret == null)null else c.markedStart + caret + committedCount - accepted, c.markedStart, c.markedStart + text.length)
        } else {
            applyInputMethod(text, committedCount, caret, 0, text.length)
        }
    }
    private fun applyInputMethod(text: String, committedCount: Int, caret: Int?, newMarkedStart: Int, newMarkedEnd: Int) {
        if (composition == null && text.isEmpty() && committedCount == 0 && selection.collapsed) return
        dispatch("IME text: committed=$committedCount composed=${text.length - committedCount}") {
            require(nativeStructureAdmission(text)) { "Native image seam is not editable text" }
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
                    seed = replacementStyle(selection),
                ).also { composition = it }
            }

            val baseSegs = session.epochBaseSegments
            val baselineUnits = baseSegs.flatMap { it.units }

            var p = 0
            while (p < text.length && p < baselineUnits.size && text[p] == baselineUnits[p].char) p++

            var s = 0
            while (s < text.length - p && s < baselineUnits.size - p && text[text.length - 1 - s] == baselineUnits[baselineUnits.size - 1 - s].char) s++

            val newSegments: List<OverlaySegment>
            if (p == baselineUnits.size && s == 0 && text.length == baselineUnits.size) {
                newSegments = baseSegs
            } else {
                val midText = text.substring(p, text.length - s)
                var candidateLink = session.seed.link
                val midUnits = midText.mapIndexed { idx, ch ->
                    if (ch.isWhitespace())candidateLink = null
                    session.synthesized.getOrPut((p + idx) to ch) {
                        TextUnit(++serial, ch, UnitKind.TEXT, session.seed.copy(link = candidateLink))
                    }
                }
                val aMid = p
                val bMid = baselineUnits.size - s

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
                    val dcPos = if (insIdx < baseSegs.size) {
                        baseSegs[insIdx].dcStart
                    } else if (baseSegs.isNotEmpty()) {
                        baseSegs.last().dcEnd
                    } else {
                        0
                    }
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
                                baseSegs[firstTouched].origin,
                            ),
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
                                baseSegs[lastTouched].origin,
                            ),
                        )
                    }

                    for (i in (lastTouched + 1) until baseSegs.size) segList.add(baseSegs[i])
                }

                if (segList.isEmpty()) {
                    val fallbackDc = if (firstTouched in baseSegs.indices) {
                        baseSegs[firstTouched].dcStart
                    } else if (baseSegs.isNotEmpty()) {
                        baseSegs[0].dcStart
                    } else {
                        0
                    }
                    segList.add(OverlaySegment(fallbackDc, fallbackDc, emptyList(), SegmentOrigin.REPLACEMENT))
                }
                newSegments = segList
            }

            if (committedCount > 0) {
                if (session.seed.link != null && text.take(committedCount).any { it.isWhitespace() }) {
                    session.seed = session.seed.copy(link = null)
                    session.synthesized.clear()
                }
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
                dc = dc.splice(sc, dCut, pCommit, canonicalRetainedParagraphs = true)
                val delta = pCommit.size - (dCut - sc)
                val newSc = sc + pCommit.size

                val finalRemSegments = ArrayList<OverlaySegment>()
                if (splitTail != null && splitTail.units.isNotEmpty()) {
                    finalRemSegments.add(
                        OverlaySegment(
                            dcStart = newSc,
                            dcEnd = splitTail.origDcEnd + delta,
                            units = splitTail.units,
                            origin = if (splitTail.origin == SegmentOrigin.RETAINED) SegmentOrigin.RETAINED else SegmentOrigin.REPLACEMENT,
                        ),
                    )
                }
                for (seg in remainingSegments) {
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
                session.segments = finalRemSegments
                session.epochBaseSegments = finalRemSegments
                session.synthesized.clear()
            } else {
                session.segments = newSegments
            }

            session.markedStart = (newMarkedStart - committedCount).coerceAtLeast(0)
            session.markedEnd = if (newMarkedStart == 0 && newMarkedEnd == text.length)null else (newMarkedEnd - committedCount).coerceAtLeast(0)
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
                dc = if (at == 0) {
                    dc.copy(startStyle = session.seed)
                } else {
                    dc.copy(
                        units = dc.units.mapIndexed { i, unit ->
                            if (i == at - 1) unit.copy(style = TextStyleFlags(), paragraphStyle = session.seed) else unit
                        },
                    )
                }
            }
        }
        val group = push(session.before, kind, kind == "Typing" && pending.isEmpty())
        val sc = session.segments.first().dcStart
        val settledTarget = capture(Selection(session.before.selection.start, sc))
        val slots = undo.map { it.id as Long? }.toMutableList()
        if (group == null && session.undoSawDirty) slots += null
        for (action in pending.toList()) {
            if (action == EditAction.Undo) {
                if (canonicalProbeHistory != null) {
                    apply(action)
                    continue
                }
                if (slots.isNotEmpty()) {
                    val id = slots.removeAt(slots.lastIndex)
                    if (id != null) {
                        check(undo.lastOrNull()?.id == id)
                        apply(action)
                    } else {
                        notice = "Pending action already reverted"
                    }
                }
            } else {
                val executable = if (action is EditAction.TextAttributes && action.target == null) {
                    if (aborted) null else action.copy(target = capture())
                } else if (action is EditAction.Format && action.target == null) {
                    if (aborted || settledTarget.collapsed) null else action.copy(target = settledTarget)
                } else {
                    action
                }
                val id = executable?.let { apply(it, queued = true) }
                if (action is EditAction.Replace || action is EditAction.Format || action is EditAction.FormatAttributes || action is EditAction.TextAttributes || action is EditAction.Paragraph || action is EditAction.Structure) slots += id
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
                selection = if (s.before.document.same(dc)) {
                    s.before.selection
                } else {
                    Selection(s.segments.first().dcStart, s.segments.first().dcStart)
                }
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
