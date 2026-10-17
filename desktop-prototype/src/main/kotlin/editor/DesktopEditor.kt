@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.io.File
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import java.text.BreakIterator
import javax.accessibility.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.*

private fun displayText(text: String): String = buildString {
    var i = 0
    while (i < text.length) { val c = text[i]; if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { append(c); append(text[++i]) } else append(if (Character.isSurrogate(c)) '\ufffd' else c); i++ }
}

internal fun paragraphAttributedDisplay(units: List<TextUnit>): AnnotatedString = buildAnnotatedString {
    val doc = Document(units)
    append(displayText(doc.text))
    val runs = displayedStyles(doc)
    var start = 0
    while (start < runs.size) {
        var end = start + 1
        while (end < runs.size && runs[end].start == runs[end - 1].end && runs[end].style == runs[start].style) end++
        val style = runs[start].style
        if (style.bold || style.italic) addStyle(SpanStyle(fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal), runs[start].start, runs[end - 1].end)
        start = end
    }
}

internal fun attributedDisplay(document: Document): AnnotatedString = buildAnnotatedString {
    append(displayText(document.text))
    val runs = displayedStyles(document)
    var start = 0
    while (start < runs.size) {
        var end = start + 1
        while (end < runs.size && runs[end].start == runs[end - 1].end && runs[end].style == runs[start].style) end++
        val style = runs[start].style
        if (style.bold || style.italic) addStyle(SpanStyle(fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal), runs[start].start, runs[end - 1].end)
        start = end
    }
}

fun paragraphBaseDirection(units: List<TextUnit>, fallback: ResolvedTextDirection): ResolvedTextDirection {
    var i = 0
    var isolateDepth = 0
    while (i < units.size) {
        val c = units[i].char
        when (c) {
            '\u2066', '\u2067', '\u2068' -> { isolateDepth++; i++; continue }
            '\u2069' -> { if (isolateDepth > 0) isolateDepth--; i++; continue }
        }
        val codePoint = if (Character.isHighSurrogate(c) && i + 1 < units.size && Character.isLowSurrogate(units[i + 1].char)) {
            Character.toCodePoint(c, units[++i].char)
        } else c.code
        if (isolateDepth == 0) {
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return ResolvedTextDirection.Rtl
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return ResolvedTextDirection.Ltr
            }
        }
        i++
    }
    return fallback
}

data class ParagraphSpan(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val hasBreak: Boolean,
    val units: List<TextUnit>
)

