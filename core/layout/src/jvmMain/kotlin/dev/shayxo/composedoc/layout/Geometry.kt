@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.layout

import androidx.compose.runtime.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.model.*
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import javax.accessibility.*
import javax.swing.*
import kotlin.math.*

fun displayText(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
            append(c)
            append(text[++i])
        } else {
            append(if (Character.isSurrogate(c)) '\ufffd' else c)
        }
        i++
    }
}

fun paragraphAttributedDisplay(units: List<TextUnit>): AnnotatedString = buildAnnotatedString {
    val doc = Document(units)
    append(displayText(doc.text))
    val runs = displayedStyles(doc)
    var start = 0
    while (start < runs.size) {
        var end = start + 1
        while (end < runs.size && runs[end].start == runs[end - 1].end && runs[end].style == runs[start].style) end++
        val style = runs[start].style
        addStyle(storedSpan(style), runs[start].start, runs[end - 1].end)
        start = end
    }
}

fun attributedDisplay(document: Document): AnnotatedString = buildAnnotatedString {
    append(displayText(document.text))
    val runs = displayedStyles(document)
    var start = 0
    while (start < runs.size) {
        var end = start + 1
        while (end < runs.size && runs[end].start == runs[end - 1].end && runs[end].style == runs[start].style) end++
        val style = runs[start].style
        addStyle(storedSpan(style), runs[start].start, runs[end - 1].end)
        start = end
    }
}

fun paragraphBaseDirection(units: List<TextUnit>, fallback: ResolvedTextDirection): ResolvedTextDirection = if (resolveParagraphDirection(units, ParagraphProperties(), if (fallback == ResolvedTextDirection.Rtl) ParagraphDirection.RTL else ParagraphDirection.LTR) == ParagraphDirection.RTL) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr
fun paragraphAlignment(properties: ParagraphProperties, rtl: Boolean): TextAlign = when (properties.alignment) {
    ParagraphAlignment.CENTER -> TextAlign.Center
    ParagraphAlignment.START -> if (rtl) TextAlign.Right else TextAlign.Left
    ParagraphAlignment.END -> if (rtl) TextAlign.Left else TextAlign.Right
}

data class ParagraphSpan(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val hasBreak: Boolean,
    val units: List<TextUnit>,
    val properties: ParagraphProperties = ParagraphProperties(),
)

fun partitionParagraphs(document: Document): List<ParagraphSpan> = document.paragraphs().map { ParagraphSpan(it.index, it.start, it.end, it.hasBreak, document.units.subList(it.start, it.end), it.properties) }

data class ParagraphLayout(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val hasBreak: Boolean,
    val text: String,
    val annotatedString: AnnotatedString,
    val baseDirection: ResolvedTextDirection,
    val layout: TextLayoutResult,
    val top: Float,
    val height: Float,
    val startLineIndex: Int,
    val image: ImageBlock? = null,
    val imageWidth: Float = 0f,
    val imageLeft: Float = 0f,
    val contentLeft: Float = 0f,
    val contentWidth: Float = 0f,
    val listColumn: ListColumn? = null,
    val marker: ListMarkerLayout? = null,
) {
    val bottom: Float get() = top + height
    val textLength: Int get() = endOffset - startOffset
    val totalLength: Int get() = textLength + (if (hasBreak) 1 else 0)
    val breakOffset: Int? get() = if (hasBreak) endOffset else null
    val lineCount: Int get() = maxOf(1, layout.lineCount)

    fun toLocalOffset(documentOffset: Int): Int = (documentOffset - startOffset).coerceIn(0, textLength)
    fun toDocumentOffset(localOffset: Int): Int = (startOffset + localOffset).coerceIn(startOffset, endOffset)
    fun isSoftWrapBoundary(local: Int): Boolean = local in 1 until textLength && text[local - 1] != '\n' && layout.getLineForOffset(local) > layout.getLineForOffset(local - 1)
}

