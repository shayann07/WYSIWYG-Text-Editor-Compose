package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.DocumentLimits
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.OverlaySegment
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.TypingSide
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.WrapAffinity
import dev.shayxo.composedoc.model.displayedStyles
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.outlineEntries
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.targetParagraphs
import kotlin.jvm.JvmOverloads
data class EditorFrame(
    val visible: Document,
    val committed: Document,
    val selection: Selection,
    val marked: IntRange?,
    val documentRevision: Long,
    val sessionRevision: Long,
    val undoCount: Int,
    val redoCount: Int,
    val waiting: Boolean,
    val notice: String,
    val typingStyle: TextStyleFlags,
    val identityGeneration: Long,
    val recoveryBasisRevision: Long,
)

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
    data class Replace(
        val start: Int,
        val end: Int,
        val text: String,
        val style: TextStyleFlags,
        val paragraphBreaks: Boolean = false,
    ) : CanonicalProbeEdit
    data class Attributes(val start: Int, val end: Int, val style: TextStyleFlags) : CanonicalProbeEdit

    /** Destination is measured after removing [start,end); retained units keep their identities. */
    data class Move(val start: Int, val end: Int, val destination: Int) : CanonicalProbeEdit
}
enum class CanonicalProbeOutcome {
    Applied,
    DeferredComposition,
    DeferredOwnerBusy,
    RejectedBasis,
    RequiresExternalHistory,
    Rejected,
}
data class CanonicalProbeBasis(val documentRevision: Long, val unitIds: List<Long>, val identityGeneration: Long)
internal data class Saved(
    val document: Document,
    val selection: Selection,
    val intent: TextStyleFlags?,
    val side: TypingSide,
)
internal data class History(val id: Long, val before: Saved, var after: Saved, val kind: String, var time: Long)
internal data class Composition(
    val before: Saved,
    var segments: List<OverlaySegment>,
    var epochBaseSegments: List<OverlaySegment>,
    var seed: TextStyleFlags,
    val synthesized: MutableMap<Pair<Int, Char>, TextUnit> = HashMap(),
    var undoSawDirty: Boolean = false,
    var markedStart: Int = 0,
    var markedEnd: Int? = null,
)
data class CapturedRange(
    val ids: List<Long>,
    val left: Long?,
    val right: Long?,
    val collapsed: Boolean,
    val backwards: Boolean,
)
sealed interface EditAction {
    data class Replace @JvmOverloads constructor(
        val target: CapturedRange,
        val text: String,
        val kind: String = "Typing",
        val paragraphBreaks: Boolean = false,
        val style: TextStyleFlags = TextStyleFlags(),
        val deleteSide: TypingSide? = null,
        val exactUtf16: Boolean = false,
    ) : EditAction
    data class Move(
        val target: CapturedRange,
        val primary: Boolean = true,
        val side: TypingSide = TypingSide.LEFT,
        val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM,
    ) : EditAction
    data class Format(val target: CapturedRange?, val key: FormatKey, val value: Boolean) : EditAction
    data class FormatAttributes(val target: CapturedRange, val bold: Boolean?, val italic: Boolean?) : EditAction
    data class TextAttributes(val target: CapturedRange?, val patch: TextStylePatch) : EditAction
    data class Paragraph(
        val anchors: List<Long?>,
        val direction: ParagraphDirection?,
        val alignment: ParagraphAlignment?,
        val before: Int? = null,
        val after: Int? = null,
        val line: Int? = null,
    ) : EditAction
    data class Structure(val target: CapturedRange, val anchors: List<Long?>, val command: StructureCommand) :
        EditAction
    data class OutlineMove(val anchor: Long?) : EditAction
    data object Undo : EditAction
    data object Redo : EditAction
}

/** At most this many commands queue behind an active composition before new ones are refused. */
private const val MAX_PENDING_ACTIONS = 256

// Reason: every public member is part of the published single-owner engine API; reducing the
// function count would require an API change. Private and internal logic lives in helper files.

