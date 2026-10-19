package dev.shayxo.composedoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.shayxo.composedoc.layout.Geometry
import dev.shayxo.composedoc.layout.ParagraphFragment
import dev.shayxo.composedoc.layout.ParagraphLayout
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.WrapAffinity
import java.awt.IllegalComponentStateException
import java.awt.Point
import java.awt.Rectangle
import java.awt.font.TextHitInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A candidate caret edge inside the native composition, in raw UTF-16 offsets. */
private data class NativeEdge(val at: Int, val dy: Float, val dx: Float)

/** Top-left x, top y and bottom y of the box reported to the input method. */
private data class CaretBox(val x: Float, val y: Float, val bottom: Float)

private fun EditorSurface.screenLocation(): Point? = try {
    if (!isShowing) null else locationOnScreen
} catch (_: IllegalComponentStateException) {
    null
}

/** [java.awt.im.InputMethodRequests.getTextLocation] for this host. */
internal fun EditorSurface.compositionTextLocation(offset: TextHitInfo?): Rectangle? {
    val g = geometry()
    val screen = if (g != null) screenLocation() else null
    return if (g == null || screen == null) null else compositionRectangle(g, screen, offset)
}

private fun EditorSurface.compositionRectangle(g: Geometry, screen: Point, offset: TextHitInfo?): Rectangle {
    val mark = published.marked
    val s = published.selection
    val at = if (mark != null) {
        mark.first + (offset?.insertionIndex ?: 0).also { require(it in 0..mark.count()) }
    } else {
        s.focus
    }
    val caret = g.caret(
        at,
        primary = if (mark != null) true else s.primary,
        wrapAffinity = if (mark != null) WrapAffinity.DOWNSTREAM else s.wrapAffinity,
    )
    val box = if (mark == null && !s.collapsed && g.paragraphForOffset(s.focus).image == null) {
        selectionLineBox(g, s)
    } else {
        CaretBox(caret.left, caret.top, caret.bottom)
    }
    return Rectangle(
        screen.x + (box.x / g.scale).roundToInt(),
        screen.y + (box.y / g.scale).roundToInt(),
        0,
        ((box.bottom - box.y) / g.scale).roundToInt().coerceAtLeast(1),
    )
}

/** The selected run on the line holding the selection end, as the composition anchor box. */
private fun selectionLineBox(g: Geometry, s: Selection): CaretBox {
    val anchorEnd = s.end
    val p = g.paragraphForOffset(anchorEnd)
    val localEnd = p.toLocalOffset(anchorEnd)
    val line = if (localEnd > 0 && localEnd == p.textLength && p.hasBreak) {
        p.layout.lineCount - 1
    } else {
        p.layout.getLineForOffset(localEnd)
    }
    val a = max(s.start, p.startOffset + p.layout.getLineStart(line))
    val b = min(s.end, p.startOffset + p.layout.getLineEnd(line, true))
    val localA = p.toLocalOffset(a)
    val localB = p.toLocalOffset(b)
    val x1 = p.layout.getHorizontalPosition(localA, true)
    val x2 = p.layout.getHorizontalPosition(localB, true)
    val rtl = p.layout.getParagraphDirection(localA) == ResolvedTextDirection.Rtl
    val x = g.x + p.contentLeft + if (rtl) max(x1, x2) else min(x1, x2)
    val y = g.y + g.paragraphTop(p, line) + p.layout.getLineTop(line)
    val bottom = g.y + g.paragraphTop(p, line) + p.layout.getLineBottom(line)
    return CaretBox(x, y, bottom)
}

/** [java.awt.im.InputMethodRequests.getLocationOffset] for this host. */
internal fun EditorSurface.compositionLocationOffset(x: Int, y: Int): TextHitInfo? {
    val mark = published.marked ?: return null
    val g = geometry()
    val screen = if (g != null) screenLocation() else null
    return if (g == null || screen == null) null else CompositionHitTest(g, mark, screen, x, y).hit()
}

/** Maps one screen point onto the marked (composing) range of [g]. */
private class CompositionHitTest(private val g: Geometry, private val mark: IntRange, screen: Point, x: Int, y: Int) {
    private val px = (x - screen.x) * g.scale
    private val py = (y - screen.y) * g.scale
    private val targetX = (x - screen.x) * g.scale - g.x
    private val targetY = (y - screen.y) * g.scale - g.y

    fun hit(): TextHitInfo? = g.fragments.firstNotNullOfOrNull { fragment ->
        if (targetY < fragment.top || targetY > fragment.top + fragment.height) null else fragmentHit(fragment)
    }

    private fun fragmentHit(fragment: ParagraphFragment): TextHitInfo? {
        val p = g.paragraphs[fragment.paragraphIndex]
        val markA = max(mark.first, p.startOffset)
        val markB = min(mark.last + 1, p.endOffset)
        return when {
            p.image != null && markA < markB -> imageHit(fragment, p)
            markA < markB -> textHit(fragment, p, p.toLocalOffset(markA), p.toLocalOffset(markB))
            else -> null
        }
    }

    private fun imageHit(fragment: ParagraphFragment, p: ParagraphLayout): TextHitInfo? {
        val left = g.x + p.imageLeft
        val top = g.y + fragment.top
        if (px !in left..left + p.imageWidth || py !in top..top + p.height) return null
        val before = if (p.baseDirection == ResolvedTextDirection.Rtl) {
            px >= left + p.imageWidth / 2
        } else {
            px <= left + p.imageWidth / 2
        }
        val at = if (before)p.startOffset else p.endOffset
        return TextHitInfo.leading(at - mark.first)
    }

    private fun textHit(fragment: ParagraphFragment, p: ParagraphLayout, localA: Int, localB: Int): TextHitInfo? {
        val localP = Offset(targetX - p.contentLeft, targetY - fragment.top + fragment.sourceTop)
        val inAnyBox = (localA until localB).any { i ->
            p.layout.getBoundingBox(i).contains(localP)
        }
        if (!inAnyBox) return null
        val localHit = p.layout.getOffsetForPosition(localP).coerceIn(localA, localB)
        val edges = ArrayList<NativeEdge>()
        // Native ranges are raw UTF-16, so do not apply grapheme snapping here.
        for (at in maxOf(localA, localHit - 2)..minOf(localB, localHit + 2)) {
            for (primary in listOf(true, false)) {
                val caret = g.caret(p.toDocumentOffset(at), primary)
                edges += NativeEdge(at, verticalDistance(py, caret.top, caret.bottom), abs(caret.left - px))
            }
        }
        val exact = edges.minWithOrNull(
            compareBy<NativeEdge> { it.dy }.thenBy { it.dx }.thenBy { abs(it.at - localHit) },
        )?.at ?: localHit
        val docHit = p.toDocumentOffset(exact)
        return TextHitInfo.leading(docHit - mark.first)
    }
}
