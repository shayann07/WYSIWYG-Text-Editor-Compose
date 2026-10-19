@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.io.File
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import java.text.BreakIterator
import javax.accessibility.*
import javax.swing.*
import kotlin.math.*

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
                status == RecoveryStatus.RECOVERED_QUALIFIED && setupSucceeded && replacementCreated && containerAttached
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

class RecoverySession(
    initialPreimage: RetainedCompositionPreimage? = null,
) {
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
            if (selected == null) selected = incomingSelection?.takeIf { retained.containsKey(it) } ?: retained.keys.lastOrNull()
        }
    }
    var isolationFenceEstablished: Boolean = false

    val hasUnresolvedObligations: Boolean
        get() = unresolvedContexts.isNotEmpty() && !isolationFenceEstablished
}

private val NOOP_UNIT: () -> Unit = {}
private val NOOP_BOOL: (Boolean) -> Unit = {}
private val NOOP_FRAME: (EditorFrame) -> Unit = {}
private val NOOP_SURFACE: (EditorSurface) -> Unit = {}

/** The transparent AWT root owns input; the Compose surface underneath only renders. */
open class EditorSurface @JvmOverloads constructor(
    val engine: EditorEngine,
    val view: ViewState,
    val diagnostics: File? = null,
    val trace: ImeTrace? = null,
    var recoveryHooks: RecoveryHooks = RecoveryHooks(),
    var recoverySession: RecoverySession = RecoverySession(),
    val services: EditorPlatformServices = EditorPlatformServices.unavailable,
    val viewModel: EditorViewModel = EditorViewModel(engine),
) : JComponent(),
    InputMethodRequests,
    javax.accessibility.Accessible {

    data class RecoveryHooks(
        val endComposition: (java.awt.im.InputContext) -> Unit = { it.endComposition() },
        val removeNotifyContext: (java.awt.im.InputContext, Component) -> Unit = { ctx, comp -> ctx.removeNotify(comp) },
        val disposeContext: (java.awt.im.InputContext) -> Unit = { it.dispose() },
        val disableInputMethods: (EditorSurface) -> Unit = { it.enableInputMethods(false) },
        val createInputContext: () -> java.awt.im.InputContext? = { java.awt.im.InputContext.getInstance() },
        val createReplacement: (EditorSurface) -> EditorSurface = { prev ->
            EditorSurface(prev.engine, prev.view, prev.diagnostics, prev.trace, prev.recoveryHooks, prev.recoverySession, prev.services, prev.viewModel)
        },
        val attachReplacement: (Container, EditorSurface, EditorSurface) -> Unit = { parentContainer, oldHost, newHost ->
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

    @Volatile private var published = engine.snapshot()
    var imageResize: ImageResizeGesture? = null
        private set
    private var imageResizeX = 0
    private var imageResizeView: List<Any> = emptyList()
    private fun resizeViewKey() = listOf<Any>(view.zoom, view.paged, width, height, view.scroll, view.typography.key)
    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        val h = imageHandles(this) ?: return
        g.color = Color(0x174ea6)
        g.fillRect(h.left.x, h.left.y, h.left.width, h.left.height)
        g.fillRect(h.right.x, h.right.y, h.right.width, h.right.height)
        imageResize?.let { drag ->
            val n = drag.width(imageResizeX)
            val w = (h.bounds.width * n.toFloat() / drag.image.width).toInt()
            val hh = (h.bounds.height * n.toFloat() / drag.image.width).toInt()
            val x = if (drag.rightHandle)h.bounds.x else h.bounds.x + h.bounds.width - w
            g.drawRect(x, h.bounds.y, w, hh)
            g.drawString("Width $n dp · Escape cancels", x.coerceAtLeast(0), (h.bounds.y - 8).coerceAtLeast(16))
        }
    }
    private var dragAnchor = 0
    private var preferredX: Float? = null
    private var keyEvents = 0
    private var imeEvents = 0
    var latestRecoveryProposal: QuarantinedCallback? = null
        private set
    val observedImeTextCallbacks: Int get() = imeEvents
    val observedTypedKeyCallbacks: Int get() = keyEvents
    private var hasCountedRetire = false
    private var hasCountedDispose = false
    private var hasCountedTimerStart = false
    private var hasCountedTimerStop = false
    private var hasCountedContextCreation = false
    private var hasCountedContextDetach = false
    private var hasCountedContextDispose = false
    private var contextBindFailure: String? = null
    private var previousRetiredHost: EditorSurface? = null
    private var ownInputContext: java.awt.im.InputContext? = null

    var activeImeEpoch = 0
        private set
    var retired = false
        private set
    var disposed = false
        private set
    var contextQualified = false
        private set
    var setupSucceeded = false
        private set
    var nativeQualified = false
        private set
    var admissionAllowed = false
        private set
    private var structuralInputPaused = false
    var recoveryStatus: RecoveryStatus = RecoveryStatus.ACTIVE_INITIAL
        private set
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
        private set
    var preferredLocale: java.util.Locale? = null
        private set
    var preferredCompositionEnabled: Boolean? = null
        private set
    var lastLocaleTransferSupported: Boolean? = null
        private set
    var lastCompositionTransferSupported: Boolean? = null
        private set
    var replacementSurface: EditorSurface? = null
        private set
    var onHostReplaced: (EditorSurface) -> Unit = NOOP_SURFACE

    private val blinkTimer = Timer(500) {
        if (!retired && !disposed) {
            if (view.focused) view.blink = !view.blink
            engine.checkSettlementWatchdog()
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

    private fun recordDiagnosticEvent(message: String) {
        runCatching { engine.onEvent(message) }
        trace?.record("recovery", message, engine.snapshot())
    }

    private fun recordStepFailure(stepName: String, failure: Throwable, failures: MutableList<String>) {
        val desc = "$stepName: ${failure.javaClass.simpleName}: ${failure.message ?: "failed"}"
        failures.add(desc)
        recoveryFailures.add(desc)
        LifecycleCounters.teardownFailuresCount.incrementAndGet()
        recordDiagnosticEvent("IME recovery failure [$desc]")
    }

    private inline fun runStep(stepName: String, failures: MutableList<String>, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        recordStepFailure(stepName, t, failures)
        false
    }

    private fun stopBlinkTimer() {
        blinkTimer.stop()
        for (listener in blinkTimer.actionListeners) {
            blinkTimer.removeActionListener(listener)
        }
        if (hasCountedTimerStart && !hasCountedTimerStop) {
            hasCountedTimerStop = true
            LifecycleCounters.timersStopped.incrementAndGet()
        }
    }

    private fun markDisposed() {
        disposed = true
        if (!hasCountedDispose) {
            hasCountedDispose = true
            LifecycleCounters.hostsDisposed.incrementAndGet()
        }
    }

    private fun clearCallbacks() {
        previousRetiredHost?.replacementSurface = null
        previousRetiredHost = null
        onEndCompositionRequested = NOOP_UNIT
        statusChanged = NOOP_FRAME
        requestSave = NOOP_BOOL
        requestOpen = NOOP_UNIT
        onHostReplaced = NOOP_SURFACE
    }

    private fun cleanupFailedAttachment(failures: MutableList<String>) {
        val oldContext = ownInputContext
        retired = true
        if (!hasCountedRetire) {
            hasCountedRetire = true
            LifecycleCounters.hostsRetired.incrementAndGet()
        }
        ownInputContext = null
        stopBlinkTimer()
        if (oldContext != null) {
            unresolvedContext = oldContext
            val detachOk = runStep("cleanupRemoveNotifyContext", failures) {
                recoveryHooks.removeNotifyContext(oldContext, this)
                if (!hasCountedContextDetach) {
                    hasCountedContextDetach = true
                    LifecycleCounters.inputContextsDetached.incrementAndGet()
                }
            }
            if (detachOk) {
                recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
                val disposeOk = runStep("cleanupDisposeContext", failures) {
                    recoveryHooks.disposeContext(oldContext)
                    if (!hasCountedContextDispose) {
                        hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                }
                if (disposeOk || hasCountedContextDispose) {
                    unresolvedContext = null
                }
            }
        }
        runStep("cleanupDisableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }
        clearCallbacks()
        markDisposed()
        contextQualified = false
        recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
    }

    fun selectInputLocale(locale: java.util.Locale, compositionEnabled: Boolean? = null): Boolean {
        if (retired || disposed) return false
        preferredLocale = locale
        if (compositionEnabled != null) {
            preferredCompositionEnabled = compositionEnabled
        }
        val ctx = ownInputContext ?: return false
        val accepted = try {
            ctx.selectInputMethod(locale)
        } catch (t: Throwable) {
            val failures = ArrayList<String>()
            recordStepFailure("selectInputMethod", t, failures)
            false
        }
        lastLocaleTransferSupported = accepted
        if (compositionEnabled != null) {
            lastCompositionTransferSupported = try {
                ctx.isCompositionEnabled = compositionEnabled
                ctx.isCompositionEnabled == compositionEnabled
            } catch (_: UnsupportedOperationException) {
                false
            } catch (t: Throwable) {
                val failures = ArrayList<String>()
                recordStepFailure("setCompositionEnabled", t, failures)
                false
            }
        } else {
            try {
                preferredCompositionEnabled = ctx.isCompositionEnabled
            } catch (_: UnsupportedOperationException) {
            } catch (_: RuntimeException) {
            }
        }
        return accepted
    }

    fun retryCleanup(): Boolean {
        var allOk = true
        for (ob in recoverySession.unresolvedContexts.toList()) {
            val ctx = ob.context
            val comp = ob.host
            if (!ob.detached) {
                try {
                    recoveryHooks.removeNotifyContext(ctx, comp)
                    ob.detached = true
                    if (!comp.hasCountedContextDetach) {
                        comp.hasCountedContextDetach = true
                        LifecycleCounters.inputContextsDetached.incrementAndGet()
                    }
                } catch (_: Throwable) {
                    allOk = false
                }
            }
            if (ob.detached && !ob.disposed) {
                try {
                    recoveryHooks.disposeContext(ctx)
                    ob.disposed = true
                    if (!comp.hasCountedContextDispose) {
                        comp.hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                } catch (_: Throwable) {
                    allOk = false
                }
            }
            if (ob.detached && ob.disposed) {
                recoverySession.unresolvedContexts.remove(ob)
            }
        }
        return allOk && !recoverySession.hasUnresolvedObligations
    }

    fun recoverExplicitly(admitQuarantined: Boolean = false): Boolean {
        if (structuralInputPaused && !retired && !disposed) {
            structuralInputPaused = false
            return abortAndRetireComposition(replaceHost = true)?.admissionAllowed == true
        }
        if (admissionAllowed) return true
        val cleanupOk = retryCleanup()
        val boundarySatisfied = (cleanupOk && !recoverySession.hasUnresolvedObligations) || recoverySession.isolationFenceEstablished
        if (!boundarySatisfied) {
            recordDiagnosticEvent("recoverExplicitly rejected: unresolved context obligations remain attached to session; isolation boundary not established")
            writeDiagnostics()
            statusChanged(engine.snapshot())
            return false
        }
        val canRecover = ownInputContext != null && contextBindFailure == null && !disposed && !retired
        if (canRecover) {
            admissionAllowed = true
            recoveryCaptureFailure = null
            setupSucceeded = true
            contextQualified = true
            recoveryStatus = RecoveryStatus.RECOVERED_QUALIFIED
            writeDiagnostics()
            statusChanged(engine.snapshot())
            return true
        }
        return false
    }

    private fun quarantineInput(callback: QuarantinedCallback) {
        val retained = recoverySession.snapshotRetainedDrafts().size
        val count = retained + quarantinedCallbacks.size + if (engine.snapshot().marked != null)1 else 0
        if (count >= 16 || callback.text.length > 100000 || quarantinedCallbacks.sumOf { it.text.length.toLong() * 2 } + callback.text.length * 2L > 1024 * 1024) {
            pauseForRecoveryCapture("RecoveryCaptureFailed: noncancelable callback exceeds reserved capture envelope (16 records, 100000 UTF-16 units/callback, 1 MiB live callback text). Existing evidence retained; incoming ${callback.text.length} units could not be retained; automatic admission suspended.")
            return
        }
        quarantinedCallbacks.add(callback)
    }
    var recoveryCaptureFailure: String? = null
        private set
    fun pauseForRecoveryCapture(message: String) {
        recoveryCaptureFailure = message
        admissionAllowed = false
        contextQualified = false
        recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
        recordDiagnosticEvent(message)
        statusChanged(engine.snapshot())
    }
    fun inspectRetainedDraft(): String? = retainedPreimage?.draftText
    fun selectRetainedDraft(record: RetainedCompositionPreimage): Boolean {
        if (!recoverySession.selectRetainedDraft(record)) return false
        writeDiagnostics()
        statusChanged(engine.snapshot())
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
        val preimage = retainedPreimage ?: return false
        val draft = preimage.recoveryDraft ?: return false
        val result = engine.applyRecoveredDraft(draft)
        if (result == RecoveryApplyResult.Applied) recoverySession.resolveRetainedDraft(preimage)
        writeDiagnostics()
        statusChanged(engine.snapshot())
        return result == RecoveryApplyResult.Applied
    }

    fun discardRecoveredDraft() {
        recoverySession.retainedPreimage = null
        writeDiagnostics()
        statusChanged(engine.snapshot())
    }

    fun discardQuarantinedCallbacks(confirmed: List<QuarantinedCallback> = quarantinedCallbacks.toList()) {
        quarantinedCallbacks.removeIf { current -> confirmed.any { it === current } }
        writeDiagnostics()
        statusChanged(engine.snapshot())
    }

    fun discardRecoverySession() {
        recoverySession.discardAllRetainedDrafts()
        quarantinedCallbacks.clear()
        writeDiagnostics()
        statusChanged(engine.snapshot())
    }

    fun establishSafeIsolationFence() {
        recoverySession.isolationFenceEstablished = true
        writeDiagnostics()
        statusChanged(engine.snapshot())
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

    fun abortAndRetireComposition(replaceHost: Boolean): EditorSurface? {
        if (retired) return replacementSurface
        LifecycleCounters.recoveryAttempts.incrementAndGet()
        val failures = ArrayList<String>()
        val wasFocused = isFocusOwner
        val initialParent = parent
        val wasAttached = initialParent != null
        val wasDisplayable = isDisplayable
        val oldContext = ownInputContext
        val observedLocale = preferredLocale ?: runCatching { oldContext?.locale }.getOrNull()
        val observedCompositionEnabled = preferredCompositionEnabled ?: try {
            oldContext?.isCompositionEnabled
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        val snapBeforeAbort = engine.snapshot()
        val draftText = snapBeforeAbort.marked?.let { range ->
            if (range.first in 0 until snapBeforeAbort.visible.text.length && range.last in range.first until snapBeforeAbort.visible.text.length) {
                snapBeforeAbort.visible.text.substring(range.first, range.last + 1)
            } else {
                ""
            }
        } ?: ""
        val preimage = if (draftText.isNotEmpty() || snapBeforeAbort.marked != null) {
            RetainedCompositionPreimage(
                draftText = draftText,
                selectionBefore = snapBeforeAbort.selection,
                markedRangeBefore = snapBeforeAbort.marked,
                committedTextBefore = snapBeforeAbort.committed.text,
                epoch = activeImeEpoch,
                recoveryDraft = engine.captureRecoveryDraft(),
            )
        } else {
            this.retainedPreimage
        }
        this.retainedPreimage = preimage

        retired = true
        if (!hasCountedRetire) {
            hasCountedRetire = true
            LifecycleCounters.hostsRetired.incrementAndGet()
        }
        activeImeEpoch++
        ownInputContext = null
        stopBlinkTimer()

        runStep("onEndCompositionRequested", failures) { onEndCompositionRequested() }
        if (oldContext != null) {
            unresolvedContext = oldContext
            runStep("endComposition", failures) { recoveryHooks.endComposition(oldContext) }
            val detachOk = runStep("removeNotifyContext", failures) {
                recoveryHooks.removeNotifyContext(oldContext, this)
                if (!hasCountedContextDetach) {
                    hasCountedContextDetach = true
                    LifecycleCounters.inputContextsDetached.incrementAndGet()
                }
            }
            if (detachOk) {
                recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
                val disposeOk = runStep("disposeContext", failures) {
                    recoveryHooks.disposeContext(oldContext)
                    if (!hasCountedContextDispose) {
                        hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                }
                if (disposeOk || hasCountedContextDispose) {
                    unresolvedContext = null
                }
            }
        }
        runStep("disableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }

        var documentAbortedCleanly = false
        runStep("engineAbortComposition", failures) {
            viewModel.dispatch(EditorIntent.AbortComposition)
            val afterAbort = engine.snapshot()
            check(afterAbort.marked == null && !afterAbort.waiting && !engine.settlementTimedOut) {
                "Engine remained in composing/waiting state after abortComposition"
            }
            // Recovery uses the captured committed-basis clock. Never retag a capsule after abort.
            documentAbortedCleanly = true
        }

        val removedDuringTeardown = disposed || (wasAttached && (parent !== initialParent || (wasDisplayable && !initialParent.isDisplayable)))
        if (removedDuringTeardown && replaceHost) {
            recordDiagnosticEvent("IME recovery: host removed or disposed during teardown; skipping replacement creation")
        }

        val savedStatusChanged = statusChanged
        val savedRequestSave = requestSave
        val savedRequestOpen = requestOpen
        val savedOnHostReplaced = onHostReplaced
        val savedBounds = bounds

        previousRetiredHost?.replacementSurface = null
        previousRetiredHost = null
        onEndCompositionRequested = NOOP_UNIT

        var replacement: EditorSurface? = null
        var containerAttachedOk = !wasAttached
        if (replaceHost && !removedDuringTeardown) {
            clearCallbacks()
            runStep("replacementCreation", failures) {
                replacement = recoveryHooks.createReplacement(this).also { next ->
                    recoverySession.mergeRetainedDrafts(next.recoverySession)
                    next.recoverySession = this.recoverySession
                    next.previousRetiredHost = this
                    next.keyEvents = keyEvents
                    next.imeEvents = imeEvents
                    next.activeImeEpoch = activeImeEpoch
                    next.preferredLocale = observedLocale
                    next.preferredCompositionEnabled = observedCompositionEnabled
                    next.statusChanged = savedStatusChanged
                    next.requestSave = savedRequestSave
                    next.requestOpen = savedRequestOpen
                    next.onHostReplaced = savedOnHostReplaced
                    next.bounds = savedBounds
                }
            }
            val created = replacement
            if (created != null) {
                if (created.ownInputContext == null || created.contextBindFailure != null) {
                    val reason = created.contextBindFailure ?: "InputContext is null"
                    recordStepFailure("contextBinding", IllegalStateException(reason), failures)
                }
                if (initialParent != null) {
                    val attachStepOk = runStep("containerAttachment", failures) {
                        recoveryHooks.attachReplacement(initialParent, this, created)
                        check(this.parent == null) { "Retired host remained attached to parent container" }
                        check(created.parent === initialParent) { "Replacement host was not attached to parent container" }
                    }
                    if (this.parent === initialParent) {
                        runCatching { initialParent.remove(this) }
                    }
                    containerAttachedOk = attachStepOk && created.parent === initialParent && this.parent == null
                    if (!containerAttachedOk && created.parent !== initialParent) {
                        created.cleanupFailedAttachment(failures)
                        // Re-bind engine observer fallback so document edits/Undo remain observable even after attachment failure
                        viewModel.onStatePublished = { f ->
                            published = f
                            view.frame = f
                            savedStatusChanged(f)
                            writeDiagnostics()
                        }
                    }
                }
                if (containerAttachedOk && !created.disposed) {
                    val newCtx = created.ownInputContext
                    if (newCtx != null) {
                        if (observedLocale != null) {
                            created.lastLocaleTransferSupported = try {
                                newCtx.selectInputMethod(observedLocale)
                            } catch (t: Throwable) {
                                recordStepFailure("restoreLocale", t, failures)
                                false
                            }
                        }
                        if (observedCompositionEnabled != null) {
                            created.lastCompositionTransferSupported = try {
                                newCtx.isCompositionEnabled = observedCompositionEnabled
                                newCtx.isCompositionEnabled == observedCompositionEnabled
                            } catch (_: UnsupportedOperationException) {
                                false
                            } catch (t: Throwable) {
                                recordStepFailure("restoreCompositionEnabled", t, failures)
                                false
                            }
                        }
                    }
                    if (wasFocused && created.parent != null) {
                        created.requestFocusInWindow()
                    }
                }
            } else {
                containerAttachedOk = false
            }
            markDisposed()
        } else {
            containerAttachedOk = false
            if (!wasAttached || parent == null) {
                clearCallbacks()
                markDisposed()
            }
        }

        val activeReplacement = replacement?.takeIf { !it.disposed && (!wasAttached || it.parent === initialParent) }
        replacementSurface = replacement

        val finalStatus: RecoveryStatus
        val finalQualified: Boolean
        val finalSetupSucceeded: Boolean
        val finalAdmissionAllowed: Boolean
        val finalNativeQualified = false // synthetic tests do not qualify real native IME routing

        if (replaceHost && !removedDuringTeardown) {
            val boundOk = activeReplacement != null && activeReplacement.ownInputContext != null && activeReplacement.contextBindFailure == null && !activeReplacement.retired
            if (failures.isEmpty() && documentAbortedCleanly && containerAttachedOk && boundOk && !recoverySession.hasUnresolvedObligations) {
                finalStatus = RecoveryStatus.RECOVERED_QUALIFIED
                finalQualified = true
                finalSetupSucceeded = true
                finalAdmissionAllowed = true
                LifecycleCounters.recoverySucceededCount.incrementAndGet()
            } else {
                finalStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
                finalQualified = false
                finalSetupSucceeded = false
                finalAdmissionAllowed = false
                LifecycleCounters.recoveryDegradedCount.incrementAndGet()
            }
        } else {
            if (failures.isEmpty() && documentAbortedCleanly) {
                finalStatus = RecoveryStatus.CONSERVATIVE_ABORT_ONLY
                finalQualified = false
                finalSetupSucceeded = false
                finalAdmissionAllowed = false
                LifecycleCounters.recoveryConservativeCount.incrementAndGet()
            } else {
                finalStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
                finalQualified = false
                finalSetupSucceeded = false
                finalAdmissionAllowed = false
                LifecycleCounters.recoveryDegradedCount.incrementAndGet()
            }
        }

        contextQualified = false
        setupSucceeded = false
        nativeQualified = false
        admissionAllowed = false
        recoveryStatus = finalStatus
        recoveryFailures.clear()
        recoveryFailures.addAll(failures)

        val report = RecoveryReport(
            epoch = activeImeEpoch,
            replaceRequested = replaceHost,
            status = finalStatus,
            contextQualified = finalQualified,
            setupSucceeded = finalSetupSucceeded,
            nativeQualified = finalNativeQualified,
            admissionAllowed = finalAdmissionAllowed,
            documentAbortedCleanly = documentAbortedCleanly,
            replacementCreated = replacement != null,
            containerAttached = containerAttachedOk && activeReplacement != null,
            removedDuringTeardown = removedDuringTeardown,
            failures = failures.toList(),
            preservedLocale = observedLocale,
            preservedCompositionEnabled = observedCompositionEnabled,
            retainedPreimage = preimage,
        )
        lastRecoveryReport = report

        replacement?.let { next ->
            next.contextQualified = finalQualified
            next.setupSucceeded = finalSetupSucceeded
            next.nativeQualified = finalNativeQualified
            next.admissionAllowed = finalAdmissionAllowed
            next.recoveryStatus = finalStatus
            next.recoveryFailures.clear()
            next.recoveryFailures.addAll(failures)
            next.lastRecoveryReport = report
            // The shared session owns record selection/disposition; do not resurrect a captured record.
        }

        val snap = engine.snapshot()
        published = snap
        view.frame = snap

        if (activeReplacement != null) {
            savedOnHostReplaced(activeReplacement)
            activeReplacement.statusChanged(snap)
            activeReplacement.writeDiagnostics()
        } else {
            savedStatusChanged(snap)
            writeDiagnostics()
        }
        return activeReplacement
    }

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
        ownInputContext = try {
            recoveryHooks.createInputContext()?.also {
                hasCountedContextCreation = true
                LifecycleCounters.inputContextsCreated.incrementAndGet()
            }
        } catch (t: Throwable) {
            contextBindFailure = "${t.javaClass.simpleName}: ${t.message ?: "failed"}"
            null
        }
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
        viewModel.onStatePublished = { f ->
            if (!disposed && (!retired || replacementSurface == null)) {
                val old = published
                published = f
                view.frame = f
                view.blink = true
                view.ensureCaret = true
                statusChanged(f)
                repaint()
                try {
                    publishAccessibleChange(old, f)
                } finally {
                    writeDiagnostics()
                }
            }
        }
        engine.onSettlementRequested = { if (!retired && !disposed) requestEndComposition() }
        engine.onEvent = { event ->
            trace?.record("engine", event, engine.snapshot())
            diagnostics?.parentFile?.let {
                it.mkdirs()
                File(it, "input-events.log").appendText("${System.currentTimeMillis()} $event\n")
            }
        }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (retired || disposed) return
                view.focused = true
                view.blink = true
                trace?.record("focus-gained", "locale=${inputContext?.locale}", engine.snapshot())
                writeDiagnostics()
            }
            override fun focusLost(e: FocusEvent) {
                if (retired || disposed) return
                view.focused = false
                trace?.record("focus-lost", "temporary=${e.isTemporary}", engine.snapshot())
                requestEndComposition()
                writeDiagnostics()
            }
        })
        addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(e: InputMethodEvent) {
                if (retired || disposed) {
                    trace?.record("awt-text-ignored", "retired=$retired; disposed=$disposed", engine.snapshot())
                    e.consume()
                    return
                }
                if (!admissionAllowed) {
                    val text = e.text?.let { iterator -> buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) } } ?: ""
                    val committed = e.committedCharacterCount
                    val caret = e.caret?.insertionIndex
                    quarantineInput(QuarantinedCallback(text, committed, caret, activeImeEpoch))
                    trace?.record("awt-text-quarantined", "text=$text; committed=$committed; caret=$caret; status=$recoveryStatus; admissionAllowed=false", engine.snapshot())
                    recordDiagnosticEvent("Input method callback quarantined: recovery status is $recoveryStatus; ambiguous native text not admitted")
                    e.consume()
                    writeDiagnostics()
                    statusChanged(engine.snapshot())
                    return
                }
                imeEvents++
                val text = e.text?.let { iterator -> buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) } } ?: ""
                val committed = e.committedCharacterCount
                val caret = e.caret?.insertionIndex
                latestRecoveryProposal = QuarantinedCallback(text, committed, caret, activeImeEpoch)
                trace?.record("awt-text", "text=$text; committed=$committed; caret=$caret; leading=${e.caret?.isLeadingEdge}; locale=${inputContext?.locale}", engine.snapshot())
                if (committed in 0..text.length && (caret == null || caret in 0..text.length - committed)) {
                    if (engine.nativeStructureAdmission(text)) {
                        viewModel.dispatch(EditorIntent.NativeText(text, committed, caret))
                    } else {
                        quarantineInput(QuarantinedCallback(text, committed, caret, activeImeEpoch))
                        structuralInputPaused = true
                        admissionAllowed = false
                        contextQualified = false
                        recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
                        recordDiagnosticEvent("Native image seam refused; input paused until explicit host recovery. Inspect capture status; callback is never replayed.")
                        writeDiagnostics()
                        statusChanged(engine.snapshot())
                    }
                } else {
                    runCatching { engine.onEvent("Rejected malformed native text/caret range") }
                }
                e.consume()
                preferredX = null
            }
            override fun caretPositionChanged(e: InputMethodEvent) {
                if (retired || disposed) {
                    trace?.record("awt-caret-ignored", "retired=$retired; disposed=$disposed", engine.snapshot())
                    e.consume()
                    return
                }
                if (!admissionAllowed) {
                    trace?.record("awt-caret-quarantined", "${e.caret}", engine.snapshot())
                    e.consume()
                    return
                }
                trace?.record("awt-caret", "${e.caret}", engine.snapshot())
                e.caret?.let { engine.inputMethodCaret(it.insertionIndex) }
                e.consume()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (retired || disposed || !admissionAllowed) {
                    e.consume()
                    return
                }
                if (e.isConsumed || published.marked != null) return
                if ((e.isControlDown || e.isMetaDown || e.isAltDown) && !e.isAltGraphDown) return
                if (e.keyChar >= ' ' && e.keyChar != '\u007f' && e.keyChar != KeyEvent.CHAR_UNDEFINED) {
                    keyEvents++
                    engine.replace(e.keyChar.toString())
                    preferredX = null
                    e.consume()
                }
            }
            override fun keyPressed(e: KeyEvent) {
                if (retired || disposed) {
                    e.consume()
                    return
                }
                if (e.isConsumed) return
                trace?.record("key-pressed", "code=${e.keyCode}; modifiers=${e.modifiersEx}; locale=${inputContext?.locale}", engine.snapshot())
                val command = (e.isControlDown || e.isMetaDown) && !e.isAltGraphDown
                when {
                    command && e.keyCode == KeyEvent.VK_S -> requestSave(e.isShiftDown)
                    command && e.keyCode == KeyEvent.VK_O -> requestOpen()
                    command && e.keyCode == KeyEvent.VK_B -> if (admissionAllowed) engine.toggleFormat(FormatKey.BOLD)
                    command && e.keyCode == KeyEvent.VK_I -> if (admissionAllowed) engine.toggleFormat(FormatKey.ITALIC)
                    command && e.keyCode == KeyEvent.VK_U -> if (admissionAllowed) engine.toggleFormat(FormatKey.UNDERLINE)
                    command && e.keyCode == KeyEvent.VK_Z -> viewModel.dispatch(EditorIntent.Command(if (e.isShiftDown) EditAction.Redo else EditAction.Undo))
                    command && e.keyCode == KeyEvent.VK_Y -> viewModel.dispatch(EditorIntent.Command(EditAction.Redo))
                    command && e.keyCode == KeyEvent.VK_A -> engine.selectAll()
                    command && e.keyCode == KeyEvent.VK_C -> copySelection(false)
                    command && e.keyCode == KeyEvent.VK_X -> copySelection(admissionAllowed)
                    command && e.keyCode == KeyEvent.VK_V -> if (admissionAllowed) paste(e.isShiftDown)
                    e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE -> {
                        if (admissionAllowed) {
                            if (command && published.selection.collapsed) {
                                wordDelete(e.keyCode == KeyEvent.VK_BACK_SPACE)
                            } else {
                                engine.delete(e.keyCode == KeyEvent.VK_BACK_SPACE)
                            }
                        }
                    }
                    e.keyCode == KeyEvent.VK_ENTER -> if (admissionAllowed) engine.replace("\n", "Enter", paragraphBreaks = !e.isShiftDown)
                    e.keyCode == KeyEvent.VK_TAB -> if (e.isShiftDown)transferFocusBackward() else transferFocus()
                    e.keyCode in arrayOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN) -> navigate(e.keyCode, e.isShiftDown, command)
                    e.keyCode == KeyEvent.VK_ESCAPE && imageResize != null -> {
                        imageResize = null
                        repaint()
                    }
                    e.keyCode == KeyEvent.VK_ESCAPE && (published.marked != null || published.waiting || engine.settlementTimedOut) -> {
                        if (published.waiting || engine.settlementTimedOut) {
                            abortAndRetireComposition()
                        } else {
                            requestEndComposition()
                        }
                    }
                    else -> return
                }
                e.consume()
                writeDiagnostics()
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (retired || disposed) return
                requestFocusInWindow()
                preferredX = null
                if (admissionAllowed) {
                    imageHandles(this@EditorSurface)?.let { h ->
                        if (h.left.contains(e.point) || h.right.contains(e.point)) {
                            val c = engine.clipboardCapture() ?: return
                            val im = c.basis.document.units[c.selection.start].image ?: return
                            imageResize = ImageResizeGesture(c, im, e.x, 1f / checkNotNull(geometry()).scale, h.right.contains(e.point))
                            imageResizeX = e.x
                            imageResizeView = resizeViewKey()
                            repaint()
                            return
                        }
                    }
                }
                val pos = hit(e.point) ?: return
                if (published.visible.units.getOrNull(pos)?.kind == UnitKind.IMAGE) {
                    dragAnchor = pos
                    engine.move(Selection(pos, pos + 1))
                    return
                }
                if (e.clickCount >= 3) {
                    val text = published.visible.text
                    val start = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(-1)) + 1
                    val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it + 1 }
                    dragAnchor = start
                    engine.move(Selection(start, end))
                } else if (e.clickCount == 2) {
                    val text = published.visible.text
                    val words = BreakIterator.getWordInstance().apply { setText(text) }
                    val a = words.preceding((pos + 1).coerceAtMost(text.length)).let { if (it < 0) 0 else it }
                    val b = words.following(pos).let { if (it < 0) text.length else it }
                    dragAnchor = a
                    engine.move(Selection(a, b))
                } else {
                    dragAnchor = if (e.isShiftDown) published.selection.anchor else pos
                    engine.move(Selection(dragAnchor, pos), side = pointerSide(e.point, pos), wrapAffinity = pointerWrapAffinity(e.point, pos))
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                val drag = imageResize ?: return
                imageResize = null
                if (admissionAllowed && !retired && !disposed && resizeViewKey() == imageResizeView)drag.finish(engine, e.x)
                repaint()
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (retired || disposed) return
                if (imageResize != null) {
                    imageResizeX = e.x
                    repaint()
                    return
                }
                if (e.y < 12) {
                    view.scroll = max(0f, view.scroll - 18)
                } else if (e.y > height - 12) {
                    view.scroll += 18
                }
                hit(e.point)?.let { engine.move(Selection(dragAnchor, it), wrapAffinity = pointerWrapAffinity(e.point, it)) }
            }
        })
        addMouseWheelListener { e ->
            if (retired || disposed) return@addMouseWheelListener
            view.ensureCaret = false
            view.scroll = max(0f, view.scroll + e.preciseWheelRotation.toFloat() * 56)
        }
        blinkTimer.start()
        hasCountedTimerStart = true
        LifecycleCounters.timersStarted.incrementAndGet()
    }

    override fun removeNotify() {
        val oldContext = ownInputContext
        val wasRetired = retired
        ownInputContext = null
        stopBlinkTimer()
        val failures = ArrayList<String>()
        if (oldContext != null) {
            unresolvedContext = oldContext
            val detachOk = runStep("removeNotifyContext", failures) {
                recoveryHooks.removeNotifyContext(oldContext, this)
                if (!hasCountedContextDetach) {
                    hasCountedContextDetach = true
                    LifecycleCounters.inputContextsDetached.incrementAndGet()
                }
            }
            if (detachOk) {
                recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
                val disposeOk = runStep("disposeContext", failures) {
                    recoveryHooks.disposeContext(oldContext)
                    if (!hasCountedContextDispose) {
                        hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                }
                if (disposeOk || hasCountedContextDispose) {
                    unresolvedContext = null
                }
            }
            runStep("disableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }
        }
        if (recoverySession.unresolvedContexts.isNotEmpty()) {
            retryCleanup()
        }
        if (failures.isNotEmpty()) {
            contextQualified = false
            setupSucceeded = false
            admissionAllowed = false
            recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
        }
        clearCallbacks()
        markDisposed()
        if (!wasRetired) {
            engine.cancelPendingOnTeardown()
        }
        super.removeNotify()
    }

    public override fun processFocusEvent(e: FocusEvent) {
        if (retired || disposed) return
        super.processFocusEvent(e)
    }
    public override fun processInputMethodEvent(e: InputMethodEvent) {
        if (retired || disposed) {
            trace?.record("awt-ime-ignored", "id=${e.id}; retired=$retired; disposed=$disposed", engine.snapshot())
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
        val presentationKey = view.typography.key + ":zoom=${view.zoom}" + (if (view.paged) ":paged" else ":continuous")
        if (cached != null &&
            cached.typographyKey == presentationKey &&
            cached.revision == published.documentRevision &&
            cached.scroll == view.scroll &&
            (width <= 0 || cached.hostWidth == width) &&
            (height <= 0 || cached.hostHeight == height)
        ) {
            return cached
        }
        val measured = try {
            view.measure?.invoke(published)
        } catch (failure: IllegalArgumentException) {
            view.layoutError = failure.message
            null
        } ?: return null
        if (measured.typographyKey != presentationKey)return null
        return measured.also { view.geometry = it }
    }
    private fun hit(point: Point): Int? {
        val g = geometry() ?: return null
        val targetY = point.y * g.scale - g.y
        val targetX = point.x * g.scale - g.x
        val fragment = g.fragmentAtY(targetY)
        val p = g.paragraphs[fragment.paragraphIndex]
        val localY = (targetY - fragment.top + fragment.sourceTop).coerceIn(fragment.sourceTop, fragment.sourceBottom)
        val localX = (targetX - p.contentLeft).coerceIn(0f, p.layout.size.width.toFloat())
        val localOffset = if (p.textLength == 0) 0 else p.layout.getOffsetForPosition(Offset(localX, localY)).coerceIn(0, p.textLength)
        if (p.image != null)return p.startOffset
        val docOffset = p.toDocumentOffset(localOffset)
        val stops = Graphemes.stops(published.visible.text)
        val nearest = stops.indexOfFirst { it >= docOffset }.let { if (it < 0) stops.lastIndex else it }
        // Shaped hit results can choose the adjacent logical edge under an explicit
        // opposite base direction. Refine locally using actual visual caret edges.
        val x = point.x * g.scale
        val y = point.y * g.scale
        data class Edge(val offset: Int, val vertical: Float, val horizontal: Float)
        val edges = ArrayList<Edge>()
        for (i in maxOf(0, nearest - 2)..minOf(stops.lastIndex, nearest + 2)) {
            val at = stops[i]
            if (at !in p.startOffset..p.endOffset) continue
            val affinities = if (p.isSoftWrapBoundary(p.toLocalOffset(at))) WrapAffinity.entries else listOf(WrapAffinity.DOWNSTREAM)
            for (primary in listOf(true, false)) {
                for (affinity in affinities) {
                    val caret = g.caret(at, primary, affinity)
                    val vertical = if (y < caret.top) {
                        caret.top - y
                    } else if (y > caret.bottom) {
                        y - caret.bottom
                    } else {
                        0f
                    }
                    edges += Edge(at, vertical, abs(caret.left - x))
                }
            }
        }
        return edges.minWithOrNull(compareBy<Edge> { it.vertical }.thenBy { it.horizontal }.thenBy { abs(it.offset - docOffset) })?.offset ?: docOffset
    }
    private fun pointerSide(point: Point, offset: Int): TypingSide {
        val g = geometry() ?: return TypingSide.LEFT
        val p = g.paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val rightOfCaret = point.x * g.scale >= g.caret(offset).left
        val rtl = p.layout.getBidiRunDirection(local) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
        return if (rightOfCaret != rtl) TypingSide.RIGHT else TypingSide.LEFT
    }
    private fun pointerWrapAffinity(point: Point, offset: Int): WrapAffinity {
        val g = geometry() ?: return WrapAffinity.DOWNSTREAM
        val p = g.paragraphForOffset(offset)
        if (!p.isSoftWrapBoundary(p.toLocalOffset(offset))) return WrapAffinity.DOWNSTREAM
        val y = point.y * g.scale
        val upstream = g.caret(offset, wrapAffinity = WrapAffinity.UPSTREAM)
        val downstream = g.caret(offset)
        return if (abs(y - (upstream.top + upstream.bottom) / 2) < abs(y - (downstream.top + downstream.bottom) / 2)) WrapAffinity.UPSTREAM else WrapAffinity.DOWNSTREAM
    }
    private data class Stop(val offset: Int, val x: Float, val line: Int, val primary: Boolean, val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM)
    private fun stops(g: Geometry): List<Stop> = Graphemes.stops(published.visible.text).flatMap { offset ->
        val p = g.paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val localLine = if (p.textLength == 0) 0 else p.layout.getLineForOffset(local)
        val globalLine = p.startLineIndex + localLine
        val primary = g.getHorizontalPosition(offset, true)
        val secondary = g.getHorizontalPosition(offset, false)
        val regularStops = listOf(Stop(offset, primary, globalLine, true, WrapAffinity.DOWNSTREAM)) +
            if (abs(primary - secondary) > .5f) listOf(Stop(offset, secondary, globalLine, false, WrapAffinity.DOWNSTREAM)) else emptyList()
        if (p.isSoftWrapBoundary(local)) {
            val upstreamLine = p.layout.getLineForOffset(local - 1)
            val box = p.layout.getBoundingBox(local - 1)
            val rtl = p.layout.getBidiRunDirection(local - 1) == ResolvedTextDirection.Rtl
            val cx = p.contentLeft + if (rtl) box.left else box.right
            val upstreamGlobalLine = p.startLineIndex + upstreamLine
            listOf(Stop(offset, cx, upstreamGlobalLine, true, WrapAffinity.UPSTREAM)) + regularStops
        } else {
            regularStops
        }
    }.sortedWith(compareBy<Stop> { it.line }.thenBy { it.x }.thenBy { it.offset }.thenBy { it.wrapAffinity })
    private fun navigate(key: Int, extend: Boolean, command: Boolean) {
        val g = geometry() ?: return
        val s = published.selection
        val text = published.visible.text
        val legalFocus = Graphemes.snap(text, s.focus, key in arrayOf(KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_END))
        val all = stops(g)
        val current = all.indexOfFirst { it.offset == legalFocus && it.primary == s.primary && it.wrapAffinity == s.wrapAffinity }.let { if (it < 0) all.indexOfFirst { p -> p.offset == legalFocus }.coerceAtLeast(0) else it }
        val here = all[current]
        var target = here
        when {
            command && key == KeyEvent.VK_HOME -> target = all.first { it.offset == 0 }
            command && key == KeyEvent.VK_END -> target = all.first { it.offset == text.length }
            command && key in arrayOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT) -> {
                val words = BreakIterator.getWordInstance().apply { setText(text) }
                val p = if (key == KeyEvent.VK_LEFT) words.preceding(s.focus).let { if (it < 0) 0 else it } else words.following(s.focus).let { if (it < 0) text.length else it }
                val snapped = Graphemes.snap(text, p, key == KeyEvent.VK_RIGHT)
                target = all.first { it.offset == snapped }
            }
            key == KeyEvent.VK_LEFT || key == KeyEvent.VK_RIGHT -> {
                if (!extend && !s.collapsed) {
                    val endpoints = all.filter { it.primary && (it.offset == s.start || it.offset == s.end) }
                    target = if (key == KeyEvent.VK_LEFT) endpoints.first() else endpoints.last()
                } else {
                    target = if (legalFocus != s.focus) all[current] else all[(current + if (key == KeyEvent.VK_LEFT) -1 else 1).coerceIn(0, all.lastIndex)]
                }
            }
            key == KeyEvent.VK_PAGE_UP || key == KeyEvent.VK_PAGE_DOWN -> {
                val x = preferredX ?: here.x.also { preferredX = it }
                val delta = (height - 48).coerceAtLeast(32) * g.scale * (if (key == KeyEvent.VK_PAGE_UP) -1 else 1)
                val y = g.caret(here.offset).top + delta
                val line = all.minByOrNull { abs(g.caret(it.offset).top - y) }?.line ?: here.line
                target = all.filter { it.line == line }.minByOrNull { abs(it.x - x) } ?: here
            }
            key == KeyEvent.VK_HOME -> target = all.first { it.line == here.line }
            key == KeyEvent.VK_END -> target = all.last { it.line == here.line }
            else -> {
                val x = preferredX ?: here.x.also { preferredX = it }
                val line = (here.line + if (key == KeyEvent.VK_UP) -1 else 1).coerceIn(0, g.totalLineCount - 1)
                target = all.filter { it.line == line }.minByOrNull { abs(it.x - x) } ?: here
            }
        }
        if (key !in arrayOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN)) preferredX = null
        engine.move(Selection(if (extend) Graphemes.snap(text, s.anchor, false) else target.offset, target.offset, target.primary, wrapAffinity = target.wrapAffinity), target.primary, if (target.offset < s.focus) TypingSide.RIGHT else TypingSide.LEFT)
    }
    private fun wordDelete(back: Boolean) {
        val text = published.visible.text
        val at = published.selection.focus
        val word = BreakIterator.getWordInstance().apply { setText(text) }
        val other = (if (back) word.preceding(at) else word.following(at)).let { if (it < 0) if (back) 0 else text.length else it }
        engine.replace("", "Delete", range = Selection(at, other), deleteSide = if (back) TypingSide.LEFT else TypingSide.RIGHT)
    }
    var clipboardMessage = ""
        private set
    fun copySelection(cut: Boolean) {
        if (retired || disposed || !admissionAllowed) return
        val captured = engine.clipboardCapture() ?: run {
            clipboardMessage = "Finish composition before clipboard commands"
            statusChanged(engine.snapshot())
            return
        }
        if (captured.selection.collapsed) return
        try {
            RichClipboard.publish(captured, Toolkit.getDefaultToolkit().systemClipboard, codec = services.fragments)
            val success = !cut || engine.clipboardApply(captured, null, plain = "", cut = true)
            clipboardMessage = if (success) (if (cut) "Cut" else "Copied rich + plain text") else "Cut target changed; content kept"
        } catch (e: Exception) {
            clipboardMessage = "Clipboard unavailable; content kept"
        }
        statusChanged(engine.snapshot())
    }
    var clipboardPasteBusy = false
        private set
    var clipboardReaderForChecks: ((Boolean) -> RichClipboard.Read)? = null

    @JvmOverloads fun paste(plainOnly: Boolean = false) {
        if (retired || disposed || !admissionAllowed || clipboardPasteBusy) return
        val captured = engine.clipboardCapture() ?: run {
            clipboardMessage = "Finish composition before pasting"
            statusChanged(engine.snapshot())
            return
        }
        val reader = clipboardReaderForChecks ?: { plain: Boolean -> RichClipboard.read(Toolkit.getDefaultToolkit().systemClipboard, plain, codec = services.fragments) }
        clipboardPasteBusy = true
        clipboardMessage = "Reading clipboard…"
        statusChanged(engine.snapshot())
        object : SwingWorker<RichClipboard.Read, Unit>() {
            override fun doInBackground(): RichClipboard.Read {
                check(!SwingUtilities.isEventDispatchThread())
                return reader(plainOnly)
            }
            override fun done() {
                clipboardPasteBusy = false
                if (retired || disposed)return
                try {
                    val data = get()
                    val success = admissionAllowed && engine.clipboardApply(captured, data.rich, data.plain)
                    clipboardMessage = if (!success) {
                        "Paste refused; target changed or unsupported data"
                    } else if (data.fallback) {
                        "Invalid rich fragment; pasted plain text"
                    } else if (data.rich != null) {
                        "Pasted rich text"
                    } else {
                        "Pasted plain text"
                    }
                } catch (e: Exception) {
                    clipboardMessage = "Clipboard unavailable or invalid; document unchanged"
                }
                statusChanged(engine.snapshot())
            }
        }.execute()
    }
    override fun getInputMethodRequests(): InputMethodRequests = this
    override fun getCommittedText(beginIndex: Int, endIndex: Int, attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator {
        val text = committedContext()
        require(beginIndex in 0..endIndex && endIndex <= text.length)
        return AttributedString(text.substring(beginIndex, endIndex)).iterator
    }
    private fun committedContext(): String = published.marked?.let { published.visible.text.removeRange(it.first, it.last + 1) } ?: published.visible.text
    override fun getCommittedTextLength() = committedContext().length
    override fun getInsertPositionOffset() = published.marked?.first ?: published.selection.start
    override fun getSelectedText(attributes: Array<out AttributedCharacterIterator.Attribute>?) = AttributedString(published.visible.text.substring(published.selection.start, published.selection.end)).iterator
    override fun cancelLatestCommittedText(attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator? = null
    override fun getTextLocation(offset: TextHitInfo?): Rectangle? {
        val g = geometry() ?: return null
        val screen = try {
            if (!isShowing) null else locationOnScreen
        } catch (_: IllegalComponentStateException) {
            null
        } ?: return null
        val mark = published.marked
        val s = published.selection
        val at = if (mark != null) mark.first + (offset?.insertionIndex ?: 0).also { require(it in 0..mark.count()) } else s.focus
        val caret = g.caret(at, primary = if (mark != null) true else s.primary, wrapAffinity = if (mark != null) WrapAffinity.DOWNSTREAM else s.wrapAffinity)
        var x = caret.left
        var y = caret.top
        var bottom = caret.bottom
        if (mark == null && !s.collapsed && g.paragraphForOffset(s.focus).image == null) {
            val anchorEnd = s.end
            val p = g.paragraphForOffset(anchorEnd)
            val localEnd = p.toLocalOffset(anchorEnd)
            val line = if (localEnd > 0 && localEnd == p.textLength && p.hasBreak) {
                p.layout.lineCount - 1
            } else {
                p.layout.getLineForOffset(localEnd)
            }
            val a = max(s.start, p.startOffset + p.layout.getLineStart(line))
            val b = min(s.end, p.startOffset + p.layout.getLineEnd(line, true))
            val localA = p.toLocalOffset(a)
            val localB = p.toLocalOffset(b)
            val x1 = p.layout.getHorizontalPosition(localA, true)
            val x2 = p.layout.getHorizontalPosition(localB, true)
            x = g.x + p.contentLeft + if (p.layout.getParagraphDirection(localA) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl) max(x1, x2) else min(x1, x2)
            y = g.y + g.paragraphTop(p, line) + p.layout.getLineTop(line)
            bottom = g.y + g.paragraphTop(p, line) + p.layout.getLineBottom(line)
        }
        return Rectangle(screen.x + (x / g.scale).roundToInt(), screen.y + (y / g.scale).roundToInt(), 0, ((bottom - y) / g.scale).roundToInt().coerceAtLeast(1))
    }
    override fun getLocationOffset(x: Int, y: Int): TextHitInfo? {
        val mark = published.marked ?: return null
        val g = geometry() ?: return null
        val screen = try {
            if (!isShowing) return null
            locationOnScreen
        } catch (_: IllegalComponentStateException) {
            return null
        }
        val targetX = (x - screen.x) * g.scale - g.x
        val targetY = (y - screen.y) * g.scale - g.y
        for (fragment in g.fragments) {
            if (targetY < fragment.top || targetY > fragment.top + fragment.height) continue
            val p = g.paragraphs[fragment.paragraphIndex]
            val markA = max(mark.first, p.startOffset)
            val markB = min(mark.last + 1, p.endOffset)
            if (p.image != null && markA < markB) {
                val left = g.x + p.imageLeft
                val top = g.y + fragment.top
                if ((x - screen.x) * g.scale in left..left + p.imageWidth && (y - screen.y) * g.scale in top..top + p.height) {
                    val before = if (p.baseDirection == ResolvedTextDirection.Rtl)(x - screen.x) * g.scale >= left + p.imageWidth / 2 else (x - screen.x) * g.scale <= left + p.imageWidth / 2
                    val at = if (before)p.startOffset else p.endOffset
                    return TextHitInfo.leading(at - mark.first)
                }
                continue
            }
            if (markA < markB) {
                val localA = p.toLocalOffset(markA)
                val localB = p.toLocalOffset(markB)
                val localP = Offset(targetX - p.contentLeft, targetY - fragment.top + fragment.sourceTop)
                val inAnyBox = (localA until localB).any { i ->
                    p.layout.getBoundingBox(i).contains(localP)
                }
                if (inAnyBox) {
                    val localHit = p.layout.getOffsetForPosition(localP).coerceIn(localA, localB)
                    val px = (x - screen.x) * g.scale
                    val py = (y - screen.y) * g.scale
                    data class NativeEdge(val at: Int, val dy: Float, val dx: Float)
                    val edges = ArrayList<NativeEdge>()
                    // Native ranges are raw UTF-16, so do not apply grapheme snapping here.
                    for (at in maxOf(localA, localHit - 2)..minOf(localB, localHit + 2)) {
                        for (primary in listOf(true, false)) {
                            val caret = g.caret(p.toDocumentOffset(at), primary)
                            val dy = if (py < caret.top) {
                                caret.top - py
                            } else if (py > caret.bottom) {
                                py - caret.bottom
                            } else {
                                0f
                            }
                            edges += NativeEdge(at, dy, abs(caret.left - px))
                        }
                    }
                    val exact = edges.minWithOrNull(compareBy<NativeEdge> { it.dy }.thenBy { it.dx }.thenBy { abs(it.at - localHit) })?.at ?: localHit
                    val docHit = p.toDocumentOffset(exact)
                    return TextHitInfo.leading(docHit - mark.first)
                }
            }
        }
        return null
    }
    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) accessibleContext = AccessibleEditor()
        return accessibleContext
    }
    private fun accessibleStyle(style: TextStyleFlags, profile: TypographyProfile = view.typography): javax.swing.text.AttributeSet = javax.swing.text.SimpleAttributeSet().apply {
        javax.swing.text.StyleConstants.setBold(this, style.bold)
        javax.swing.text.StyleConstants.setItalic(this, style.italic)
        javax.swing.text.StyleConstants.setUnderline(this, style.underline)
        style.link?.let {
            addAttribute("LinkURI", it.uri)
            addAttribute("LinkId", it.id)
        }
        javax.swing.text.StyleConstants.setStrikeThrough(this, style.strike)
        if (style.color >= 0) javax.swing.text.StyleConstants.setForeground(this, Color(style.color))
        // Requested presentation, not a claim about the physical fallback face for every glyph.
        javax.swing.text.StyleConstants.setFontFamily(this, StoredFonts.profile(style.font)?.requestedFamilyName ?: profile.requestedFamilyName)
        javax.swing.text.StyleConstants.setFontSize(this, if (style.size == 0) profile.sizeSp else style.size)
    }
    private fun paragraphAccessibleStyle(document: Document, index: Int): javax.swing.text.AttributeSet {
        val at = index.coerceIn(0, document.units.size)
        val target = document.paragraphAt(at)
        var resolved = ParagraphDirection.LTR
        for (p in document.paragraphs().take(target.index + 1)) resolved = resolveParagraphDirection(document.units.subList(p.start, p.end), p.properties, resolved)
        val flags = document.units.getOrNull(at)?.let { if (it.kind == UnitKind.PARAGRAPH_BREAK) it.paragraphStyle else it.style } ?: document.startStyle
        return javax.swing.text.SimpleAttributeSet(accessibleStyle(flags)).apply {
            addAttribute("ParagraphDirection", target.properties.direction.name)
            addAttribute("HeadingLevel", target.properties.heading)
            addAttribute("ListKind", target.properties.list.name)
            addAttribute("ListItem", target.properties.item)
            if (target.properties.item.isNotEmpty())addAttribute("ListDepth", document.itemDepth(target.properties.item))
            document.units.getOrNull(at)?.image?.let {
                addAttribute("ImageAlternativeText", it.alt)
                addAttribute("ImageResource", it.resource)
            }
            addAttribute("ParagraphAlignment", target.properties.alignment.name)
            javax.swing.text.StyleConstants.setBidiLevel(this, if (resolved == ParagraphDirection.RTL) 1 else 0)
            val align = when (target.properties.alignment) {
                ParagraphAlignment.CENTER -> javax.swing.text.StyleConstants.ALIGN_CENTER
                ParagraphAlignment.START -> if (resolved == ParagraphDirection.RTL) javax.swing.text.StyleConstants.ALIGN_RIGHT else javax.swing.text.StyleConstants.ALIGN_LEFT
                ParagraphAlignment.END -> if (resolved == ParagraphDirection.RTL) javax.swing.text.StyleConstants.ALIGN_LEFT else javax.swing.text.StyleConstants.ALIGN_RIGHT
            }
            javax.swing.text.StyleConstants.setAlignment(this, align)
        }
    }
    fun changeTypography(profile: TypographyProfile) {
        check(SwingUtilities.isEventDispatchThread())
        check(!retired && !disposed) { "Retired typography surface" }
        val previous = view.typography
        if (previous == profile) return
        view.changeTypography(profile)
        val units = published.visible.units
        fun style(index: Int) = units[index].let { if (it.kind == UnitKind.PARAGRAPH_BREAK) it.paragraphStyle else it.style }
        var start = 0
        while (start < units.size) {
            val flags = style(start)
            var end = start + 1
            while (end < units.size && style(end) == flags) end++
            getAccessibleContext().firePropertyChange(
                AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED,
                AccessibleAttributeSequence(start, end, accessibleStyle(flags, previous)),
                AccessibleAttributeSequence(start, end, accessibleStyle(flags, profile)),
            )
            start = end
        }
        repaint()
    }
    private fun publishAccessibleChange(old: EditorFrame, current: EditorFrame) {
        val context = getAccessibleContext()
        val before = old.visible.text
        val after = current.visible.text
        if (before != after) {
            var prefix = 0
            while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < minOf(before.length, after.length) - prefix && before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
            val oldEnd = before.length - suffix
            val newEnd = after.length - suffix
            val removed = if (oldEnd > prefix) AccessibleTextSequence(prefix, oldEnd, before.substring(prefix, oldEnd)) else null
            val inserted = if (newEnd > prefix) AccessibleTextSequence(prefix, newEnd, after.substring(prefix, newEnd)) else null
            context.firePropertyChange(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY, removed, inserted)
        } else {
            fun style(doc: Document, i: Int) = doc.units[i].let { if (it.kind == UnitKind.PARAGRAPH_BREAK) it.paragraphStyle else it.style }
            var i = 0
            while (i < current.visible.units.size) {
                val previous = style(old.visible, i)
                val next = style(current.visible, i)
                if (previous == next) {
                    i++
                    continue
                }
                var end = i + 1
                while (end < current.visible.units.size && style(old.visible, end) == previous && style(current.visible, end) == next) end++
                context.firePropertyChange(
                    AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED,
                    AccessibleAttributeSequence(i, end, accessibleStyle(previous)),
                    AccessibleAttributeSequence(i, end, accessibleStyle(next)),
                )
                i = end
            }
        }
        if (old.selection != current.selection) context.firePropertyChange(AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY, null, null)
        if (before == after && old.visible.paragraphs().map { it.properties } != current.visible.paragraphs().map { it.properties }) {
            for (p in current.visible.paragraphs()) {
                context.firePropertyChange(
                    AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED,
                    AccessibleAttributeSequence(p.start, p.end, paragraphAccessibleStyle(old.visible, p.start)),
                    AccessibleAttributeSequence(p.start, p.end, paragraphAccessibleStyle(current.visible, p.start)),
                )
            }
            context.firePropertyChange(AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY, null, current.documentRevision)
        }
        context.firePropertyChange(AccessibleContext.ACCESSIBLE_CARET_PROPERTY, old.selection.focus, current.selection.focus)
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
            check(!retired && !disposed && admissionAllowed) { "Editor input is paused; accessibility edits were not admitted" }
        }
        private fun <T> edit(requireAdmission: Boolean = true, block: () -> T): T {
            val basis = published
            return onOwner {
                if (requireAdmission) checkEditable() else check(!retired && !disposed) { "Retired accessibility surface" }
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
            if (!retired && !disposed && admissionAllowed) add(AccessibleState.EDITABLE) else remove(AccessibleState.EDITABLE)
        }
        override fun getTextRange(startIndex: Int, endIndex: Int): String = onOwner {
            require(startIndex <= endIndex)
            range(startIndex, endIndex)
            published.visible.text.substring(startIndex, endIndex)
        }
        override fun setTextContents(text: String) = edit {
            engine.replaceExact(text, "AccessibilityReplaceAll", Selection(0, published.visible.units.size))
        }
        override fun insertTextAtIndex(index: Int, text: String) = replaceText(index, index, text)
        override fun replaceText(startIndex: Int, endIndex: Int, text: String) = edit {
            engine.replaceExact(text, "AccessibilityReplace", range(startIndex, endIndex))
        }
        override fun delete(startIndex: Int, endIndex: Int) = replaceText(startIndex, endIndex, "")
        override fun selectText(startIndex: Int, endIndex: Int) = edit(requireAdmission = false) {
            engine.move(range(startIndex, endIndex))
        }
        override fun setAttributes(startIndex: Int, endIndex: Int, attributes: javax.swing.text.AttributeSet) {
            val capturedAttributes = attributes.copyAttributes()
            edit {
                val target = range(startIndex, endIndex)
                require(!target.collapsed || (published.selection.collapsed && published.selection.focus == target.focus)) {
                    "Collapsed accessibility formatting requires selection at the target caret"
                }
                val keys = capturedAttributes.attributeNames.toList()
                require(keys.all { it == javax.swing.text.StyleConstants.Bold || it == javax.swing.text.StyleConstants.Italic }) {
                    "Only bold/italic accessibility attributes are supported by this prototype"
                }
                fun flag(key: Any): Boolean? {
                    if (!capturedAttributes.isDefined(key)) return null
                    return capturedAttributes.getAttribute(key) as? Boolean ?: throw IllegalArgumentException("Accessibility style must be Boolean")
                }
                val bold = flag(javax.swing.text.StyleConstants.Bold)
                val italic = flag(javax.swing.text.StyleConstants.Italic)
                if (bold != null || italic != null) viewModel.dispatch(EditorIntent.Command(EditAction.FormatAttributes(engine.capture(target), bold, italic)))
            }
        }
        override fun cut(startIndex: Int, endIndex: Int) = edit {
            check(published.marked == null && !published.waiting) { "Finish native composition before clipboard commands" }
            engine.move(range(startIndex, endIndex))
            copySelection(true)
        }
        override fun paste(startIndex: Int) = edit {
            check(published.marked == null && !published.waiting) { "Finish native composition before clipboard commands" }
            engine.move(range(startIndex, startIndex))
            this@EditorSurface.paste()
        }
        override fun getIndexAtPoint(p: Point): Int = onOwner { hit(p) ?: -1 }
        override fun getCharacterBounds(i: Int): Rectangle? = onOwner {
            val g = geometry() ?: return@onOwner null
            if (i !in published.visible.text.indices) return@onOwner null
            val p = g.paragraphForOffset(i)
            if (i == p.breakOffset) {
                val r = p.layout.getCursorRect(p.textLength)
                val top = g.paragraphTop(p, p.lineCount - 1)
                return@onOwner Rectangle(((g.x + p.contentLeft + r.left) / g.scale).toInt(), ((g.y + top + r.top) / g.scale).toInt(), 0, (r.height / g.scale).toInt())
            }
            val local = p.toLocalOffset(i)
            if (p.image != null) {
                val c = g.caret(p.startOffset)
                return@onOwner Rectangle((minOf(g.caret(p.endOffset).left, c.left) / g.scale).roundToInt(), (c.top / g.scale).roundToInt(), (p.imageWidth / g.scale).roundToInt(), (p.height / g.scale).roundToInt())
            }
            val r = p.layout.getBoundingBox(local)
            val top = g.paragraphTop(p, p.layout.getLineForOffset(local))
            Rectangle(((g.x + p.contentLeft + r.left) / g.scale).toInt(), ((g.y + top + r.top) / g.scale).toInt(), (r.width / g.scale).toInt(), (r.height / g.scale).toInt())
        }
        override fun getCharCount() = published.visible.text.length
        override fun getCaretPosition() = published.selection.focus
        override fun getSelectionStart() = published.selection.start
        override fun getSelectionEnd() = published.selection.end
        override fun getSelectedText(): String? = published.let { it.visible.text.substring(it.selection.start, it.selection.end).takeIf { text -> text.isNotEmpty() } }
        override fun getCharacterAttribute(i: Int): javax.swing.text.AttributeSet? = onOwner {
            val unit = published.visible.units.getOrNull(i) ?: return@onOwner null
            val style = if (unit.kind == UnitKind.PARAGRAPH_BREAK) unit.paragraphStyle else unit.style
            paragraphAccessibleStyle(published.visible, i)
        }
        override fun getAtIndex(part: Int, index: Int): String? = segment(part, index, 0)
        override fun getAfterIndex(part: Int, index: Int): String? = segment(part, index, 1)
        override fun getBeforeIndex(part: Int, index: Int): String? = segment(part, index, -1)
        private fun segment(part: Int, index: Int, step: Int): String? {
            val text = published.visible.text
            if (index !in text.indices)return null
            if (part == AccessibleText.CHARACTER)return text.getOrNull(index + step)?.toString()
            val it = (if (part == AccessibleText.WORD)BreakIterator.getWordInstance() else BreakIterator.getSentenceInstance()).apply { setText(text) }
            var a = it.preceding(index + 1).coerceAtLeast(0)
            var b = it.following(index).let { if (it < 0)text.length else it }
            if (step > 0) {
                a = b
                b = it.following(a)
            } else if (step < 0) {
                b = a
                a = it.preceding(b)
            }
            return if (a < 0 || b < 0 || a >= b)null else text.substring(a, b)
        }
    }
    fun writeDiagnostics() {
        val f = diagnostics ?: return
        f.parentFile.mkdirs()
        fun quoted(s: String) = "\"" + s.flatMap { c ->
            when (c) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (c.code < 32 || Character.isSurrogate(c)) "\\u%04x".format(c.code) else c.toString()
            }.toList()
        }.joinToString("") + "\""
        val s = engine.snapshot()
        published = s
        val failuresJson = recoveryFailures.joinToString(",") { quoted(it) }
        val draftStr = retainedPreimage?.draftText ?: ""
        f.writeText("{\"text\":${quoted(s.visible.text)},\"anchor\":${s.selection.anchor},\"focus\":${s.selection.focus},\"undo\":${s.undoCount},\"redo\":${s.redoCount},\"documentRevision\":${s.documentRevision},\"sessionRevision\":${s.sessionRevision},\"composing\":${s.marked != null},\"waiting\":${s.waiting},\"focused\":$isFocusOwner,\"keyTypedEvents\":$keyEvents,\"imeTextEvents\":$imeEvents,\"activeImeEpoch\":$activeImeEpoch,\"recoveryStatus\":${quoted(recoveryStatus.name)},\"contextQualified\":$contextQualified,\"setupSucceeded\":$setupSucceeded,\"nativeQualified\":$nativeQualified,\"admissionAllowed\":$admissionAllowed,\"quarantinedCallbacks\":${quarantinedCallbacks.size},\"retainedDraft\":${quoted(draftStr)},\"recoveryFailures\":[$failuresJson],\"notice\":${quoted(s.notice)}}")
    }
}
