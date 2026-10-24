package dev.shayxo.composedoc.androidsample

import android.graphics.Rect
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Screen-coordinate occlusion oracle for the mounted fullscreen/adjust-resize test window. */
internal class MountedKeyboardProbe(private val checks: AdapterInstrumentation) {
    private val baseline = checks.main { visibleFrame() }
    private val records = JSONArray()

    init {
        record("hidden-baseline")
        check(additionalOcclusion() == 0) { "System bars alone must not satisfy keyboard occlusion" }
    }

    fun additionalOcclusion(): Int = checks.main { baseline.bottom - visibleFrame().bottom }

    fun record(stage: String) {
        checks.main {
            val view = checkNotNull(checks.activity.inputView)
            val visible = visibleFrame()
            val display = DisplayMetrics()
            checks.activity.windowManager.defaultDisplay.getRealMetrics(display)
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val insets = view.rootWindowInsets
            val caret = view.caretRectangle()
            records.put(
                JSONObject().put("stage", stage).put("visibleFrameScreen", coordinates(visible))
                    .put("baselineFrameScreen", coordinates(baseline))
                    .put("additionalOcclusion", baseline.bottom - visible.bottom)
                    .put("realDisplayBounds", JSONArray(listOf(0, 0, display.widthPixels, display.heightPixels)))
                    .put("hostOriginScreen", JSONArray(origin.toList()))
                    .put("hostSize", JSONArray(listOf(view.width, view.height)))
                    .put("softInputMode", checks.activity.window.attributes.softInputMode)
                    .put(
                        "stableInsets",
                        JSONArray(
                            listOf(
                                insets?.stableInsetLeft,
                                insets?.stableInsetTop,
                                insets?.stableInsetRight,
                                insets?.stableInsetBottom,
                            ),
                        ),
                    )
                    .put(
                        "systemInsets",
                        JSONArray(
                            listOf(
                                insets?.systemWindowInsetLeft,
                                insets?.systemWindowInsetTop,
                                insets?.systemWindowInsetRight,
                                insets?.systemWindowInsetBottom,
                            ),
                        ),
                    )
                    .put("caretBottomScreen", caret?.let { origin[1] + it.bottom }),
            )
        }
        write()
    }

    fun write() {
        File(checks.evidenceRoot, "keyboard-geometry.json").writeText(records.toString(2), Charsets.UTF_8)
    }

    private fun visibleFrame() = Rect().also { checks.activity.inputView!!.getWindowVisibleDisplayFrame(it) }
    private fun coordinates(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
}