fun partitionParagraphs(document: Document): List<ParagraphSpan> {
    val result = ArrayList<ParagraphSpan>()
    val units = document.units
    var currentStart = 0
    var pIndex = 0
    for (i in units.indices) {
        if (units[i].kind == UnitKind.PARAGRAPH_BREAK) {
            result.add(ParagraphSpan(pIndex++, currentStart, i, true, units.subList(currentStart, i)))
            currentStart = i + 1
        }
    }
    result.add(ParagraphSpan(pIndex, currentStart, units.size, false, units.subList(currentStart, units.size)))
    return result
}

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
    val startLineIndex: Int
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
    val hostHeight: Int = 0
) {
    val layout: TextLayoutResult get() = paragraphs.first().layout
    val totalLineCount: Int get() = paragraphs.sumOf { it.lineCount }

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
        return p.layout.getHorizontalPosition(p.toLocalOffset(documentOffset), primary)
    }

    fun breakAndEmptySelectionRects(selection: Selection): List<androidx.compose.ui.geometry.Rect> {
        if (selection.collapsed) return emptyList()
        val s = selection
        val rects = ArrayList<androidx.compose.ui.geometry.Rect>()
        val stripWidth = 8f * scale
        for (p in paragraphs) {
            val py = y + p.top
            if (p.hasBreak && s.start <= p.endOffset && s.end > p.endOffset) {
                val local = p.textLength
                val cx = p.layout.getHorizontalPosition(local, true)
                val r = p.layout.getCursorRect(local)
                val top = py + r.top
                val height = if (r.height > 0) r.height else p.height
                if (p.baseDirection == ResolvedTextDirection.Rtl) {
                    rects.add(androidx.compose.ui.geometry.Rect(x + cx - stripWidth, top, x + cx, top + height))
                } else {
                    rects.add(androidx.compose.ui.geometry.Rect(x + cx, top, x + cx + stripWidth, top + height))
                }
            } else if (p.textLength == 0 && s.start <= p.startOffset && s.end > p.startOffset) {
                val cx = p.layout.getHorizontalPosition(0, true)
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
        val py = y + p.top
        if (wrapAffinity == WrapAffinity.UPSTREAM && p.isSoftWrapBoundary(local)) {
            val upstreamLine = p.layout.getLineForOffset(local - 1)
            val box = p.layout.getBoundingBox(local - 1)
            val rtl = p.layout.getBidiRunDirection(local - 1) == ResolvedTextDirection.Rtl
            val cx = if (rtl) box.left else box.right
            val top = p.layout.getLineTop(upstreamLine)
            val bottom = p.layout.getLineBottom(upstreamLine)
            val caretHeight = if (bottom > top) bottom - top else p.height
            return androidx.compose.ui.geometry.Rect(x + cx, py + top, x + cx + scale * 1.5f, py + top + caretHeight)
        }
        val r = p.layout.getCursorRect(local)
        val cx = p.layout.getHorizontalPosition(local, primary)
        val caretHeight = if (r.height > 0) r.height else p.height
        return androidx.compose.ui.geometry.Rect(x + cx, py + r.top, x + cx + scale * 1.5f, py + r.top + caretHeight)
    }
}

class ViewState(frame: EditorFrame) {
    var frame by mutableStateOf(frame)
    var scroll by mutableStateOf(0f)
    var focused by mutableStateOf(false)
    var blink by mutableStateOf(true)
    var ensureCaret = true
    var hostWidth: Int = 0
    var hostHeight: Int = 0
    var geometry: Geometry? = null
    var measure: ((EditorFrame) -> Geometry)? = null
    var measureAtSize: ((EditorFrame, Float, Float) -> Geometry)? = null
    var singleMeasure: ((Document) -> TextLayoutResult)? = null
    var referenceMeasure: ((Document) -> List<Pair<ParagraphSpan, TextLayoutResult>>)? = null
}

fun referenceMeasure(document: Document, textWidth: Int, measurer: TextMeasurer, style: TextStyle): List<Pair<ParagraphSpan, TextLayoutResult>> {
    val spans = ArrayList<ParagraphSpan>()
    var start = 0
    var idx = 0
    for (i in document.units.indices) {
        if (document.units[i].kind == UnitKind.PARAGRAPH_BREAK) {
            spans.add(ParagraphSpan(idx++, start, i, true, document.units.subList(start, i)))
            start = i + 1
        }
    }
    spans.add(ParagraphSpan(idx, start, document.units.size, false, document.units.subList(start, document.units.size)))

    var fallbackRtl = false
    return spans.map { span ->
        val rawText = buildString(span.units.size) { for (u in span.units) append(u.char) }
        val bidi = java.text.Bidi(
            rawText,
            if (fallbackRtl) java.text.Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
        )
        val isRtl = if (rawText.isEmpty()) fallbackRtl else !bidi.baseIsLeftToRight()
        fallbackRtl = isRtl
        val annotated = buildAnnotatedString {
            append(displayText(rawText))
            for (i in span.units.indices) {
                val st = span.units[i].style
                if (st.bold || st.italic) {
                    addStyle(
                        SpanStyle(
                            fontWeight = if (st.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal
                        ),
                        i,
                        i + 1
                    )
                }
            }
        }
        val paraStyle = style.copy(
            textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr
        )
        val layout = synchronized(measurer) {
            measurer.measure(annotated, style = paraStyle, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth))
        }
        span to layout
    }
}

@Composable
private fun DocumentCanvas(state: ViewState) {
    val measurer = rememberTextMeasurer()
    val fontFamilyResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val densityObj = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    Canvas(Modifier.fillMaxSize()) {
        val scale = density
        val canvasW = size.width
        val canvasH = size.height
        val style = TextStyle(color = ComposeColor(0xff202b3c), fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 30.sp, textDirection = TextDirection.Content)
        fun measureWith(frame: EditorFrame, awtW: Int, awtH: Int): Geometry {
            val w = if (awtW > 0) awtW * scale else canvasW
            val h = if (awtH > 0) awtH * scale else canvasH
            val pageWidth = min(w - 24.dp.toPx(), 800.dp.toPx()).coerceAtLeast(150.dp.toPx())
            val pageLeft = (w - pageWidth) / 2
            val padding = min(52.dp.toPx(), pageWidth * .1f)
            val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
            val spans = partitionParagraphs(frame.visible)
            var fallback = ResolvedTextDirection.Ltr
            var currentTop = 0f
            var startLineIndex = 0
            val defaultLineHeight = synchronized(measurer) {
                measurer.measure(AnnotatedString(" "), style = style, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth)).size.height.toFloat()
            }
            val paragraphs = ArrayList<ParagraphLayout>(spans.size)
            for (span in spans) {
                val dir = paragraphBaseDirection(span.units, fallback)
                fallback = dir
                val annotated = paragraphAttributedDisplay(span.units)
                val paraStyle = style.copy(
                    textDirection = if (dir == ResolvedTextDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
                )
                val layout = synchronized(measurer) {
                    measurer.measure(annotated, style = paraStyle, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth))
                }
                val pHeight = if (span.units.isEmpty() && layout.size.height == 0) defaultLineHeight else max(layout.size.height.toFloat(), defaultLineHeight)
                val rawText = span.units.joinToString("") { it.char.toString() }
                paragraphs.add(
                    ParagraphLayout(
                        index = span.index,
                        startOffset = span.startOffset,
                        endOffset = span.endOffset,
                        hasBreak = span.hasBreak,
                        text = rawText,
                        annotatedString = annotated,
                        baseDirection = dir,
                        layout = layout,
                        top = currentTop,
                        height = pHeight,
                        startLineIndex = startLineIndex
                    )
                )
                currentTop += pHeight
                startLineIndex += maxOf(1, layout.lineCount)
            }
            val totalHeight = currentTop
            return Geometry(
                paragraphs = paragraphs,
                totalHeight = totalHeight,
                x = pageLeft + padding,
                y = 28.dp.toPx() + padding - state.scroll * scale,
                scale = scale,
                revision = frame.documentRevision,
                textWidth = textWidth,
                scroll = state.scroll,
                hostWidth = if (awtW > 0) awtW else (canvasW / scale).roundToInt(),
                hostHeight = if (awtH > 0) awtH else (canvasH / scale).roundToInt()
            )
        }
        fun measure(frame: EditorFrame): Geometry = measureWith(frame, state.hostWidth, state.hostHeight)
        state.measure = ::measure
        state.measureAtSize = { frame, awtW, awtH -> measureWith(frame, awtW.roundToInt(), awtH.roundToInt()) }
        val pageWidth = min(size.width - 24.dp.toPx(), 800.dp.toPx()).coerceAtLeast(150.dp.toPx())
        val pageLeft = (size.width - pageWidth) / 2
        val padding = min(52.dp.toPx(), pageWidth * .1f)
        val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
        state.singleMeasure = { doc ->
            val annotated = attributedDisplay(doc)
            synchronized(measurer) {
                measurer.measure(annotated, style = style, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth))
            }
        }
        state.referenceMeasure = { doc ->
            val uncachedMeasurer = TextMeasurer(fontFamilyResolver, densityObj, layoutDirection, cacheSize = 0)
            referenceMeasure(doc, textWidth, uncachedMeasurer, style)
        }
        val frame = state.frame
        var geo = measure(frame)
        val contentHeight = geo.totalHeight + padding * 2 + 56.dp.toPx()
        val maxScroll = max(0f, (contentHeight - size.height) / scale)
        var scroll = state.scroll.coerceIn(0f, maxScroll)
        if (state.ensureCaret) {
            val caret = geo.caret(frame.selection.focus, frame.selection.primary, frame.selection.wrapAffinity)
            if (caret.bottom > size.height - 30.dp.toPx()) scroll += (caret.bottom - size.height + 30.dp.toPx()) / scale
            if (caret.top < 24.dp.toPx()) scroll -= (24.dp.toPx() - caret.top) / scale
            state.ensureCaret = false
        }
        scroll = scroll.coerceIn(0f, maxScroll)
        if (scroll != state.scroll) { state.scroll = scroll; geo = measure(frame) }
        state.geometry = geo
        drawRect(ComposeColor(0xffeef1f5))
        val pageY = 28.dp.toPx() - state.scroll * scale
        val pageHeight = max(size.height - 56.dp.toPx(), geo.totalHeight + padding * 2)
        drawRect(ComposeColor(0xffe1e6ed), Offset(pageLeft + 2.dp.toPx(), pageY + 3.dp.toPx()), androidx.compose.ui.geometry.Size(pageWidth, pageHeight))
        drawRect(ComposeColor.White, Offset(pageLeft, pageY), androidx.compose.ui.geometry.Size(pageWidth, pageHeight))
        val s = frame.selection
        if (!s.collapsed) {
            val selColor = ComposeColor(if (state.focused) 0xffc8dcff else 0xffdce3ed)
            for (p in geo.paragraphs) {
                val selStart = max(s.start, p.startOffset)
                val selEnd = min(s.end, p.endOffset)
                if (selStart < selEnd) {
                    val localA = p.toLocalOffset(selStart)
                    val localB = p.toLocalOffset(selEnd)
                    val path = p.layout.getPathForRange(localA, localB)
                    withTransform({ translate(geo.x, geo.y + p.top) }) {
                        drawPath(path, selColor)
                    }
                }
            }
            for (r in geo.breakAndEmptySelectionRects(s)) {
                drawRect(selColor, r.topLeft, r.size)
            }
        }
        for (p in geo.paragraphs) {
            drawText(p.layout, topLeft = Offset(geo.x, geo.y + p.top))
        }
        if (frame.visible.text.isEmpty() && frame.marked == null) {
            val placeholder = measurer.measure(AnnotatedString("Start typing…"), style = style.copy(color = ComposeColor(0xff9aa4b2)))
            drawText(placeholder, topLeft = Offset(geo.x, geo.y))
        }
        frame.marked?.takeUnless { it.isEmpty() }?.let { range ->
            for (p in geo.paragraphs) {
                val markA = max(range.first, p.startOffset)
                val markB = min(range.last + 1, p.endOffset)
                if (markA < markB) {
                    val localA = p.toLocalOffset(markA)
                    val localB = p.toLocalOffset(markB)
                    val first = p.layout.getLineForOffset(localA)
                    val last = p.layout.getLineForOffset(localB)
                    for (line in first..last) {
                        val a = max(localA, p.layout.getLineStart(line))
                        val b = min(localB, p.layout.getLineEnd(line, true))
                        if (a <= b) {
                            val x1 = p.layout.getHorizontalPosition(a, true)
                            val x2 = p.layout.getHorizontalPosition(b, true)
                            val lineY = geo.y + p.top + p.layout.getLineBottom(line) - 2.dp.toPx()
                            drawLine(ComposeColor(0xff3467d5), Offset(geo.x + min(x1, x2), lineY), Offset(geo.x + max(x1, x2), lineY), 1.5.dp.toPx())
                        }
                    }
                }
            }
        }
        if (state.focused && state.blink && s.collapsed) {
            val caret = geo.caret(s.focus, s.primary, s.wrapAffinity)
            drawRect(ComposeColor(0xff2559ba), caret.topLeft, caret.size)
        }
        if (maxScroll > 0) {
            val h = max(32.dp.toPx(), size.height * size.height / contentHeight)
            val y = (size.height - h) * state.scroll / maxScroll
            drawRoundRect(ComposeColor(0xffb1bac8), Offset(size.width - 7.dp.toPx(), y), androidx.compose.ui.geometry.Size(4.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

enum class RecoveryStatus {
    ACTIVE_INITIAL,
    RECOVERED_QUALIFIED,
    CONSERVATIVE_ABORT_ONLY,
    DEGRADED_RECOVERY_FAILED
}

data class RetainedCompositionPreimage(
    val draftText: String,
    val selectionBefore: Selection,
    val markedRangeBefore: IntRange?,
    val committedTextBefore: String,
    val epoch: Int
)

data class QuarantinedCallback(
    val text: String,
    val committed: Int,
    val caret: Int?,
    val epoch: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class RecoveryReport(
    val epoch: Int,
    val replaceRequested: Boolean,
    val status: RecoveryStatus,
    val contextQualified: Boolean,
    val setupSucceeded: Boolean = contextQualified,
    val nativeQualified: Boolean = false,
    val admissionAllowed: Boolean = status == RecoveryStatus.RECOVERED_QUALIFIED && contextQualified,
    val documentAbortedCleanly: Boolean,
    val replacementCreated: Boolean,
    val containerAttached: Boolean,
    val removedDuringTeardown: Boolean,
    val failures: List<String>,
    val preservedLocale: java.util.Locale?,
    val preservedCompositionEnabled: Boolean?,
    val retainedPreimage: RetainedCompositionPreimage? = null
) {
    val succeeded: Boolean
        get() = documentAbortedCleanly && failures.isEmpty() &&
            if (replaceRequested && !removedDuringTeardown) {
                status == RecoveryStatus.RECOVERED_QUALIFIED && setupSucceeded && replacementCreated && containerAttached
            } else {
                status == RecoveryStatus.CONSERVATIVE_ABORT_ONLY && !setupSucceeded && !replacementCreated
            }
}

data class UnresolvedContextObligation(
    val context: java.awt.im.InputContext,
    val host: EditorSurface,
    var detached: Boolean = false,
    var disposed: Boolean = false
)

class RecoverySession(
    initialPreimage: RetainedCompositionPreimage? = null
) {
    val unresolvedContexts: MutableList<UnresolvedContextObligation> = java.util.concurrent.CopyOnWriteArrayList()
    val quarantinedCallbacks: MutableList<QuarantinedCallback> = java.util.concurrent.CopyOnWriteArrayList()
    var retainedPreimage: RetainedCompositionPreimage? = initialPreimage
    var isolationFenceEstablished: Boolean = false

    val hasUnresolvedObligations: Boolean
        get() = unresolvedContexts.isNotEmpty() && !isolationFenceEstablished
}

private val NOOP_UNIT: () -> Unit = {}
private val NOOP_BOOL: (Boolean) -> Unit = {}
private val NOOP_FRAME: (EditorFrame) -> Unit = {}
private val NOOP_SURFACE: (EditorSurface) -> Unit = {}

/** The transparent AWT root owns input; the Compose surface underneath only renders. */
open class EditorSurface @JvmOverloads constructor(
    val engine: EditorEngine,
    val view: ViewState,
    val diagnostics: File? = null,
    val trace: ImeTrace? = null,
    var recoveryHooks: RecoveryHooks = RecoveryHooks(),
    var recoverySession: RecoverySession = RecoverySession()
) : JComponent(), InputMethodRequests {

    data class RecoveryHooks(
        val endComposition: (java.awt.im.InputContext) -> Unit = { it.endComposition() },
        val removeNotifyContext: (java.awt.im.InputContext, Component) -> Unit = { ctx, comp -> ctx.removeNotify(comp) },
        val disposeContext: (java.awt.im.InputContext) -> Unit = { it.dispose() },
        val disableInputMethods: (EditorSurface) -> Unit = { it.enableInputMethods(false) },
        val createInputContext: () -> java.awt.im.InputContext? = { java.awt.im.InputContext.getInstance() },
        val createReplacement: (EditorSurface) -> EditorSurface = { prev ->
            EditorSurface(prev.engine, prev.view, prev.diagnostics, prev.trace, prev.recoveryHooks, prev.recoverySession)
        },
        val attachReplacement: (Container, EditorSurface, EditorSurface) -> Unit = { parentContainer, oldHost, newHost ->
            if (parentContainer is JLayeredPane) {
                val layer = JLayeredPane.getLayer(oldHost)
                parentContainer.remove(oldHost)
                parentContainer.add(newHost)
                parentContainer.setLayer(newHost, layer)
            } else {
                val idx = parentContainer.components.indexOf(oldHost)
                parentContainer.remove(oldHost)
                if (idx >= 0) parentContainer.add(newHost, idx) else parentContainer.add(newHost)
            }
            parentContainer.revalidate()
            parentContainer.repaint()
        }
    )

    data class CounterSnapshot(
        val hostsCreated: Int,
        val hostsRetired: Int,
        val hostsDisposed: Int,
        val timersStarted: Int,
        val timersStopped: Int,
        val inputContextsCreated: Int,
        val inputContextsDetached: Int,
        val inputContextsDisposed: Int,
        val recoveryAttempts: Int,
        val recoverySucceededCount: Int,
        val recoveryConservativeCount: Int,
        val recoveryDegradedCount: Int,
        val teardownFailuresCount: Int
    ) {
        val activeHosts: Int get() = hostsCreated - hostsDisposed
        val activeTimers: Int get() = timersStarted - timersStopped
        val activeInputContexts: Int get() = inputContextsCreated - inputContextsDisposed
    }

    object LifecycleCounters {
        val hostsCreated = java.util.concurrent.atomic.AtomicInteger(0)
        val hostsRetired = java.util.concurrent.atomic.AtomicInteger(0)
        val hostsDisposed = java.util.concurrent.atomic.AtomicInteger(0)
        val timersStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val timersStopped = java.util.concurrent.atomic.AtomicInteger(0)
        val inputContextsCreated = java.util.concurrent.atomic.AtomicInteger(0)
        val inputContextsDetached = java.util.concurrent.atomic.AtomicInteger(0)
        val inputContextsDisposed = java.util.concurrent.atomic.AtomicInteger(0)
        val recoveryAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        val recoverySucceededCount = java.util.concurrent.atomic.AtomicInteger(0)
        val recoveryConservativeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val recoveryDegradedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val teardownFailuresCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun snapshot(): CounterSnapshot = CounterSnapshot(
            hostsCreated = hostsCreated.get(),
            hostsRetired = hostsRetired.get(),
            hostsDisposed = hostsDisposed.get(),
            timersStarted = timersStarted.get(),
            timersStopped = timersStopped.get(),
            inputContextsCreated = inputContextsCreated.get(),
            inputContextsDetached = inputContextsDetached.get(),
            inputContextsDisposed = inputContextsDisposed.get(),
            recoveryAttempts = recoveryAttempts.get(),
            recoverySucceededCount = recoverySucceededCount.get(),
            recoveryConservativeCount = recoveryConservativeCount.get(),
            recoveryDegradedCount = recoveryDegradedCount.get(),
            teardownFailuresCount = teardownFailuresCount.get()
        )

        fun reset() {
            hostsCreated.set(0)
            hostsRetired.set(0)
            hostsDisposed.set(0)
            timersStarted.set(0)
            timersStopped.set(0)
            inputContextsCreated.set(0)
            inputContextsDetached.set(0)
            inputContextsDisposed.set(0)
            recoveryAttempts.set(0)
            recoverySucceededCount.set(0)
            recoveryConservativeCount.set(0)
            recoveryDegradedCount.set(0)
            teardownFailuresCount.set(0)
        }
    }

    @Volatile private var published = engine.snapshot()
    private var dragAnchor = 0
    private var preferredX: Float? = null
    private var keyEvents = 0
    private var imeEvents = 0
    private var hasCountedRetire = false
    private var hasCountedDispose = false
    private var hasCountedTimerStart = false
    private var hasCountedTimerStop = false
    private var hasCountedContextCreation = false
    private var hasCountedContextDetach = false
    private var hasCountedContextDispose = false
    private var contextBindFailure: String? = null
    private var previousRetiredHost: EditorSurface? = null
    private var ownInputContext: java.awt.im.InputContext? = null

    var activeImeEpoch = 0; private set
    var retired = false; private set
    var disposed = false; private set
    var contextQualified = false; private set
    var setupSucceeded = false; private set
    var nativeQualified = false; private set
    var admissionAllowed = false; private set
    var recoveryStatus: RecoveryStatus = RecoveryStatus.ACTIVE_INITIAL; private set
    val recoveryFailures: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val quarantinedCallbacks: MutableList<QuarantinedCallback>
        get() = recoverySession.quarantinedCallbacks
    var retainedPreimage: RetainedCompositionPreimage?
        get() = recoverySession.retainedPreimage
        set(value) { recoverySession.retainedPreimage = value }
    var unresolvedContext: java.awt.im.InputContext?
        get() = recoverySession.unresolvedContexts.firstOrNull { it.host === this }?.context
            ?: recoverySession.unresolvedContexts.firstOrNull()?.context
        set(value) {
            if (value == null) {
                recoverySession.unresolvedContexts.removeIf { it.host === this }
            } else {
                if (!recoverySession.unresolvedContexts.any { it.context === value }) {
                    recoverySession.unresolvedContexts.add(
                        UnresolvedContextObligation(value, this, hasCountedContextDetach, hasCountedContextDispose)
                    )
                }
            }
        }
    var lastRecoveryReport: RecoveryReport? = null; private set
    var preferredLocale: java.util.Locale? = null; private set
    var preferredCompositionEnabled: Boolean? = null; private set
    var lastLocaleTransferSupported: Boolean? = null; private set
    var lastCompositionTransferSupported: Boolean? = null; private set
    var replacementSurface: EditorSurface? = null; private set
    var onHostReplaced: (EditorSurface) -> Unit = NOOP_SURFACE

    private val blinkTimer = Timer(500) {
        if (!retired && !disposed) {
            if (view.focused) view.blink = !view.blink
            engine.checkSettlementWatchdog()
        }
    }

    val isTimerRunning: Boolean get() = blinkTimer.isRunning
    val hasRetainedCallbacks: Boolean
        get() = blinkTimer.actionListeners.isNotEmpty() ||
            onEndCompositionRequested !== NOOP_UNIT ||
            statusChanged !== NOOP_FRAME ||
            requestSave !== NOOP_BOOL ||
            requestOpen !== NOOP_UNIT ||
            onHostReplaced !== NOOP_SURFACE

    var statusChanged: (EditorFrame) -> Unit = NOOP_FRAME
    var requestSave: (Boolean) -> Unit = NOOP_BOOL
    var requestOpen: () -> Unit = NOOP_UNIT
    var onEndCompositionRequested: () -> Unit = NOOP_UNIT

    override fun getInputContext(): java.awt.im.InputContext? = if (retired || disposed) null else ownInputContext

    private fun recordDiagnosticEvent(message: String) {
        runCatching { engine.onEvent(message) }
        trace?.record("recovery", message, engine.snapshot())
    }

    private fun recordStepFailure(stepName: String, failure: Throwable, failures: MutableList<String>) {
        val desc = "$stepName: ${failure.javaClass.simpleName}: ${failure.message ?: "failed"}"
        failures.add(desc)
        recoveryFailures.add(desc)
        LifecycleCounters.teardownFailuresCount.incrementAndGet()
        recordDiagnosticEvent("IME recovery failure [$desc]")
    }

    private inline fun runStep(stepName: String, failures: MutableList<String>, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            recordStepFailure(stepName, t, failures)
            false
        }
    }

    private fun stopBlinkTimer() {
        blinkTimer.stop()
        for (listener in blinkTimer.actionListeners) {
            blinkTimer.removeActionListener(listener)
        }
        if (hasCountedTimerStart && !hasCountedTimerStop) {
            hasCountedTimerStop = true
            LifecycleCounters.timersStopped.incrementAndGet()
        }
    }

    private fun markDisposed() {
        disposed = true
        if (!hasCountedDispose) {
            hasCountedDispose = true
            LifecycleCounters.hostsDisposed.incrementAndGet()
        }
    }

    private fun clearCallbacks() {
        previousRetiredHost?.replacementSurface = null
        previousRetiredHost = null
        onEndCompositionRequested = NOOP_UNIT
        statusChanged = NOOP_FRAME
        requestSave = NOOP_BOOL
        requestOpen = NOOP_UNIT
        onHostReplaced = NOOP_SURFACE
    }

    private fun cleanupFailedAttachment(failures: MutableList<String>) {
        val oldContext = ownInputContext
        retired = true
        if (!hasCountedRetire) {
            hasCountedRetire = true
            LifecycleCounters.hostsRetired.incrementAndGet()
        }
        ownInputContext = null
        stopBlinkTimer()
        if (oldContext != null) {
            unresolvedContext = oldContext
            val detachOk = runStep("cleanupRemoveNotifyContext", failures) {
                recoveryHooks.removeNotifyContext(oldContext, this)
                if (!hasCountedContextDetach) {
                    hasCountedContextDetach = true
                    LifecycleCounters.inputContextsDetached.incrementAndGet()
                }
            }
            if (detachOk) {
                recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
                val disposeOk = runStep("cleanupDisposeContext", failures) {
                    recoveryHooks.disposeContext(oldContext)
                    if (!hasCountedContextDispose) {
                        hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                }
                if (disposeOk || hasCountedContextDispose) {
                    unresolvedContext = null
                }
            }
        }
        runStep("cleanupDisableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }
        clearCallbacks()
        markDisposed()
        contextQualified = false
        recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
    }

    fun selectInputLocale(locale: java.util.Locale, compositionEnabled: Boolean? = null): Boolean {
        if (retired || disposed) return false
        preferredLocale = locale
        if (compositionEnabled != null) {
            preferredCompositionEnabled = compositionEnabled
        }
        val ctx = ownInputContext ?: return false
        val accepted = try {
            ctx.selectInputMethod(locale)
        } catch (t: Throwable) {
            val failures = ArrayList<String>()
            recordStepFailure("selectInputMethod", t, failures)
            false
        }
        lastLocaleTransferSupported = accepted
        if (compositionEnabled != null) {
            lastCompositionTransferSupported = try {
                ctx.isCompositionEnabled = compositionEnabled
                ctx.isCompositionEnabled == compositionEnabled
            } catch (_: UnsupportedOperationException) {
                false
            } catch (t: Throwable) {
                val failures = ArrayList<String>()
                recordStepFailure("setCompositionEnabled", t, failures)
                false
            }
        } else {
            try {
                preferredCompositionEnabled = ctx.isCompositionEnabled
            } catch (_: UnsupportedOperationException) {
            } catch (_: RuntimeException) {
            }
        }
        return accepted
    }

    fun retryCleanup(): Boolean {
        var allOk = true
        for (ob in recoverySession.unresolvedContexts.toList()) {
            val ctx = ob.context
            val comp = ob.host
            if (!ob.detached) {
                try {
                    recoveryHooks.removeNotifyContext(ctx, comp)
                    ob.detached = true
                    if (!comp.hasCountedContextDetach) {
                        comp.hasCountedContextDetach = true
                        LifecycleCounters.inputContextsDetached.incrementAndGet()
                    }
                } catch (_: Throwable) {
                    allOk = false
                }
            }
            if (ob.detached && !ob.disposed) {
                try {
                    recoveryHooks.disposeContext(ctx)
                    ob.disposed = true
                    if (!comp.hasCountedContextDispose) {
                        comp.hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                } catch (_: Throwable) {
                    allOk = false
                }
            }
            if (ob.detached && ob.disposed) {
                recoverySession.unresolvedContexts.remove(ob)
            }
        }
        return allOk && !recoverySession.hasUnresolvedObligations
    }

    fun recoverExplicitly(admitQuarantined: Boolean = false): Boolean {
        if (admissionAllowed) return true
        val cleanupOk = retryCleanup()
        val boundarySatisfied = (cleanupOk && !recoverySession.hasUnresolvedObligations) || recoverySession.isolationFenceEstablished
        if (!boundarySatisfied) {
            recordDiagnosticEvent("recoverExplicitly rejected: unresolved context obligations remain attached to session; isolation boundary not established")
            writeDiagnostics()
            statusChanged(engine.snapshot())
            return false
        }
        val canRecover = ownInputContext != null && contextBindFailure == null && !disposed && !retired
        if (canRecover) {
            admissionAllowed = true
            setupSucceeded = true
            contextQualified = true
            recoveryStatus = RecoveryStatus.RECOVERED_QUALIFIED
            writeDiagnostics()
            statusChanged(engine.snapshot())
            return true
        }
        return false
    }

    fun inspectRetainedDraft(): String? = retainedPreimage?.draftText

    fun exportRetainedDraft(file: File): Boolean {
        val draft = retainedPreimage?.draftText ?: return false
        file.parentFile?.mkdirs()
        file.writeText(draft, Charsets.UTF_8)
        return true
    }

    fun applyRecoveredDraft(): Boolean {
        val draft = retainedPreimage?.draftText?.takeIf { it.isNotEmpty() } ?: return false
        engine.perform(EditAction.Replace(engine.capture(), draft, kind = "RecoveredDraft"))
        recoverySession.retainedPreimage = null
        writeDiagnostics()
        statusChanged(engine.snapshot())
        return true
    }

    fun discardRecoveredDraft() {
        recoverySession.retainedPreimage = null
        writeDiagnostics()
        statusChanged(engine.snapshot())
    }

    fun discardQuarantinedCallbacks() {
        quarantinedCallbacks.clear()
        writeDiagnostics()
        statusChanged(engine.snapshot())
    }

    fun discardRecoverySession() {
        discardRecoveredDraft()
        discardQuarantinedCallbacks()
    }

    fun establishSafeIsolationFence() {
        recoverySession.isolationFenceEstablished = true
        writeDiagnostics()
        statusChanged(engine.snapshot())
    }

    fun retryRecovery(): EditorSurface? {
        retryCleanup()
        return abortAndRetireComposition(replaceHost = true)
    }

    fun requestEndComposition() {
        if (retired || disposed) return
        val failures = ArrayList<String>()
        runStep("onEndCompositionRequested", failures) { onEndCompositionRequested() }
        val ctx = ownInputContext
        if (ctx != null) {
            runStep("endComposition", failures) { recoveryHooks.endComposition(ctx) }
        }
    }

    fun abortCompositionConservatively(): RecoveryReport {
        abortAndRetireComposition(replaceHost = false)
        return checkNotNull(lastRecoveryReport)
    }

    fun abortAndRetireComposition() {
        abortAndRetireComposition(replaceHost = true)
    }

    fun abortAndRetireComposition(replaceHost: Boolean): EditorSurface? {
        if (retired) return replacementSurface
        LifecycleCounters.recoveryAttempts.incrementAndGet()
        val failures = ArrayList<String>()
        val wasFocused = isFocusOwner
        val initialParent = parent
        val wasAttached = initialParent != null
        val wasDisplayable = isDisplayable
        val oldContext = ownInputContext
        val observedLocale = preferredLocale ?: runCatching { oldContext?.locale }.getOrNull()
        val observedCompositionEnabled = preferredCompositionEnabled ?: try {
            oldContext?.isCompositionEnabled
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

        val snapBeforeAbort = engine.snapshot()
        val draftText = snapBeforeAbort.marked?.let { range ->
            if (range.first in 0 until snapBeforeAbort.visible.text.length && range.last in range.first until snapBeforeAbort.visible.text.length) {
                snapBeforeAbort.visible.text.substring(range.first, range.last + 1)
            } else ""
        } ?: ""
        val preimage = if (draftText.isNotEmpty() || snapBeforeAbort.marked != null) {
            RetainedCompositionPreimage(
                draftText = draftText,
                selectionBefore = snapBeforeAbort.selection,
                markedRangeBefore = snapBeforeAbort.marked,
                committedTextBefore = snapBeforeAbort.committed.text,
                epoch = activeImeEpoch
            )
        } else this.retainedPreimage
        this.retainedPreimage = preimage

        retired = true
        if (!hasCountedRetire) {
            hasCountedRetire = true
            LifecycleCounters.hostsRetired.incrementAndGet()
        }
        activeImeEpoch++
        ownInputContext = null
        stopBlinkTimer()

        runStep("onEndCompositionRequested", failures) { onEndCompositionRequested() }
        if (oldContext != null) {
            unresolvedContext = oldContext
            runStep("endComposition", failures) { recoveryHooks.endComposition(oldContext) }
            val detachOk = runStep("removeNotifyContext", failures) {
                recoveryHooks.removeNotifyContext(oldContext, this)
                if (!hasCountedContextDetach) {
                    hasCountedContextDetach = true
                    LifecycleCounters.inputContextsDetached.incrementAndGet()
                }
            }
            if (detachOk) {
                recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
                val disposeOk = runStep("disposeContext", failures) {
                    recoveryHooks.disposeContext(oldContext)
                    if (!hasCountedContextDispose) {
                        hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                }
                if (disposeOk || hasCountedContextDispose) {
                    unresolvedContext = null
                }
            }
        }
        runStep("disableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }

        var documentAbortedCleanly = false
        runStep("engineAbortComposition", failures) {
            engine.abortComposition()
            val afterAbort = engine.snapshot()
            check(afterAbort.marked == null && !afterAbort.waiting && !engine.settlementTimedOut) {
                "Engine remained in composing/waiting state after abortComposition"
            }
            documentAbortedCleanly = true
        }

        val removedDuringTeardown = disposed || (wasAttached && (parent !== initialParent || (wasDisplayable && !initialParent.isDisplayable)))
        if (removedDuringTeardown && replaceHost) {
            recordDiagnosticEvent("IME recovery: host removed or disposed during teardown; skipping replacement creation")
        }

        val savedStatusChanged = statusChanged
        val savedRequestSave = requestSave
        val savedRequestOpen = requestOpen
        val savedOnHostReplaced = onHostReplaced
        val savedBounds = bounds

        previousRetiredHost?.replacementSurface = null
        previousRetiredHost = null
        onEndCompositionRequested = NOOP_UNIT

        var replacement: EditorSurface? = null
        var containerAttachedOk = !wasAttached
        if (replaceHost && !removedDuringTeardown) {
            clearCallbacks()
            runStep("replacementCreation", failures) {
                replacement = recoveryHooks.createReplacement(this).also { next ->
                    next.recoverySession = this.recoverySession
                    next.previousRetiredHost = this
                    next.keyEvents = keyEvents
                    next.imeEvents = imeEvents
                    next.activeImeEpoch = activeImeEpoch
                    next.preferredLocale = observedLocale
                    next.preferredCompositionEnabled = observedCompositionEnabled
                    next.statusChanged = savedStatusChanged
                    next.requestSave = savedRequestSave
                    next.requestOpen = savedRequestOpen
                    next.onHostReplaced = savedOnHostReplaced
                    next.bounds = savedBounds
                }
            }
            val created = replacement
            if (created != null) {
                if (created.ownInputContext == null || created.contextBindFailure != null) {
                    val reason = created.contextBindFailure ?: "InputContext is null"
                    recordStepFailure("contextBinding", IllegalStateException(reason), failures)
                }
                if (initialParent != null) {
                    val attachStepOk = runStep("containerAttachment", failures) {
                        recoveryHooks.attachReplacement(initialParent, this, created)
                        check(this.parent == null) { "Retired host remained attached to parent container" }
                        check(created.parent === initialParent) { "Replacement host was not attached to parent container" }
                    }
                    if (this.parent === initialParent) {
                        runCatching { initialParent.remove(this) }
                    }
                    containerAttachedOk = attachStepOk && created.parent === initialParent && this.parent == null
                    if (!containerAttachedOk && created.parent !== initialParent) {
                        created.cleanupFailedAttachment(failures)
                        // Re-bind engine observer fallback so document edits/Undo remain observable even after attachment failure
                        engine.onChange = { f ->
                            published = f
                            view.frame = f
                            savedStatusChanged(f)
                            writeDiagnostics()
                        }
                    }
                }
                if (containerAttachedOk && !created.disposed) {
                    val newCtx = created.ownInputContext
                    if (newCtx != null) {
                        if (observedLocale != null) {
                            created.lastLocaleTransferSupported = try {
                                newCtx.selectInputMethod(observedLocale)
                            } catch (t: Throwable) {
                                recordStepFailure("restoreLocale", t, failures)
                                false
                            }
                        }
                        if (observedCompositionEnabled != null) {
                            created.lastCompositionTransferSupported = try {
                                newCtx.isCompositionEnabled = observedCompositionEnabled
                                newCtx.isCompositionEnabled == observedCompositionEnabled
                            } catch (_: UnsupportedOperationException) {
                                false
                            } catch (t: Throwable) {
                                recordStepFailure("restoreCompositionEnabled", t, failures)
                                false
                            }
                        }
                    }
                    if (wasFocused && created.parent != null) {
                        created.requestFocusInWindow()
                    }
                }
            } else {
                containerAttachedOk = false
            }
            markDisposed()
        } else {
            containerAttachedOk = false
            if (!wasAttached || parent == null) {
                clearCallbacks()
                markDisposed()
            }
        }

        val activeReplacement = replacement?.takeIf { !it.disposed && (!wasAttached || it.parent === initialParent) }
        replacementSurface = replacement

        val finalStatus: RecoveryStatus
        val finalQualified: Boolean
        val finalSetupSucceeded: Boolean
        val finalAdmissionAllowed: Boolean
        val finalNativeQualified = false // synthetic tests do not qualify real native IME routing

        if (replaceHost && !removedDuringTeardown) {
            val boundOk = activeReplacement != null && activeReplacement.ownInputContext != null && activeReplacement.contextBindFailure == null && !activeReplacement.retired
            if (failures.isEmpty() && documentAbortedCleanly && containerAttachedOk && boundOk && !recoverySession.hasUnresolvedObligations) {
                finalStatus = RecoveryStatus.RECOVERED_QUALIFIED
                finalQualified = true
                finalSetupSucceeded = true
                finalAdmissionAllowed = true
                LifecycleCounters.recoverySucceededCount.incrementAndGet()
            } else {
                finalStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
                finalQualified = false
                finalSetupSucceeded = false
                finalAdmissionAllowed = false
                LifecycleCounters.recoveryDegradedCount.incrementAndGet()
            }
        } else {
            if (failures.isEmpty() && documentAbortedCleanly) {
                finalStatus = RecoveryStatus.CONSERVATIVE_ABORT_ONLY
                finalQualified = false
                finalSetupSucceeded = false
                finalAdmissionAllowed = false
                LifecycleCounters.recoveryConservativeCount.incrementAndGet()
            } else {
                finalStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
                finalQualified = false
                finalSetupSucceeded = false
                finalAdmissionAllowed = false
                LifecycleCounters.recoveryDegradedCount.incrementAndGet()
            }
        }

        contextQualified = false
        setupSucceeded = false
        nativeQualified = false
        admissionAllowed = false
        recoveryStatus = finalStatus
        recoveryFailures.clear()
        recoveryFailures.addAll(failures)

        val report = RecoveryReport(
            epoch = activeImeEpoch,
            replaceRequested = replaceHost,
            status = finalStatus,
            contextQualified = finalQualified,
            setupSucceeded = finalSetupSucceeded,
            nativeQualified = finalNativeQualified,
            admissionAllowed = finalAdmissionAllowed,
            documentAbortedCleanly = documentAbortedCleanly,
            replacementCreated = replacement != null,
            containerAttached = containerAttachedOk && activeReplacement != null,
            removedDuringTeardown = removedDuringTeardown,
            failures = failures.toList(),
            preservedLocale = observedLocale,
            preservedCompositionEnabled = observedCompositionEnabled,
            retainedPreimage = preimage
        )
        lastRecoveryReport = report

        replacement?.let { next ->
            next.contextQualified = finalQualified
            next.setupSucceeded = finalSetupSucceeded
            next.nativeQualified = finalNativeQualified
            next.admissionAllowed = finalAdmissionAllowed
            next.recoveryStatus = finalStatus
            next.recoveryFailures.clear()
            next.recoveryFailures.addAll(failures)
            next.lastRecoveryReport = report
            next.retainedPreimage = preimage
        }

        val snap = engine.snapshot()
        published = snap
        view.frame = snap

        if (activeReplacement != null) {
            savedOnHostReplaced(activeReplacement)
            activeReplacement.statusChanged(snap)
            activeReplacement.writeDiagnostics()
        } else {
            savedStatusChanged(snap)
            writeDiagnostics()
        }
        return activeReplacement
    }

    fun dispatchInputMethodEventWithEpoch(e: InputMethodEvent, epoch: Int) {
        if (retired || disposed || epoch != activeImeEpoch) {
            e.consume()
            return
        }
        processInputMethodEvent(e)
    }

    init {
        LifecycleCounters.hostsCreated.incrementAndGet()
        isOpaque = false; isFocusable = true; focusTraversalKeysEnabled = false
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR); enableInputMethods(true)
        ownInputContext = try {
            recoveryHooks.createInputContext()?.also {
                hasCountedContextCreation = true
                LifecycleCounters.inputContextsCreated.incrementAndGet()
            }
        } catch (t: Throwable) {
            contextBindFailure = "${t.javaClass.simpleName}: ${t.message ?: "failed"}"
            null
        }
        if (ownInputContext == null && contextBindFailure == null) {
            contextBindFailure = "InputContext.getInstance() returned null"
        }
        val initialSetupOk = ownInputContext != null && contextBindFailure == null
        setupSucceeded = initialSetupOk
        contextQualified = initialSetupOk
        nativeQualified = false // synthetic / default: real native qualification remains NOT RUN
        admissionAllowed = initialSetupOk
        if (initialSetupOk) {
            recoveryStatus = RecoveryStatus.ACTIVE_INITIAL
        } else {
            recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
            val desc = "contextBinding: $contextBindFailure"
            recoveryFailures.add(desc)
            LifecycleCounters.teardownFailuresCount.incrementAndGet()
            runCatching { recoveryHooks.disableInputMethods(this) }
            recordDiagnosticEvent("Initial InputContext binding failed [$desc]")
        }
        engine.onChange = { f ->
            if (!disposed && (!retired || replacementSurface == null)) {
                val old = published; published = f; view.frame = f; view.blink = true; view.ensureCaret = true
                statusChanged(f); repaint()
                try {
                    getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY, old.visible.text, f.visible.text)
                    getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_CARET_PROPERTY, old.selection.focus, f.selection.focus)
                } finally { writeDiagnostics() }
            }
        }
        engine.onSettlementRequested = { if (!retired && !disposed) requestEndComposition() }
        engine.onEvent = { event -> trace?.record("engine",event,engine.snapshot()); diagnostics?.parentFile?.let { it.mkdirs(); File(it, "input-events.log").appendText("${System.currentTimeMillis()} $event\n") } }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (retired || disposed) return
                view.focused = true; view.blink = true; trace?.record("focus-gained","locale=${inputContext?.locale}",engine.snapshot()); writeDiagnostics()
            }
            override fun focusLost(e: FocusEvent) {
                if (retired || disposed) return
                view.focused = false; trace?.record("focus-lost","temporary=${e.isTemporary}",engine.snapshot()); requestEndComposition(); writeDiagnostics()
            }
        })
        addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(e: InputMethodEvent) {
                if (retired || disposed) {
                    trace?.record("awt-text-ignored","retired=$retired; disposed=$disposed",engine.snapshot())
                    e.consume()
                    return
                }
                if (!admissionAllowed) {
                    val text = e.text?.let { iterator -> buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) } } ?: ""
                    val committed = e.committedCharacterCount
                    val caret = e.caret?.insertionIndex
                    quarantinedCallbacks.add(QuarantinedCallback(text, committed, caret, activeImeEpoch))
                    trace?.record("awt-text-quarantined", "text=$text; committed=$committed; caret=$caret; status=$recoveryStatus; admissionAllowed=false", engine.snapshot())
                    recordDiagnosticEvent("Input method callback quarantined: recovery status is $recoveryStatus; ambiguous native text not admitted")
                    e.consume()
                    writeDiagnostics()
                    statusChanged(engine.snapshot())
                    return
                }
                imeEvents++
                val text = e.text?.let { iterator -> buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) } } ?: ""
                val committed = e.committedCharacterCount
                val caret = e.caret?.insertionIndex
                trace?.record("awt-text","text=$text; committed=$committed; caret=$caret; leading=${e.caret?.isLeadingEdge}; locale=${inputContext?.locale}",engine.snapshot())
                if (committed in 0..text.length && (caret == null || caret in 0..text.length - committed)) engine.inputMethod(text, committed, caret)
                else runCatching { engine.onEvent("Rejected malformed native text/caret range") }
                e.consume(); preferredX = null
            }
            override fun caretPositionChanged(e: InputMethodEvent) {
                if (retired || disposed) {
                    trace?.record("awt-caret-ignored","retired=$retired; disposed=$disposed",engine.snapshot())
                    e.consume()
                    return
                }
                if (!admissionAllowed) {
                    trace?.record("awt-caret-quarantined", "${e.caret}", engine.snapshot())
                    e.consume()
                    return
                }
                trace?.record("awt-caret","${e.caret}",engine.snapshot()); e.caret?.let { engine.inputMethodCaret(it.insertionIndex) }; e.consume()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (retired || disposed || !admissionAllowed) { e.consume(); return }
                if (e.isConsumed || published.marked != null) return
                if ((e.isControlDown || e.isMetaDown || e.isAltDown) && !e.isAltGraphDown) return
                if (e.keyChar >= ' ' && e.keyChar != '\u007f' && e.keyChar != KeyEvent.CHAR_UNDEFINED) { keyEvents++; engine.replace(e.keyChar.toString()); preferredX = null; e.consume() }
            }
            override fun keyPressed(e: KeyEvent) {
                if (retired || disposed) { e.consume(); return }
                if (e.isConsumed) return
                trace?.record("key-pressed","code=${e.keyCode}; modifiers=${e.modifiersEx}; locale=${inputContext?.locale}",engine.snapshot())
                val command = (e.isControlDown || e.isMetaDown) && !e.isAltGraphDown
                when {
                    command && e.keyCode == KeyEvent.VK_S -> requestSave(e.isShiftDown)
                    command && e.keyCode == KeyEvent.VK_O -> requestOpen()
                    command && e.keyCode == KeyEvent.VK_B -> if (admissionAllowed) engine.toggleFormat(FormatKey.BOLD)
                    command && e.keyCode == KeyEvent.VK_I -> if (admissionAllowed) engine.toggleFormat(FormatKey.ITALIC)
                    command && e.keyCode == KeyEvent.VK_Z -> engine.perform(if (e.isShiftDown) EditAction.Redo else EditAction.Undo)
                    command && e.keyCode == KeyEvent.VK_Y -> engine.perform(EditAction.Redo)
                    command && e.keyCode == KeyEvent.VK_A -> engine.selectAll()
                    command && e.keyCode == KeyEvent.VK_C -> copySelection(false)
                    command && e.keyCode == KeyEvent.VK_X -> copySelection(admissionAllowed)
                    command && e.keyCode == KeyEvent.VK_V -> if (admissionAllowed) paste()
                    e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE -> {
                        if (admissionAllowed) {
                            if (command && published.selection.collapsed) wordDelete(e.keyCode == KeyEvent.VK_BACK_SPACE)
                            else engine.delete(e.keyCode == KeyEvent.VK_BACK_SPACE)
                        }
                    }
                    e.keyCode == KeyEvent.VK_ENTER -> if (admissionAllowed) engine.replace("\n", "Enter", paragraphBreaks = !e.isShiftDown)
                    e.keyCode == KeyEvent.VK_TAB -> if (admissionAllowed) engine.replace("\t", "Typing")
                    e.keyCode in arrayOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END) -> navigate(e.keyCode, e.isShiftDown, command)
                    e.keyCode == KeyEvent.VK_ESCAPE && (published.marked != null || published.waiting || engine.settlementTimedOut) -> {
                        if (published.waiting || engine.settlementTimedOut) abortAndRetireComposition()
                        else requestEndComposition()
                    }
                    else -> return
                }
                e.consume(); writeDiagnostics()
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (retired || disposed) return
                requestFocusInWindow(); preferredX = null
                val pos = hit(e.point) ?: return
                if (e.clickCount >= 3) {
                    val text = published.visible.text; val start = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(-1)) + 1; val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it + 1 }
                    dragAnchor = start; engine.move(Selection(start, end))
                } else if (e.clickCount == 2) {
                    val text = published.visible.text; val words = BreakIterator.getWordInstance().apply { setText(text) }
                    val a = words.preceding((pos + 1).coerceAtMost(text.length)).let { if (it < 0) 0 else it }; val b = words.following(pos).let { if (it < 0) text.length else it }
                    dragAnchor = a; engine.move(Selection(a, b))
                } else { dragAnchor = if (e.isShiftDown) published.selection.anchor else pos; engine.move(Selection(dragAnchor, pos), side = pointerSide(e.point, pos)) }
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() { override fun mouseDragged(e: MouseEvent) {
            if (retired || disposed) return
            if (e.y < 12) view.scroll = max(0f, view.scroll - 18) else if (e.y > height - 12) view.scroll += 18
            hit(e.point)?.let { engine.move(Selection(dragAnchor, it)) }
        } })
        addMouseWheelListener { e ->
            if (retired || disposed) return@addMouseWheelListener
            view.ensureCaret = false; view.scroll = max(0f, view.scroll + e.preciseWheelRotation.toFloat() * 56)
        }
        blinkTimer.start()
        hasCountedTimerStart = true
        LifecycleCounters.timersStarted.incrementAndGet()
    }

    override fun removeNotify() {
        val oldContext = ownInputContext
        val wasRetired = retired
        ownInputContext = null
        stopBlinkTimer()
        val failures = ArrayList<String>()
        if (oldContext != null) {
            unresolvedContext = oldContext
            val detachOk = runStep("removeNotifyContext", failures) {
                recoveryHooks.removeNotifyContext(oldContext, this)
                if (!hasCountedContextDetach) {
                    hasCountedContextDetach = true
                    LifecycleCounters.inputContextsDetached.incrementAndGet()
                }
            }
            if (detachOk) {
                recoverySession.unresolvedContexts.firstOrNull { it.context === oldContext }?.detached = true
                val disposeOk = runStep("disposeContext", failures) {
                    recoveryHooks.disposeContext(oldContext)
                    if (!hasCountedContextDispose) {
                        hasCountedContextDispose = true
                        LifecycleCounters.inputContextsDisposed.incrementAndGet()
                    }
                }
                if (disposeOk || hasCountedContextDispose) {
                    unresolvedContext = null
                }
            }
            runStep("disableInputMethods", failures) { recoveryHooks.disableInputMethods(this) }
        }
        if (recoverySession.unresolvedContexts.isNotEmpty()) {
            retryCleanup()
        }
        if (failures.isNotEmpty()) {
            contextQualified = false
            setupSucceeded = false
            admissionAllowed = false
            recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
        }
        clearCallbacks()
        markDisposed()
        if (!wasRetired) {
            engine.cancelPendingOnTeardown()
        }
        super.removeNotify()
    }

    public override fun processFocusEvent(e: FocusEvent) {
        if (retired || disposed) return
        super.processFocusEvent(e)
    }
    public override fun processInputMethodEvent(e: InputMethodEvent) {
        if (retired || disposed) {
            trace?.record("awt-ime-ignored", "id=${e.id}; retired=$retired; disposed=$disposed", engine.snapshot())
            e.consume()
            return
        }
        super.processInputMethodEvent(e)
    }
    public override fun processKeyEvent(e: KeyEvent) {
        if (retired || disposed) {
            e.consume()
            return
        }
        super.processKeyEvent(e)
    }
    fun geometry(): Geometry? {
        if (width > 0 && height > 0) {
            view.hostWidth = width
            view.hostHeight = height
        }
        val cached = view.geometry
        if (cached != null && cached.revision == published.documentRevision && cached.scroll == view.scroll &&
            (width <= 0 || cached.hostWidth == width) &&
            (height <= 0 || cached.hostHeight == height)) {
            return cached
        }
        return view.measure?.invoke(published)?.also { view.geometry = it }
    }
    private fun hit(point: Point): Int? {
        val g = geometry() ?: return null
        val targetY = point.y * g.scale - g.y
        val targetX = point.x * g.scale - g.x
        val p = when {
            targetY <= 0 -> g.paragraphs.first()
            targetY >= g.totalHeight -> g.paragraphs.last()
            else -> g.paragraphs.firstOrNull { targetY in it.top..it.bottom }
                ?: g.paragraphs.minByOrNull { abs((it.top + it.height / 2) - targetY) }
                ?: g.paragraphs.first()
        }
        val localY = (targetY - p.top).coerceIn(0f, p.height)
        val localX = targetX.coerceIn(0f, g.textWidth.toFloat())
        val localOffset = if (p.textLength == 0) 0 else p.layout.getOffsetForPosition(Offset(localX, localY)).coerceIn(0, p.textLength)
        val docOffset = p.toDocumentOffset(localOffset)
        val a = Graphemes.snap(published.visible.text, docOffset, false)
        val b = Graphemes.snap(published.visible.text, docOffset, true)
        return if (abs(g.caret(a).left - point.x * g.scale) < abs(g.caret(b).left - point.x * g.scale)) a else b
    }
    private fun pointerSide(point: Point, offset: Int): TypingSide {
        val g = geometry() ?: return TypingSide.LEFT
        val p = g.paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val rightOfCaret = point.x * g.scale >= g.caret(offset).left
        val rtl = p.layout.getBidiRunDirection(local) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
        return if (rightOfCaret != rtl) TypingSide.RIGHT else TypingSide.LEFT
    }
    private data class Stop(val offset: Int, val x: Float, val line: Int, val primary: Boolean, val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM)
    private fun stops(g: Geometry): List<Stop> = Graphemes.stops(published.visible.text).flatMap { offset ->
        val p = g.paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val localLine = if (p.textLength == 0) 0 else p.layout.getLineForOffset(local)
        val globalLine = p.startLineIndex + localLine
        val primary = p.layout.getHorizontalPosition(local, true)
        val secondary = p.layout.getHorizontalPosition(local, false)
        val regularStops = listOf(Stop(offset, primary, globalLine, true, WrapAffinity.DOWNSTREAM)) +
            if (abs(primary - secondary) > .5f) listOf(Stop(offset, secondary, globalLine, false, WrapAffinity.DOWNSTREAM)) else emptyList()
        if (p.isSoftWrapBoundary(local)) {
            val upstreamLine = p.layout.getLineForOffset(local - 1)
            val box = p.layout.getBoundingBox(local - 1)
            val rtl = p.layout.getBidiRunDirection(local - 1) == ResolvedTextDirection.Rtl
            val cx = if (rtl) box.left else box.right
            val upstreamGlobalLine = p.startLineIndex + upstreamLine
            listOf(Stop(offset, cx, upstreamGlobalLine, true, WrapAffinity.UPSTREAM)) + regularStops
        } else {
            regularStops
        }
    }.sortedWith(compareBy<Stop> { it.line }.thenBy { it.x }.thenBy { it.offset }.thenBy { it.wrapAffinity })
    private fun navigate(key: Int, extend: Boolean, command: Boolean) {
        val g = geometry() ?: return; val s = published.selection; val text = published.visible.text
        val legalFocus = Graphemes.snap(text, s.focus, key in arrayOf(KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_END))
        val all = stops(g); val current = all.indexOfFirst { it.offset == legalFocus && it.primary == s.primary && it.wrapAffinity == s.wrapAffinity }.let { if (it < 0) all.indexOfFirst { p -> p.offset == legalFocus }.coerceAtLeast(0) else it }
        val here = all[current]; var target = here
        when {
            command && key == KeyEvent.VK_HOME -> target = all.first { it.offset == 0 }
            command && key == KeyEvent.VK_END -> target = all.first { it.offset == text.length }
            command && key in arrayOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT) -> { val words = BreakIterator.getWordInstance().apply { setText(text) }; val p = if (key == KeyEvent.VK_LEFT) words.preceding(s.focus).let { if (it < 0) 0 else it } else words.following(s.focus).let { if (it < 0) text.length else it }; val snapped = Graphemes.snap(text, p, key == KeyEvent.VK_RIGHT); target = all.first { it.offset == snapped } }
            key == KeyEvent.VK_LEFT || key == KeyEvent.VK_RIGHT -> {
                if (!extend && !s.collapsed) {
                    val endpoints = all.filter { it.primary && (it.offset == s.start || it.offset == s.end) }
                    target = if (key == KeyEvent.VK_LEFT) endpoints.first() else endpoints.last()
                }
                else target = if (legalFocus != s.focus) all[current] else all[(current + if (key == KeyEvent.VK_LEFT) -1 else 1).coerceIn(0, all.lastIndex)]
            }
            key == KeyEvent.VK_HOME -> target = all.first { it.line == here.line }
            key == KeyEvent.VK_END -> target = all.last { it.line == here.line }
            else -> { val x = preferredX ?: here.x.also { preferredX = it }; val line = (here.line + if (key == KeyEvent.VK_UP) -1 else 1).coerceIn(0, g.totalLineCount - 1); target = all.filter { it.line == line }.minByOrNull { abs(it.x - x) } ?: here }
        }
        if (key !in arrayOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN)) preferredX = null
        engine.move(Selection(if (extend) Graphemes.snap(text, s.anchor, false) else target.offset, target.offset, target.primary, wrapAffinity = target.wrapAffinity), target.primary, if (target.offset < s.focus) TypingSide.RIGHT else TypingSide.LEFT)
    }
    private fun wordDelete(back: Boolean) {
        val text = published.visible.text; val at = published.selection.focus
        val word = BreakIterator.getWordInstance().apply { setText(text) }
        val other = (if (back) word.preceding(at) else word.following(at)).let { if (it < 0) if (back) 0 else text.length else it }
        engine.replace("", "Delete", range = Selection(at, other), deleteSide = if (back) TypingSide.LEFT else TypingSide.RIGHT)
    }
    fun copySelection(cut: Boolean) {
        val s = published.selection; if (s.collapsed) return
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(published.visible.text.substring(s.start, s.end)), null)
            if (cut && admissionAllowed && !retired && !disposed) engine.replace("", "Cut")
        } catch (_: IllegalStateException) { Toolkit.getDefaultToolkit().beep() }
    }
    fun paste() {
        if (retired || disposed || !admissionAllowed) return
        try {
            val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: return
            engine.replace(text.replace("\r\n", "\n").replace('\r', '\n'), "Paste", paragraphBreaks = true)
        } catch (_: Exception) { Toolkit.getDefaultToolkit().beep() }
    }
    override fun getInputMethodRequests(): InputMethodRequests = this
    override fun getCommittedText(beginIndex: Int, endIndex: Int, attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator {
        val text = committedContext(); require(beginIndex in 0..endIndex && endIndex <= text.length); return AttributedString(text.substring(beginIndex, endIndex)).iterator
    }
    private fun committedContext(): String = published.marked?.let { published.visible.text.removeRange(it.first, it.last + 1) } ?: published.visible.text
    override fun getCommittedTextLength() = committedContext().length
    override fun getInsertPositionOffset() = published.marked?.first ?: published.selection.start
    override fun getSelectedText(attributes: Array<out AttributedCharacterIterator.Attribute>?) = AttributedString(published.visible.text.substring(published.selection.start, published.selection.end)).iterator
    override fun cancelLatestCommittedText(attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator? = null
    override fun getTextLocation(offset: TextHitInfo?): Rectangle? {
        val g = geometry() ?: return null
        val screen = try {
            if (!isShowing) null else locationOnScreen
        } catch (_: IllegalComponentStateException) {
            null
        } ?: return null
        val mark = published.marked; val s = published.selection
        val at = if (mark != null) mark.first + (offset?.insertionIndex ?: 0).also { require(it in 0..mark.count()) } else s.focus
        val caret = g.caret(at, primary = if (mark != null) true else s.primary, wrapAffinity = if (mark != null) WrapAffinity.DOWNSTREAM else s.wrapAffinity)
        var x = caret.left; var y = caret.top; var bottom = caret.bottom
        if (mark == null && !s.collapsed) {
            val anchorEnd = s.end
            val p = g.paragraphForOffset(anchorEnd)
            val localEnd = p.toLocalOffset(anchorEnd)
            val line = if (localEnd > 0 && localEnd == p.textLength && p.hasBreak) {
                p.layout.lineCount - 1
            } else {
                p.layout.getLineForOffset(localEnd)
            }
            val a = max(s.start, p.startOffset + p.layout.getLineStart(line)); val b = min(s.end, p.startOffset + p.layout.getLineEnd(line, true))
            val localA = p.toLocalOffset(a); val localB = p.toLocalOffset(b)
            val x1 = p.layout.getHorizontalPosition(localA, true); val x2 = p.layout.getHorizontalPosition(localB, true)
            x = g.x + if (p.layout.getParagraphDirection(localA) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl) max(x1, x2) else min(x1, x2)
            y = g.y + p.top + p.layout.getLineTop(line); bottom = g.y + p.top + p.layout.getLineBottom(line)
        }
        return Rectangle(screen.x + (x / g.scale).roundToInt(), screen.y + (y / g.scale).roundToInt(), 0, ((bottom - y) / g.scale).roundToInt().coerceAtLeast(1))
    }
    override fun getLocationOffset(x: Int, y: Int): TextHitInfo? {
        val mark = published.marked ?: return null; val g = geometry() ?: return null
        val screen = try {
            if (!isShowing) return null
            locationOnScreen
        } catch (_: IllegalComponentStateException) {
            return null
        }
        val targetX = (x - screen.x) * g.scale - g.x
        val targetY = (y - screen.y) * g.scale - g.y
        for (p in g.paragraphs) {
            val markA = max(mark.first, p.startOffset)
            val markB = min(mark.last + 1, p.endOffset)
            if (markA < markB) {
                val localA = p.toLocalOffset(markA)
                val localB = p.toLocalOffset(markB)
                val localP = Offset(targetX, targetY - p.top)
                val inAnyBox = (localA until localB).any { i ->
                    p.layout.getBoundingBox(i).contains(localP)
                }
                if (inAnyBox) {
                    val localHit = p.layout.getOffsetForPosition(localP).coerceIn(localA, localB)
                    val docHit = p.toDocumentOffset(localHit)
                    return TextHitInfo.leading(docHit - mark.first)
                }
            }
        }
        return null
    }
    override fun getAccessibleContext(): AccessibleContext { if (accessibleContext == null) accessibleContext = AccessibleEditor(); return accessibleContext }
    protected inner class AccessibleEditor : AccessibleJComponent(), AccessibleText {
        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.TEXT
        override fun getAccessibleName(): String = "Document editor"
        override fun getAccessibleDescription(): String = "Editable document. Type text; use Control Z to undo."
        override fun getAccessibleText(): AccessibleText = this
        override fun getIndexAtPoint(p: Point): Int = hit(p) ?: -1
        override fun getCharacterBounds(i: Int): Rectangle? {
            val g = geometry() ?: return null
            if (i !in published.visible.text.indices) return null
            val p = g.paragraphForOffset(i)
            if (i == p.breakOffset) {
                val r = p.layout.getCursorRect(p.textLength)
                return Rectangle(((g.x + r.left) / g.scale).toInt(), ((g.y + p.top + r.top) / g.scale).toInt(), 0, (r.height / g.scale).toInt())
            }
            val local = p.toLocalOffset(i)
            val r = p.layout.getBoundingBox(local)
            return Rectangle(((g.x + r.left) / g.scale).toInt(), ((g.y + p.top + r.top) / g.scale).toInt(), (r.width / g.scale).toInt(), (r.height / g.scale).toInt())
        }
        override fun getCharCount() = published.visible.text.length
        override fun getCaretPosition() = published.selection.focus
        override fun getSelectionStart() = published.selection.start
        override fun getSelectionEnd() = published.selection.end
        override fun getSelectedText(): String? = published.visible.text.substring(selectionStart, selectionEnd).takeIf { it.isNotEmpty() }
        override fun getCharacterAttribute(i: Int): javax.swing.text.AttributeSet? = null
        override fun getAtIndex(part: Int, index: Int): String? = segment(part,index,0)
        override fun getAfterIndex(part: Int, index: Int): String? = segment(part,index,1)
        override fun getBeforeIndex(part: Int, index: Int): String? = segment(part,index,-1)
        private fun segment(part: Int, index: Int, step: Int): String? {
            val text=published.visible.text; if(index !in text.indices)return null
            if(part==AccessibleText.CHARACTER)return text.getOrNull(index+step)?.toString()
            val it=(if(part==AccessibleText.WORD)BreakIterator.getWordInstance() else BreakIterator.getSentenceInstance()).apply {setText(text)}
            var a=it.preceding(index+1).coerceAtLeast(0);var b=it.following(index).let {if(it<0)text.length else it}
            if(step>0){a=b;b=it.following(a)}else if(step<0){b=a;a=it.preceding(b)}
            return if(a<0||b<0||a>=b)null else text.substring(a,b)
        }
    }
    fun writeDiagnostics() {
        val f = diagnostics ?: return; f.parentFile.mkdirs()
        fun quoted(s: String) = "\"" + s.flatMap { c -> when (c) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> if (c.code < 32 || Character.isSurrogate(c)) "\\u%04x".format(c.code) else c.toString() }.toList() }.joinToString("") + "\""
        val s=engine.snapshot()
        published=s
        val failuresJson = recoveryFailures.joinToString(",") { quoted(it) }
        val draftStr = retainedPreimage?.draftText ?: ""
        f.writeText("{\"text\":${quoted(s.visible.text)},\"anchor\":${s.selection.anchor},\"focus\":${s.selection.focus},\"undo\":${s.undoCount},\"redo\":${s.redoCount},\"documentRevision\":${s.documentRevision},\"sessionRevision\":${s.sessionRevision},\"composing\":${s.marked!=null},\"waiting\":${s.waiting},\"focused\":$isFocusOwner,\"keyTypedEvents\":$keyEvents,\"imeTextEvents\":$imeEvents,\"activeImeEpoch\":$activeImeEpoch,\"recoveryStatus\":${quoted(recoveryStatus.name)},\"contextQualified\":$contextQualified,\"setupSucceeded\":$setupSucceeded,\"nativeQualified\":$nativeQualified,\"admissionAllowed\":$admissionAllowed,\"quarantinedCallbacks\":${quarantinedCallbacks.size},\"retainedDraft\":${quoted(draftStr)},\"recoveryFailures\":[$failuresJson],\"notice\":${quoted(s.notice)}}")
    }
}

