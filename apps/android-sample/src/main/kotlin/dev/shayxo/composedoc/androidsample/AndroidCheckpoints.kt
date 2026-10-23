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

/** One batch in flight, one latest snapshot per owner pending. Typing cannot grow the write queue. */
internal class AndroidCheckpoints(private val owners: AndroidOwners, private val store: AndroidRecoveryStore) {
    private val pending = LinkedHashMap<String, MobileCheckpoint>()
    private val last = HashMap<String, Triple<Document, Document?, String>>()
    private val names = HashMap<String, String>()
    private val resolved = HashMap<String, Long>()
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
        val packets = entries.map { tab ->
            changed(tab)
            val frame = tab.model.snapshot()
            val proposal = tab.model.captureRecoveryDraft()?.document
            val evidence = if (proposal != null || frame.waiting) tab.model.recoveryCompositionEvidence() else ""
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
            if (outcome.durable) packets.forEach(::published)
            complete(outcome)
        }) { work -> io.execute(work) }
    }
    fun discard(owner: String, through: Long, complete: (Result<Boolean>) -> Unit) {
        // Fence pending close outcomes before asynchronous deletion, not after its UI callback.
        closes.discarding(owner, through)
        try {
            io.execute {
                val result = runCatching { store.discard(owner, through) }
                owners.main.post {
                    if (result.getOrDefault(false)) discarded(owner, through)
                    complete(result)
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            complete(Result.failure(failure))
        }
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
        successful[packet.owner] = packet.sequence
        if ((pending[packet.owner]?.sequence ?: Long.MAX_VALUE) <= packet.sequence) pending.remove(packet.owner)
        if (publishedOwners.add(packet.owner)) owners.recoverableCount++
        retainedDrafts.remove(packet.owner)?.let { owners.unfinished.remove(it) }
        val tab = owners.tabs.firstOrNull { it.id == packet.owner } ?: return
        val latency = SystemClock.elapsedRealtime() - packet.capturedAt
        tab.recovery = "Recovery #${packet.sequence} completed (${latency}ms)" +
            if (tab.sequence > packet.sequence) "; newer edits pending" else ""
    }
    fun discarded(owner: String, through: Long) {
        closes.discarding(owner, through)
        resolved[owner] = maxOf(resolved[owner] ?: 0, through)
        last.remove(owner)
        names.remove(owner)
        if ((pending[owner]?.sequence ?: Long.MAX_VALUE) <= through) pending.remove(owner)
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
        // The executor drains captured immutable work; process death before completion remains a loss window.
        val remaining = pending.values.toList()
        pending.clear()
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
