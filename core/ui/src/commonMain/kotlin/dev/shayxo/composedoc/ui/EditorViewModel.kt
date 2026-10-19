package dev.shayxo.composedoc.ui

import androidx.lifecycle.ViewModel
import dev.shayxo.composedoc.domain.CapturedRange
import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.domain.FindBasis
import dev.shayxo.composedoc.domain.FindMatch
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.OutlineEntry
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.TypingSide
import dev.shayxo.composedoc.model.WrapAffinity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorUiState(val frame: EditorFrame)

/**
 * Every document mutation a UI or application can request. [R] is the synchronous
 * outcome the single-owner engine reports for that intent.
 */
sealed interface EditorIntent<out R> {
    data class Command(val action: EditAction) : EditorIntent<Unit>

    data class Select(
        val selection: Selection,
        val primary: Boolean = selection.primary,
        val side: TypingSide = TypingSide.LEFT,
        val wrapAffinity: WrapAffinity = selection.wrapAffinity,
    ) : EditorIntent<Unit>

    data object SelectAll : EditorIntent<Unit>

    /** Replaces [range], or the current selection when null, with typed or pasted text. */
    data class Type(
        val text: String,
        val kind: String = "Typing",
        val paragraphBreaks: Boolean = false,
        val range: Selection? = null,
        val deleteSide: TypingSide? = null,
    ) : EditorIntent<Unit>

    data class ReplaceExact(
        val text: String,
        val kind: String,
        val range: Selection,
        val paragraphBreaks: Boolean = false,
    ) : EditorIntent<Unit>

    data class Delete(val backward: Boolean) : EditorIntent<Unit>

    data class NativeText(val text: String, val committedCount: Int, val caret: Int?) : EditorIntent<Unit>

    data class NativeCaret(val offset: Int) : EditorIntent<Unit>

    data object AbortComposition : EditorIntent<Unit>

    data class ToggleFormat(val key: FormatKey) : EditorIntent<Unit>

    data class SetTextStyle(val patch: TextStylePatch) : EditorIntent<Unit>

    data class SetParagraphProperties(
        val direction: ParagraphDirection? = null,
        val alignment: ParagraphAlignment? = null,
        val before: Int? = null,
        val after: Int? = null,
        val line: Int? = null,
    ) : EditorIntent<Unit>

    data class Structure(val command: StructureCommand) : EditorIntent<Unit>

    data class ApplyStructure(val capture: ClipboardCapture, val command: StructureCommand) : EditorIntent<Boolean>

    data class ApplyClipboard(
        val capture: ClipboardCapture,
        val fragment: RichFragment?,
        val plain: String? = null,
        val cut: Boolean = false,
    ) : EditorIntent<Boolean>

    data class NavigateOutline(val anchor: Long?) : EditorIntent<Boolean>

    data class ReplaceFound(val basis: FindBasis, val matches: List<FindMatch>, val text: String, val all: Boolean) :
        EditorIntent<FindReplaceResult>

    data class ApplyRecoveredDraft(val draft: RecoveredDraft) : EditorIntent<RecoveryApplyResult>

    data class CheckSettlementWatchdog(val timeoutMs: Long = DEFAULT_SETTLEMENT_TIMEOUT_MS) : EditorIntent<Boolean>

    data object CancelPendingOnTeardown : EditorIntent<Unit>

    companion object {
        const val DEFAULT_SETTLEMENT_TIMEOUT_MS = 2_000L
    }
}

/**
 * Unidirectional data flow boundary: immutable [uiState] flows out, [EditorIntent]s
 * flow in through [dispatch], and read-only queries never mutate. The domain engine
 * stays the sole document owner and is not reachable from UI or application code
 * (enforced by `architectureCheck`).
 */
class EditorViewModel(private val engine: EditorEngine) : ViewModel() {
    constructor(document: Document = Document(emptyList())) : this(EditorEngine(document))

    private val mutableState = MutableStateFlow(EditorUiState(engine.snapshot()))
    val uiState: StateFlow<EditorUiState> = mutableState.asStateFlow()
    var onStatePublished: (EditorFrame) -> Unit = {}
    private val publish: (EditorFrame) -> Unit = { frame ->
        mutableState.value = EditorUiState(frame)
        onStatePublished(frame)
    }

    init {
        engine.onChange = publish
    }

    /** Qualification-harness access to the owner; UI and application code must dispatch intents. */
    val verificationEngine: EditorEngine get() = engine

