package dev.shayxo.composedoc.androidsample

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.ui.EditorIntent
import org.json.JSONObject
import java.io.File

/** Actual Android owners/controller/store. No fake disk, scheduler, edit or close completion. */
internal fun AdapterInstrumentation.recoveryRetryChecks() {
    group("failed close automatically retries its accepted recovery without another edit") { retryFailedWrite() }
    group("rejected close preserves the prior autosave obligation for its owner") { retryRejectedClose() }
    group("failed discard resumes the fenced recovery obligation") { retryFailedDiscard() }
    group("pristine blank is skipped while discarded empty writing requires a new record") { blankAndDiscarded() }
    group("one failed owner does not prevent a later owner in the same close from being saved") { independentPackets() }
}

internal fun AdapterInstrumentation.independentPackets() {
    val owners = main { AndroidOwners(File(evidenceRoot, "independent-packets/documents"), blank = true) }
    val bad = main { owners.tabs[0] }
    val good = main { owners.tabs[1] }
    val directory = File(owners.directory.parentFile, "rich-recovery/${bad.id}")
    check(directory.mkdirs())
    val fault = File(directory, "order.bin")
    check(fault.mkdir())
    var outcome: RecoveryCloseOutcome? = null
    try {
        main {
            bad.model.dispatch(EditorIntent.Type("failed first owner"))
            good.model.dispatch(EditorIntent.Type("second owner محفوظ"))
            owners.checkpoints.keep(listOf(bad, good)) { outcome = it }
        }
        waitUntil { main { outcome != null } }
        check(main { !outcome!!.durable && bad in owners.tabs && good in owners.tabs })
        val entry = owners.recoveryStore.entries().single { it.owner == good.id }
        check(owners.recoveryStore.read(good.id, entry.sequence).document.text == "second owner محفوظ")
        check(fault.delete())
    } finally {
        main { owners.dispose() }
    }
}

internal fun AdapterInstrumentation.retryFailedDiscard() {
    val owners = main { AndroidOwners(File(evidenceRoot, "retry-discard/documents"), blank = true) }
    val tab = main { owners.tabs.first() }
    val directory = File(owners.directory.parentFile, "rich-recovery/${tab.id}")
    check(directory.mkdirs())
    val fault = File(directory, "order.bin")
    check(fault.mkdir())
    val outcomes = mutableListOf<RecoveryCloseOutcome>()
    var discardResult: Result<Boolean>? = null
    try {
        main {
            tab.model.dispatch(EditorIntent.Type("failed discard retains writing"))
            owners.checkpoints.changed(tab)
            owners.checkpoints.keep(listOf(tab)) { outcomes += it }
            owners.checkpoints.discard(tab.id, tab.sequence) { discardResult = it }
        }
        waitUntil { main { outcomes.size == 1 && discardResult != null } }
        check(main { !outcomes.single().durable && discardResult!!.isFailure && tab in owners.tabs })
        check(fault.delete())
        waitUntil { owners.recoveryStore.entries().any { it.owner == tab.id } }
        val entry = owners.recoveryStore.entries().single { it.owner == tab.id }
        check(owners.recoveryStore.read(tab.id, entry.sequence).document.text == "failed discard retains writing")
        check(main { outcomes.size == 1 && tab in owners.tabs })
    } finally {
        main { owners.dispose() }
    }
}

internal fun AdapterInstrumentation.blankAndDiscarded() {
    val owners = main { AndroidOwners(File(evidenceRoot, "blank-discard/documents"), blank = true) }
    val tab = main { owners.tabs.first() }
    var outcome: RecoveryCloseOutcome? = null
    try {
        main { owners.checkpoints.keep(listOf(tab)) { outcome = it } }
        waitUntil { main { outcome != null } }
        check(main { outcome!!.durable && tab.sequence == 0L })
        check(owners.recoveryStore.entries().none { it.owner == tab.id })
        main {
            tab.model.dispatch(EditorIntent.Type("x"))
            owners.checkpoints.changed(tab)
            owners.checkpoints.flush()
        }
        waitUntil { owners.recoveryStore.entries().any { it.owner == tab.id } }
        val entry = owners.recoveryStore.entries().single { it.owner == tab.id }
        var discarded = false
        main { owners.checkpoints.discard(tab.id, entry.sequence) { discarded = it.getOrThrow() } }
        waitUntil { main { discarded } }
        main {
            tab.model.dispatch(EditorIntent.Command(EditAction.Undo))
            outcome = null
            owners.checkpoints.keep(listOf(tab)) { outcome = it }
        }
        waitUntil { main { outcome != null } }
        check(main { outcome!!.durable })
        val fresh = owners.recoveryStore.entries().single { it.owner == tab.id }
        check(fresh.sequence > entry.sequence)
        check(owners.recoveryStore.read(tab.id, fresh.sequence).document.text.isEmpty())
    } finally {
        main { owners.dispose() }
    }
}

