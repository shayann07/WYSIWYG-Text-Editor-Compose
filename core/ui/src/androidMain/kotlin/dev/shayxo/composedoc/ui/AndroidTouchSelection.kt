package dev.shayxo.composedoc.ui

import android.icu.text.BreakIterator
import android.view.ActionMode
import android.view.GestureDetector
import android.view.MotionEvent
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import java.util.Locale
import kotlin.math.abs

/** ICU chooses words; the pinned application segmenter alone chooses legal edit boundaries. */
internal fun androidWordSelection(document: Document, offset: Int, locale: Locale): Selection {
    val text = document.text
    if (text.isEmpty()) return Selection(0, 0)
    val at = offset.coerceIn(0, text.lastIndex)
    return if (document.units[at].kind != UnitKind.TEXT) {
        Selection(at, at + 1)
    } else {
        wordAt(text, at, locale)
    }
}

private fun wordAt(text: String, at: Int, locale: Locale): Selection {
    val iterator = BreakIterator.getWordInstance(locale).apply { setText(text) }
    val end = iterator.following(at).takeIf { it != BreakIterator.DONE } ?: text.length
    val start = iterator.previous().coerceAtLeast(0)
    val word = text.substring(start, end).any { it.isLetterOrDigit() }
    val a = Graphemes.snap(text, if (word) start else at, after = false)
    val b = Graphemes.snap(text, if (word) end else at + 1, after = true)
    return Selection(a, b)
}

/** Touch presentation state only. Every selection still goes through the authoritative owner. */
internal class AndroidTouchSelection(private val host: AndroidEditorView) {
    private enum class Drag { NONE, ANCHOR, FOCUS }
    private var drag = Drag.NONE
    private var fixed = 0
    private var basis = -1L
    private var pointerX = 0f
    private var pointerY = 0f
    private var scrolling = false
    private val scale get() = host.resources.displayMetrics.density
    private val scrollTick = object : Runnable {
        override fun run() {
            if (drag == Drag.NONE || !host.isAttachedToWindow) {
                scrolling = false
                return
            }
            val edge = EDGE_DP * scale
            val delta = when {
                pointerY < edge -> -minOf(MAX_SCROLL_DP * scale, (edge - pointerY) / EDGE_SPEED_DIVISOR)
                pointerY > host.height - edge -> minOf(
                    MAX_SCROLL_DP * scale,
                    (pointerY - host.height + edge) / EDGE_SPEED_DIVISOR,
                )
                else -> 0f
            }
            if (delta != 0f) {
                host.scrollBy(delta)
                extend()
            }
            host.postOnAnimation(this)
        }
    }
    private val gestures = GestureDetector(
        host.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val at = host.hit(e.x, e.y)
                val block = host.geometry?.paragraph(at)?.block
                val image = block?.let {
                    host.model.snapshot().visible.units.getOrNull(it.start)?.kind == UnitKind.IMAGE
                }
                val selection = if (block != null &&
                    image == true
                ) {
                    Selection(block.start, block.end)
                } else {
                    Selection(at, at)
                }
                host.model.dispatch(EditorIntent.Select(selection))
                if (image != true) host.showKeyboard()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val frame = host.model.snapshot()
                val locale = host.resources.configuration.locales[0] ?: Locale.getDefault()
                val at = host.geometry?.wordHit(e.x, e.y + host.scrollOffset) ?: host.hit(e.x, e.y)
                val range = androidWordSelection(frame.visible, at, locale)
                host.model.dispatch(EditorIntent.Select(range))
                begin(Drag.FOCUS, range.anchor)
                host.startActionMode(AndroidSelectionActions(host), ActionMode.TYPE_FLOATING)
            }
            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (drag == Drag.NONE) host.scrollBy(distanceY)
                return true
            }
        },
    )

    fun event(event: MotionEvent): Boolean {
        pointerX = event.x
        pointerY = event.y
        if (event.actionMasked == MotionEvent.ACTION_DOWN && endpoint(event.x, event.y)) return true
        val handled = if (drag == Drag.NONE) gestures.onTouchEvent(event) else false
        val moving = event.actionMasked == MotionEvent.ACTION_MOVE && drag != Drag.NONE
        if (moving) extend()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            stop()
            if (event.actionMasked == MotionEvent.ACTION_UP) host.performClick()
        }
        return handled || moving || event.actionMasked == MotionEvent.ACTION_UP || drag != Drag.NONE
    }

    private fun endpoint(x: Float, y: Float): Boolean {
        val frame = host.model.snapshot()
        val geometry =
            host.geometry?.takeIf { it.basisRevision == frame.documentRevision && !frame.selection.collapsed }
                ?: return false
        val a = geometry.caret(frame.selection.anchor)
        val b = geometry.caret(frame.selection.focus)
        val radius = HANDLE_TARGET_DP * scale
        fun near(left: Float, bottom: Float) = abs(left - x) <= radius && abs(bottom - host.scrollOffset - y) <= radius
        return when {
            near(a.left, a.bottom) -> {
                begin(Drag.ANCHOR, frame.selection.focus)
                true
            }
            near(b.left, b.bottom) -> {
                begin(Drag.FOCUS, frame.selection.anchor)
                true
            }
            else -> false
        }
    }

    private fun begin(kind: Drag, opposite: Int) {
        drag = kind
        fixed = opposite
        basis = host.model.snapshot().documentRevision
        host.parent?.requestDisallowInterceptTouchEvent(true)
        if (!scrolling) {
            scrolling = true
            host.postOnAnimation(scrollTick)
        }
    }

    private fun extend() {
        if (host.model.snapshot().documentRevision != basis) {
            stop()
            return
        }
        val at = host.hit(pointerX, pointerY)
        val selection = if (drag == Drag.ANCHOR) Selection(at, fixed) else Selection(fixed, at)
        host.model.dispatch(EditorIntent.Select(selection))
    }

    fun stop() {
        drag = Drag.NONE
        scrolling = false
        host.removeCallbacks(scrollTick)
        host.parent?.requestDisallowInterceptTouchEvent(false)
    }

    companion object {
        private const val HANDLE_TARGET_DP = 24f
        private const val EDGE_DP = 44f
        private const val MAX_SCROLL_DP = 18f
        private const val EDGE_SPEED_DIVISOR = 3f
    }
}