    var onEngineEvent: (String) -> Unit
        get() = engine.onEvent
        set(value) {
            engine.onEvent = value
        }

    var onSettlementRequested: () -> Unit
        get() = engine.onSettlementRequested
        set(value) {
            engine.onSettlementRequested = value
        }

    @Suppress("UNCHECKED_CAST")
    fun <R> dispatch(intent: EditorIntent<R>): R = when (intent) {
        is EditorIntent.Command -> engine.perform(intent.action)
        is EditorIntent.Select -> engine.move(intent.selection, intent.primary, intent.side, intent.wrapAffinity)
        EditorIntent.SelectAll -> engine.selectAll()
        is EditorIntent.Type -> type(intent)
        is EditorIntent.ReplaceExact -> engine.replaceExact(
            intent.text,
            intent.kind,
            intent.range,
            intent.paragraphBreaks,
        )
        is EditorIntent.Delete -> engine.delete(intent.backward)
        is EditorIntent.NativeText -> engine.inputMethod(intent.text, intent.committedCount, intent.caret)
        is EditorIntent.NativeCaret -> engine.inputMethodCaret(intent.offset)
        EditorIntent.AbortComposition -> engine.abortComposition()
        is EditorIntent.ToggleFormat -> engine.toggleFormat(intent.key)
        is EditorIntent.SetTextStyle -> engine.setTextStyle(intent.patch)
        is EditorIntent.SetParagraphProperties ->
            engine.setParagraphProperties(intent.direction, intent.alignment, intent.before, intent.after, intent.line)
        is EditorIntent.Structure -> engine.structure(intent.command)
        is EditorIntent.ApplyStructure -> engine.applyStructure(intent.capture, intent.command)
        is EditorIntent.ApplyClipboard -> engine.clipboardApply(
            intent.capture,
            intent.fragment,
            intent.plain,
            intent.cut,
        )
        is EditorIntent.NavigateOutline -> engine.navigateOutline(intent.anchor)
        is EditorIntent.ReplaceFound -> engine.replaceFound(intent.basis, intent.matches, intent.text, intent.all)
        is EditorIntent.ApplyRecoveredDraft -> engine.applyRecoveredDraft(intent.draft)
        is EditorIntent.CheckSettlementWatchdog -> engine.checkSettlementWatchdog(intent.timeoutMs)
        EditorIntent.CancelPendingOnTeardown -> engine.cancelPendingOnTeardown()
    } as R

    private fun type(intent: EditorIntent.Type) {
        val range = intent.range
        if (range == null) {
            engine.replace(intent.text, intent.kind, intent.paragraphBreaks, deleteSide = intent.deleteSide)
        } else {
            engine.replace(intent.text, intent.kind, intent.paragraphBreaks, range, intent.deleteSide)
        }
    }

    // Read-only queries over the owner's current state.
    fun snapshot(): EditorFrame = engine.snapshot()

    fun clipboardCapture(): ClipboardCapture? = engine.clipboardCapture()

    fun capture(): CapturedRange = engine.capture()

    fun capture(range: Selection): CapturedRange = engine.capture(range)

    fun formatState(key: FormatKey): Boolean? = engine.formatState(key)

    fun paragraphDirectionState(): ParagraphDirection? = engine.paragraphDirectionState()

    fun paragraphAlignmentState(): ParagraphAlignment? = engine.paragraphAlignmentState()

    fun <T> paragraphState(value: (ParagraphProperties) -> T): T? = engine.paragraphState(value)

    fun <T> textStyleState(value: (TextStyleFlags) -> T): T? = engine.textStyleState(value)

    fun outline(): List<OutlineEntry> = engine.outline()

    fun nativeStructureAdmission(text: String): Boolean = engine.nativeStructureAdmission(text)

    fun compositionCaptureToken(): Any? = engine.compositionCaptureToken()

    fun recoveryCompositionEvidence(): String = engine.recoveryCompositionEvidence()

    fun captureRecoveryDraft(): RecoveredDraft? = engine.captureRecoveryDraft()

    fun captureFindBasis(): FindBasis = engine.captureFindBasis()

    val settlementTimedOut: Boolean get() = engine.settlementTimedOut

    fun detach() {
        if (engine.onChange === publish) engine.onChange = {}
        onStatePublished = {}
    }

    override fun onCleared() {
        detach()
    }
}
