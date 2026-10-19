package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.LocalCopy
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.model.Document
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

private const val DEFAULT_CHECKPOINT_INTERVAL_MS = 2000

class LocalRecoveryController(
    val store: LocalRecoveryStore,
    val refresh: () -> Unit,
    interval: Int = DEFAULT_CHECKPOINT_INTERVAL_MS,
) {
    val session = UUID.randomUUID().toString()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "astra-recovery-$session") }
    private var observed: LocalCopy? = null
    private var pending: LocalCopy? = null
    private var running = false
    private var closing: Boolean? = null // true: explicit normal close/discard; false: keep on unexpected disposal
    private var completed = 0L
    private var completedTime = 0L
    private var failure: String? = null
    var maximumPending = 0
        private set
    private val timer = javax.swing.Timer(interval) { checkpointNow() }.apply { start() }

    @Synchronized fun observe(document: Document, original: Path?) {
        if (closing != null) return
        val path = original?.toAbsolutePath()?.normalize()?.toString()
        val old = observed
        if (old != null && old.originalPath == path && old.holds(document)) {
            return
        }
        observed = LocalCopy(session, (old?.sequence ?: 0) + 1, System.currentTimeMillis(), path, document)
    }

    private fun LocalCopy.holds(candidate: Document) = document === candidate || document.same(candidate)

    @Synchronized fun checkpointNow() {
        val copy = observed?.takeIf { closing == null }?.takeIf { it.sequence > completed } ?: return
        pending = copy
        maximumPending = maxOf(maximumPending, 1)
        startWorker()
    }

    // Reason: the recovery worker is a resilience boundary; any store failure must be recorded as a
    // retryable status instead of terminating the single background writer.
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    private fun startWorker() {
        if (running) return
        running = true
        executor.execute {
            var lease: LocalRecoveryStore.Lease? = synchronized(this) { heldLease }
            try {
                while (true) {
                    val copy = takePending()
                    if (copy != null) {
                        try {
                            if (lease == null) lease = store.acquire(session)
                            store.publish(checkNotNull(lease), copy)
                            synchronized(this) {
                                completed = maxOf(completed, copy.sequence)
                                completedTime = System.currentTimeMillis()
                                failure = null
                            }
                        } catch (e: Exception) {
                            synchronized(this) { failure = failureText(e) }
                        }
                        SwingUtilities.invokeLater(refresh)
                    }
                    synchronized(this) {
                        if (pending != null) continue
                        if (closing != null) {
                            try {
                                if (closing == true && lease != null) store.discard(checkNotNull(lease))
                            } catch (e: Exception) {
                                failure = "Cleanup failed; copy kept: ${e.message}"
                            }
                            lease?.close()
                            lease = null
                            executor.shutdown()
                        }
                        running = false
                        return@execute
                    }
                }
            } finally {
                // A live controller keeps its lease between writes, so it is never offered as a crashed session.
                synchronized(this) { heldLease = lease }
            }
        }
    }
    private var heldLease: LocalRecoveryStore.Lease? = null

    private fun takePending(): LocalCopy? = synchronized(this) {
        if (closing == true) pending = null
        pending.also { pending = null }
    }

    private fun failureText(e: Exception) = e.message ?: e.javaClass.simpleName

    @Synchronized fun finish(discard: Boolean) {
        if (closing != null) return
        timer.stop()
        closing = discard
        if (!discard && observed?.sequence != completed) pending = observed
        startWorker()
    }

    @Synchronized fun status(): String = when {
        failure != null -> "Recovery copy failed — retrying"
        completed == 0L -> "Recovery copy pending"
        completed < (observed?.sequence ?: 0) -> "Recovery pending (last ${time()})"
        else -> "Recovery copy ${time()}"
    }

    @Synchronized fun detail(): String = failure
        ?: "Only committed content. 2-second scheduling plus disk time; " +
        "only the last completed copy is protected."
    private fun time(): String = java.time.Instant.ofEpochMilli(
        completedTime,
    ).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    @Synchronized fun state(): Map<String, Any?> = mapOf(
        "session" to session,
        "observed" to observed?.sequence,
        "completed" to completed,
        "running" to running,
        "pending" to (pending != null),
        "failure" to failure,
        "closing" to closing,
        "maximumPending" to maximumPending,
    )
}
