package dev.shayxo.composedoc.androidsample

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val CARET_COLOR_RGB = 0x2559ba
private const val RGB_MASK = 0xffffff
private const val SAMPLE_WIDTH = 3
private const val SAMPLE_HEIGHT = 5
private const val BLINK_SAMPLES = 20
private const val SAMPLE_GAP_MS = 80L
private const val COPY_TIMEOUT_SECONDS = 3L

internal fun AdapterInstrumentation.caretChecks() {
    group("rendered insertion caret blinks, hides on blur and never changes history") {
        replaceMounted("")
        val view = main { activity.inputView!! }
        main { view.requestFocus() }
        waitUntil { main { view.hasFocus() && view.hasWindowFocus() && view.caretRectangle() != null } }
        val before = main { activity.owners.current.snapshot() }
        val visible = (0 until BLINK_SAMPLES).map {
            SystemClock.sleep(SAMPLE_GAP_MS)
            caretInk()
        }
        check(visible.any { it } && visible.any { !it }) { "Caret pixels never entered both blink phases: $visible" }
        try {
            main {
                view.isFocusable = false
                view.clearFocus()
            }
            waitUntil { main { !view.hasFocus() } }
            repeat(SAMPLE_WIDTH) {
                SystemClock.sleep(SAMPLE_GAP_MS)
                check(!caretInk()) { "Caret remained painted after editor lost focus" }
            }
        } finally {
            main {
                view.isFocusable = true
                view.requestFocus()
            }
        }
        val after = main { activity.owners.current.snapshot() }
        check(after.committed.same(before.committed) && after.selection == before.selection)
        check(after.undoCount == before.undoCount && after.redoCount == before.redoCount)
    }
}

private fun AdapterInstrumentation.caretInk(): Boolean {
    val region = main {
        val view = activity.inputView!!
        val caret = checkNotNull(view.caretRectangle())
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val x = location[0] + caret.left.toInt()
        val y = location[1] + ((caret.top + caret.bottom) / 2).toInt()
        Rect(x, y, x + SAMPLE_WIDTH, y + SAMPLE_HEIGHT)
    }
    val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
    val done = CountDownLatch(1)
    var result = PixelCopy.ERROR_UNKNOWN
    try {
        PixelCopy.request(activity.window, region, bitmap, {
            result = it
            done.countDown()
        }, Handler(Looper.getMainLooper()))
        check(done.await(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        return (0 until SAMPLE_HEIGHT).any { y ->
            (0 until SAMPLE_WIDTH).any { x -> bitmap.getPixel(x, y) and RGB_MASK == CARET_COLOR_RGB }
        }
    } finally {
        bitmap.recycle()
    }
}
