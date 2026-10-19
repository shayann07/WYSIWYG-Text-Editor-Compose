package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.EditorFrame
import java.awt.Container
import java.awt.Rectangle
import java.awt.im.InputContext
import java.util.Locale

/** Host observations taken before any teardown step runs. */
private data class TeardownBasis(
    val wasFocused: Boolean,
    val initialParent: Container?,
    val wasDisplayable: Boolean,
    val oldContext: InputContext?,
    val observedLocale: Locale?,
    val observedCompositionEnabled: Boolean?,
) {
    val wasAttached: Boolean get() = initialParent != null
}

/** Host callbacks and bounds captured before they are cleared, for transfer to the replacement. */
private class SavedHostCallbacks(host: EditorSurface) {
    val statusChanged: (EditorFrame) -> Unit = host.statusChanged
    val requestSave: (Boolean) -> Unit = host.requestSave
    val requestOpen: () -> Unit = host.requestOpen
    val onHostReplaced: (EditorSurface) -> Unit = host.onHostReplaced
    val bounds: Rectangle = host.bounds
}

private data class ReplacementResult(val replacement: EditorSurface?, val containerAttachedOk: Boolean)

/** Everything the recovery report and the replacement host inherit from one retirement. */
private data class RecoveryOutcome(
    val replaceHost: Boolean,
    val status: RecoveryStatus,
    val documentAbortedCleanly: Boolean,
    val result: ReplacementResult,
    val activeReplacement: EditorSurface?,
    val removedDuringTeardown: Boolean,
    val failures: List<String>,
    val basis: TeardownBasis,
    val preimage: RetainedCompositionPreimage?,
)

/** Retires this host's composition and, when requested, replaces the host. */
internal fun EditorSurface.retireAndRecover(replaceHost: Boolean): EditorSurface? {
    if (retired) return replacementSurface
    EditorSurface.LifecycleCounters.recoveryAttempts.incrementAndGet()
    val failures = ArrayList<String>()
    val basis = observeTeardownBasis()
    val preimage = retainCompositionPreimage()
    retireHost(basis.oldContext, failures)
    val documentAbortedCleanly = abortEngineComposition(failures)
    val removedDuringTeardown = removedDuringTeardown(basis)
    if (removedDuringTeardown && replaceHost) {
        recordDiagnosticEvent("IME recovery: host removed or disposed during teardown; skipping replacement creation")
    }
    val saved = SavedHostCallbacks(this)
    previousRetiredHost?.replacementSurface = null
    previousRetiredHost = null
    onEndCompositionRequested = NOOP_UNIT
    val replacing = replaceHost && !removedDuringTeardown
    val result = if (replacing) {
        replaceRetiredHost(basis, saved, failures)
    } else {
        if (!basis.wasAttached || parent == null) {
            clearCallbacks()
            markDisposed()
        }
        ReplacementResult(null, false)
    }
    val replacement = result.replacement
    val activeReplacement = replacement?.takeIf {
        !it.disposed && (!basis.wasAttached || it.parent === basis.initialParent)
    }
    replacementSurface = replacement
    val status = resolveRecoveryStatus(replacing, failures, documentAbortedCleanly, result, activeReplacement)
    val outcome = RecoveryOutcome(
        replaceHost,
        status,
        documentAbortedCleanly,
        result,
        activeReplacement,
        removedDuringTeardown,
        failures,
        basis,
        preimage,
    )
    return publishRecoveryOutcome(outcome, saved)
}

private fun EditorSurface.observeTeardownBasis(): TeardownBasis {
    val wasFocused = isFocusOwner
    val initialParent = parent
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
    return TeardownBasis(
        wasFocused,
        initialParent,
        wasDisplayable,
        oldContext,
        observedLocale,
        observedCompositionEnabled,
    )
}

private fun EditorSurface.retainCompositionPreimage(): RetainedCompositionPreimage? {
    val snapBeforeAbort = viewModel.snapshot()
    val draftText = snapBeforeAbort.marked?.let { range ->
        val visibleLength = snapBeforeAbort.visible.text.length
        if (range.first in 0 until visibleLength && range.last in range.first until visibleLength) {
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
            recoveryDraft = viewModel.captureRecoveryDraft(),
        )
    } else {
        this.retainedPreimage
    }
    this.retainedPreimage = preimage
    return preimage
}

