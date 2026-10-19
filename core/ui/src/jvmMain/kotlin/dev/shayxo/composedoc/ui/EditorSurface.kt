@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.ui

import androidx.compose.runtime.key
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.layout.Geometry
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.io.File
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleEditableText
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.accessibility.AccessibleStateSet
import javax.accessibility.AccessibleText
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import javax.swing.Timer
enum class RecoveryStatus {
    ACTIVE_INITIAL,
    RECOVERED_QUALIFIED,
    CONSERVATIVE_ABORT_ONLY,
    DEGRADED_RECOVERY_FAILED,
}

data class RetainedCompositionPreimage(
    val draftText: String,
    val selectionBefore: Selection,
    val markedRangeBefore: IntRange?,
    val committedTextBefore: String,
    val epoch: Int,
    val recoveryDraft: RecoveredDraft? = null,
    // Runtime logical record identity; copies retain it.
    val recordKey: Any = Any(),
)

data class QuarantinedCallback(
    val text: String,
    val committed: Int,
    val caret: Int?,
    val epoch: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Diagnostic only: these callbacks have no verified document basis and cannot be replayed. */
fun quarantineDiagnostic(entries: List<QuarantinedCallback>): String = evidenceJson(
    mapOf(
        "kind" to "unverified-input-diagnostic",
        "warning" to "Not accepted document content. No replay or recovery basis.",
        "entries" to entries.map {
            mapOf(
                "utf16" to it.text.map { c -> c.code },
                "text" to it.text,
                "committedCharacterCount" to it.committed,
                "caret" to it.caret,
                "epoch" to it.epoch,
                "timestampMillis" to it.timestamp,
            )
        },
    ),
)

data class RecoveryReport(
    val epoch: Int,
    val replaceRequested: Boolean,
    val status: RecoveryStatus,
    val contextQualified: Boolean,
    val setupSucceeded: Boolean = contextQualified,
    val nativeQualified: Boolean = false,
    val admissionAllowed: Boolean = status == RecoveryStatus.RECOVERED_QUALIFIED && contextQualified,
    val documentAbortedCleanly: Boolean,
    val replacementCreated: Boolean,
    val containerAttached: Boolean,
    val removedDuringTeardown: Boolean,
    val failures: List<String>,
    val preservedLocale: java.util.Locale?,
    val preservedCompositionEnabled: Boolean?,
    val retainedPreimage: RetainedCompositionPreimage? = null,
) {
    val succeeded: Boolean
        get() = documentAbortedCleanly &&
            failures.isEmpty() &&
            if (replaceRequested && !removedDuringTeardown) {
                status == RecoveryStatus.RECOVERED_QUALIFIED &&
                    setupSucceeded &&
                    replacementCreated &&
                    containerAttached
            } else {
                status == RecoveryStatus.CONSERVATIVE_ABORT_ONLY && !setupSucceeded && !replacementCreated
            }
}

data class UnresolvedContextObligation(
    val context: java.awt.im.InputContext,
    val host: EditorSurface,
    var detached: Boolean = false,
    var disposed: Boolean = false,
)

class RecoverySession(initialPreimage: RetainedCompositionPreimage? = null) {
    val unresolvedContexts: MutableList<UnresolvedContextObligation> = java.util.concurrent.CopyOnWriteArrayList()
    val quarantinedCallbacks: MutableList<QuarantinedCallback> = java.util.concurrent.CopyOnWriteArrayList()
    private val retained = LinkedHashMap<Any, RetainedCompositionPreimage>()
    private var selected: Any? = null
    init {
        if (initialPreimage != null) {
            retained[initialPreimage.recordKey] = initialPreimage
            selected = initialPreimage.recordKey
        }
    }
    var retainedPreimage: RetainedCompositionPreimage?
        @Synchronized get() = selected?.let { retained[it] }

        @Synchronized set(value) {
            if (value == null) {
                retainedPreimage?.let { resolveRetainedDraft(it) }
                return
            }
            retained[value.recordKey] = value
            selected = value.recordKey
        }

    @Synchronized fun snapshotRetainedDrafts(): List<RetainedCompositionPreimage> = retained.values.toList()

    @Synchronized fun selectRetainedDraft(value: RetainedCompositionPreimage): Boolean {
        if (!retained.containsKey(value.recordKey)) return false
        selected = value.recordKey
        return true
    }

    @Synchronized fun resolveRetainedDraft(value: RetainedCompositionPreimage): Boolean {
        if (retained[value.recordKey] !== value) return false
        retained.remove(value.recordKey)
        if (selected === value.recordKey) selected = retained.keys.lastOrNull()
        return true
    }

    @Synchronized fun discardAllRetainedDrafts() {
        retained.clear()
        selected = null
    }
    fun mergeRetainedDrafts(other: RecoverySession) {
        if (other === this) return
        val incoming = other.snapshotRetainedDrafts()
        val incomingSelection = other.retainedPreimage?.recordKey
        synchronized(this) {
            incoming.forEach { retained.putIfAbsent(it.recordKey, it) }
            if (selected == null) {
                selected = incomingSelection?.takeIf { retained.containsKey(it) } ?: retained.keys.lastOrNull()
            }
        }
    }
    var isolationFenceEstablished: Boolean = false

    val hasUnresolvedObligations: Boolean
        get() = unresolvedContexts.isNotEmpty() && !isolationFenceEstablished
}

internal val NOOP_UNIT: () -> Unit = {}
internal val NOOP_BOOL: (Boolean) -> Unit = {}
internal val NOOP_FRAME: (EditorFrame) -> Unit = {}
internal val NOOP_SURFACE: (EditorSurface) -> Unit = {}

/** Caret blink and settlement-watchdog period. */
private const val BLINK_INTERVAL_MS = 500

/** The transparent AWT root owns input; the Compose surface underneath only renders. */
// Reason (LongParameterList): public constructor; grouping its parameters would change the published API.
// Reason (TooManyFunctions): every remaining non-private function is published API (see apiCheck).
@Suppress("LongParameterList", "TooManyFunctions")
open class EditorSurface @JvmOverloads constructor(
    val viewModel: EditorViewModel,
    val view: ViewState,
    val diagnostics: File? = null,
    val trace: ImeTrace? = null,
    var recoveryHooks: RecoveryHooks = RecoveryHooks(),
    var recoverySession: RecoverySession = RecoverySession(),
    val services: EditorPlatformServices = EditorPlatformServices.unavailable,
) : JComponent(),
    InputMethodRequests,
    javax.accessibility.Accessible {

    data class RecoveryHooks(
        val endComposition: (java.awt.im.InputContext) -> Unit = { it.endComposition() },
        val removeNotifyContext: (java.awt.im.InputContext, Component) -> Unit = { ctx, comp ->
            ctx.removeNotify(comp)
        },
        val disposeContext: (java.awt.im.InputContext) -> Unit = { it.dispose() },
        val disableInputMethods: (EditorSurface) -> Unit = { it.enableInputMethods(false) },
        val createInputContext: () -> java.awt.im.InputContext? = { java.awt.im.InputContext.getInstance() },
        val createReplacement: (EditorSurface) -> EditorSurface = { prev ->
            EditorSurface(
                prev.viewModel,
                prev.view,
                prev.diagnostics,
                prev.trace,
                prev.recoveryHooks,
                prev.recoverySession,
                prev.services,
            )
        },
        val attachReplacement: (
            Container,
            EditorSurface,
            EditorSurface,
        ) -> Unit = { parentContainer, oldHost, newHost ->
            if (parentContainer is JLayeredPane) {
                val layer = JLayeredPane.getLayer(oldHost)
                parentContainer.remove(oldHost)
                parentContainer.add(newHost)
                parentContainer.setLayer(newHost, layer)
            } else {
                val idx = parentContainer.components.indexOf(oldHost)
                parentContainer.remove(oldHost)
                if (idx >= 0) parentContainer.add(newHost, idx) else parentContainer.add(newHost)
            }
            parentContainer.revalidate()
            parentContainer.repaint()
        },
    )

    data class CounterSnapshot(
        val hostsCreated: Int,
        val hostsRetired: Int,
        val hostsDisposed: Int,
        val timersStarted: Int,
        val timersStopped: Int,
        val inputContextsCreated: Int,
        val inputContextsDetached: Int,
        val inputContextsDisposed: Int,
        val recoveryAttempts: Int,
        val recoverySucceededCount: Int,
        val recoveryConservativeCount: Int,
        val recoveryDegradedCount: Int,
        val teardownFailuresCount: Int,
    ) {
        val activeHosts: Int get() = hostsCreated - hostsDisposed
        val activeTimers: Int get() = timersStarted - timersStopped
        val activeInputContexts: Int get() = inputContextsCreated - inputContextsDisposed
    }

    object LifecycleCounters {
        val hostsCreated = java.util.concurrent.atomic.AtomicInteger(0)
        val hostsRetired = java.util.concurrent.atomic.AtomicInteger(0)
        val hostsDisposed = java.util.concurrent.atomic.AtomicInteger(0)
        val timersStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val timersStopped = java.util.concurrent.atomic.AtomicInteger(0)
        val inputContextsCreated = java.util.concurrent.atomic.AtomicInteger(0)
        val inputContextsDetached = java.util.concurrent.atomic.AtomicInteger(0)
        val inputContextsDisposed = java.util.concurrent.atomic.AtomicInteger(0)
        val recoveryAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val recoverySucceededCount = java.util.concurrent.atomic.AtomicInteger(0)
        val recoveryConservativeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val recoveryDegradedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val teardownFailuresCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun snapshot(): CounterSnapshot = CounterSnapshot(
            hostsCreated = hostsCreated.get(),
            hostsRetired = hostsRetired.get(),
            hostsDisposed = hostsDisposed.get(),
            timersStarted = timersStarted.get(),
            timersStopped = timersStopped.get(),
            inputContextsCreated = inputContextsCreated.get(),
            inputContextsDetached = inputContextsDetached.get(),
            inputContextsDisposed = inputContextsDisposed.get(),
            recoveryAttempts = recoveryAttempts.get(),
            recoverySucceededCount = recoverySucceededCount.get(),
            recoveryConservativeCount = recoveryConservativeCount.get(),
            recoveryDegradedCount = recoveryDegradedCount.get(),
            teardownFailuresCount = teardownFailuresCount.get(),
        )

        fun reset() {
            hostsCreated.set(0)
            hostsRetired.set(0)
            hostsDisposed.set(0)
            timersStarted.set(0)
            timersStopped.set(0)
            inputContextsCreated.set(0)
            inputContextsDetached.set(0)
            inputContextsDisposed.set(0)
            recoveryAttempts.set(0)
            recoverySucceededCount.set(0)
            recoveryConservativeCount.set(0)
            recoveryDegradedCount.set(0)
            teardownFailuresCount.set(0)
        }
    }

    @Volatile internal var published = viewModel.snapshot()
    var imageResize: ImageResizeGesture? = null
        internal set
    internal var imageResizeX = 0
    internal var imageResizeView: List<Any> = emptyList()
    internal fun resizeViewKey() = listOf<Any>(view.zoom, view.paged, width, height, view.scroll, view.typography.key)
    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        paintImageHandles(g)
    }
    internal var dragAnchor = 0
    internal var preferredX: Float? = null
    internal var keyEvents = 0
    internal var imeEvents = 0
    var latestRecoveryProposal: QuarantinedCallback? = null
        internal set
    val observedImeTextCallbacks: Int get() = imeEvents
    val observedTypedKeyCallbacks: Int get() = keyEvents
    internal var hasCountedRetire = false
    internal var hasCountedDispose = false
    internal var hasCountedTimerStart = false
    internal var hasCountedTimerStop = false
    internal var hasCountedContextCreation = false
    internal var hasCountedContextDetach = false
    internal var hasCountedContextDispose = false
    internal var contextBindFailure: String? = null
    internal var previousRetiredHost: EditorSurface? = null
    internal var ownInputContext: java.awt.im.InputContext? = null

    var activeImeEpoch = 0
        internal set
    var retired = false
        internal set
    var disposed = false
        internal set
    var contextQualified = false
        internal set
    var setupSucceeded = false
        internal set
    var nativeQualified = false
        internal set
    var admissionAllowed = false
        internal set
    internal var structuralInputPaused = false
    var recoveryStatus: RecoveryStatus = RecoveryStatus.ACTIVE_INITIAL
        internal set
    val recoveryFailures: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val quarantinedCallbacks: MutableList<QuarantinedCallback>
        get() = recoverySession.quarantinedCallbacks
    var retainedPreimage: RetainedCompositionPreimage?
        get() = recoverySession.retainedPreimage
        set(value) {
            recoverySession.retainedPreimage = value
        }
    var unresolvedContext: java.awt.im.InputContext?
        get() = recoverySession.unresolvedContexts.firstOrNull { it.host === this }?.context
            ?: recoverySession.unresolvedContexts.firstOrNull()?.context
        set(value) {
            if (value == null) {
                recoverySession.unresolvedContexts.removeIf { it.host === this }
            } else {
                if (!recoverySession.unresolvedContexts.any { it.context === value }) {
                    recoverySession.unresolvedContexts.add(
                        UnresolvedContextObligation(value, this, hasCountedContextDetach, hasCountedContextDispose),
                    )
                }
            }
        }
    var lastRecoveryReport: RecoveryReport? = null
        internal set
    var preferredLocale: java.util.Locale? = null
        internal set
    var preferredCompositionEnabled: Boolean? = null
        internal set
    var lastLocaleTransferSupported: Boolean? = null
        internal set
    var lastCompositionTransferSupported: Boolean? = null
        internal set
    var replacementSurface: EditorSurface? = null
        internal set
    var onHostReplaced: (EditorSurface) -> Unit = NOOP_SURFACE

    internal val blinkTimer = Timer(BLINK_INTERVAL_MS) {
        if (!retired && !disposed) {
            if (view.focused) view.blink = !view.blink
            viewModel.dispatch(EditorIntent.CheckSettlementWatchdog())
        }
    }

    val isTimerRunning: Boolean get() = blinkTimer.isRunning
    val hasRetainedCallbacks: Boolean
        get() = blinkTimer.actionListeners.isNotEmpty() ||
            onEndCompositionRequested !== NOOP_UNIT ||
            statusChanged !== NOOP_FRAME ||
            requestSave !== NOOP_BOOL ||
            requestOpen !== NOOP_UNIT ||
            onHostReplaced !== NOOP_SURFACE

    var statusChanged: (EditorFrame) -> Unit = NOOP_FRAME
    var requestSave: (Boolean) -> Unit = NOOP_BOOL
    var requestOpen: () -> Unit = NOOP_UNIT
    var onEndCompositionRequested: () -> Unit = NOOP_UNIT

    override fun getInputContext(): java.awt.im.InputContext? = if (retired || disposed) null else ownInputContext

    fun selectInputLocale(locale: java.util.Locale, compositionEnabled: Boolean? = null): Boolean =
        transferInputLocale(locale, compositionEnabled)

    fun retryCleanup(): Boolean = retryContextCleanup()

    // Reason: public API (see apiCheck); the parameter is part of the published signature.
    @Suppress("UnusedParameter")
    fun recoverExplicitly(admitQuarantined: Boolean = false): Boolean = recoverHostExplicitly()

    var recoveryCaptureFailure: String? = null
        internal set
    fun pauseForRecoveryCapture(message: String) {
        recoveryCaptureFailure = message
        admissionAllowed = false
        contextQualified = false
        recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
        recordDiagnosticEvent(message)
        statusChanged(viewModel.snapshot())
    }
    fun inspectRetainedDraft(): String? = retainedPreimage?.draftText
    fun selectRetainedDraft(record: RetainedCompositionPreimage): Boolean {
        if (!recoverySession.selectRetainedDraft(record)) return false
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
        return true
    }
    fun retainedDraftDocument(): Document? = retainedPreimage?.recoveryDraft?.document
    fun exportRetainedDocument(file: File): Boolean {
        val document = retainedDraftDocument() ?: return false
        services.documents.save(file.path, document)
        return true
    }

    fun exportRetainedDraft(file: File): Boolean {
        val draft = retainedPreimage?.draftText ?: return false
        file.parentFile?.mkdirs()
        file.writeText(draft, Charsets.UTF_8)
        return true
    }

    fun applyRecoveredDraft(): Boolean {
        val preimage = retainedPreimage
        val draft = preimage?.recoveryDraft
        if (preimage == null || draft == null) return false
        val result = viewModel.dispatch(EditorIntent.ApplyRecoveredDraft(draft))
        if (result == RecoveryApplyResult.Applied) recoverySession.resolveRetainedDraft(preimage)
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
        return result == RecoveryApplyResult.Applied
    }

    fun discardRecoveredDraft() {
        recoverySession.retainedPreimage = null
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
    }

    fun discardQuarantinedCallbacks(confirmed: List<QuarantinedCallback> = quarantinedCallbacks.toList()) {
        quarantinedCallbacks.removeIf { current -> confirmed.any { it === current } }
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
    }

    fun discardRecoverySession() {
        recoverySession.discardAllRetainedDrafts()
        quarantinedCallbacks.clear()
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
    }

    fun establishSafeIsolationFence() {
        recoverySession.isolationFenceEstablished = true
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
    }

    fun retryRecovery(): EditorSurface? {
        retryCleanup()
        return abortAndRetireComposition(replaceHost = true)
    }

    fun requestEndComposition() {
        if (retired || disposed) return
        val failures = ArrayList<String>()
        runStep("onEndCompositionRequested", failures) { onEndCompositionRequested() }
        val ctx = ownInputContext
        if (ctx != null) {
            runStep("endComposition", failures) { recoveryHooks.endComposition(ctx) }
        }
    }

    fun abortCompositionConservatively(): RecoveryReport {
        abortAndRetireComposition(replaceHost = false)
        return checkNotNull(lastRecoveryReport)
    }

    fun abortAndRetireComposition() {
        abortAndRetireComposition(replaceHost = true)
    }

    fun abortAndRetireComposition(replaceHost: Boolean): EditorSurface? = retireAndRecover(replaceHost)

    fun dispatchInputMethodEventWithEpoch(e: InputMethodEvent, epoch: Int) {
        if (retired || disposed || epoch != activeImeEpoch) {
            e.consume()
            return
        }
        processInputMethodEvent(e)
    }

    init {
        LifecycleCounters.hostsCreated.incrementAndGet()
        isOpaque = false
        isFocusable = true
        focusTraversalKeysEnabled = false
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        enableInputMethods(true)
        ownInputContext = bindInputContext()
        if (ownInputContext == null && contextBindFailure == null) {
            contextBindFailure = "InputContext.getInstance() returned null"
        }
        val initialSetupOk = ownInputContext != null && contextBindFailure == null
        setupSucceeded = initialSetupOk
        contextQualified = initialSetupOk
        nativeQualified = false // synthetic / default: real native qualification remains NOT RUN
        admissionAllowed = initialSetupOk
        if (initialSetupOk) {
            recoveryStatus = RecoveryStatus.ACTIVE_INITIAL
        } else {
            recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
            val desc = "contextBinding: $contextBindFailure"
            recoveryFailures.add(desc)
            LifecycleCounters.teardownFailuresCount.incrementAndGet()
            runCatching { recoveryHooks.disableInputMethods(this) }
            recordDiagnosticEvent("Initial InputContext binding failed [$desc]")
        }
        bindViewModelCallbacks()
        installInputListeners()
        blinkTimer.start()
        hasCountedTimerStart = true
        LifecycleCounters.timersStarted.incrementAndGet()
    }

    override fun removeNotify() {
        releaseOnRemoveNotify()
        super.removeNotify()
    }

    public override fun processFocusEvent(e: FocusEvent) {
        if (retired || disposed) return
        super.processFocusEvent(e)
    }
    public override fun processInputMethodEvent(e: InputMethodEvent) {
        if (retired || disposed) {
            trace?.record("awt-ime-ignored", "id=${e.id}; retired=$retired; disposed=$disposed", viewModel.snapshot())
            e.consume()
            return
        }
        super.processInputMethodEvent(e)
    }
    public override fun processKeyEvent(e: KeyEvent) {
        if (retired || disposed) {
            e.consume()
            return
        }
        super.processKeyEvent(e)
    }
    fun geometry(): Geometry? {
        if (width > 0 && height > 0) {
            view.hostWidth = width
            view.hostHeight = height
        }
        val cached = view.geometry
        val presentationKey =
            view.typography.key + ":zoom=${view.zoom}" + (if (view.paged) ":paged" else ":continuous")
        return if (cached != null && isCurrentGeometry(cached, presentationKey)) {
            cached
        } else {
            measureGeometry(presentationKey)
        }
    }
    var clipboardMessage = ""
        internal set
    fun copySelection(cut: Boolean) = copySelectionToClipboard(cut)
    var clipboardPasteBusy = false
        internal set
    var clipboardReaderForChecks: ((Boolean) -> RichClipboard.Read)? = null

    @JvmOverloads fun paste(plainOnly: Boolean = false) = pasteFromClipboard(plainOnly)
    override fun getInputMethodRequests(): InputMethodRequests = this
    override fun getCommittedText(
        beginIndex: Int,
        endIndex: Int,
        attributes: Array<out AttributedCharacterIterator.Attribute>?,
    ): AttributedCharacterIterator {
        val text = committedContext()
        require(beginIndex in 0..endIndex && endIndex <= text.length)
        return AttributedString(text.substring(beginIndex, endIndex)).iterator
    }
    private fun committedContext(): String =
        published.marked?.let { published.visible.text.removeRange(it.first, it.last + 1) } ?: published.visible.text
    override fun getCommittedTextLength() = committedContext().length
    override fun getInsertPositionOffset() = published.marked?.first ?: published.selection.start
    override fun getSelectedText(attributes: Array<out AttributedCharacterIterator.Attribute>?) =
        AttributedString(published.visible.text.substring(published.selection.start, published.selection.end)).iterator
    override fun cancelLatestCommittedText(
        attributes: Array<out AttributedCharacterIterator.Attribute>?,
    ): AttributedCharacterIterator? = null
    override fun getTextLocation(offset: TextHitInfo?): Rectangle? = compositionTextLocation(offset)
    override fun getLocationOffset(x: Int, y: Int): TextHitInfo? = compositionLocationOffset(x, y)
    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) accessibleContext = AccessibleEditor()
        return accessibleContext
    }
    fun changeTypography(profile: TypographyProfile) {
        check(SwingUtilities.isEventDispatchThread())
        check(!retired && !disposed) { "Retired typography surface" }
        val previous = view.typography
        if (previous == profile) return
        view.changeTypography(profile)
        fireTypographyAttributeChanges(previous, profile)
        repaint()
    }
    protected inner class AccessibleEditor :
        AccessibleJComponent(),
        AccessibleEditableText {
        private fun <T> onOwner(block: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return block()
            var value: Result<T>? = null
            SwingUtilities.invokeAndWait { value = runCatching(block) }
            return checkNotNull(value).getOrThrow()
        }
        private fun checkEditable() {
            check(!retired && !disposed && admissionAllowed) {
                "Editor input is paused; accessibility edits were not admitted"
            }
        }
        private fun <T> edit(requireAdmission: Boolean = true, block: () -> T): T {
            val basis = published
            return onOwner {
                if (requireAdmission) {
                    checkEditable()
                } else {
                    check(!retired && !disposed) {
                        "Retired accessibility surface"
                    }
                }
                check(published.documentRevision == basis.documentRevision && published.visible == basis.visible) {
                    "AccessibilityBaseChanged: document changed before the queued request was admitted"
                }
                block()
            }
        }
        private fun range(start: Int, end: Int): Selection {
            val size = published.visible.units.size
            require(start in 0..size && end in 0..size) { "Accessibility range is outside its current document basis" }
            return Selection(start, end)
        }
        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.TEXT
        override fun getAccessibleName(): String = "Document editor"
        override fun getAccessibleDescription(): String = "Editable document. Type text; use Control Z to undo."
        override fun getAccessibleText(): AccessibleText = this
        override fun getAccessibleEditableText(): AccessibleEditableText = this
        override fun getAccessibleStateSet(): AccessibleStateSet = super.getAccessibleStateSet().apply {
            add(AccessibleState.MULTI_LINE)
            if (!retired &&
                !disposed &&
                admissionAllowed
            ) {
                add(AccessibleState.EDITABLE)
            } else {
                remove(AccessibleState.EDITABLE)
            }
        }
        override fun getTextRange(startIndex: Int, endIndex: Int): String = onOwner {
            require(startIndex <= endIndex)
            range(startIndex, endIndex)
            published.visible.text.substring(startIndex, endIndex)
        }
        override fun setTextContents(text: String) = edit {
            viewModel.dispatch(
                EditorIntent.ReplaceExact(text, "AccessibilityReplaceAll", Selection(0, published.visible.units.size)),
            )
        }
        override fun insertTextAtIndex(index: Int, text: String) = replaceText(index, index, text)
        override fun replaceText(startIndex: Int, endIndex: Int, text: String) = edit {
            viewModel.dispatch(EditorIntent.ReplaceExact(text, "AccessibilityReplace", range(startIndex, endIndex)))
        }
        override fun delete(startIndex: Int, endIndex: Int) = replaceText(startIndex, endIndex, "")
        override fun selectText(startIndex: Int, endIndex: Int) = edit(requireAdmission = false) {
            viewModel.dispatch(EditorIntent.Select(range(startIndex, endIndex)))
        }
        override fun setAttributes(startIndex: Int, endIndex: Int, attributes: javax.swing.text.AttributeSet) {
            val capturedAttributes = attributes.copyAttributes()
            edit {
                applyAccessibleAttributes(range(startIndex, endIndex), capturedAttributes)
            }
        }
        override fun cut(startIndex: Int, endIndex: Int) = edit {
            check(published.marked == null && !published.waiting) {
                "Finish native composition before clipboard commands"
            }
            viewModel.dispatch(EditorIntent.Select(range(startIndex, endIndex)))
            copySelection(true)
        }
        override fun paste(startIndex: Int) = edit {
            check(published.marked == null && !published.waiting) {
                "Finish native composition before clipboard commands"
            }
            viewModel.dispatch(EditorIntent.Select(range(startIndex, startIndex)))
            this@EditorSurface.paste()
        }
        override fun getIndexAtPoint(p: Point): Int = onOwner { hit(p) ?: -1 }
        override fun getCharacterBounds(i: Int): Rectangle? = onOwner { accessibleCharacterBounds(i) }
        override fun getCharCount() = published.visible.text.length
        override fun getCaretPosition() = published.selection.focus
        override fun getSelectionStart() = published.selection.start
        override fun getSelectionEnd() = published.selection.end
        override fun getSelectedText(): String? = published.let {
            it.visible.text.substring(it.selection.start, it.selection.end).takeIf { text -> text.isNotEmpty() }
        }
        override fun getCharacterAttribute(i: Int): javax.swing.text.AttributeSet? = onOwner {
            val unit = published.visible.units.getOrNull(i) ?: return@onOwner null
            val style = if (unit.kind == UnitKind.PARAGRAPH_BREAK) unit.paragraphStyle else unit.style
            paragraphAccessibleStyle(published.visible, i)
        }
        override fun getAtIndex(part: Int, index: Int): String? =
            accessibleSegment(published.visible.text, part, index, 0)
        override fun getAfterIndex(part: Int, index: Int): String? =
            accessibleSegment(published.visible.text, part, index, 1)
        override fun getBeforeIndex(part: Int, index: Int): String? =
            accessibleSegment(published.visible.text, part, index, -1)
    }
    fun writeDiagnostics() {
        val f = diagnostics ?: return
        f.parentFile.mkdirs()
        val s = viewModel.snapshot()
        published = s
        f.writeText(diagnosticsJson(s))
    }
}
