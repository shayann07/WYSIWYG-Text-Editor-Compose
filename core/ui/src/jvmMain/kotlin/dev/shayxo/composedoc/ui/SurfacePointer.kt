package dev.shayxo.composedoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.shayxo.composedoc.layout.Geometry
import dev.shayxo.composedoc.layout.ParagraphLayout
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TypingSide
import dev.shayxo.composedoc.model.WrapAffinity
import java.awt.Point
import java.awt.event.KeyEvent
import java.text.BreakIterator
import kotlin.math.abs

/** Primary and secondary bidi caret positions closer than this share one navigation stop. */
private const val SPLIT_CARET_EPSILON = .5f

/** Page Up/Down moves by the viewport height less this margin, but at least the minimum, in AWT pixels. */
private const val PAGE_STEP_MARGIN = 48
private const val MIN_PAGE_STEP = 32

/** A candidate visual caret edge for a pointer hit. */
private data class HitEdge(val offset: Int, val vertical: Float, val horizontal: Float)

/** A caret stop in visual order, used by keyboard navigation. */
private data class CaretStop(
    val offset: Int,
    val x: Float,
    val line: Int,
    val primary: Boolean,
    val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM,
)

/** The selection, its legal focus stop and every caret stop for one navigation key press. */
private class NavigationContext(
    val g: Geometry,
    val all: List<CaretStop>,
    val selection: Selection,
    val text: String,
    val legalFocus: Int,
    val current: Int,
) {
    val here: CaretStop = all[current]
}

/** Vertical distance from [y] to the span [top]..[bottom]; zero inside it. */
internal fun verticalDistance(y: Float, top: Float, bottom: Float): Float = if (y < top) {
    top - y
} else if (y > bottom) {
    y - bottom
} else {
    0f
}

internal fun EditorSurface.isCurrentGeometry(cached: Geometry, presentationKey: String): Boolean =
    cached.typographyKey == presentationKey &&
        cached.revision == published.documentRevision &&
        cached.scroll == view.scroll &&
        (width <= 0 || cached.hostWidth == width) &&
        (height <= 0 || cached.hostHeight == height)

internal fun EditorSurface.measureGeometry(presentationKey: String): Geometry? {
    val measured = try {
        view.measure?.invoke(published)
    } catch (failure: IllegalArgumentException) {
        view.layoutError = failure.message
        null
    }
    return measured?.takeIf { it.typographyKey == presentationKey }?.also { view.geometry = it }
}

internal fun EditorSurface.hit(point: Point): Int? {
    val g = geometry() ?: return null
    val targetY = point.y * g.scale - g.y
    val targetX = point.x * g.scale - g.x
    val fragment = g.fragmentAtY(targetY)
    val p = g.paragraphs[fragment.paragraphIndex]
    val localY = (targetY - fragment.top + fragment.sourceTop).coerceIn(fragment.sourceTop, fragment.sourceBottom)
    val localX = (targetX - p.contentLeft).coerceIn(0f, p.layout.size.width.toFloat())
    val localOffset = if (p.textLength == 0) {
        0
    } else {
        p.layout.getOffsetForPosition(Offset(localX, localY)).coerceIn(0, p.textLength)
    }
    return if (p.image != null) p.startOffset else refineHit(g, p, point, p.toDocumentOffset(localOffset))
}

private fun EditorSurface.refineHit(g: Geometry, p: ParagraphLayout, point: Point, docOffset: Int): Int {
    val stops = Graphemes.stops(published.visible.text)
    val nearest = stops.indexOfFirst { it >= docOffset }.let { if (it < 0) stops.lastIndex else it }
    // Shaped hit results can choose the adjacent logical edge under an explicit
    // opposite base direction. Refine locally using actual visual caret edges.
    val x = point.x * g.scale
    val y = point.y * g.scale
    val edges = ArrayList<HitEdge>()
    for (i in maxOf(0, nearest - 2)..minOf(stops.lastIndex, nearest + 2)) {
        val at = stops[i]
        if (at in p.startOffset..p.endOffset) edges += hitEdgesAt(g, p, at, x, y)
    }
    return edges.minWithOrNull(
        compareBy<HitEdge> { it.vertical }.thenBy { it.horizontal }.thenBy { abs(it.offset - docOffset) },
    )?.offset ?: docOffset
}

