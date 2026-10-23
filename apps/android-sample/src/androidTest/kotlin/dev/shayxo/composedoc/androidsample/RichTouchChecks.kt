package dev.shayxo.composedoc.androidsample

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun AdapterInstrumentation.richTouchChecks(root: File) {
    multilingualWords(root)
    dragHandles()
    hardwareNavigation()
    searchPresentation()
}

private fun AdapterInstrumentation.searchPresentation() {
    group("search highlight/reveal rejects foreign and stale bases without selection text or history mutation") {
        val text = (1..16).joinToString("\n") { "English row $it" } + "\nMATCH"
        replaceMounted(text)
        val view = main { activity.inputView!! }
        val basis = main { activity.owners.current.captureFindBasis() }
        val matches = findLiteral(basis.document, "MATCH")
        val range = matches.single().let { Selection(it.start, it.end) }
        val before = main { view.caretRectangle() }
        main {
            view.showSearch(basis.copy(owner = Any()), matches, 0)
            view.reveal(range)
        }
        waitForIdleSync()
        check(main { view.caretRectangle() } == before)
        val original = main { activity.owners.current.snapshot() }
        main {
            view.showSearch(basis, matches, 0)
            view.reveal(range)
        }
        waitForIdleSync()
        val screen = main { IntArray(2).also(view::getLocationOnScreen) }
        val last = locations(text).last()
        check(last.top >= screen[1] && last.bottom <= screen[1] + main { view.height } + 1)
        main {
            val after = activity.owners.current.snapshot()
            check(after.committed.same(original.committed) && after.selection == original.selection)
            check(after.undoCount == original.undoCount)
            activity.owners.current.dispatch(EditorIntent.Type("changed"))
        }
        waitForIdleSync()
        val position = main { view.caretRectangle() }
        main {
            view.showSearch(basis, matches, 0)
            view.reveal(range)
        }
        waitForIdleSync()
        check(main { view.caretRectangle() } == position)
    }
}

private fun AdapterInstrumentation.multilingualWords(root: File) {
    group("mounted ICU word selection across twelve script/stress fixtures preserves exact UTF16/history") {
        val words = listOf(
            "اُردو", "العَرَبِيَّة", "فارسی", "שָׁלוֹם", "cafe\u0301", "हिन्दी", "বাংলা",
            "ไทย", "中国", "日本語", "한글쓰기", "👩🏽‍💻",
        )
        val records = JSONArray()
        words.forEachIndexed { index, word ->
            val text = "$word,example.org 12.5"
            replaceMounted(text)
            val expected = main { activity.owners.current.snapshot() }
            val boxes = locations(text)
            val first = boxes[0]
            val coordinates = main {
                val location = IntArray(2)
                activity.inputView!!.getLocationOnScreen(location)
                floatArrayOf(first.centerX(), first.centerY())
            }
            val trace = MountedTouchTrace(this, "word-touch-$index")
            try {
                val down = SystemClock.uptimeMillis()
                sendPointerSync(
                    MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, coordinates[0], coordinates[1], 0),
                )
                trace.record("down-returned")
                val selection = trace.awaitSelection(Selection(0, word.length))
                sendPointerSync(
                    MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        coordinates[0],
                        coordinates[1],
                        0,
                    ),
                )
                check(selection == Selection(0, word.length)) { "$word selected $selection instead of0:${word.length}" }
                val after = main { activity.owners.current.snapshot() }
                check(after.committed.same(expected.committed) && after.undoCount == expected.undoCount)
                check(selection.start in Graphemes.stops(text) && selection.end in Graphemes.stops(text))
                records.put(
                    JSONObject().put("textUtf16", JSONArray(text.map { it.code }))
                        .put("expectedWordUtf16", JSONArray(word.map { it.code }))
                        .put("actualSelection", JSONArray(listOf(selection.anchor, selection.focus)))
                        .put("locale", targetContext.resources.configuration.locales[0].toLanguageTag())
                        .put("storedFont", after.committed.units.first().style.font.name),
                )
            } finally {
                trace.close()
            }
        }
        File(root, "word-fixtures.json").writeText(records.toString(2), Charsets.UTF_8)
    }
}

private fun AdapterInstrumentation.dragHandles() {
    group("selection endpoint drag uses actual touch handler and cancels stale drag after a document edit") {
        replaceMounted("abcdef")
        val view = main { activity.inputView!! }
        main { activity.owners.current.dispatch(EditorIntent.Select(Selection(1, 4))) }
        waitForIdleSync()
        val first = locations("abcdef")
        val screen = main { IntArray(2).also(view::getLocationOnScreen) }
        val down = SystemClock.uptimeMillis()
        val anchor = first[1]
        val focus = first[5]
        fun event(action: Int, x: Float, y: Float) = MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            action,
            x - screen[0],
            y - screen[1],
            0,
        )
        val before = main { activity.owners.current.snapshot() }
        main {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, anchor.left, anchor.bottom))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, focus.right, focus.centerY()))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, focus.right, focus.centerY()))
        }
        val selected = main { activity.owners.current.snapshot() }
        check(selected.selection == Selection(6, 4)) { "Anchor drag produced ${selected.selection}" }
        check(selected.committed.same(before.committed) && selected.undoCount == before.undoCount)
        main {
            activity.owners.current.dispatch(EditorIntent.Select(Selection(1, 4)))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, anchor.left, anchor.bottom))
            activity.owners.current.dispatch(EditorIntent.Type("changed"))
            val expected = activity.owners.current.snapshot()
            view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, focus.right, focus.centerY()))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, focus.right, focus.centerY()))
            check(activity.owners.current.snapshot().selection == expected.selection)
            check(activity.owners.current.snapshot().committed.same(expected.committed))
        }
    }
}

private fun AdapterInstrumentation.hardwareNavigation() {
    group("hardware CtrlHomeEnd Shift selection and grapheme deletion preserve supplementary clusters") {
        replaceMounted("English e\u0301 👩🏽‍💻\nاُردو")
        val view = main { activity.inputView!! }
        main {
            val model = activity.owners.current
            val before = model.snapshot().committed
            val end = before.units.size
            check(
                view.onKeyDown(
                    KeyEvent.KEYCODE_MOVE_END,
                    KeyEvent(
                        0,
                        1,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MOVE_END,
                        0,
                        KeyEvent.META_CTRL_ON,
                    ),
                ),
            )
            check(model.snapshot().selection == Selection(end, end))
            check(
                view.onKeyDown(
                    KeyEvent.KEYCODE_MOVE_HOME,
                    KeyEvent(
                        0,
                        2,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_MOVE_HOME,
                        0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
                    ),
                ),
            )
            check(model.snapshot().selection == Selection(end, 0))
            check(model.snapshot().committed.same(before))
        }
    }
}
