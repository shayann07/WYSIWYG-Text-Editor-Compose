package dev.shayxo.composedoc.ui

import java.awt.im.InputContext
import java.util.Locale

/** Reserved capture envelope for callbacks that arrive while admission is suspended. */
private const val MAX_QUARANTINED_RECORDS = 16
private const val MAX_CALLBACK_UTF16_UNITS = 100000
private const val MAX_LIVE_CALLBACK_BYTES = 1024 * 1024

internal fun EditorSurface.recordDiagnosticEvent(message: String) {
    runCatching { viewModel.onEngineEvent(message) }
    trace?.record("recovery", message, viewModel.snapshot())
}

internal fun EditorSurface.recordStepFailure(stepName: String, failure: Throwable, failures: MutableList<String>) {
    val desc = "$stepName: ${failure.javaClass.simpleName}: ${failure.message ?: "failed"}"
    failures.add(desc)
    recoveryFailures.add(desc)
    EditorSurface.LifecycleCounters.teardownFailuresCount.incrementAndGet()
    recordDiagnosticEvent("IME recovery failure [$desc]")
}

// Reason: each teardown step runs at an AWT/input-method boundary; any failure must be recorded
// as a step failure so the remaining steps still run.
@Suppress("TooGenericExceptionCaught")
internal inline fun EditorSurface.runStep(
    stepName: String,
    failures: MutableList<String>,
    block: () -> Unit,
): Boolean = try {
    block()
    true
} catch (t: Throwable) {
    recordStepFailure(stepName, t, failures)
    false
}

internal fun EditorSurface.stopBlinkTimer() {
    blinkTimer.stop()
    for (listener in blinkTimer.actionListeners) {
        blinkTimer.removeActionListener(listener)
    }
    if (hasCountedTimerStart && !hasCountedTimerStop) {
        hasCountedTimerStop = true
        EditorSurface.LifecycleCounters.timersStopped.incrementAndGet()
    }
}

internal fun EditorSurface.markDisposed() {
    disposed = true
    if (!hasCountedDispose) {
        hasCountedDispose = true
        EditorSurface.LifecycleCounters.hostsDisposed.incrementAndGet()
    }
}

internal fun EditorSurface.markRetired() {
    retired = true
    if (!hasCountedRetire) {
        hasCountedRetire = true
        EditorSurface.LifecycleCounters.hostsRetired.incrementAndGet()
    }
}

internal fun EditorSurface.clearCallbacks() {
    previousRetiredHost?.replacementSurface = null
    previousRetiredHost = null
    onEndCompositionRequested = NOOP_UNIT
    statusChanged = NOOP_FRAME
    requestSave = NOOP_BOOL
    requestOpen = NOOP_UNIT
    onHostReplaced = NOOP_SURFACE
}

// Reason: the platform input-context factory is an AWT boundary; any failure is recorded as a
// binding failure and leaves the host in degraded recovery instead of failing construction.
@Suppress("TooGenericExceptionCaught")
internal fun EditorSurface.bindInputContext(): InputContext? = try {
    recoveryHooks.createInputContext()?.also {
        hasCountedContextCreation = true
        EditorSurface.LifecycleCounters.inputContextsCreated.incrementAndGet()
    }
} catch (t: Throwable) {
    contextBindFailure = "${t.javaClass.simpleName}: ${t.message ?: "failed"}"
    null
}

/** Detaches then disposes [oldContext], keeping it as an unresolved obligation until both succeed. */
internal fun EditorSurface.detachAndDispose(
    oldContext: InputContext,
    failures: MutableList<String>,
    removeStep: String,
    disposeStep: String,
) {
    val detachOk = runStep(removeStep, failures) {
        recoveryHooks.removeNotifyContext(oldContext, this)
        if (!hasCountedContextDetach) {
            hasCountedContextDetach = true
            EditorSurface.LifecycleCounters.inputContextsDetached.incrementAndGet()
        }
    }
    if (!detachOk) return
    recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
    val disposeOk = runStep(disposeStep, failures) {
        recoveryHooks.disposeContext(oldContext)
        if (!hasCountedContextDispose) {
            hasCountedContextDispose = true
            EditorSurface.LifecycleCounters.inputContextsDisposed.incrementAndGet()
        }
    }
    if (disposeOk || hasCountedContextDispose) {
        unresolvedContext = null
    }
}

// Reason: input-method selection crosses into the platform IME; any failure is recorded as a
// step failure and reported as an unsupported transfer.
@Suppress("TooGenericExceptionCaught")
internal fun EditorSurface.trySelectInputMethod(
    ctx: InputContext,
    locale: Locale,
    stepName: String,
    failures: MutableList<String>,
): Boolean = try {
    ctx.selectInputMethod(locale)
} catch (t: Throwable) {
    recordStepFailure(stepName, t, failures)
    false
}

// Reason: composition toggling crosses into the platform IME; any failure is recorded as a
// step failure and reported as an unsupported transfer.
@Suppress("TooGenericExceptionCaught")
internal fun EditorSurface.trySetCompositionEnabled(
    ctx: InputContext,
    enabled: Boolean,
    stepName: String,
    failures: MutableList<String>,
): Boolean = try {
    ctx.isCompositionEnabled = enabled
    ctx.isCompositionEnabled == enabled
} catch (_: UnsupportedOperationException) {
    false
} catch (t: Throwable) {
    recordStepFailure(stepName, t, failures)
    false
}

