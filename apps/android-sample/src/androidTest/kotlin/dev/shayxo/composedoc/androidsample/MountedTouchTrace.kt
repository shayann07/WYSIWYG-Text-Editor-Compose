package dev.shayxo.composedoc.androidsample

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Selection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Observes and delegates the existing window callback; never replaces a View's touch listener. */
internal class MountedTouchTrace(
    private val checks: AdapterInstrumentation,
    private val label: String,
    private val suppressDelivery: Boolean = false,
) : AutoCloseable {
    private val records = JSONArray()
    private val view = checks.main { checkNotNull(checks.activity.inputView) }
    private val window = checks.main { checks.activity.window }
    private val previous = checks.main { checkNotNull(window.callback) }
    private val owner = view.model
    private val basis = checks.main { owner.snapshot() }
    private val previousPublication = checks.main { owner.onStatePublished }
    private var expected: Selection? = null
    private val publication = CountDownLatch(1)
    private val observedPublication: (EditorFrame) -> Unit = { frame ->
        previousPublication(frame)
        record("owner-published")
        if (frame.selection == expected || !sameBasis(frame)) publication.countDown()
    }
    private var held: MotionEvent? = null
    private val observer = object : Window.Callback by previous {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            held?.recycle()
            held = if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                null
            } else {
                MotionEvent.obtain(event)
            }
            record("delivered-before", event)
            if (suppressDelivery) {
                record("delivery-suppressed", event)
                return true
            }
            return previous.dispatchTouchEvent(event).also { record("delivered-after", event) }
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            previous.onWindowFocusChanged(hasFocus)
            record("window-focus-$hasFocus")
        }
    }

    init {
        checks.main {
            window.callback = observer
            owner.onStatePublished = observedPublication
        }
        record("before")
    }

    /** A fixed two-second synchronization deadline, not a user-facing latency qualification. */
    fun awaitSelection(selection: Selection): Selection {
        checks.main {
            expected = selection
            val frame = owner.snapshot()
            check(sameBasis(frame)) { "Touch changed the captured owner/document/history" }
            if (frame.selection == selection) publication.countDown()
        }
        val received = publication.await(2, TimeUnit.SECONDS)
        val actual = checks.main {
            val frame = owner.snapshot()
            check(sameBasis(frame)) { "Touch changed the captured owner/document/history" }
            frame.selection
        }
        record(if (received) "publication-received" else "publication-deadline")
        check(received && actual == selection) { "Timed out awaiting exact selection $selection; actual=$actual" }
        return actual
    }

    private fun sameBasis(frame: EditorFrame) = checks.activity.owners.current === owner &&
        frame.documentRevision == basis.documentRevision && frame.committed.same(basis.committed) &&
        frame.undoCount == basis.undoCount && frame.redoCount == basis.redoCount

    fun record(stage: String, event: MotionEvent? = null) {
        checks.main {
            val frame = checks.activity.owners.current.snapshot()
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val value = JSONObject().put("stage", stage).put("uptime", SystemClock.uptimeMillis())
                .put("longPressTimeout", ViewConfiguration.getLongPressTimeout())
                .put("tapTimeout", ViewConfiguration.getTapTimeout())
                .put("origin", JSONArray(origin.toList())).put("width", view.width).put("height", view.height)
                .put("attached", view.isAttachedToWindow).put("windowFocus", view.hasWindowFocus())
                .put("viewFocus", view.hasFocus())
                .put("revision", frame.documentRevision).put("caret", view.caretRectangle().toString())
                .put("selection", JSONArray(listOf(frame.selection.anchor, frame.selection.focus)))
                .put("nativeEvents", JSONArray(checks.activity.owners.nativeEvents.takeLast(8)))
            event?.let {
                value.put("action", it.actionMasked).put("downTime", it.downTime).put("eventTime", it.eventTime)
                    .put("source", it.source).put("flags", it.flags).put("x", it.x).put("y", it.y)
                    .put("rawX", it.rawX).put("rawY", it.rawY)
            }
            records.put(value)
        }
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let(failures::add)
        }
        try {
            attempt { cancelGesture() }
            attempt { record("finished") }
        } finally {
            // Diagnostic failure must never prevent restoring the real callbacks or recycling events.
            attempt { restoreCallbacks() }
            attempt { File(checks.evidenceRoot, "$label.json").writeText(records.toString(2), Charsets.UTF_8) }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun cancelGesture() {
        val cancel = checks.main {
            held?.let {
                MotionEvent.obtain(
                    it.downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL,
                    it.rawX,
                    it.rawY,
                    0,
                )
            }
        }
        cancel?.let { checks.sendPointerSync(it) }
    }

    private fun restoreCallbacks() {
        checks.main {
            if (window.callback === observer) window.callback = previous
            if (owner.onStatePublished === observedPublication) owner.onStatePublished = previousPublication
            held?.recycle()
            held = null
        }
    }
}