private fun EditorSurface.retireHost(oldContext: InputContext?, failures: MutableList<String>) {
    markRetired()
    activeImeEpoch++
    ownInputContext = null
    stopBlinkTimer()

    runStep("onEndCompositionRequested", failures) { onEndCompositionRequested() }
    if (oldContext != null) {
        unresolvedContext = oldContext
        runStep("endComposition", failures) { recoveryHooks.endComposition(oldContext) }
        detachAndDispose(oldContext, failures, "removeNotifyContext", "disposeContext")
    }
    runStep("disableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }
}

private fun EditorSurface.abortEngineComposition(failures: MutableList<String>): Boolean {
    var documentAbortedCleanly = false
    runStep("engineAbortComposition", failures) {
        viewModel.dispatch(EditorIntent.AbortComposition)
        val afterAbort = viewModel.snapshot()
        check(afterAbort.marked == null && !afterAbort.waiting && !viewModel.settlementTimedOut) {
            "Engine remained in composing/waiting state after abortComposition"
        }
        // Recovery uses the captured committed-basis clock. Never retag a capsule after abort.
        documentAbortedCleanly = true
    }
    return documentAbortedCleanly
}

private fun EditorSurface.removedDuringTeardown(basis: TeardownBasis): Boolean {
    val initialParent = basis.initialParent
    return disposed ||
        (
            initialParent != null &&
                (parent !== initialParent || (basis.wasDisplayable && !initialParent.isDisplayable))
            )
}

private fun EditorSurface.replaceRetiredHost(
    basis: TeardownBasis,
    saved: SavedHostCallbacks,
    failures: MutableList<String>,
): ReplacementResult {
    clearCallbacks()
    val created = createReplacementHost(basis, saved, failures)
    var containerAttachedOk = !basis.wasAttached
    if (created != null) {
        if (created.ownInputContext == null || created.contextBindFailure != null) {
            val reason = created.contextBindFailure ?: "InputContext is null"
            recordStepFailure("contextBinding", IllegalStateException(reason), failures)
        }
        val initialParent = basis.initialParent
        if (initialParent != null) {
            containerAttachedOk = attachReplacementHost(created, initialParent, saved, failures)
        }
        if (containerAttachedOk && !created.disposed) {
            restoreInputPreferences(created, basis, failures)
        }
    } else {
        containerAttachedOk = false
    }
    markDisposed()
    return ReplacementResult(created, containerAttachedOk)
}

private fun EditorSurface.createReplacementHost(
    basis: TeardownBasis,
    saved: SavedHostCallbacks,
    failures: MutableList<String>,
): EditorSurface? {
    var replacement: EditorSurface? = null
    runStep("replacementCreation", failures) {
        replacement = recoveryHooks.createReplacement(this).also { next ->
            recoverySession.mergeRetainedDrafts(next.recoverySession)
            next.recoverySession = this.recoverySession
            next.previousRetiredHost = this
            next.keyEvents = keyEvents
            next.imeEvents = imeEvents
            next.activeImeEpoch = activeImeEpoch
            next.preferredLocale = basis.observedLocale
            next.preferredCompositionEnabled = basis.observedCompositionEnabled
            next.statusChanged = saved.statusChanged
            next.requestSave = saved.requestSave
            next.requestOpen = saved.requestOpen
            next.onHostReplaced = saved.onHostReplaced
            next.bounds = saved.bounds
        }
    }
    return replacement
}

/** Swaps [created] into [initialParent]; returns whether the container now holds only the replacement. */
private fun EditorSurface.attachReplacementHost(
    created: EditorSurface,
    initialParent: Container,
    saved: SavedHostCallbacks,
    failures: MutableList<String>,
): Boolean {
    val attachStepOk = runStep("containerAttachment", failures) {
        recoveryHooks.attachReplacement(initialParent, this, created)
        check(this.parent == null) { "Retired host remained attached to parent container" }
        check(created.parent === initialParent) { "Replacement host was not attached to parent container" }
    }
    if (this.parent === initialParent) {
        runCatching { initialParent.remove(this) }
    }
    val containerAttachedOk = attachStepOk && created.parent === initialParent && this.parent == null
    if (!containerAttachedOk && created.parent !== initialParent) {
        created.cleanupFailedAttachment(failures)
        // Re-bind engine observer fallback so document edits/Undo remain observable even after attachment failure
        viewModel.onStatePublished = { f ->
            published = f
            view.frame = f
            saved.statusChanged(f)
            writeDiagnostics()
        }
    }
    return containerAttachedOk
}

private fun EditorSurface.cleanupFailedAttachment(failures: MutableList<String>) {
    val oldContext = ownInputContext
    markRetired()
    ownInputContext = null
    stopBlinkTimer()
    if (oldContext != null) {
        unresolvedContext = oldContext
        detachAndDispose(oldContext, failures, "cleanupRemoveNotifyContext", "cleanupDisposeContext")
    }
    runStep("cleanupDisableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }
    clearCallbacks()
    markDisposed()
    contextQualified = false
    recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
}

private fun EditorSurface.restoreInputPreferences(
    created: EditorSurface,
    basis: TeardownBasis,
    failures: MutableList<String>,
) {
    val newCtx = created.ownInputContext
    if (newCtx != null) {
        if (basis.observedLocale != null) {
            created.lastLocaleTransferSupported =
                trySelectInputMethod(newCtx, basis.observedLocale, "restoreLocale", failures)
        }
        if (basis.observedCompositionEnabled != null) {
            created.lastCompositionTransferSupported = trySetCompositionEnabled(
                newCtx,
                basis.observedCompositionEnabled,
                "restoreCompositionEnabled",
                failures,
            )
        }
    }
    if (basis.wasFocused && created.parent != null) {
        created.requestFocusInWindow()
    }
}

private fun EditorSurface.resolveRecoveryStatus(
    replacing: Boolean,
    failures: List<String>,
    documentAbortedCleanly: Boolean,
    result: ReplacementResult,
    activeReplacement: EditorSurface?,
): RecoveryStatus {
    val status = if (replacing) {
        val boundOk = activeReplacement != null &&
            activeReplacement.ownInputContext != null &&
            activeReplacement.contextBindFailure == null &&
            !activeReplacement.retired
        val qualified = failures.isEmpty() &&
            documentAbortedCleanly &&
            result.containerAttachedOk &&
            boundOk &&
            !recoverySession.hasUnresolvedObligations
        if (qualified) RecoveryStatus.RECOVERED_QUALIFIED else RecoveryStatus.DEGRADED_RECOVERY_FAILED
    } else {
        if (failures.isEmpty() && documentAbortedCleanly) {
            RecoveryStatus.CONSERVATIVE_ABORT_ONLY
        } else {
            RecoveryStatus.DEGRADED_RECOVERY_FAILED
        }
    }
    val counters = EditorSurface.LifecycleCounters
    when (status) {
        RecoveryStatus.RECOVERED_QUALIFIED -> counters.recoverySucceededCount.incrementAndGet()
        RecoveryStatus.CONSERVATIVE_ABORT_ONLY -> counters.recoveryConservativeCount.incrementAndGet()
        else -> counters.recoveryDegradedCount.incrementAndGet()
    }
    return status
}

private fun EditorSurface.publishRecoveryOutcome(outcome: RecoveryOutcome, saved: SavedHostCallbacks): EditorSurface? {
    contextQualified = false
    setupSucceeded = false
    nativeQualified = false
    admissionAllowed = false
    recoveryStatus = outcome.status
    recoveryFailures.clear()
    recoveryFailures.addAll(outcome.failures)

    val report = recoveryReport(outcome)
    lastRecoveryReport = report

    outcome.result.replacement?.let { next ->
        val qualified = outcome.status == RecoveryStatus.RECOVERED_QUALIFIED
        next.contextQualified = qualified
        next.setupSucceeded = qualified
        next.nativeQualified = false // synthetic tests do not qualify real native IME routing
        next.admissionAllowed = qualified
        next.recoveryStatus = outcome.status
        next.recoveryFailures.clear()
        next.recoveryFailures.addAll(outcome.failures)
        next.lastRecoveryReport = report
        // The shared session owns record selection/disposition; do not resurrect a captured record.
    }

    val snap = viewModel.snapshot()
    published = snap
    view.frame = snap

    val activeReplacement = outcome.activeReplacement
    if (activeReplacement != null) {
        saved.onHostReplaced(activeReplacement)
        activeReplacement.statusChanged(snap)
        activeReplacement.writeDiagnostics()
    } else {
        saved.statusChanged(snap)
        writeDiagnostics()
    }
    return activeReplacement
}

private fun EditorSurface.recoveryReport(outcome: RecoveryOutcome): RecoveryReport {
    // Only a qualified replacement is qualified, set up and admitting; synthetic tests never
    // qualify real native IME routing.
    val qualified = outcome.status == RecoveryStatus.RECOVERED_QUALIFIED
    return RecoveryReport(
        epoch = activeImeEpoch,
        replaceRequested = outcome.replaceHost,
        status = outcome.status,
        contextQualified = qualified,
        setupSucceeded = qualified,
        nativeQualified = false,
        admissionAllowed = qualified,
        documentAbortedCleanly = outcome.documentAbortedCleanly,
        replacementCreated = outcome.result.replacement != null,
        containerAttached = outcome.result.containerAttachedOk && outcome.activeReplacement != null,
        removedDuringTeardown = outcome.removedDuringTeardown,
        failures = outcome.failures.toList(),
        preservedLocale = outcome.basis.observedLocale,
        preservedCompositionEnabled = outcome.basis.observedCompositionEnabled,
        retainedPreimage = outcome.preimage,
    )
}
