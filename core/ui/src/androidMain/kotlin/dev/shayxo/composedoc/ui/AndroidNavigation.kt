package dev.shayxo.composedoc.ui

import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection

/** Application navigation uses pinned grapheme stops and the current platform's measured caret positions. */
internal fun AndroidEditorView.navigate(event: KeyEvent): Boolean {
    val keys = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
    )
    if (event.keyCode !in keys) return false
    val frame = model.snapshot()
    geometry?.takeIf { it.basisRevision == frame.documentRevision }?.let { navigateMeasured(event, frame, it) }
    return true
}

private fun AndroidEditorView.navigateMeasured(event: KeyEvent, frame: EditorFrame, g: AndroidEditorGeometry) {
    val selection = frame.selection
    val focus = selection.focus
    val p = g.paragraph(focus)
    val local = (focus - p.block.start).coerceIn(0, p.layout.layoutInput.text.length)
    val line = p.layout.getLineForOffset(local)
    val stops = Graphemes.stops(frame.visible.text)
    val caret = g.caret(focus)
    fun lineStops(): List<Int> = stops.filter { at ->
        at in p.block.start..p.block.end && p.layout.getLineForOffset(at - p.block.start) == line
    }.sortedWith(compareBy<Int> { g.caret(it).left }.thenBy { it })
    val at = when (event.keyCode) {
        KeyEvent.KEYCODE_MOVE_HOME -> if (event.isCtrlPressed) 0 else p.block.start + p.layout.getLineStart(line)
        KeyEvent.KEYCODE_MOVE_END -> if (event.isCtrlPressed) {
            frame.visible.units.size
        } else {
            p.block.start +
                p.layout.getLineEnd(line, visibleEnd = true)
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
            verticalTarget(g, caret, event, height)
        }
        else -> {
            val candidates = lineStops()
            val index = candidates.indexOf(focus)
            val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            candidates.getOrNull(index + if (right) 1 else -1)
                ?: stops.getOrNull(stops.indexOf(focus) + if (right) 1 else -1) ?: focus
        }
    }
    val target = Graphemes.snap(frame.visible.text, at.coerceIn(0, frame.visible.units.size), after = true)
    model.dispatch(EditorIntent.Select(Selection(if (event.isShiftPressed) selection.anchor else target, target)))
}

private fun verticalTarget(g: AndroidEditorGeometry, caret: Rect, event: KeyEvent, height: Int): Int {
    val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN
    val page = event.keyCode == KeyEvent.KEYCODE_PAGE_UP || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN
    val advance = if (page) height.toFloat() else (caret.height + LINE_FUZZ)
    return g.hit(caret.left, if (down) caret.bottom + advance / 2 else caret.top - advance / 2)
}

private const val LINE_FUZZ = 2f