fun windowFiles(window: JFrame): WindowFiles? = window.rootPane?.getClientProperty("editor.files") as? WindowFiles
fun windowLayeredPane(window: JFrame): JLayeredPane? = window.rootPane?.getClientProperty("editor.layers") as? JLayeredPane
@Suppress("UNCHECKED_CAST")
fun windowCurrentSurface(window: JFrame): EditorSurface? = (window.rootPane?.getClientProperty("editor.surfaceGetter") as? (() -> EditorSurface))?.invoke()

fun showEditor(diagnostics: File? = null, initial: Document = Document(emptyList()), initialPath: java.nio.file.Path? = null, trace: ImeTrace? = null, traceLanguage: String? = null, offscreenTest: Boolean = false, windowLocation: Point? = null): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    val engine=EditorEngine(initial);val state=ViewState(engine.snapshot());var surface=EditorSurface(engine,state,diagnostics,trace)
    var recoverBtn: JButton? = null
    var applyDraftBtn: JButton? = null
    var discardDraftBtn: JButton? = null
    surface.onHostReplaced={
        surface=it
        recoverBtn?.isVisible = !surface.admissionAllowed
        applyDraftBtn?.isVisible = surface.inspectRetainedDraft() != null
        discardDraftBtn?.isVisible = surface.inspectRetainedDraft() != null
    }
    val window=JFrame("Astra Editor — Desktop Prototype")
    lateinit var files: WindowFiles
    val compose=ComposePanel(renderSettings=RenderSettings.SwingGraphics()).apply {isFocusable=false;setContent {DocumentCanvas(state)}}
    val layers=object:JLayeredPane(){override fun doLayout(){compose.setBounds(0,0,width,height);surface.setBounds(0,0,width,height)};override fun getPreferredSize()=Dimension(1020,640)}
    // Kotlin otherwise selects add(Component, index), not add(Component, layer).
    layers.add(compose); layers.setLayer(compose, 0)
    layers.add(surface); layers.setLayer(surface, 100)
    val bg=Color(0xfafcff);val header=JPanel(BorderLayout()).apply {background=bg;border=EmptyBorder(16,24,14,24)}
    val title=JLabel("Untitled").apply {font=Font("Segoe UI",Font.BOLD,20);foreground=Color(0x20304a)}
    val subtitle=JLabel("Astra editor  /  Desktop prototype").apply {font=Font("Segoe UI",Font.PLAIN,12);foreground=Color(0x64748b)}
    val titles=JPanel(GridLayout(2,1,0,3)).apply {isOpaque=false;add(title);add(subtitle)};header.add(titles,BorderLayout.WEST)
    if(trace!=null) {
        subtitle.text="IME test: test text is logged locally"
        val languages=linkedMapOf("English" to "en-US","Japanese" to "ja-JP","Urdu" to "ur-PK","Arabic" to "ar-SA")
        val picker=JComboBox(languages.keys.toTypedArray())
        val result=JLabel("Choose an installed input method; then type test text.")
        picker.addActionListener {
            val tag=languages.getValue(picker.selectedItem as String)
            val accepted=surface.selectInputLocale(java.util.Locale.forLanguageTag(tag), compositionEnabled = if (tag == "ja-JP") true else null)
            val ctx=surface.inputContext
            val compositionEnabled = if(accepted && tag=="ja-JP") runCatching { ctx?.isCompositionEnabled }.getOrNull() else null
            result.text="Input: $tag — ${if(accepted)"selected" else "unavailable"}"
            trace.record("select-input","requested=$tag; accepted=$accepted; actual=${ctx?.locale}; compositionEnabled=$compositionEnabled",engine.snapshot())
            surface.requestFocusInWindow()
        }
        if(traceLanguage!=null) SwingUtilities.invokeLater { picker.selectedItem=languages.entries.first { it.value==traceLanguage }.key }
        header.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {isOpaque=false;add(picker);add(result)},BorderLayout.SOUTH)
        trace.record("session-start","Live input test mode; no synthetic injection. OS=${System.getProperty("os.name")}; Java=${System.getProperty("java.version")}",engine.snapshot())
    }
    val buttons=JPanel(FlowLayout(FlowLayout.RIGHT,8,0)).apply {isOpaque=false}
    fun button(label:String,tooltip:String,action:()->Unit)=JButton(label).apply {isFocusable=false;font=Font("Segoe UI",Font.PLAIN,13);toolTipText=tooltip;addActionListener {action();surface.requestFocusInWindow()};buttons.add(this)}
    val open=button("Open","Open a document in a new window (Ctrl+O)"){files.open()}
    val save=button("Save","Save document (Ctrl+S); Save As: Ctrl+Shift+S"){files.save()}
    val undo=button("Undo","Ctrl+Z"){engine.perform(EditAction.Undo)};val redo=button("Redo","Ctrl+Shift+Z or Ctrl+Y"){engine.perform(EditAction.Redo)}
    val bold=button("Bold","Toggle bold (Ctrl+B)"){if (surface.admissionAllowed) engine.toggleFormat(FormatKey.BOLD)}
    val italic=button("Italic","Toggle italic (Ctrl+I)"){if (surface.admissionAllowed) engine.toggleFormat(FormatKey.ITALIC)}
    button("Copy","Copy selected text (Ctrl+C)"){surface.copySelection(false)}
    val pasteBtn=button("Paste","Paste plain text (Ctrl+V)"){surface.paste()}
    val rBtn = button("Recover","Recover editor input after failure"){surface.recoverExplicitly(false)}
    val aBtn = button("Apply Draft", "Apply retained recovered draft text to document") { surface.applyRecoveredDraft() }
    val dBtn = button("Discard Draft", "Discard retained recovered draft text") { surface.discardRecoveredDraft() }
    rBtn.isVisible = !surface.admissionAllowed
    aBtn.isVisible = surface.inspectRetainedDraft() != null
    dBtn.isVisible = surface.inspectRetainedDraft() != null
    recoverBtn = rBtn
    applyDraftBtn = aBtn
    discardDraftBtn = dBtn
    header.add(buttons,BorderLayout.EAST)
    val footer=JPanel(BorderLayout()).apply {background=bg;border=EmptyBorder(10,24,10,24)}
    val position=JLabel();val notice=JLabel();listOf(position,notice).forEach {it.font=Font("Segoe UI",Font.PLAIN,12);it.foreground=Color(0x64748b)}
    footer.add(position,BorderLayout.WEST);footer.add(notice,BorderLayout.EAST)
    files=WindowFiles(window,engine,initialPath,onDiscardComposition={surface.abortAndRetireComposition(replaceHost=false)}){surface.statusChanged(engine.snapshot())}
    surface.requestSave={files.save(it)};surface.requestOpen={files.open()}
    surface.statusChanged={f->val before=f.visible.text.take(f.selection.focus);val line=before.count {it=='\n'}+1;val col=Graphemes.stops(before.substringAfterLast('\n')).size;position.text="Line $line · Column $col"+(if(f.selection.collapsed)"" else " · ${f.selection.end-f.selection.start} selected")
        recoverBtn?.isVisible = !surface.admissionAllowed
        applyDraftBtn?.isVisible = surface.inspectRetainedDraft() != null
        discardDraftBtn?.isVisible = surface.inspectRetainedDraft() != null
        val degradedNote = if (!surface.admissionAllowed && surface.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED) " · Input paused (degraded recovery) — Save available" else ""
        notice.text=files.status+(if(f.notice=="Ready")"" else " · ${f.notice}")+degradedNote;title.text=files.name;window.title="${files.name} — Astra Editor";open.isEnabled=!files.busy;save.isEnabled=!files.busy;undo.isEnabled=f.undoCount>0||f.marked!=null;redo.isEnabled=f.redoCount>0&&f.marked==null
        pasteBtn.isEnabled = !files.busy && surface.admissionAllowed
        bold.isEnabled = !files.busy && surface.admissionAllowed
        italic.isEnabled = !files.busy && surface.admissionAllowed
        fun label(name: String, key: FormatKey) = when(engine.formatState(key)) { true -> "$name on"; false -> name; null -> "$name mixed" }
        bold.text=label("Bold",FormatKey.BOLD);italic.text=label("Italic",FormatKey.ITALIC)
    }
    surface.statusChanged(engine.snapshot())
    val surfaceGetter: () -> EditorSurface = { surface }
    window.rootPane.putClientProperty("editor.files", files)
    window.rootPane.putClientProperty("editor.layers", layers)
    window.rootPane.putClientProperty("editor.surfaceGetter", surfaceGetter)
    return window.apply {
        minimumSize=Dimension(940,400)
        contentPane=JPanel(BorderLayout()).apply {add(header,BorderLayout.NORTH);add(layers,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH)}
        pack()
        when {
            windowLocation != null -> { isAutoRequestFocus = false; location = windowLocation }
            offscreenTest -> { isAutoRequestFocus = false; setLocation(-16000, -16000) }
            else -> setLocationRelativeTo(null)
        }
        isVisible=true
        addWindowListener(object:WindowAdapter(){override fun windowClosed(e:WindowEvent){if (!surface.disposed) surface.removeNotify(); compose.dispose()}})
        SwingUtilities.invokeLater {if(!offscreenTest && windowLocation == null) surface.requestFocusInWindow();surface.writeDiagnostics()}
    }
}

