package dev.shayxo.composedoc.androidsample

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shayxo.composedoc.data.AndroidDocumentRepository
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.androidTextSliceLimitation
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val MAX_NATIVE_EVENTS = 256
private const val DOCUMENT_IO_QUEUE_CAPACITY = 4

/** Retained application controller: two independent library owners, never a second text authority. */
class AndroidOwners(directory: File, blank: Boolean = false) {
    val models = mutableStateListOf(EditorViewModel(), EditorViewModel())
    private val locations = mutableListOf(freshLocation(), freshLocation())
    var active by mutableIntStateOf(0)
    var status by mutableStateOf("Unsaved; process death retains completed explicit saves only")
    val unfinished = mutableStateListOf<RecoveredDraft>()
    val nativeEvents = ArrayDeque<String>()
    fun nativeEvent(value: String) {
        if (nativeEvents.size == MAX_NATIVE_EVENTS) nativeEvents.removeFirst()
        nativeEvents.addLast(value)
    }
    private val repository = AndroidDocumentRepository(directory)
    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    private val io = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(DOCUMENT_IO_QUEUE_CAPACITY),
    )
    init {
        if (!blank) {
            models[0].dispatch(
                EditorIntent.Type(
                    "اُردو تحریر — العربية English 42\n" +
                        "یہ مقامی قابلِ تدوین نمونہ ہے۔ ک\u200dتاب، می\u200cروم۔\n" +
                        "English café e\u0301, https://example.org (12.5) 👩🏽‍💻\n\n",
                    paragraphBreaks = true,
                ),
            )
        }
    }
    val current: EditorViewModel get() = models[active]
    fun retainUnfinished() {
        current.captureRecoveryDraft()?.let(::retainDraft)
    }
    fun retainDraft(draft: RecoveredDraft) {
        if (unfinished.none { sameDraft(it, draft) }) {
            unfinished += draft
        }
    }
    private fun sameDraft(existing: RecoveredDraft, proposed: RecoveredDraft): Boolean {
        if (existing.ownerScope !== proposed.ownerScope) return false
        return existing.basis.same(proposed.basis) &&
            existing.document.same(proposed.document) &&
            existing.selection == proposed.selection
    }
    fun openUnfinishedCopy(index: Int) {
        retainUnfinished()
        models += EditorViewModel(unfinished[index].document)
        locations += freshLocation()
        active = models.lastIndex
        status = "Opened proposal as a separate unsaved copy; original/memory record retained"
    }
    fun save() {
        val frame = current.snapshot()
        if (current.compositionCaptureToken() != null ||
            frame.waiting
        ) {
            status = "Finish or explicitly cancel composing input before Save"
            return
        }
        val owner = current
        val location = locations[active]
        status = "Saving captured committed content…"
        work({ repository.save(location, frame.committed) }) {
            status =
                if (owner.snapshot().committed.same(
                        frame.committed,
                    )
                ) {
                    "Saved $location"
                } else {
                    "Captured version saved; newer edits remain unsaved"
                }
        }
    }
    fun reopen() {
        work({
            val location = repository.locations().firstOrNull() ?: error("No saved document yet")
            repository.load(location)
        }) { document ->
            val limitation = androidTextSliceLimitation(document)
            if (limitation != null) {
                status = "$limitation; saved file/current document retained"
                return@work
            }
            retainUnfinished()
            models += EditorViewModel(document)
            locations += freshLocation()
            active = models.lastIndex
            status = "Opened latest saved content as an unsaved independent copy; previous draft retained"
        }
    }
    private fun freshLocation() = "document-${java.util.UUID.randomUUID()}.astra"
    private fun <T> work(read: () -> T, complete: (T) -> Unit) {
        try {
            io.execute {
                val result = runCatching(read)
                main.post {
                    if (!disposed) {
                        result.fold(complete) {
                            status =
                                "IO failed: ${it.message}; current document retained"
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            status =
                "IO queue full; retry after current work finishes"
        }
    }
    fun dispose() {
        disposed = true
        models.forEach {
            it.dispatch(EditorIntent.AbortComposition)
            it.dispatch(EditorIntent.CancelPendingOnTeardown)
            it.detach()
        }
        io.shutdown()
    }
}