internal fun AdapterInstrumentation.retryFailedWrite() {
    val owners = main { AndroidOwners(File(evidenceRoot, "retry-write/documents"), blank = true) }
    val tab = main { owners.tabs.first() }
    val directory = File(owners.directory.parentFile, "rich-recovery/${tab.id}")
    check(directory.mkdirs())
    val fault = File(directory, "order.bin")
    check(fault.mkdir())
    val before = main {
        tab.model.dispatch(EditorIntent.Type("اُردو autosave survives failed close"))
        owners.checkpoints.changed(tab)
        tab.model.snapshot()
    }
    val outcomes = mutableListOf<RecoveryCloseOutcome>()
    try {
        main { owners.checkpoints.keep(listOf(tab)) { outcomes += it } }
        waitUntil { main { outcomes.size == 1 } }
        check(main { !outcomes.single().durable && outcomes.single().reason.isNotBlank() && tab in owners.tabs })
        check(fault.delete())
        // No edit, changed(), explicit flush(), keep(), or close retry may rescue this assertion.
        waitUntil { owners.recoveryStore.entries().any { it.owner == tab.id } }
        val entry = owners.recoveryStore.entries().single { it.owner == tab.id }
        val restored = owners.recoveryStore.read(tab.id, entry.sequence)
        main {
            val after = tab.model.snapshot()
            check(restored.document.same(before.committed) && after.committed.same(before.committed))
            check(after.undoCount == before.undoCount && outcomes.size == 1 && tab in owners.tabs)
        }
        retryReceipt("write", entry.sequence, outcomes.single())
    } finally {
        main { owners.dispose() }
    }
}

internal fun AdapterInstrumentation.retryRejectedClose() {
    val owners = main { AndroidOwners(File(evidenceRoot, "retry-busy/documents"), blank = true) }
    val filler = main { owners.tabs[0] }
    val victim = main { owners.tabs[1] }
    val fillerOutcomes = mutableListOf<RecoveryCloseOutcome>()
    val victimOutcomes = mutableListOf<RecoveryCloseOutcome>()
    try {
        main {
            filler.model.dispatch(EditorIntent.Type("busy owner"))
            victim.model.dispatch(EditorIntent.Type("another owner has pending اُردو autosave"))
            owners.checkpoints.changed(victim)
            // Completion callbacks cannot drain until this single main-thread turn returns.
            repeat(16) { owners.checkpoints.keep(listOf(filler)) { fillerOutcomes += it } }
            owners.checkpoints.keep(listOf(victim)) { victimOutcomes += it }
            check(victimOutcomes.size == 1 && !victimOutcomes.single().durable)
            check(victimOutcomes.single().reason.contains("busy"))
        }
        waitUntil { main { fillerOutcomes.size == 16 } }
        waitUntil { owners.recoveryStore.entries().any { it.owner == victim.id } }
        val entry = owners.recoveryStore.entries().single { it.owner == victim.id }
        val restored = owners.recoveryStore.read(victim.id, entry.sequence)
        main {
            check(restored.document.same(victim.model.snapshot().committed))
            check(victimOutcomes.size == 1 && victim in owners.tabs && fillerOutcomes.size == 16)
        }
        retryReceipt("admission", entry.sequence, victimOutcomes.single())
    } finally {
        main { owners.dispose() }
    }
}

private fun AdapterInstrumentation.retryReceipt(kind: String, sequence: Long, outcome: RecoveryCloseOutcome) {
    File(evidenceRoot, "automatic-retry-$kind.json").writeText(
        JSONObject().put("status", "PASS").put("closeOutcome", outcome.state.name)
            .put("reason", outcome.reason).put("recoveredSequence", sequence)
            .put("noFurtherEditingOrCloseRetry", true).toString(2),
        Charsets.UTF_8,
    )
}
