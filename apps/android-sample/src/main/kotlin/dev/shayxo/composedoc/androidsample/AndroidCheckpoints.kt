package dev.shayxo.composedoc.androidsample

import android.os.SystemClock
import dev.shayxo.composedoc.data.AndroidRecoveryStore
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.model.Document
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class MobileCheckpoint(
    val owner: String,
    val sequence: Long,
    val name: String,
    val document: Document,
    val proposal: Document?,
    val evidence: String,
    val capturedAt: Long,
)

private class RecoveryDiscard(val owner: String, val through: Long)

/** One batch in flight, one latest snapshot per owner pending. Typing cannot grow the write queue. */
internal class AndroidCheckpoints(private val owners: AndroidOwners, private val store: AndroidRecoveryStore) {
    private val pending = LinkedHashMap<String, MobileCheckpoint>()
    private val last = HashMap<String, Triple<Document, Document?, String>>()
    private val names = HashMap<String, String>()
    private val resolved = HashMap<String, Long>()
    private val discards = LinkedHashSet<RecoveryDiscard>()
    private val deferredByDiscard = HashMap<String, MobileCheckpoint>()
    private val successful = HashMap<String, Long>()
    private val retainedDrafts = HashMap<String, RecoveredDraft>()
    private val publishedOwners = HashSet<String>()
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(CLOSE_QUEUE_LIMIT))
    private val closes = RecoveryCloseRequests(owners, store)
    private var running = false
    private var closed = false
    private var scheduled = false
    private var retryDelay = CHECKPOINT_INTERVAL_MS
    private val timer = Runnable {
        scheduled = false
        flush()
    }

    fun changed(tab: AndroidTab) {
        if (closed) return
        val frame = tab.model.snapshot()
        val proposal = tab.model.captureRecoveryDraft()?.document
        val evidence = if (proposal != null || frame.waiting) tab.model.recoveryCompositionEvidence() else ""
        val prior = last[tab.id]
        val pristine = proposal == null && evidence.isEmpty() && frame.committed.same(Document(emptyList()))
        val unchanged = prior?.first === frame.committed && prior.second == proposal && prior.third == evidence
        val sameCheckpoint = unchanged && names[tab.id] == tab.name
        if ((prior == null && pristine) || sameCheckpoint) return
        last[tab.id] = Triple(frame.committed, proposal, evidence)
        names[tab.id] = tab.name
        pending[tab.id] =
            MobileCheckpoint(
                tab.id,
                ++tab.sequence,
                tab.name,
                frame.committed,
                proposal,
                evidence,
                SystemClock.elapsedRealtime(),
            )
        schedule()
    }
    fun retained(draft: RecoveredDraft, name: String) {
        val id = UUID.randomUUID().toString()
        retainedDrafts[id] = draft
        pending[id] = MobileCheckpoint(
            id,
            1,
            "$name · unfinished proposal",
            draft.basis,
            draft.document,
            "Separate proposal; selection ${draft.selection.anchor}:${draft.selection.focus}; no replay",
            SystemClock.elapsedRealtime(),
        )
        schedule()
    }
    private fun schedule() {
        if (!scheduled &&
            !closed
        ) {
            scheduled = true
            owners.main.postDelayed(timer, retryDelay)
        }
    }
    fun keep(entries: List<AndroidTab>, complete: (RecoveryCloseOutcome) -> Unit) {
        if (closed) {
            complete(RecoveryCloseOutcome.cancelled("Recovery controller was disposed; writing was not closed"))
            return
        }
        val packets = entries.mapNotNull { tab ->
            changed(tab)
            val frame = tab.model.snapshot()
            val proposal = tab.model.captureRecoveryDraft()?.document
            val evidence = if (proposal != null || frame.waiting) tab.model.recoveryCompositionEvidence() else ""
            // Never-written empty tabs need no record. A discarded or previously edited tab still does.
            val untouched = last[tab.id] == null && tab.id !in resolved && discardFence(tab.id) == 0L
            val empty = proposal == null && evidence.isEmpty() && frame.committed.same(Document(emptyList()))
            if (untouched && empty) return@mapNotNull null
            MobileCheckpoint(
                tab.id,
                ++tab.sequence,
                tab.name,
                frame.committed,
                proposal,
                evidence,
                SystemClock.elapsedRealtime(),
            ).also { packet ->
                last[tab.id] = Triple(packet.document, packet.proposal, packet.evidence)
                names[tab.id] = tab.name
                pending.remove(tab.id)
            }
        } + pending.values.filter { packet ->
            retainedDrafts[packet.owner]?.let { draft ->
                entries.any { it.model.captureFindBasis().owner === draft.ownerScope }
            } == true
        }
        closes.request(entries, packets, { outcome ->
            if (outcome.durable) {
                packets.forEach(::published)
            } else {
                retryUnpublished(packets)
            }
            complete(outcome)
        }) { work -> io.execute(work) }
    }
    private fun retryUnpublished(packets: List<MobileCheckpoint>) {
        if (closed) return
        retryDelay = RETRY_INTERVAL_MS
        packets.forEach { packet ->
            val fence = maxOf(resolved[packet.owner] ?: 0, successful[packet.owner] ?: 0)
            val ownerPresent = owners.tabs.any { it.id == packet.owner } || packet.owner in retainedDrafts
            val newerPending = (pending[packet.owner]?.sequence ?: 0) >= packet.sequence
            if (packet.sequence <= fence || !ownerPresent || newerPending) return@forEach
            if (packet.sequence <= discardFence(packet.owner)) deferDiscard(packet) else pending[packet.owner] = packet
        }
        if (pending.isNotEmpty()) schedule()
    }
    private fun deferDiscard(packet: MobileCheckpoint) {
        if ((deferredByDiscard[packet.owner]?.sequence ?: 0) < packet.sequence) deferredByDiscard[packet.owner] = packet
    }
    fun discard(owner: String, through: Long, complete: (Result<Boolean>) -> Unit) {
        // Fence pending close outcomes before asynchronous deletion, not after its UI callback.
        val request = RecoveryDiscard(owner, through)
        discards += request
        pending[owner]?.takeIf { it.sequence <= through }?.let {
            pending.remove(owner)
            retryUnpublished(listOf(it))
        }
        closes.discarding(owner, through)
        try {
            io.execute {
                val result = runCatching { store.discard(owner, through) }
                owners.main.post {
                    finishDiscard(request, result, complete)
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            finishDiscard(request, Result.failure(failure), complete)
        }
    }
    private fun finishDiscard(request: RecoveryDiscard, result: Result<Boolean>, complete: (Result<Boolean>) -> Unit) {
        discards.remove(request)
        if (result.getOrDefault(false)) discarded(request.owner, request.through)
        deferredByDiscard.remove(request.owner)?.let { retryUnpublished(listOf(it)) }
        complete(result)
    }
    fun flush() {
        owners.main.removeCallbacks(timer)
        scheduled = false
        if (running) {
            schedule()
            return
        }
        val batch = pending.values.toList()
        pending.clear()
        if (batch.isEmpty()) return
        running = true
        try {
            io.execute {
                val results = batch.map { packet ->
                    packet to runCatching {
                        store.checkpoint(
                            packet.owner,
                            packet.sequence,
                            packet.name,
                            packet.document,
                            packet.proposal,
                            packet.evidence,
                        )
                    }
                }
                owners.main.post { completed(results) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            running = false
            batch.forEach { if ((pending[it.owner]?.sequence ?: 0) < it.sequence) pending[it.owner] = it }
            schedule()
        }
    }
    private fun completed(results: List<Pair<MobileCheckpoint, Result<Boolean>>>) {
        running = false
        if (closed) return
        retryDelay = if (results.any { it.second.isFailure }) RETRY_INTERVAL_MS else CHECKPOINT_INTERVAL_MS
        results.forEach { (packet, result) ->
            if (packet.sequence <= maxOf(resolved[packet.owner] ?: 0, successful[packet.owner] ?: 0)) return@forEach
            if (packet.sequence <= discardFence(packet.owner)) {
                retryUnpublished(listOf(packet))
                return@forEach
            }
            val tab = owners.tabs.firstOrNull { it.id == packet.owner }
            if (tab == null && packet.owner !in retainedDrafts) return@forEach
            result.fold(onSuccess = { published(packet) }, onFailure = {
                if (tab != null) tab.recovery = "Recovery failed: ${it.message}"
                owners.status = "Recovery failed: ${it.message}; previous copies retained"
                if ((pending[packet.owner]?.sequence ?: 0) < packet.sequence) pending[packet.owner] = packet
            })
        }
        if (pending.isNotEmpty()) schedule()
    }
    private fun published(packet: MobileCheckpoint) {
        if (packet.sequence <= maxOf(resolved[packet.owner] ?: 0, successful[packet.owner] ?: 0)) return
        if (packet.sequence <= discardFence(packet.owner)) {
            retryUnpublished(listOf(packet))
        } else {
            successful[packet.owner] = packet.sequence
            if ((pending[packet.owner]?.sequence ?: Long.MAX_VALUE) <= packet.sequence) pending.remove(packet.owner)
            if (publishedOwners.add(packet.owner)) owners.recoverableCount++
            retainedDrafts.remove(packet.owner)?.let { owners.unfinished.remove(it) }
            owners.tabs.firstOrNull { it.id == packet.owner }?.let { tab ->
                val latency = SystemClock.elapsedRealtime() - packet.capturedAt
                tab.recovery = "Recovery #${packet.sequence} completed (${latency}ms)" +
                    if (tab.sequence > packet.sequence) "; newer edits pending" else ""
            }
        }
    }
    private fun discardFence(owner: String) = discards.filter { it.owner == owner }.maxOfOrNull { it.through } ?: 0L
    fun discarded(owner: String, through: Long) {
        closes.discarding(owner, through)
        resolved[owner] = maxOf(resolved[owner] ?: 0, through)
        last.remove(owner)
        names.remove(owner)
        if ((pending[owner]?.sequence ?: Long.MAX_VALUE) <= through) pending.remove(owner)
        if ((deferredByDiscard[owner]?.sequence ?: Long.MAX_VALUE) <= through) deferredByDiscard.remove(owner)
        retainedDrafts.remove(owner)?.let { owners.unfinished.remove(it) }
        publishedOwners.remove(owner)
        successful.remove(owner)
        owners.tabs.firstOrNull { it.id == owner }?.recovery = "Recovery discarded; new edits create a new checkpoint"
    }
    fun closedOwner(owner: String) {
        last.remove(owner)
        names.remove(owner)
        pending.remove(owner)
        // Keep ordering fences until this controller retires: delayed callbacks may still arrive.
    }
    fun close() {
        owners.main.removeCallbacks(timer)
        closes.close()
        // Cancellation may restore captured packets for the final drain, but must not leave a delayed retry alive.
        owners.main.removeCallbacks(timer)
        scheduled = false
        // The executor drains captured immutable work; process death before completion remains a loss window.
        // A failed in-flight discard must not consume the last pending snapshot during teardown.
        // The store's durable discard fence rejects this write if the discard succeeds.
        val remaining = (pending.values + deferredByDiscard.values).groupBy { it.owner }
            .values.map { packets -> packets.maxBy { it.sequence } }
        pending.clear()
        deferredByDiscard.clear()
        if (remaining.isNotEmpty()) {
            runCatching {
                io.execute {
                    remaining.forEach { packet ->
                        runCatching {
                            store.checkpoint(
                                packet.owner,
                                packet.sequence,
                                packet.name,
                                packet.document,
                                packet.proposal,
                                packet.evidence,
                            )
                        }
                            .onFailure {
                                android.util.Log.e(
                                    "ComposeDocRecovery",
                                    "Final checkpoint failed; older copies retained",
                                    it,
                                )
                            }
                    }
                }
            }.onFailure {
                owners.status = "Final recovery queue failed; older copies retained: ${it.message}"
            }
        }
        closed = true
        io.shutdown()
    }
    companion object {
        private const val CLOSE_QUEUE_LIMIT = 16
        private const val CHECKPOINT_INTERVAL_MS = 400L
        private const val RETRY_INTERVAL_MS = 5000L
    }
}
