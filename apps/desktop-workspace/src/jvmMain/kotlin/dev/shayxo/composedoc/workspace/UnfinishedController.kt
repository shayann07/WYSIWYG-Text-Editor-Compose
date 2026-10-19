package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.UnfinishedCapture
import dev.shayxo.composedoc.data.UnfinishedKind
import dev.shayxo.composedoc.data.UnfinishedStore
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.QuarantinedCallback
import dev.shayxo.composedoc.ui.RecoverySession
import dev.shayxo.composedoc.ui.RetainedCompositionPreimage
import java.nio.file.Path
import java.util.UUID
import javax.swing.SwingUtilities

private data class UnfinishedBatch(val captures: List<UnfinishedCapture>, val accepted: Document, val name: String, val generation: Long)

class UnfinishedController(val store: UnfinishedStore, val owner: String, val refresh: () -> Unit, val failed: (String) -> Unit) {
    private val keys = java.util.IdentityHashMap<Any, String>()
    private val stableCaptures = HashMap<String, UnfinishedCapture>()
    private val retainedVersions = java.util.IdentityHashMap<Any, RetainedCompositionPreimage>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "unfinished-recovery").apply { isDaemon = true } }
    private var heldLease: LocalRecoveryStore.Lease? = null
    private var generation = 0L
    private var desired: List<UnfinishedCapture> = emptyList()
    private var accepted = Document(emptyList())
    private var name = "Untitled"
    private var running = false
    private var pending = false
    private var stopped = false
    private var lastSignature: List<Any?> = emptyList()
    private var problem: String? = null
    private var published = 0L
    private val decisions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var closeDecision = false
    fun decisionBusy() = decisions.isNotEmpty() || closeDecision
    private val timer = javax.swing.Timer(700) { flush() }.apply { start() }
    fun observe(viewModel: EditorViewModel, session: RecoverySession, path: Path?, native: QuarantinedCallback? = null) {
        check(SwingUtilities.isEventDispatchThread())
        if (stopped)return
        val f = viewModel.snapshot()
        val retained = session.snapshotRetainedDrafts()
        val q = session.quarantinedCallbacks.toList()
        val token = viewModel.compositionCaptureToken()
        val signature = listOf(f.visible, f.committed, token, retained, q, f.marked, f.selection)
        if (signature == lastSignature)return
        if (desired.isEmpty() && token == null && retained.isEmpty() && q.isEmpty()) {
            lastSignature = signature
            return
        }
        lastSignature = signature
        generation++
        fun id(key: Any) = keys.getOrPut(key) { UUID.randomUUID().toString() }
        val captures = ArrayList<UnfinishedCapture>()
        if (token != null) {
            val evidence = viewModel.recoveryCompositionEvidence()
            if (evidence.length > 1900) {
                problem = "RecoveryCaptureFailed: composition provenance exceeds 2048-unit capture envelope; evidence remains in memory"
                failed(problem!!)
                return
            }
            captures += UnfinishedCapture(
                id(token), generation, UnfinishedKind.ACTIVE_CANDIDATE, f.committed, f.visible, f.selection, f.marked,
                text = native?.text ?: "", committed = native?.committed ?: 0, caret = native?.caret, epoch = native?.epoch ?: 0, basisRevision = f.recoveryBasisRevision, identityGeneration = f.identityGeneration,
                provenance = (if (native == null)"Semantic owner capture (native provenance unavailable); " else "AWT callback ${native.timestamp}; ") + evidence,
            )
        }
        for (r in retained) {
            val d = r.recoveryDraft
            val key = id(r.recordKey)
            val previous = stableCaptures[key]
            if (retainedVersions[r.recordKey] === r && previous != null) {
                captures += previous
                continue
            }
            val captured = UnfinishedCapture(
                key, generation, UnfinishedKind.RETAINED_DRAFT, d?.basis ?: f.committed, d?.document, d?.selection ?: r.selectionBefore, r.markedRangeBefore, r.draftText, epoch = r.epoch, basisRevision = d?.basisRevision ?: 0, identityGeneration = d?.basisIdentityGeneration ?: 0,
                provenance = if (d != null) {
                    "Attributed retained preimage; original runtime authority is not resumable"
                } else {
                    "No attributed original basis available; base slab is supporting current Dc only. Original committed context follows (${r.committedTextBefore.length} raw UTF-16 units):\n" + r.committedTextBefore
                },
            )
            stableCaptures[key] = captured
            retainedVersions[r.recordKey] = r
            captures += captured
        }
        for (c in q) {
            val key = id(c)
            captures += stableCaptures.getOrPut(key) { UnfinishedCapture(key, generation, UnfinishedKind.QUARANTINE, f.committed, null, Selection(0, 0), null, c.text, c.committed, c.caret, c.epoch, provenance = "AWT callback quarantined; epoch=${c.epoch}; timestamp=${c.timestamp}; no replay authority") }
        }
        val estimate = captures.sumOf { (it.basis.units.size.toLong() + (it.draft?.units?.size ?: 0)) * 64 + (it.text.length + it.provenance.length) * 2L + (it.basis.resources.values + (it.draft?.resources?.values ?: emptyList())).sumOf { r -> r.data.length.toLong() } }
        if (captures.size > 16 || estimate > 64L * 1024 * 1024 || captures.any { it.text.length > 100000 }) {
            problem = "RecoveryCaptureFailed: capture envelope (16 records / 64 MiB in-flight estimate / 100,000 callback units) exceeded; evidence remains in memory"
            failed(problem!!)
            return
        }
        synchronized(this) {
            desired = captures
            accepted = f.committed
            name = path?.toString() ?: "Untitled"
            pending = true
        }
    }

    @Synchronized fun flush() {
        if (stopped || running || !pending)return
        running = true
        executor.execute {
            try {
                val lease = heldLease ?: store.recovery.acquire(owner).also { heldLease = it }
                run {
                    while (true) {
                        val task = synchronized(this) { if (!pending)null else UnfinishedBatch(desired, accepted, name, generation).also { pending = false } } ?: break
                        val current = task.captures
                        val old = store.entries(owner)
                        current.forEach { store.publish(lease, it, task.name) }
                        // A disappeared active candidate was committed/canceled; removed retained/quarantine was explicitly resolved.
                        old.mapNotNull { it.record }.filter { r -> current.none { it.id == r.id } }.forEach { store.resolve(lease, it, task.accepted) }
                        synchronized(this) {
                            published = maxOf(published, task.generation)
                            problem = null
                        }
                    }
                }
                SwingUtilities.invokeLater {
                    val keep = synchronized(this) { desired.map { it.id }.toSet() }
                    keys.entries.removeIf { it.value !in keep }
                    stableCaptures.keys.retainAll(keep)
                    retainedVersions.keys.removeIf { !keys.containsKey(it) }
                }
            } catch (e: Exception) {
                synchronized(this) {
                    problem = "RecoveryCaptureFailed: ${e.message}"
                    pending = true
                }
                SwingUtilities.invokeLater { failed(problem!!) }
            } finally {
                synchronized(this) { running = false }
                SwingUtilities.invokeLater(refresh)
            }
        }
    }

    @Synchronized fun status() = problem ?: if (pending || running) {
        "Unfinished recovery pending"
    } else if (desired.isEmpty()) {
        "No unresolved unfinished input (last receipt $published)"
    } else {
        "Unfinished recovery published $published — ${desired.size} record(s)"
    }

    @Synchronized fun state() = mapOf("owner" to owner, "generation" to generation, "published" to published, "pending" to pending, "running" to running, "failure" to problem)

    /** UI disposal is delayed until its exact generation is durably resolved. No failed write consumes memory evidence. */
    fun resolveLive(key: Any, apply: (() -> Document?)? = null, remove: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        val id = keys[key] ?: run {
            failed("RecoveryCaptureFailed: record has not been captured; retry after publication")
            return
        }
        val capturedGeneration = synchronized(this) { desired.firstOrNull { it.id == id }?.generation }
        if (decisions.size >= 16 || !decisions.add(id))return
        refresh()
        fun decisionDone() {
            decisions.remove(id)
            refresh()
        }
        flush()
        executor.execute {
            try {
                val lease = heldLease ?: store.recovery.acquire(owner).also { heldLease = it }
                val r = store.entries(owner).firstOrNull { it.id == id }?.record ?: error("Record not durably published yet; retry. In-memory evidence kept.")
                check(r.generation == capturedGeneration) { "Selected recovery version changed; inspect the new version before resolving it" }
                if (apply == null) {
                    store.resolve(lease, r)
                    SwingUtilities.invokeLater {
                        remove()
                        decisionDone()
                    }
                } else {
                    SwingUtilities.invokeLater {
                        val accepted = apply()
                        if (accepted != null) {
                            executor.execute {
                                try {
                                    store.resolve(lease, r, accepted)
                                    SwingUtilities.invokeLater {
                                        remove()
                                        decisionDone()
                                    }
                                } catch (e: Exception) {
                                    SwingUtilities.invokeLater {
                                        failed("Apply result is in memory; durable resolution failed, record kept: ${e.message}")
                                        decisionDone()
                                    }
                                }
                            }
                        } else {
                            decisionDone()
                        }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    failed("Recovery decision failed; record kept: ${e.message}")
                    decisionDone()
                }
            }
        }
    }
    fun captureDiscardBeforeClose(): ((() -> Unit) -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        val selected = synchronized(this) { desired.map { it.id to it.generation } }
        return { done -> discardSnapshotBeforeClose(selected, done) }
    }
    private fun discardSnapshotBeforeClose(selected: List<Pair<String, Long>>, done: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        if (closeDecision)return
        closeDecision = true
        flush()
        executor.execute {
            try {
                val lease = heldLease ?: store.recovery.acquire(owner).also { heldLease = it }
                val current = store.entries(owner).mapNotNull { it.record }
                for ((id, generation) in selected) {
                    val r = current.firstOrNull { it.id == id } ?: continue
                    check(r.generation == generation) { "New recovery generation arrived; review and close again" }
                    store.resolve(lease, r)
                }
                SwingUtilities.invokeLater {
                    closeDecision = false
                    done()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    closeDecision = false
                    failed("Discard persistence failed; window and evidence kept: ${e.message}")
                }
            }
        }
    }
    fun close() {
        timer.stop()
        flush()
        stopped = true
        executor.execute {
            heldLease?.close()
            heldLease = null
        }
        executor.shutdown()
    }
}
