package dev.shayxo.composedoc.androidsample

import dev.shayxo.composedoc.data.AndroidRecoveryStore
import java.util.concurrent.RejectedExecutionException

internal data class RecoveryCloseOutcome(val state: State, val reason: String = "") {
    enum class State { DURABLE, CANCELLED, FAILED }
    val durable get() = state == State.DURABLE
    companion object {
        fun cancelled(reason: String) = RecoveryCloseOutcome(State.CANCELLED, reason)
        fun failed(reason: String) = RecoveryCloseOutcome(State.FAILED, reason)
    }
}

private data class CloseTarget(val tab: AndroidTab, val session: Long)
private class CloseObligation(
    val targets: List<CloseTarget>,
    val packets: List<MobileCheckpoint>,
    val complete: (RecoveryCloseOutcome) -> Unit,
)

/** Independent bounded obligations; neither an empty queue nor snapshot equality proves coverage. */
internal class RecoveryCloseRequests(private val owners: AndroidOwners, private val store: AndroidRecoveryStore) {
    private val pending = LinkedHashSet<CloseObligation>()
    private var disposed = false
    fun request(
        entries: List<AndroidTab>,
        packets: List<MobileCheckpoint>,
        complete: (RecoveryCloseOutcome) -> Unit,
        enqueue: (Runnable) -> Unit,
    ) {
        if (disposed || pending.size >= MAX_REQUESTS) {
            val reason = "Recovery close is busy/disposed; draft retained. Retry after pending work."
            complete(RecoveryCloseOutcome.failed(reason))
            return
        }
        val targets = entries.map { CloseTarget(it, it.model.snapshot().sessionRevision) }
        val request = CloseObligation(targets, packets, complete)
        pending += request
        try {
            enqueue(
                Runnable {
                    // A failed owner's write must not prevent independent captured owners from being protected.
                    val failures = packets.mapNotNull { runCatching { publishAndVerify(it) }.exceptionOrNull() }
                    val result = if (failures.isEmpty()) Result.success(Unit) else Result.failure(failures.first())
                    owners.main.post { completed(request, result) }
                },
            )
        } catch (failure: RejectedExecutionException) {
            val reason = "Recovery close queue full; writing retained: ${failure.message}"
            finish(request, RecoveryCloseOutcome.failed(reason))
        }
    }
    private fun publishAndVerify(packet: MobileCheckpoint) {
        check(
            store.checkpoint(
                packet.owner,
                packet.sequence,
                packet.name,
                packet.document,
                packet.proposal,
                packet.evidence,
            ),
        ) { "Captured recovery generation was rejected or discarded; writing retained. Retry close." }
        val stored = store.read(packet.owner, packet.sequence)
        check(stored.document.same(packet.document) && stored.evidence == packet.evidence) {
            "Recovery read-back differs from captured writing; close refused"
        }
        val proposalMatches = if (packet.proposal == null) {
            stored.proposal == null
        } else {
            stored.proposal?.same(packet.proposal) == true
        }
        check(proposalMatches) {
            "Unfinished evidence was not retained; close refused"
        }
    }
    private fun completed(request: CloseObligation, result: Result<Unit>) {
        if (request !in pending) return
        val current = request.targets.all {
            it.tab in owners.tabs && it.tab.model.snapshot().sessionRevision == it.session
        }
        val outcome = when {
            disposed || !current -> RecoveryCloseOutcome.cancelled("Writing changed during close; draft retained")
            result.isFailure -> {
                val reason = result.exceptionOrNull()?.message
                RecoveryCloseOutcome.failed("Recovery close failed: $reason")
            }
            else -> RecoveryCloseOutcome(RecoveryCloseOutcome.State.DURABLE)
        }
        finish(request, outcome)
    }
    private fun finish(request: CloseObligation, outcome: RecoveryCloseOutcome) {
        if (pending.remove(request)) {
            runCatching { request.complete(outcome) }.onFailure {
                owners.status = "Close callback failed; recovery retained: ${it.message}"
            }
        }
    }
    fun discarding(owner: String, through: Long) {
        pending.toList().filter { request -> request.packets.any { it.owner == owner && it.sequence <= through } }
            .forEach {
                finish(it, RecoveryCloseOutcome.cancelled("Required generation discarded; writing stays open"))
            }
    }
    fun close() {
        disposed = true
        pending.toList().forEach {
            finish(it, RecoveryCloseOutcome.cancelled("Recovery controller retired; close cancelled"))
        }
    }
    private companion object {
        private const val MAX_REQUESTS = 16
    }
}