internal fun EditorSurface.transferInputLocale(locale: Locale, compositionEnabled: Boolean?): Boolean {
    if (retired || disposed) return false
    preferredLocale = locale
    if (compositionEnabled != null) {
        preferredCompositionEnabled = compositionEnabled
    }
    val ctx = ownInputContext
    return if (ctx == null) false else transferToContext(ctx, locale, compositionEnabled)
}

private fun EditorSurface.transferToContext(ctx: InputContext, locale: Locale, compositionEnabled: Boolean?): Boolean {
    val accepted = trySelectInputMethod(ctx, locale, "selectInputMethod", ArrayList())
    lastLocaleTransferSupported = accepted
    if (compositionEnabled != null) {
        lastCompositionTransferSupported =
            trySetCompositionEnabled(ctx, compositionEnabled, "setCompositionEnabled", ArrayList())
    } else {
        try {
            preferredCompositionEnabled = ctx.isCompositionEnabled
        } catch (_: UnsupportedOperationException) {
        } catch (_: RuntimeException) {
        }
    }
    return accepted
}

internal fun EditorSurface.retryContextCleanup(): Boolean {
    var allOk = true
    for (ob in recoverySession.unresolvedContexts.toList()) {
        if (!retryObligation(ob)) allOk = false
    }
    return allOk && !recoverySession.hasUnresolvedObligations
}

private fun EditorSurface.retryObligation(ob: UnresolvedContextObligation): Boolean {
    var ok = true
    if (!ob.detached) {
        ok = retryDetach(ob)
    }
    if (ob.detached && !ob.disposed) {
        ok = retryDispose(ob) && ok
    }
    if (ob.detached && ob.disposed) {
        recoverySession.unresolvedContexts.remove(ob)
    }
    return ok
}

private fun EditorSurface.retryDetach(ob: UnresolvedContextObligation): Boolean {
    val comp = ob.host
    return try {
        recoveryHooks.removeNotifyContext(ob.context, comp)
        ob.detached = true
        if (!comp.hasCountedContextDetach) {
            comp.hasCountedContextDetach = true
            EditorSurface.LifecycleCounters.inputContextsDetached.incrementAndGet()
        }
        true
    } catch (_: Throwable) {
        false
    }
}

private fun EditorSurface.retryDispose(ob: UnresolvedContextObligation): Boolean {
    val comp = ob.host
    return try {
        recoveryHooks.disposeContext(ob.context)
        ob.disposed = true
        if (!comp.hasCountedContextDispose) {
            comp.hasCountedContextDispose = true
            EditorSurface.LifecycleCounters.inputContextsDisposed.incrementAndGet()
        }
        true
    } catch (_: Throwable) {
        false
    }
}

/** Everything `removeNotify` releases before the AWT peer goes away. */
internal fun EditorSurface.releaseOnRemoveNotify() {
    val oldContext = ownInputContext
    val wasRetired = retired
    ownInputContext = null
    stopBlinkTimer()
    val failures = ArrayList<String>()
    if (oldContext != null) {
        unresolvedContext = oldContext
        detachAndDispose(oldContext, failures, "removeNotifyContext", "disposeContext")
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
        viewModel.dispatch(EditorIntent.CancelPendingOnTeardown)
    }
}

internal fun EditorSurface.recoverHostExplicitly(): Boolean {
    if (structuralInputPaused && !retired && !disposed) {
        structuralInputPaused = false
        return abortAndRetireComposition(replaceHost = true)?.admissionAllowed == true
    }
    return admissionAllowed || recoverAfterCleanup()
}

private fun EditorSurface.recoverAfterCleanup(): Boolean {
    val cleanupOk = retryCleanup()
    val boundarySatisfied =
        (cleanupOk && !recoverySession.hasUnresolvedObligations) || recoverySession.isolationFenceEstablished
    val canRecover =
        boundarySatisfied && ownInputContext != null && contextBindFailure == null && !disposed && !retired
    if (!boundarySatisfied) {
        recordDiagnosticEvent(
            "recoverExplicitly rejected: unresolved context obligations remain attached to session; " +
                "isolation boundary not established",
        )
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
    } else if (canRecover) {
        admissionAllowed = true
        recoveryCaptureFailure = null
        setupSucceeded = true
        contextQualified = true
        recoveryStatus = RecoveryStatus.RECOVERED_QUALIFIED
        writeDiagnostics()
        statusChanged(viewModel.snapshot())
    }
    return canRecover
}

internal fun EditorSurface.quarantineInput(callback: QuarantinedCallback) {
    val retained = recoverySession.snapshotRetainedDrafts().size
    val count = retained + quarantinedCallbacks.size + if (viewModel.snapshot().marked != null)1 else 0
    val liveBytes = quarantinedCallbacks.sumOf { it.text.length.toLong() * 2 } + callback.text.length * 2L
    val exceedsEnvelope = count >= MAX_QUARANTINED_RECORDS ||
        callback.text.length > MAX_CALLBACK_UTF16_UNITS ||
        liveBytes > MAX_LIVE_CALLBACK_BYTES
    if (exceedsEnvelope) {
        pauseForRecoveryCapture(
            "RecoveryCaptureFailed: noncancelable callback exceeds reserved capture envelope " +
                "(16 records, 100000 UTF-16 units/callback, 1 MiB live callback text). " +
                "Existing evidence retained; incoming ${callback.text.length} units could not be retained; " +
                "automatic admission suspended.",
        )
        return
    }
    quarantinedCallbacks.add(callback)
}
