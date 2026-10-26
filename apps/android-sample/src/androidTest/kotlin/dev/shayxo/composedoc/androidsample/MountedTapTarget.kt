package dev.shayxo.composedoc.androidsample

import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.shayxo.composedoc.ui.AndroidEditorView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class MountedTapTarget(
    private val activity: MainActivity,
    private val view: AndroidEditorView,
    val points: FloatArray,
) {
    private val window = activity.window
    val origin = IntArray(2).also(view::getLocationOnScreen).toList()
    val width = view.width
    val height = view.height
    private val owner = view.model
    private val basis = owner.snapshot()
    val display = checkNotNull(view.display).displayId
    fun matches(checks: AdapterInstrumentation): Boolean {
        val frame = owner.snapshot()
        return checks.activity === activity &&
            activity.window === window &&
            activity.inputView === view &&
            activity.owners.current === owner &&
            view.isAttachedToWindow &&
            view.hasWindowFocus() &&
            view.hasFocus() &&
            window.decorView.findFocus() === view &&
            view.display?.displayId == display &&
            layoutMatches() &&
            frame.documentRevision == basis.documentRevision &&
            frame.committed.same(basis.committed) &&
            frame.undoCount == basis.undoCount &&
            frame.redoCount == basis.redoCount
    }
    private fun layoutMatches(): Boolean {
        val currentOrigin = IntArray(2).also(view::getLocationOnScreen)
        return currentOrigin.toList() == origin && view.width == width && view.height == height
    }
}

/** One platform shell tap; coordinates/timestamps are never substituted with a model mutation. */
internal fun AdapterInstrumentation.systemTap(target: MountedTapTarget, label: String) {
    check(Looper.myLooper() != Looper.getMainLooper())
    main {
        check(target.matches(this)) { "Captured tap target changed; no injection or retry" }
        check(target.points[0] >= target.origin[0] && target.points[0] < target.origin[0] + target.width)
        check(target.points[1] >= target.origin[1] && target.points[1] < target.origin[1] + target.height)
    }
    val command = tapCommand(target.points[0], target.points[1], target.display, tapFixture)
    val input = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
    val executor = Executors.newSingleThreadExecutor()
    try {
        val future = executor.submit<ByteArray> {
            input.use { stream ->
                val buffer = ByteArray(1025)
                var size = 0
                while (size < buffer.size) {
                    val n = stream.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                buffer.copyOf(size)
            }
        }
        val raw = future.get(8, TimeUnit.SECONDS)
        File(evidenceRoot, "$label-shell.txt").writeBytes(raw)
        check(raw.size <= 1024) { "Tap receipt exceeds 1024 bytes" }
        tapExit(raw.toString(Charsets.UTF_8))
    } finally {
        input.close()
        executor.shutdownNow()
    }
}
