package dev.shayxo.composedoc.androidsample

import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One bounded snapshot per existing stage, no polling, connection probes, focus or IME requests. */
internal class MountedKeyboardTrace(private val checks: AdapterInstrumentation) {
    private var baseline: IntArray? = null
    private var request: JSONObject? = null
    private var failed = false
    private val identities = KeyboardTraceIdentities()
    private val recorder = KeyboardTraceRecorder(::capture, write = { text ->
        check(Looper.myLooper() != Looper.getMainLooper()) { "Trace disk write on UI thread" }
        File(checks.evidenceRoot, "keyboard-immediate-state.json").writeText(text, Charsets.UTF_8)
        if (failed) failureLog()
    })

    fun <T> around(block: () -> T): T = recorder.around(block)
    fun record(stage: String) = recorder.record(stage)
    fun baseline(value: IntArray) {
        baseline = value.copyOf()
        record("hidden-baseline")
    }

    /** Wraps the existing single request, recording its receipt rather than inferring visibility. */
    fun <T> request(name: String, action: () -> T): T {
        record("before-$name")
        val started = SystemClock.uptimeMillis()
        try {
            return action().also { value ->
                request = JSONObject().put("name", name).put("startedUptime", started)
                    .put("finishedUptime", SystemClock.uptimeMillis())
                    .put("receipt", if (value is Boolean) value else "Unit: no public return from showKeyboard")
            }
        } finally {
            record("after-$name")
        }
    }

    private fun capture(stage: String): String {
        if (stage == "failure") failed = true
        return checks.main {
            val activity = checks.activity
            val view = activity.inputView
            val window = activity.window
            val visible = Rect()
            view?.getWindowVisibleDisplayFrame(visible)
            val origin = IntArray(2)
            view?.getLocationOnScreen(origin)
            val insets = view?.rootWindowInsets
            val frame = view?.model?.snapshot()
            val caret = view?.caretRectangle()
            JSONObject().put("stage", stage).put("uptime", SystemClock.uptimeMillis())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("elapsedRealtime", SystemClock.elapsedRealtime())
                .put("activity", identity(activity))
                .put("resumedActivity", identity(MainActivity.currentActivity?.get()))
                .put("window", identity(window)).put("decor", identity(window.decorView))
                .put("windowToken", identity(view?.windowToken)).put("owner", identity(view?.model))
                .put("currentOwner", identity(activity.owners.current)).put("view", identity(view))
                .put("rootView", identity(view?.rootView)).put("focusedView", identity(window.decorView.findFocus()))
                .put("lifecycle", activity.lifecycle.currentState.name)
                .put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
                .put("attached", view?.isAttachedToWindow).put("shown", view?.isShown)
                .put("viewFocus", view?.hasFocus()).put("windowFocus", view?.hasWindowFocus())
                // isActive/isAcceptingText call checkFocus(), which can create a connection.
                // Do not perturb the witness with a connection probe.
                .put("servedActive", "NOT_QUERIED: public InputMethodManager queries call checkFocus")
                .put(
                    "imeBottom",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets?.getInsets(WindowInsets.Type.ime())?.bottom
                    } else {
                        null
                    },
                )
                .put(
                    "imeVisible",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        insets?.isVisible(WindowInsets.Type.ime())
                    } else {
                        null
                    },
                )
                .put("visibleFrame", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
                .put("baselineFrame", baseline?.let { JSONArray(it.toList()) })
                .put("additionalOcclusion", baseline?.let { it[3] - visible.bottom })
                .put("origin", JSONArray(origin.toList())).put("width", view?.width).put("height", view?.height)
                .put("softInputMode", window.attributes.softInputMode)
                .put("caret", caret?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
                .put("revision", frame?.documentRevision).put("undoCount", frame?.undoCount)
                .put("selection", frame?.let { JSONArray(listOf(it.selection.anchor, it.selection.focus)) })
                .put("lastRequest", request).toString()
        }
    }

    private fun identity(value: Any?): Int? = identities.of(value)

    private fun failureLog() {
        val command = "timeout 3 logcat -d -v epoch -t 200 ImeTracker:I InputMethodManager:W ComposeDocStartup:I '*:S'"
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(checks.uiAutomation.executeShellCommand(command)).use {
            val buffer = ByteArray(32769)
            var size = 0
            while (size < buffer.size) {
                val count = it.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        File(checks.evidenceRoot, "keyboard-immediate-logcat.txt").writeBytes(bytes.copyOf(minOf(bytes.size, 32768)))
        check(bytes.size <= 32768) { "Immediate keyboard log exceeds 32768 bytes; retained bounded prefix" }
        val text = bytes.toString(Charsets.UTF_8)
        check(!text.contains("Permission denied") && !text.contains("timeout:") && !text.startsWith("logcat:")) {
            "Immediate keyboard log unavailable; raw failure retained"
        }
    }
}
