package dev.shayxo.composedoc.androidsample

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shayxo.composedoc.data.AndroidClipboardFiles
import dev.shayxo.composedoc.data.AndroidDocumentRepository
import dev.shayxo.composedoc.data.AndroidRecoveryStore
import dev.shayxo.composedoc.data.AndroidUriRepository
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.androidTextSliceLimitation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class AndroidTab(val model: EditorViewModel, val id: String = UUID.randomUUID().toString()) {
    var name by mutableStateOf("Untitled")
    var uri: String? = null
    var sourceHash: String? = null
    var saved by mutableStateOf<Document?>(null)
    var operation = 0L
    var saveGeneration = 0L
    var saveInFlight by mutableStateOf(false)
    var notice by mutableStateOf("")
    var sequence = 0L
    var recovery by mutableStateOf("Recovery pending")
    val dirty get() = saved?.same(model.snapshot().committed) != true
}

/** Application controller. Captures survive Activity recreation; models own document state. */
class AndroidOwners(val directory: File, blank: Boolean = false) {
    val models = mutableStateListOf<EditorViewModel>()
    internal val tabs = mutableStateListOf<AndroidTab>()
    var active by mutableIntStateOf(0)
    var status by mutableStateOf("New/Open keep existing drafts in independent tabs")
    val unfinished = mutableStateListOf<RecoveredDraft>()
    val nativeEvents = ArrayDeque<String>()
    internal val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val observers = HashMap<String, Job>()
    internal var recoverableCount by mutableIntStateOf(0)
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(IO_QUEUE))
    internal val safetyRoot = File(directory.parentFile, "save-safety")
    internal val recoveryStore = AndroidRecoveryStore(File(directory.parentFile, "rich-recovery"))
    internal val checkpoints = AndroidCheckpoints(this, recoveryStore)
    internal var uriRepository: AndroidUriRepository? = null
    internal var clipboardFiles: AndroidClipboardFiles? = null
    internal var disposed = false
    internal val pickers = HashMap<Int, AndroidPickerRequest>()
    internal var nextRequest = FIRST_REQUEST
    init {
        add(Document(emptyList()))
        add(Document(emptyList()))
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
    internal val tab: AndroidTab get() = tabs[active]
    internal fun attach(context: Context) {
        uriRepository = AndroidUriRepository(context, safetyRoot)
        clipboardFiles = AndroidClipboardFiles(context, "${context.packageName}.clipboard")
        work({ recoveryStore.entries().size }) { count ->
            recoverableCount = count
            if (count > 0) status = "$count local recovery drafts available in Recovery; restore opens an unsaved copy"
        }
    }
    internal fun add(
        document: Document,
        name: String = "Untitled",
        uri: String? = null,
        hash: String? = null,
        activate: Boolean = false,
    ): AndroidTab {
        check(tabs.size < MAX_TABS) { "Eight editing tabs are open. Save/close a tab before opening another." }
        check(androidTextSliceLimitation(document) == null) { androidTextSliceLimitation(document).orEmpty() }
        val entry = AndroidTab(EditorViewModel(document)).also {
            it.name = name
            it.uri = uri
            it.sourceHash = hash
            it.saved =
                if (uri != null) document else null
        }
        tabs += entry
        models += entry.model
        observers[entry.id] = scope.launch { entry.model.uiState.collect { checkpoints.changed(entry) } }
        if (activate) active = tabs.lastIndex
        return entry
    }
    internal fun newDocument() {
        retainUnfinished()
        runCatching { add(Document(emptyList()), activate = true) }.fold(
            onSuccess = { status = "New unsaved document; previous draft retained" },
            onFailure = {
                status =
                    it.message.orEmpty()
            },
        )
    }
    internal fun closeDocument(entry: AndroidTab, complete: (RecoveryCloseOutcome) -> Unit = {}) {
        val captured = entry.model.snapshot()
        entry.model.captureRecoveryDraft()?.let(::retainDraft)
        checkpoints.changed(entry)
        checkpoints.keep(listOf(entry)) { outcome ->
            if (!outcome.durable) {
                note(entry.model, outcome.reason)
                complete(outcome)
                return@keep
            }
            val index = tabs.indexOfFirst { it === entry }
            if (index >= 0 && entry.model.snapshot().sessionRevision == captured.sessionRevision) {
                observers.remove(entry.id)?.cancel()
                checkpoints.closedOwner(entry.id)
                entry.model.dispatch(EditorIntent.AbortComposition)
                entry.model.dispatch(EditorIntent.CancelPendingOnTeardown)
                entry.model.detach()
                tabs.removeAt(index)
                models.removeAt(index)
                if (tabs.isEmpty()) add(Document(emptyList()))
                active = active.coerceAtMost(tabs.lastIndex)
                status = "Tab closed after checkpoint; its recovery remains available"
                complete(outcome)
            } else {
                note(entry.model, "Close cancelled: writing changed while checkpointing; draft retained")
                complete(RecoveryCloseOutcome.cancelled("Writing changed while checkpointing; draft retained"))
            }
        }
    }
    fun nativeEvent(value: String) {
        if (nativeEvents.size == MAX_NATIVE_EVENTS) nativeEvents.removeFirst()
        nativeEvents.addLast(value)
    }
    internal fun note(owner: EditorViewModel, value: String) {
        nativeEvent(value)
        tabs.firstOrNull { it.model === owner }?.notice =
            value
        if (current === owner) status = value
    }
    fun retainUnfinished() {
        current.captureRecoveryDraft()?.let(::retainDraft)
        checkpoints.changed(tab)
    }
    fun retainDraft(draft: RecoveredDraft) {
        if (unfinished.none {
                val content = it.ownerScope === draft.ownerScope &&
                    it.basis.same(draft.basis) &&
                    it.document.same(draft.document)
                content && it.selection == draft.selection
            }
        ) {
            unfinished += draft
            checkpoints.retained(
                draft,
                tabs.firstOrNull { it.model.captureFindBasis().owner === draft.ownerScope }?.name ?: "Unfinished input",
            )
        }
    }
    fun openUnfinishedCopy(index: Int) {
        retainUnfinished()
        add(unfinished[index].document, "Recovered proposal (unsaved)", activate = true)
        status = "Separate proposal copy opened; original evidence retained"
    }
    internal fun saveBound(): Boolean {
        val entry = tab
        val uri = entry.uri ?: return false
        saveTo(entry, uri, entry.sourceHash)
        return true
    }
    internal fun saveTo(
        entry: AndroidTab,
        uri: String,
        expected: String?,
        captured: Document? = null,
        complete: (() -> Unit)? = null,
    ) {
        val frame = entry.model.snapshot()
        if (captured == null &&
            (entry.model.compositionCaptureToken() != null || frame.waiting)
        ) {
            note(entry.model, "Finish the keyboard candidate before Save; proposal remains recoverable")
            return
        }
        if (entry.saveInFlight) {
            note(entry.model, "A captured save is already running; newer edits remain recoverable")
            return
        }
        val operation = ++entry.saveGeneration
        val accepted = captured ?: frame.committed
        entry.saveInFlight = true
        val repository = checkNotNull(uriRepository)
        note(entry.model, "Saving captured document; later edits remain independent…")
        val queued = work({
            runCatching { repository.write(uri, accepted, expected) }
        }, entry.model) { result ->
            if (tabs.none { it === entry } || entry.saveGeneration != operation) return@work
            entry.saveInFlight = false
            val digest = result.getOrElse {
                note(entry.model, "Save failed: ${it.message}; safety copies and draft retained")
                return@work
            }
            entry.uri = uri
            entry.sourceHash = digest
            entry.saved = accepted
            note(
                entry.model,
                if (entry.dirty) {
                    "Captured save verified; newer edits remain dirty"
                } else {
                    "Saved and verified provider bytes"
                },
            )
            complete?.invoke()
        }
        if (!queued) entry.saveInFlight = false
    }

    /** Private-copy compatibility action for executable integration callers; UI Save uses SAF. */
    fun save() {
        if (saveBound()) return
        val entry = tab
        val frame = entry.model.snapshot()
        if (entry.model.compositionCaptureToken() != null ||
            frame.waiting
        ) {
            status = "Finish composing before Save"
            return
        }
        val location = "document-${UUID.randomUUID()}.astra"
        work({
            AndroidDocumentRepository(directory).save(location, frame.committed)
        }) { note(entry.model, "Private copy saved; use Save As for a provider document") }
    }
    fun reopen() {
        work({
            val repository = AndroidDocumentRepository(directory)
            repository.load(
                repository.locations().firstOrNull() ?: error("No private copy yet"),
            )
        }) {
            add(it, "Private copy (unsaved)", activate = true)
        }
    }
    internal fun <T> work(read: () -> T, owner: EditorViewModel = current, complete: (T) -> Unit): Boolean {
        val capturedOwner = owner
        try {
            io.execute {
                val result = runCatching(read)
                main.post {
                    if (!disposed) {
                        result.fold(
                            onSuccess = {
                                runCatching { complete(it) }.onFailure { failure ->
                                    status = "Result refused; work retained: ${failure.message}"
                                }
                            },
                            onFailure = {
                                note(capturedOwner, "IO failed: ${it.message}; drafts and recovery retained")
                            },
                        )
                    }
                }
            }
            return true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            status =
                "Document IO queue full; retry after pending work"
            return false
        }
    }
    fun dispose() {
        retainUnfinished()
        tabs.forEach { checkpoints.changed(it) }
        checkpoints.close()
        disposed = true
        scope.cancel()
        models.forEach {
            it.dispatch(EditorIntent.AbortComposition)
            it.dispatch(EditorIntent.CancelPendingOnTeardown)
            it.detach()
        }
        io.shutdown()
    }
    companion object {
        private const val MAX_NATIVE_EVENTS = 256
        private const val MAX_TABS = 8
        private const val IO_QUEUE = 4
        private const val FIRST_REQUEST = 1000
    }
}