/** Plain-text vertical slice: one owner, Dc + provisional replacement, independent history.
 * Array snapshots are intentional for this prototype, NOT the production persistent index. */
// Reason: the existing public owner commands are API; splitting this facade would change caller signatures.
@Suppress("TooManyFunctions")
class EditorEngine @JvmOverloads constructor(
    initial: Document = Document(emptyList()),
    internal val now: () -> Long = { monotonicMillis() },
) {
    private val owner = ownerThreadToken()

    // Runtime owner only; not a durable DocumentId or authentication credential.
    internal val recoveryOwnerScope = Any()
    internal var serial = 0L
    internal var historySerial = 0L
    internal var dc = Document(
        initial.units.map { unit ->
            if (unit.kind == UnitKind.PARAGRAPH_BREAK &&
                unit.style != TextStyleFlags() &&
                unit.paragraphStyle == TextStyleFlags()
            ) {
                unit.copy(
                    id = ++serial,
                    style = TextStyleFlags(),
                    paragraphStyle = unit.style,
                    followingBlockId = Long.MIN_VALUE,
                )
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
    internal var composition: Composition? = null
    internal var selection = Selection(0, 0)
    internal var intent: TextStyleFlags? = null
    internal var side = TypingSide.LEFT
    internal val undo = ArrayList<History>()
    internal val redo = ArrayList<History>()
    internal val pending = ArrayList<EditAction>()
    private val dispatchQueue = ArrayDeque<() -> Unit>()
    private var busy = false
    private var documentRevision = 0L
    private var sessionRevision = 0L
    private var identityGeneration = 0L
    internal var recoveryBasisRevision = 0L // Exact committed Dc changes, excluding provisional-only publications.
    internal var typingBarrier = false
    internal var notice = "Ready"
    var settlementRequestedAt: Long? = null
        internal set
    var settlementTimedOut = false
        internal set
    var imeEpoch = 0L
        internal set
    var onChange: (EditorFrame) -> Unit = {}
    var onSettlementRequested: () -> Unit = {}
    var onEvent: (String) -> Unit = {}
    internal var canonicalProbeHistory: ((Boolean) -> Unit)? = null
    fun captureFindBasis(): FindBasis {
        check(ownerThreadToken() === owner)
        return FindBasis(dc, recoveryBasisRevision, recoveryOwnerScope)
    }
    fun replaceFound(basis: FindBasis, matches: List<FindMatch>, text: String, all: Boolean): FindReplaceResult {
        check(ownerThreadToken() === owner)
        val refused = findReplaceRefusal(basis, matches, text)
        if (refused != null) return refused
        var outcome = FindReplaceResult.Invalid
        dispatch(if (all) "Replace All" else "Replace match", onCommitted = { outcome = FindReplaceResult.Applied }) {
            applyFoundReplacement(basis, matches, text, all)
        }
        return outcome
    }
    private fun findReplaceRefusal(basis: FindBasis, matches: List<FindMatch>, text: String): FindReplaceResult? =
        when {
            editingBusy() || canonicalProbeHistory != null -> FindReplaceResult.Busy
            !findBasisCurrent(basis) -> FindReplaceResult.Stale
            findMatchesInvalid(dc, matches) -> FindReplaceResult.Invalid
            replacedUnitCount(dc, matches, text) > DocumentLimits.MAX_UNITS -> FindReplaceResult.TooLarge
            matchesAlreadyReplaced(dc, matches, text) -> FindReplaceResult.NoChange
            else -> null
        }
    private fun findBasisCurrent(basis: FindBasis) =
        basis.owner === recoveryOwnerScope && basis.revision == recoveryBasisRevision && basis.document == dc
    private fun editingBusy() = busy || composition != null || pending.isNotEmpty()
    fun installCanonicalProbeHistory(handler: (Boolean) -> Unit) {
        check(ownerThreadToken() === owner && canonicalProbeHistory == null && composition == null)
        canonicalProbeHistory = handler // One-way for this probe session; never expose stale local roots again.
    }
    fun applyCanonicalProbe(
        basis: CanonicalProbeBasis,
        edits: List<CanonicalProbeEdit>,
        mappedSelection: Selection,
    ): CanonicalProbeOutcome {
        check(ownerThreadToken() === owner)
        val deferred = canonicalProbeDeferral(basis)
        if (deferred != null) return deferred
        var applied = false
        dispatch("Canonical probe transaction", onCommitted = { applied = true }) {
            applyCanonicalProbeEdits(edits, mappedSelection)
            // Accepted remote/authored inverses do not enter local root-snapshot Undo.
            // Existing local history is retained; all history requests use the installed port.
        }
        return if (applied) CanonicalProbeOutcome.Applied else CanonicalProbeOutcome.Rejected
    }
    private fun canonicalProbeDeferral(basis: CanonicalProbeBasis): CanonicalProbeOutcome? = when {
        busy -> CanonicalProbeOutcome.DeferredOwnerBusy
        composition != null || pending.isNotEmpty() -> CanonicalProbeOutcome.DeferredComposition
        canonicalProbeHistory == null -> CanonicalProbeOutcome.RequiresExternalHistory
        !canonicalProbeBasisCurrent(basis) -> CanonicalProbeOutcome.RejectedBasis
        else -> null
    }
    private fun canonicalProbeBasisCurrent(basis: CanonicalProbeBasis) = documentRevision == basis.documentRevision &&
        identityGeneration == basis.identityGeneration &&
        dc.units.map { it.id } == basis.unitIds
    internal fun visible() = composition?.let { c ->
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
                "segments" to c.segments.map {
                    mapOf(
                        "dcStart" to it.dcStart,
                        "dcEnd" to it.dcEnd,
                        "units" to it.units.size,
                        "origin" to it.origin.name,
                    )
                },
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
        if (editingBusy()) return RecoveryApplyResult.RecoveryBusy
        var result = RecoveryApplyResult.RecoveryRejected
        var staged = false
        dispatch("Apply recovered draft", onCommitted = { if (staged) result = RecoveryApplyResult.Applied }) {
            val ownerMatches = ownsRecoveryDraft(draft)
            if (!ownerMatches || !draftBasisCurrent(draft)) {
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
                applyDraftDocument(draft)
                staged = true
            }
        }
        return result
    }
    private fun draftBasisCurrent(draft: RecoveredDraft) = recoveryBasisRevision == draft.basisRevision &&
        identityGeneration == draft.basisIdentityGeneration &&
        dc == draft.basis
    private fun applyDraftDocument(draft: RecoveredDraft) {
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
    }
    internal fun units(
        text: String,
        breaks: Boolean = false,
        style: TextStyleFlags = effectiveTyping(),
        properties: ParagraphProperties = ParagraphProperties(),
    ): List<TextUnit> {
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
                followingParagraph = if (isBreak) {
                    properties.copy(pagination = properties.pagination.copy(breakBefore = false, keepWithNext = false))
                } else {
                    ParagraphProperties()
                },
                structuralBreak = if (isBreak && it == '\u000c')StructuralBreak.PAGE else StructuralBreak.PARAGRAPH,
            )
        }
    }
    internal fun saved() = Saved(visible(), selection, intent, side)
    private fun inherit(at: Int, from: TypingSide): TextStyleFlags {
        val u = visible().units
        val left = u.getOrNull(at - 1)?.takeIf { it.kind == UnitKind.TEXT }?.style
        val right = u.getOrNull(at)?.takeIf { it.kind == UnitKind.TEXT }?.style
        return (if (from == TypingSide.LEFT) left ?: right else right ?: left)
            ?: u.getOrNull(at - 1)?.takeIf { it.kind == UnitKind.PARAGRAPH_BREAK }?.paragraphStyle
            ?: visible().startStyle
    }
    internal fun effectiveTyping(): TextStyleFlags {
        val seed = intent ?: inherit(selection.focus, side)
        val u = visible().units
        val left = u.getOrNull(selection.focus - 1)?.style?.link
        val right = u.getOrNull(selection.focus)?.style?.link
        return if (left != null && left == right) seed.copy(link = left) else seed.copy(link = null)
    }
    internal fun replacementStyle(range: Selection): TextStyleFlags = if (range.collapsed) {
        effectiveTyping()
    } else {
        visible().units.subList(range.start, range.end).firstOrNull { it.kind == UnitKind.TEXT }?.style
            ?: effectiveTyping()
    }
    fun formatState(key: FormatKey): Boolean? {
        if (selection.collapsed) return effectiveTyping().get(key)
        val values = displayedStyles(visible())
            .filter { it.start < selection.end && it.end > selection.start }
            .map { it.style.get(key) }
            .distinct()
        return values.singleOrNull()
    }
    fun toggleFormat(key: FormatKey) {
        val value = formatState(key) != true
        perform(EditAction.Format(if (composition != null && selection.collapsed) null else capture(), key, value))
    }
    internal fun dispatch(label: String, onCommitted: (() -> Unit)? = null, block: () -> Unit) {
        check(ownerThreadToken() === owner) { "Editing must run on the owner thread" }
        dispatchQueue.addLast { runTransaction(label, onCommitted, block) }
        if (busy) return
        busy = true
        try {
            while (dispatchQueue.isNotEmpty()) dispatchQueue.removeFirst().invoke()
        } finally {
            busy = false
        }
    }

    // Reason: a dispatched edit is a transaction; any failure inside it, whatever its type, must
    // roll the owner back to the captured state and surface as a rejected edit.
    @Suppress("TooGenericExceptionCaught")
    private fun runTransaction(label: String, onCommitted: (() -> Unit)?, block: () -> Unit) {
        val before = visible()
        val checkpoint = EngineCheckpoint(this)
        try {
            block()
            check(selection.anchor in 0..visible().units.size && selection.focus in 0..visible().units.size)
            check(visible().units.map { it.id }.distinct().size == visible().units.size)
        } catch (e: Exception) {
            checkpoint.restore(this)
            notice = "Edit rejected: ${e.message}"
            runCatching { onEvent("Rejected $label: ${e.message}") }
            runCatching { onChange(snapshot()) }
            return
        }
        if (!before.same(visible())) documentRevision++
        if (checkpoint.dc != dc) recoveryBasisRevision++
        if (checkpoint.dc.units.map { it.id } != dc.units.map { it.id }) identityGeneration++
        sessionRevision++
        onCommitted?.invoke()
        runCatching { onEvent(label) }
        runCatching { onChange(snapshot()) }.onFailure { runCatching { onEvent("Observer failure: ${it.message}") } }
    }
    fun capture(range: Selection = selection): CapturedRange {
        val u = visible().units
        require(range.start >= 0 && range.end <= u.size)
        return CapturedRange(
            u.subList(range.start, range.end).map { it.id },
            u.getOrNull(range.start - 1)?.id,
            u.getOrNull(range.end)?.id,
            range.collapsed,
            range.anchor > range.focus,
        )
    }
    fun perform(action: EditAction) {
        dispatch(action::class.simpleName ?: "EditAction") {
            if (composition != null) {
                if (action == EditAction.Redo) {
                    notice = "Finish the current composition before Redo."
                    return@dispatch
                }
                if (pending.size >= MAX_PENDING_ACTIONS) {
                    notice = "Finish composing before another command."
                    return@dispatch
                }
                if (action == EditAction.Undo &&
                    (!composition!!.before.document.same(visible()) || !composition!!.before.document.same(dc))
                ) {
                    composition!!.undoSawDirty = true
                }
                if (action is EditAction.Move && pending.lastOrNull() is EditAction.Move) {
                    pending[pending.lastIndex] = action
                } else {
                    pending += action
                }
                notice = "Waiting for the input method to finish…"
                settlementTimedOut = false
                if (settlementRequestedAt == null) settlementRequestedAt = now()
            } else {
                applyAction(action)
            }
        }
        if (composition != null && pending.isNotEmpty()) onSettlementRequested()
    }
    fun checkSettlementWatchdog(timeoutMs: Long = 2_000L): Boolean {
        if (composition == null || pending.isEmpty()) {
            settlementRequestedAt = null
            return false
        }
        val req = settlementRequestedAt
        val expired = req != null && (now() - req) >= timeoutMs
        if (expired) {
            dispatch("Settlement watchdog timeout") {
                settlementTimedOut = true
                settlementRequestedAt = null
                pending.clear()
                notice = "Input method did not respond; pending commands cancelled. " +
                    "Press Escape or abort composition to recover."
            }
        }
        return expired
    }
    fun cancelPendingOnTeardown() {
        if (pending.isEmpty() && settlementRequestedAt == null && !settlementTimedOut) return
        dispatch("Surface teardown") {
            pending.clear()
            settlementRequestedAt = null
            settlementTimedOut = false
        }
    }
    fun replace(
        text: String,
        kind: String = "Typing",
        paragraphBreaks: Boolean = false,
        range: Selection = selection,
        deleteSide: TypingSide? = null,
    ) {
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
            visible().units.subList(range.start, range.end).firstOrNull { it.kind == UnitKind.TEXT }?.style
                ?: inherit(range.start, TypingSide.LEFT)
        }
        perform(EditAction.Replace(capture(range), text, kind, paragraphBreaks, seed, exactUtf16 = true))
    }
    fun move(
        range: Selection,
        primary: Boolean = range.primary,
        side: TypingSide = TypingSide.LEFT,
        wrapAffinity: WrapAffinity = range.wrapAffinity,
    ) = perform(EditAction.Move(capture(range), primary, side, wrapAffinity))
    fun setParagraphProperties(
        direction: ParagraphDirection? = null,
        alignment: ParagraphAlignment? = null,
        before: Int? = null,
        after: Int? = null,
        line: Int? = null,
    ) {
        if (listOf(direction, alignment, before, after, line).all { it == null }) return
        perform(
            EditAction.Paragraph(
                visible().targetParagraphs(selection).map { it.anchor },
                direction,
                alignment,
                before,
                after,
                line,
            ),
        )
    }
    fun paragraphDirectionState(): ParagraphDirection? =
        visible().targetParagraphs(selection).map { it.properties.direction }.distinct().singleOrNull()
    fun paragraphAlignmentState(): ParagraphAlignment? =
        visible().targetParagraphs(selection).map { it.properties.alignment }.distinct().singleOrNull()
    fun setTextStyle(patch: TextStylePatch) =
        perform(EditAction.TextAttributes(if (composition != null && selection.collapsed) null else capture(), patch))
    fun <T> textStyleState(value: (TextStyleFlags) -> T): T? = if (selection.collapsed) {
        value(effectiveTyping())
    } else {
        displayedStyles(visible())
            .filter { it.start < selection.end && it.end > selection.start }
            .map { value(it.style) }
            .distinct()
            .singleOrNull()
    }
    fun <T> paragraphState(value: (ParagraphProperties) -> T): T? =
        visible().targetParagraphs(selection).map { value(it.properties) }.distinct().singleOrNull()
    fun clipboardCapture(): ClipboardCapture? {
        if (editingBusy()) return null
        val stops = Graphemes.stops(dc.text)
        return if (selection.start in stops && selection.end in stops) {
            ClipboardCapture(captureFindBasis(), selection, sessionRevision)
        } else {
            null
        }
    }
    fun clipboardApply(
        c: ClipboardCapture,
        fragment: RichFragment?,
        plain: String? = null,
        cut: Boolean = false,
    ): Boolean {
        val current = !editingBusy() && canonicalProbeHistory == null && clipboardCaptureCurrent(c)
        val a = c.selection.start
        val b = c.selection.end
        return when {
            !current -> false
            cut && a == b -> false
            plain != null -> pastePlain(plain, cut, a, b)
            fragment == null -> false
            else -> pasteRich(fragment, a, b)
        }
    }
    private fun clipboardCaptureCurrent(c: ClipboardCapture) = c.basis.owner === recoveryOwnerScope &&
        c.basis.revision == recoveryBasisRevision &&
        c.basis.document == dc &&
        c.selection == selection &&
        c.sessionRevision == sessionRevision
    private fun pastePlain(plain: String, cut: Boolean, a: Int, b: Int): Boolean {
        val fits = dc.units.size.toLong() - (b - a) + plain.length <= DocumentLimits.MAX_UNITS
        if (fits) replace(plain, if (cut) "Cut" else "Paste plain", paragraphBreaks = true)
        return fits
    }
    fun structure(command: StructureCommand) =
        perform(EditAction.Structure(capture(), visible().targetParagraphs(selection).map { it.anchor }, command))
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
        val deleteSide = if (backward) TypingSide.LEFT else TypingSide.RIGHT
        if (!s.selection.collapsed) {
            replace("", "Delete", deleteSide = deleteSide)
            return
        }
        val block = s.visible.paragraphAt(s.selection.focus)
        val adjacent = if (backward)s.selection.focus - 1 else s.selection.focus
        when {
            backward && startsFirstItemParagraph(s, block) -> structure(StructureCommand.Outdent)
            s.visible.units.getOrNull(adjacent)?.kind == UnitKind.IMAGE -> move(Selection(adjacent, adjacent + 1))
            else -> {
                val at = s.selection.focus
                val stops = Graphemes.stops(s.visible.text)
                val range = if (backward) {
                    Selection(stops.lastOrNull { it < at } ?: at, at)
                } else {
                    Selection(at, stops.firstOrNull { it > at } ?: at)
                }
                replace("", "Delete", range = range, deleteSide = deleteSide)
            }
        }
    }
    private fun startsFirstItemParagraph(s: EditorFrame, block: ParagraphBlock) = block.properties.item.isNotEmpty() &&
        s.selection.focus == block.start &&
        s.visible.paragraphs().first { it.properties.item == block.properties.item }.anchor == block.anchor

    /** Strict normalized native retarget; raw UTF-16, never grapheme snapping. No platform clipping. */
    fun retargetComposition(range: Selection) {
        dispatch("Native retarget") { retargetOverlay(range) }
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
    fun nativeStructureAdmission(text: String): Boolean = admitsNativeText(text)
    fun inputMethod(text: String, committedCount: Int, caret: Int?) {
        require(committedCount in 0..text.length)
        require(caret == null || caret in 0..text.length - committedCount)
        val c = composition
        if (c != null && (c.markedStart != 0 || c.markedEnd != null)) {
            val raw = c.segments.flatMap { it.units }.joinToString("") { it.char.toString() }
            val end = c.markedEnd ?: raw.length
            val combined = raw.take(c.markedStart) + text + raw.substring(end)
            val accepted = if (committedCount == 0)0 else c.markedStart + committedCount
            applyInputMethod(
                combined,
                accepted,
                if (caret == null)null else c.markedStart + caret + committedCount - accepted,
                c.markedStart,
                c.markedStart + text.length,
            )
        } else {
            applyInputMethod(text, committedCount, caret, 0, text.length)
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

/** Local undo restores the root snapshot; an installed canonical probe history port handles it instead. */
internal fun EditorEngine.applyUndo(): Long? {
    val external = canonicalProbeHistory
    if (external != null) {
        external(false)
        notice = "External history requested"
    } else {
        undo.removeLastOrNull()?.let { h ->
            dc = h.before.document
            selection = h.before.selection
            intent = h.before.intent
            side = h.before.side
            redo += h
            typingBarrier = true
            notice = "Undone"
        }
    }
    return null
}

internal fun EditorEngine.applyRedo(): Long? {
    val external = canonicalProbeHistory
    if (external != null) {
        external(true)
        notice = "External history requested"
    } else {
        redo.removeLastOrNull()?.let { h ->
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
