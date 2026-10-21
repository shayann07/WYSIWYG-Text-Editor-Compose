package dev.shayxo.composedoc.androidsample

import android.os.SystemClock
import android.view.MotionEvent
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent

internal fun AdapterInstrumentation.richLifecycleChecks() {
    recreateStructuredSelection()
    recreateWithComposition()
    edgeAutoscroll()
}

private fun AdapterInstrumentation.recreateStructuredSelection() {
    group("recreation retains backward structured selection, attributed history and authoritative owner") {
        replaceMounted("Heading\nاُردو English")
        val owners = main { activity.owners }
        val before = main {
            owners.current.dispatch(EditorIntent.Select(Selection(7, 0)))
            owners.current.dispatch(EditorIntent.Structure(StructureCommand.Role(2)))
            owners.current.dispatch(EditorIntent.Select(Selection(18, 8)))
            owners.current.snapshot()
        }
        recreateMounted()
        main {
            check(activity.owners === owners)
            val after = owners.current.snapshot()
            check(after.committed.same(before.committed) && after.selection == before.selection)
            check(after.undoCount == before.undoCount && after.redoCount == before.redoCount)
        }
    }
}

private fun AdapterInstrumentation.recreateWithComposition() {
    group("recreation retires active native callback and durably retains its separate unfinished proposal") {
        replaceMounted("accepted اُردو")
        val model = main { activity.owners.current }
        val old = main { connection(activity.inputView!!) }
        val accepted = main { model.snapshot().committed }
        val proposal = main {
            check(old.setSelection(accepted.units.size, accepted.units.size))
            check(old.setComposingText("仮入力", 1))
            checkNotNull(model.captureRecoveryDraft()).document
        }
        recreateMounted()
        main {
            check(activity.owners.current === model)
            check(model.snapshot().committed.same(accepted))
            check(!old.commitText("STALE", 1))
            check(model.compositionCaptureToken() == null)
        }
        val store = main { activity.owners.recoveryStore }
        waitUntil { store.entries().any { it.hasProposal } }
        check(
            store.entries().filter { it.hasProposal }.any { entry ->
                store.read(entry.owner, entry.sequence).let {
                    it.document.same(accepted) &&
                        it.proposal?.same(proposal) == true
                }
            },
        )
    }
}

private fun AdapterInstrumentation.edgeAutoscroll() {
    group("actual endpoint drag autoscroll extends beyond initial viewport without text/history mutation") {
        replaceMounted((1..32).joinToString("\n") { "English row $it اُردو" })
        val model = main { activity.owners.current }
        main { model.dispatch(EditorIntent.Select(Selection(0, 0))) }
        waitUntil { main { activity.inputView!!.caretRectangle()?.top ?: -1f } >= 0 }
        main { model.dispatch(EditorIntent.Select(Selection(0, 4))) }
        waitForIdleSync()
        val view = main { activity.inputView!! }
        val caret = main { checkNotNull(view.caretRectangle()) }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) =
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        val before = main { model.snapshot() }
        main {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, caret.left, caret.bottom))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, caret.left, view.height - 1f))
        }
        SystemClock.sleep(100)
        val first = main { model.snapshot().selection.focus }
        waitUntil { main { model.snapshot().selection.focus > first } }
        main {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, caret.left, view.height - 1f))
            val after = model.snapshot()
            check(after.committed.same(before.committed) && after.undoCount == before.undoCount)
            check(after.selection.anchor == 0)
        }
    }
}

private fun AdapterInstrumentation.recreateMounted() {
    val previous = activity
    main { activity.recreate() }
    waitUntil {
        val next = MainActivity.currentActivity?.get()
        next != null && next !== previous && main { next.inputView != null }
    }
    activity = checkNotNull(MainActivity.currentActivity!!.get())
}
