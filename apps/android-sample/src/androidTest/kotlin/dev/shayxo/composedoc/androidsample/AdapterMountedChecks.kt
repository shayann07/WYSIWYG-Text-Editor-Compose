package dev.shayxo.composedoc.androidsample

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent

internal fun AdapterInstrumentation.mountedChecks() {
    mountedChecksCase1()
    mountedChecksCase2()
    mountedChecksCase3()
    mountedChecksCase4()
    mountedChecksCase5()
    mountedChecksCase6()
}

private fun AdapterInstrumentation.mountedChecksCase1() {
    group("editable accessibility exposes exact text/selection and executes set-text/set-selection actions") {
        replaceMounted("abcd")
        main {
            val view = activity.inputView!!
            val node = view.createAccessibilityNodeInfo()
            check(
                node.isEditable &&
                    node.text.toString() == "abcd",
            )
            check(
                view.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                            3,
                        )
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                            1,
                        )
                    },
                ),
            )
            check(
                activity.owners.current.snapshot().selection == Selection(
                    3,
                    1,
                ),
            )
            check(
                view.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            "اُردو 42",
                        )
                    },
                ),
            )
            check(activity.owners.current.snapshot().committed.text == "اُردو 42")
        }
    }
}

private fun AdapterInstrumentation.mountedChecksCase2() {
    mountedHitChecks()
}

internal fun AdapterInstrumentation.mountedHitChecks() {
    group("instrumented touch hit uses independent Latin prefix advance") {
        replaceMounted("abcd")
        val coordinates = main {
            val v = activity.inputView!!
            val screen = IntArray(2)
            v.getLocationOnScreen(screen)
            val inset = 16f * targetContext.resources.displayMetrics.density
            floatArrayOf(
                screen[0] + inset + referencePaint().measureText("ab") + 0.5f,
                screen[1] + inset + referencePaint().fontSpacing / 2,
            )
        }
        val t = SystemClock.uptimeMillis()
        sendPointerSync(
            MotionEvent.obtain(
                t,
                t,
                MotionEvent.ACTION_DOWN,
                coordinates[0],
                coordinates[1],
                0,
            ),
        )
        val downDeliveredAt = SystemClock.uptimeMillis()
        sendPointerSync(
            MotionEvent.obtain(
                t,
                t + 40,
                MotionEvent.ACTION_UP,
                coordinates[0],
                coordinates[1],
                0,
            ),
        )
        val upDeliveredAt = SystemClock.uptimeMillis()
        waitForIdleSync()
        val actual = main { activity.owners.current.snapshot().selection }
        check(actual == Selection(2, 2)) {
            "Expected independent tap caret2; actual=$actual; " +
                "downDeliveryMs=${downDeliveredAt - t}; upDeliveryMs=${upDeliveredAt - t}; " +
                "sent=${coordinates.toList()}; ${main { touchHitEnvironment() }}"
        }
        assertSuppressedLongPressCannotPass()
        assertLongPressDrag()
    }
}

private fun AdapterInstrumentation.touchHitEnvironment(): String {
    val view = checkNotNull(activity.inputView)
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return "currentOrigin=${location.toList()}; size=${view.width}x${view.height}; " +
        "attached=${view.isAttachedToWindow}; focus=${view.hasWindowFocus()}; " +
        "revision=${activity.owners.current.snapshot().documentRevision}; caret=${view.caretRectangle()}"
}