data class Geometry(
    val paragraphs: List<ParagraphLayout>,
    val totalHeight: Float,
    val x: Float,
    val y: Float,
    val scale: Float,
    val revision: Long,
    val textWidth: Int,
    val scroll: Float = 0f,
    val hostWidth: Int = 0,
    val hostHeight: Int = 0,
    val typographyKey: String = TypographyProfile.system.key + ":continuous",
    val fragments: List<ParagraphFragment> = paragraphs.map { ParagraphFragment(it.index, 0, it.lineCount - 1, 0f, it.height, it.top, 0) },
    val pageHeight: Float = 0f,
    val pageGap: Float = 0f,
    val pageCount: Int = 1,
    val documentPagePlan: DocumentPagePlan? = null,
) {
    val layout: TextLayoutResult get() = paragraphs.first().layout
    val totalLineCount: Int get() = paragraphs.sumOf { it.lineCount }
    fun paragraphTop(p: ParagraphLayout, line: Int): Float = fragments.first { it.paragraphIndex == p.index && line in it.firstLine..it.lastLine }.let { it.top - it.sourceTop }
    fun fragmentAtY(contentY: Float): ParagraphFragment = fragments.firstOrNull { contentY in it.top..(it.top + it.height) }
        ?: fragments.minBy { if (contentY < it.top) it.top - contentY else contentY - it.top - it.height }

    fun paragraphForOffset(documentOffset: Int): ParagraphLayout {
        if (paragraphs.isEmpty()) throw IllegalStateException("Geometry has no paragraphs")
        if (documentOffset <= 0) return paragraphs.first()
        for (p in paragraphs) {
            if (documentOffset in p.startOffset..p.endOffset) return p
        }
        return paragraphs.last()
    }

    fun getLineForOffset(documentOffset: Int, wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM): Int {
        val p = paragraphForOffset(documentOffset)
        val local = p.toLocalOffset(documentOffset)
        if (p.textLength == 0) return p.startLineIndex
        val line = if (wrapAffinity == WrapAffinity.UPSTREAM && p.isSoftWrapBoundary(local)) {
            p.layout.getLineForOffset(local - 1)
        } else {
            p.layout.getLineForOffset(local)
        }
        return p.startLineIndex + line
    }

    fun getParagraphDirection(documentOffset: Int): ResolvedTextDirection = paragraphForOffset(documentOffset).baseDirection

    fun getHorizontalPosition(documentOffset: Int, primary: Boolean = true): Float {
        val p = paragraphForOffset(documentOffset)
        if (p.image != null)return p.imageLeft + (if ((documentOffset == p.startOffset) == (p.baseDirection == ResolvedTextDirection.Rtl))p.imageWidth else 0f)
        return p.contentLeft + p.layout.getHorizontalPosition(p.toLocalOffset(documentOffset), primary)
    }

    fun breakAndEmptySelectionRects(selection: Selection): List<androidx.compose.ui.geometry.Rect> {
        if (selection.collapsed) return emptyList()
        val s = selection
        val rects = ArrayList<androidx.compose.ui.geometry.Rect>()
        val stripWidth = 8f * scale
        for (p in paragraphs) {
            if (p.image != null) {
                if (p.hasBreak && s.start <= p.endOffset && s.end > p.endOffset) {
                    val c = caret(p.endOffset)
                    val rtl = p.baseDirection == ResolvedTextDirection.Rtl
                    rects += androidx.compose.ui.geometry.Rect(c.left - if (rtl)stripWidth else 0f, c.top, c.left + if (rtl)0f else stripWidth, c.bottom)
                }
                continue
            }
            val line = p.layout.getLineForOffset(p.textLength)
            val py = y + paragraphTop(p, line)
            if (p.hasBreak && s.start <= p.endOffset && s.end > p.endOffset) {
                val local = p.textLength
                val cx = p.contentLeft + p.layout.getHorizontalPosition(local, true)
                val r = p.layout.getCursorRect(local)
                val top = py + r.top
                val height = if (r.height > 0) r.height else p.height
                if (p.baseDirection == ResolvedTextDirection.Rtl) {
                    rects.add(androidx.compose.ui.geometry.Rect(x + cx - stripWidth, top, x + cx, top + height))
                } else {
                    rects.add(androidx.compose.ui.geometry.Rect(x + cx, top, x + cx + stripWidth, top + height))
                }
            } else if (p.textLength == 0 && s.start <= p.startOffset && s.end > p.startOffset) {
                val cx = p.contentLeft + p.layout.getHorizontalPosition(0, true)
                val r = p.layout.getCursorRect(0)
                val top = py + r.top
                val height = if (r.height > 0) r.height else p.height
                if (p.baseDirection == ResolvedTextDirection.Rtl) {
                    rects.add(androidx.compose.ui.geometry.Rect(x + cx - stripWidth, top, x + cx, top + height))
                } else {
                    rects.add(androidx.compose.ui.geometry.Rect(x + cx, top, x + cx + stripWidth, top + height))
                }
            }
        }
        return rects
    }

    fun hasRenderedSelection(selection: Selection): Boolean {
        if (selection.collapsed) return false
        val s = selection
        for (p in paragraphs) {
            val selStart = maxOf(s.start, p.startOffset)
            val selEnd = minOf(s.end, p.endOffset)
            if (selStart < selEnd) return true
        }
        return breakAndEmptySelectionRects(selection).isNotEmpty()
    }

    fun caret(offset: Int, primary: Boolean = true, wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM): androidx.compose.ui.geometry.Rect {
        val p = paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val line = if (wrapAffinity == WrapAffinity.UPSTREAM && p.isSoftWrapBoundary(local)) p.layout.getLineForOffset(local - 1) else p.layout.getLineForOffset(local)
        val py = y + paragraphTop(p, line)
        if (wrapAffinity == WrapAffinity.UPSTREAM && p.isSoftWrapBoundary(local)) {
            val upstreamLine = p.layout.getLineForOffset(local - 1)
            val box = p.layout.getBoundingBox(local - 1)
            val rtl = p.layout.getBidiRunDirection(local - 1) == ResolvedTextDirection.Rtl
            val cx = p.contentLeft + if (rtl) box.left else box.right
            val top = p.layout.getLineTop(upstreamLine)
            val bottom = p.layout.getLineBottom(upstreamLine)
            val caretHeight = if (bottom > top) bottom - top else p.height
            return androidx.compose.ui.geometry.Rect(x + cx, py + top, x + cx + scale * 1.5f, py + top + caretHeight)
        }
        if (p.image != null) {
            val left = p.imageLeft
            val cx = left + if ((local == 0) == (p.baseDirection == ResolvedTextDirection.Rtl))p.imageWidth else 0f
            return androidx.compose.ui.geometry.Rect(x + cx, py, x + cx + scale * 1.5f, py + p.height)
        }
        val r = p.layout.getCursorRect(local)
        val cx = p.contentLeft + p.layout.getHorizontalPosition(local, primary)
        val caretHeight = if (r.height > 0) r.height else p.height
        return androidx.compose.ui.geometry.Rect(x + cx, py + r.top, x + cx + scale * 1.5f, py + r.top + caretHeight)
    }
}

fun referenceMeasure(document: Document, textWidth: Int, measurer: TextMeasurer, style: TextStyle): List<Pair<ParagraphSpan, TextLayoutResult>> {
    val spans = partitionParagraphs(document)

    var fallbackRtl = false
    return spans.map { span ->
        val rawText = buildString(span.units.size) { for (u in span.units) append(u.char) }
        val bidi = java.text.Bidi(
            rawText,
            if (fallbackRtl) java.text.Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
        )
        val isRtl = when (span.properties.direction) {
            ParagraphDirection.RTL -> true
            ParagraphDirection.LTR -> false
            ParagraphDirection.AUTO -> if (rawText.isEmpty()) fallbackRtl else !bidi.baseIsLeftToRight()
        }
        fallbackRtl = isRtl
        val image = span.units.singleOrNull()?.image
        val imageResource = image?.let { document.resources[it.resource] }
        val annotated = if (image != null) AnnotatedString(" ") else paragraphAttributedDisplay(span.units)
        val paraStyle = style.copy(
            textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr,
            textAlign = paragraphAlignment(span.properties, isRtl),
        )
        val layout = synchronized(measurer) {
            measurer.measure(annotated, style = paraStyle, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth))
        }
        span to layout
    }
}