private fun hitEdgesAt(g: Geometry, p: ParagraphLayout, at: Int, x: Float, y: Float): List<HitEdge> {
    val affinities = if (p.isSoftWrapBoundary(p.toLocalOffset(at))) {
        WrapAffinity.entries
    } else {
        listOf(WrapAffinity.DOWNSTREAM)
    }
    val edges = ArrayList<HitEdge>()
    for (primary in listOf(true, false)) {
        for (affinity in affinities) {
            val caret = g.caret(at, primary, affinity)
            edges += HitEdge(at, verticalDistance(y, caret.top, caret.bottom), abs(caret.left - x))
        }
    }
    return edges
}

internal fun EditorSurface.pointerSide(point: Point, offset: Int): TypingSide {
    val g = geometry() ?: return TypingSide.LEFT
    val p = g.paragraphForOffset(offset)
    val local = p.toLocalOffset(offset)
    val rightOfCaret = point.x * g.scale >= g.caret(offset).left
    val rtl = p.layout.getBidiRunDirection(local) == ResolvedTextDirection.Rtl
    return if (rightOfCaret != rtl) TypingSide.RIGHT else TypingSide.LEFT
}

internal fun EditorSurface.pointerWrapAffinity(point: Point, offset: Int): WrapAffinity {
    val g = geometry() ?: return WrapAffinity.DOWNSTREAM
    val p = g.paragraphForOffset(offset)
    return if (p.isSoftWrapBoundary(p.toLocalOffset(offset))) {
        nearerWrapAffinity(g, point, offset)
    } else {
        WrapAffinity.DOWNSTREAM
    }
}

private fun nearerWrapAffinity(g: Geometry, point: Point, offset: Int): WrapAffinity {
    val y = point.y * g.scale
    val upstream = g.caret(offset, wrapAffinity = WrapAffinity.UPSTREAM)
    val downstream = g.caret(offset)
    val upstreamDistance = abs(y - (upstream.top + upstream.bottom) / 2)
    val downstreamDistance = abs(y - (downstream.top + downstream.bottom) / 2)
    return if (upstreamDistance < downstreamDistance) WrapAffinity.UPSTREAM else WrapAffinity.DOWNSTREAM
}

private fun EditorSurface.caretStops(g: Geometry): List<CaretStop> = Graphemes.stops(published.visible.text)
    .flatMap { offset ->
        val p = g.paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val localLine = if (p.textLength == 0) 0 else p.layout.getLineForOffset(local)
        val globalLine = p.startLineIndex + localLine
        val primary = g.getHorizontalPosition(offset, true)
        val secondary = g.getHorizontalPosition(offset, false)
        val regularStops = listOf(CaretStop(offset, primary, globalLine, true, WrapAffinity.DOWNSTREAM)) +
            if (abs(primary - secondary) > SPLIT_CARET_EPSILON) {
                listOf(CaretStop(offset, secondary, globalLine, false, WrapAffinity.DOWNSTREAM))
            } else {
                emptyList()
            }
        if (p.isSoftWrapBoundary(local)) {
            val upstreamLine = p.layout.getLineForOffset(local - 1)
            val box = p.layout.getBoundingBox(local - 1)
            val rtl = p.layout.getBidiRunDirection(local - 1) == ResolvedTextDirection.Rtl
            val cx = p.contentLeft + if (rtl) box.left else box.right
            val upstreamGlobalLine = p.startLineIndex + upstreamLine
            listOf(CaretStop(offset, cx, upstreamGlobalLine, true, WrapAffinity.UPSTREAM)) + regularStops
        } else {
            regularStops
        }
    }
    .sortedWith(compareBy<CaretStop> { it.line }.thenBy { it.x }.thenBy { it.offset }.thenBy { it.wrapAffinity })

internal fun EditorSurface.navigate(key: Int, extend: Boolean, command: Boolean) {
    val g = geometry() ?: return
    val s = published.selection
    val text = published.visible.text
    val legalFocus = Graphemes.snap(text, s.focus, key in arrayOf(KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_END))
    val all = caretStops(g)
    val current = all.indexOfFirst {
        it.offset == legalFocus && it.primary == s.primary && it.wrapAffinity == s.wrapAffinity
    }.let { if (it < 0) all.indexOfFirst { p -> p.offset == legalFocus }.coerceAtLeast(0) else it }
    val target = navigationTarget(NavigationContext(g, all, s, text, legalFocus, current), key, extend, command)
    if (key !in arrayOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN)) preferredX = null
    val anchor = if (extend) Graphemes.snap(text, s.anchor, false) else target.offset
    viewModel.dispatch(
        EditorIntent.Select(
            Selection(anchor, target.offset, target.primary, wrapAffinity = target.wrapAffinity),
            target.primary,
            if (target.offset < s.focus) TypingSide.RIGHT else TypingSide.LEFT,
        ),
    )
}