private fun AdapterInstrumentation.assertLongPressDrag() {
    replaceMounted("ab cd")
    val before = main { activity.owners.current.snapshot() }
    val points = main {
        val view = activity.inputView!!
        val screen = IntArray(2)
        view.getLocationOnScreen(screen)
        val inset = 16f * targetContext.resources.displayMetrics.density
        val paint = referencePaint()
        floatArrayOf(
            screen[0] + inset + paint.measureText("a") / 2,
            screen[1] + inset + paint.fontSpacing / 2,
            screen[0] + inset + paint.measureText("ab cd") + 0.5f,
        )
    }
    val trace = MountedTouchTrace(this, "latin-longpress")
    try {
        val down = SystemClock.uptimeMillis()
        sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, points[0], points[1], 0))
        trace.record("down-returned")
        val selected = trace.awaitSelection(Selection(0, 2))
        check(selected == Selection(0, 2))
        val move = SystemClock.uptimeMillis()
        sendPointerSync(MotionEvent.obtain(down, move, MotionEvent.ACTION_MOVE, points[2], points[1], 0))
        sendPointerSync(MotionEvent.obtain(down, move + 40, MotionEvent.ACTION_UP, points[2], points[1], 0))
        waitForIdleSync()
        val after = main { activity.owners.current.snapshot() }
        check(after.selection == Selection(0, 5)) { "Long-press drag stayed at ${after.selection}" }
        check(after.committed.same(before.committed) && after.undoCount == before.undoCount)
    } finally {
        trace.close()
    }
}

private fun AdapterInstrumentation.assertSuppressedLongPressCannotPass() {
    replaceMounted("ab cd")
    val trace = MountedTouchTrace(this, "suppressed-longpress", suppressDelivery = true)
    try {
        val point = locations("ab cd").first()
        val down = SystemClock.uptimeMillis()
        sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point.centerX(), point.centerY(), 0))
        val failure = runCatching { trace.awaitSelection(Selection(0, 2)) }.exceptionOrNull()
        check(
            failure is IllegalStateException &&
                failure.message?.startsWith("Timed out awaiting exact selection") == true,
        )
        check(main { activity.owners.current.snapshot().selection } == Selection(0, 0))
    } finally {
        trace.close()
    }
}

private fun AdapterInstrumentation.mountedChecksCase3() {
    mountedKeyboardChecks()
}

internal fun AdapterInstrumentation.mountedKeyboardChecks() {
    group("IME activation and caret reveal respect independent platform keyboard bounds") {
        replaceMounted((1..20).joinToString("\n") { "کتاب العربية English $it" })
        main {
            val h = activity.inputView!!
            val manager = targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(h.windowToken, 0)
        }
        waitUntil { main { imeBottom() } == 0 }
        waitForIdleSync()
        val keyboard = MountedKeyboardProbe(this)
        val before = main { activity.inputView!!.height }
        main {
            val v = activity.owners.current
            val end = v.snapshot().visible.units.size
            v.dispatch(EditorIntent.Select(Selection(end, end)))
            activity.inputView!!.showKeyboard()
        }
        waitUntil { main { imeBottom() } > 0 }
        waitUntil { keyboard.additionalOcclusion() > 0 }
        waitUntil { main { caretInsideViewport() } }
        waitUntil { main { caretAboveKeyboard() } }
        check(main { activity.inputView!!.height } > 0) { "No editing viewport above keyboard; prior height=$before" }
        keyboard.record("shown")
        main {
            val view = activity.inputView!!
            val manager = targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        waitUntil { main { imeBottom() } == 0 && keyboard.additionalOcclusion() == 0 }
        keyboard.record("hidden-again")
        keyboard.write()
        assertClosedConnectionReopens()
    }
}
private fun AdapterInstrumentation.imeBottom(): Int {
    val view = activity.inputView!!
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        return view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
    }
    val display = android.util.DisplayMetrics()
    activity.windowManager.defaultDisplay.getRealMetrics(display)
    val visible = android.graphics.Rect()
    view.getWindowVisibleDisplayFrame(visible)
    val obscured = display.heightPixels - visible.bottom
    val navigation = view.rootWindowInsets?.stableInsetBottom ?: 0
    return if (obscured > navigation) obscured else 0
}
private fun AdapterInstrumentation.caretInsideViewport(): Boolean {
    val view = activity.inputView!!
    val caret = view.caretRectangle()
    return caret != null && caret.top >= -1 && caret.bottom <= view.height + 1
}
private fun AdapterInstrumentation.caretAboveKeyboard(): Boolean {
    val view = activity.inputView!!
    val caret = view.caretRectangle() ?: return false
    val screen = IntArray(2)
    view.getLocationOnScreen(screen)
    val display = android.util.DisplayMetrics()
    activity.windowManager.defaultDisplay.getRealMetrics(display)
    val visible = android.graphics.Rect()
    view.getWindowVisibleDisplayFrame(visible)
    val keyboardTop = minOf(display.heightPixels - imeBottom(), visible.bottom)
    return imeBottom() > 0 && caretInsideViewport() && screen[1] + caret.bottom <= keyboardTop + 1
}

