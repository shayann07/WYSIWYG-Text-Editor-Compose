package dev.shayxo.composedoc.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidPath
import dev.shayxo.composedoc.domain.FindBasis
import dev.shayxo.composedoc.domain.FindMatch
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection

/** Highlights/reveal never dispatch an edit or select an interior grapheme match. */
internal class AndroidSearchPresentation(private val host: AndroidEditorView) {
    private var basis: FindBasis? = null
    private var matches = emptyList<FindMatch>()
    private var active = -1
    private val paint = Paint()

    fun show(value: FindBasis?, found: List<FindMatch>, index: Int) {
        basis = value
        matches = found
        active = index
        refresh()
        host.invalidate()
    }
    private fun current(): Boolean {
        val captured = basis ?: return false
        val frame = host.model.snapshot()
        val now = host.model.captureFindBasis()
        return captured.owner === now.owner &&
            captured.revision == now.revision &&
            captured.document == now.document &&
            frame.marked == null &&
            !frame.waiting &&
            host.model.compositionCaptureToken() == null
    }
    fun refresh() {
        if (!current()) {
            basis = null
            matches = emptyList()
            active = -1
        }
    }
    fun reveal(range: Selection) {
        // A bare offset has no owner/basis. Admit it only as a match from the still-current search capture.
        if (!current() || matches.none { it.start == range.start && it.end == range.end }) return
        val frame = host.model.snapshot()
        val g = host.geometry?.takeIf { it.basisRevision == frame.documentRevision } ?: return
        val at = Graphemes.snap(frame.visible.text, range.start.coerceIn(0, frame.visible.units.size), after = false)
        val caret = g.caret(at)
        val margin = REVEAL_DP * host.resources.displayMetrics.density
        val top = caret.top - host.scrollOffset
        val bottom = caret.bottom - host.scrollOffset
        val delta = when {
            bottom > host.height - margin -> bottom - host.height + margin
            top < margin -> top - margin
            else -> 0f
        }
        if (delta != 0f) host.scrollBy(delta)
        host.post { host.notifyCursor() }
    }
    fun draw(canvas: Canvas) {
        if (!current()) return
        val frame = host.model.snapshot()
        val g = host.geometry?.takeIf { it.basisRevision == frame.documentRevision } ?: return
        canvas.save()
        canvas.translate(0f, -host.scrollOffset)
        matches.forEachIndexed { index, match ->
            if (match.start >= 0 && match.start < match.end && match.end <= frame.visible.units.size) {
                paint.color = if (index == active) ACTIVE else OTHER
                for (p in g.paragraphs) {
                    val a = maxOf(match.start, p.block.start) - p.block.start
                    val b = minOf(match.end, p.block.end) - p.block.start
                    if (a < b && p.image == null) {
                        canvas.save()
                        canvas.translate(p.left, p.top)
                        canvas.drawPath(p.layout.getPathForRange(a, b).asAndroidPath(), paint)
                        canvas.restore()
                    }
                }
            }
        }
        canvas.restore()
    }
    companion object {
        private const val REVEAL_DP = 16f
        private const val ACTIVE = 0x66ff9e32
        private const val OTHER = 0x44ffe46b
    }
}