private fun EditorSurface.navigationTarget(
    nav: NavigationContext,
    key: Int,
    extend: Boolean,
    command: Boolean,
): CaretStop {
    val all = nav.all
    val here = nav.here
    return when {
        command && key == KeyEvent.VK_HOME -> all.first { it.offset == 0 }
        command && key == KeyEvent.VK_END -> all.first { it.offset == nav.text.length }
        command && key in arrayOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT) -> wordTarget(nav, key)
        key == KeyEvent.VK_LEFT || key == KeyEvent.VK_RIGHT -> horizontalTarget(nav, key, extend)
        key == KeyEvent.VK_PAGE_UP || key == KeyEvent.VK_PAGE_DOWN -> pageTarget(nav, key)
        key == KeyEvent.VK_HOME -> all.first { it.line == here.line }
        key == KeyEvent.VK_END -> all.last { it.line == here.line }
        else -> lineTarget(nav, key)
    }
}

private fun wordTarget(nav: NavigationContext, key: Int): CaretStop {
    val text = nav.text
    val words = BreakIterator.getWordInstance().apply { setText(text) }
    val p = if (key == KeyEvent.VK_LEFT) {
        words.preceding(nav.selection.focus).let { if (it < 0) 0 else it }
    } else {
        words.following(nav.selection.focus).let { if (it < 0) text.length else it }
    }
    val snapped = Graphemes.snap(text, p, key == KeyEvent.VK_RIGHT)
    return nav.all.first { it.offset == snapped }
}

private fun horizontalTarget(nav: NavigationContext, key: Int, extend: Boolean): CaretStop {
    val s = nav.selection
    val all = nav.all
    return if (!extend && !s.collapsed) {
        val endpoints = all.filter { it.primary && (it.offset == s.start || it.offset == s.end) }
        if (key == KeyEvent.VK_LEFT) endpoints.first() else endpoints.last()
    } else if (nav.legalFocus != s.focus) {
        all[nav.current]
    } else {
        all[(nav.current + if (key == KeyEvent.VK_LEFT) -1 else 1).coerceIn(0, all.lastIndex)]
    }
}

private fun EditorSurface.pageTarget(nav: NavigationContext, key: Int): CaretStop {
    val g = nav.g
    val here = nav.here
    val x = preferredX ?: here.x.also { preferredX = it }
    val delta =
        (height - PAGE_STEP_MARGIN).coerceAtLeast(MIN_PAGE_STEP) * g.scale * (if (key == KeyEvent.VK_PAGE_UP) -1 else 1)
    val y = g.caret(here.offset).top + delta
    val line = nav.all.minByOrNull { abs(g.caret(it.offset).top - y) }?.line ?: here.line
    return nav.all.filter { it.line == line }.minByOrNull { abs(it.x - x) } ?: here
}

private fun EditorSurface.lineTarget(nav: NavigationContext, key: Int): CaretStop {
    val here = nav.here
    val x = preferredX ?: here.x.also { preferredX = it }
    val line = (here.line + if (key == KeyEvent.VK_UP) -1 else 1).coerceIn(0, nav.g.totalLineCount - 1)
    return nav.all.filter { it.line == line }.minByOrNull { abs(it.x - x) } ?: here
}

internal fun EditorSurface.wordDelete(back: Boolean) {
    val text = published.visible.text
    val at = published.selection.focus
    val word = BreakIterator.getWordInstance().apply { setText(text) }
    val other = (if (back) word.preceding(at) else word.following(at)).let {
        if (it < 0) if (back) 0 else text.length else it
    }
    viewModel.dispatch(
        EditorIntent.Type(
            "",
            "Delete",
            range = Selection(at, other),
            deleteSide = if (back) TypingSide.LEFT else TypingSide.RIGHT,
        ),
    )
}