private fun AdapterInstrumentation.mountedChecksCase4() {
    group("two mounted owners and stale callbacks remain independent through focus switching") {
        val original = main { activity.owners.current }
        val old = main { connection(activity.inputView!!) }
        main { activity.owners.active = 1 }
        waitUntil { main { activity.inputView?.model === activity.owners.models[1] } }
        main {
            check(
                !old.commitText(
                    "LATE",
                    1,
                ),
            )
            check(activity.owners.current.snapshot().committed.text == "")
            check(original.snapshot().committed.text.isNotEmpty())
        }
        main { activity.owners.active = 0 }
        waitUntil { main { activity.inputView?.model === original } }
    }
}

private fun AdapterInstrumentation.mountedChecksCase5() {
    group("Activity recreation retains owner/text/attributed history and retires old connection") {
        val owners = main { activity.owners }
        val before = main { owners.current.snapshot() }
        val old = main { connection(activity.inputView!!) }
        val previous = activity
        main { activity.recreate() }
        waitUntil {
            val next = MainActivity.currentActivity?.get()
            next != null &&
                next !== previous &&
                main { next.inputView != null }
        }
        activity = MainActivity.currentActivity!!.get()!!
        main {
            check(activity.owners === owners)
            check(owners.current.snapshot().committed.same(before.committed))
            check(owners.current.snapshot().undoCount == before.undoCount)
            check(
                !old.commitText(
                    "STALE",
                    1,
                ),
            )
        }
    }
}

private fun AdapterInstrumentation.mountedChecksCase6() {
    group("background/foreground retains committed owner without serializing a live native session") {
        val owners = main { activity.owners }
        val before = main { owners.current.snapshot().committed }
        main { activity.moveTaskToBack(true) }
        waitForIdleSync()
        targetContext.startActivity(
            Intent(
                targetContext,
                MainActivity::class.java,
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
        waitUntil { main { MainActivity.currentActivity?.get()?.hasWindowFocus() == true } }
        activity = MainActivity.currentActivity!!.get()!!
        main {
            check(activity.owners === owners)
            check(owners.current.snapshot().committed.same(before))
            check(owners.current.compositionCaptureToken() == null)
        }
    }
}

private fun AdapterInstrumentation.assertClosedConnectionReopens() {
    val before = main { activity.owners.current.snapshot() }
    val old = main { connection(activity.inputView!!) }
    val creations = main { activity.owners.nativeEvents.count { it.startsWith("createInputConnection") } }
    main {
        old.closeConnection()
        activity.inputView!!.showKeyboard()
    }
    waitUntil {
        main { activity.owners.nativeEvents.count { it.startsWith("createInputConnection") } > creations }
    }
    main {
        check(!old.commitText("STALE", 1))
        check(activity.owners.current.snapshot().committed.same(before.committed))
        check(activity.owners.current.snapshot().undoCount == before.undoCount)
    }
}

internal fun AdapterInstrumentation.closedConnectionCheck() {
    group("explicit keyboard activation replaces a closed connection without reviving stale callbacks") {
        assertClosedConnectionReopens()
    }
}
