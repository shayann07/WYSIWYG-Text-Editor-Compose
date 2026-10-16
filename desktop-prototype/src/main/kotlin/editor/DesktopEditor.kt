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
    while (i < units.size) {
        val c = units[i].char
        val codePoint = if (Character.isHighSurrogate(c) && i + 1 < units.size && Character.isLowSurrogate(units[i + 1].char)) {
            Character.toCodePoint(c, units[++i].char)
        } else c.code
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return ResolvedTextDirection.Rtl
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return ResolvedTextDirection.Ltr
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
}

data class Geometry(
    val paragraphs: List<ParagraphLayout>,
    val totalHeight: Float,
    val x: Float,
    val y: Float,
    val scale: Float,
    val revision: Long,
    val textWidth: Int,
    val scroll: Float = 0f
) {
    val layout: TextLayoutResult get() = paragraphs.first().layout
    val totalLineCount: Int get() = paragraphs.sumOf { it.lineCount }

    fun paragraphForOffset(documentOffset: Int, leadingAffinity: Boolean = false): ParagraphLayout {
        if (paragraphs.isEmpty()) throw IllegalStateException("Geometry has no paragraphs")
        if (documentOffset <= 0) return paragraphs.first()
        for (i in paragraphs.indices) {
            val p = paragraphs[i]
            if (p.hasBreak) {
                if (documentOffset in p.startOffset until p.endOffset) return p
                if (documentOffset == p.endOffset) {
                    return if (leadingAffinity && i + 1 < paragraphs.size) paragraphs[i + 1] else p
                }
            } else {
                if (documentOffset in p.startOffset..p.endOffset) return p
            }
        }
        return paragraphs.last()
    }

    fun getLineForOffset(documentOffset: Int): Int {
        val p = paragraphForOffset(documentOffset)
        return p.startLineIndex + (if (p.textLength == 0) 0 else p.layout.getLineForOffset(p.toLocalOffset(documentOffset)))
    }

    fun getParagraphDirection(documentOffset: Int): ResolvedTextDirection = paragraphForOffset(documentOffset).baseDirection

    fun getHorizontalPosition(documentOffset: Int, primary: Boolean = true): Float {
        val p = paragraphForOffset(documentOffset)
        return p.layout.getHorizontalPosition(p.toLocalOffset(documentOffset), primary)
    }

    fun caret(offset: Int, primary: Boolean = true, leadingAffinity: Boolean = false): androidx.compose.ui.geometry.Rect {
        val p = paragraphForOffset(offset, leadingAffinity)
        val local = p.toLocalOffset(offset)
        val r = p.layout.getCursorRect(local)
        val cx = p.layout.getHorizontalPosition(local, primary)
        val py = y + p.top
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
    var geometry: Geometry? = null
    var measure: ((EditorFrame) -> Geometry)? = null
}

fun referenceMeasure(document: Document, textWidth: Int, measurer: TextMeasurer, style: TextStyle): List<Pair<ParagraphSpan, TextLayoutResult>> {
    val spans = partitionParagraphs(document)
    var fallback = ResolvedTextDirection.Ltr
    return spans.map { span ->
        val dir = paragraphBaseDirection(span.units, fallback)
        fallback = dir
        val annotated = paragraphAttributedDisplay(span.units)
        val paraStyle = style.copy(
            textDirection = if (dir == ResolvedTextDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
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
    Canvas(Modifier.fillMaxSize()) {
        val scale = density
        val pageWidth = min(size.width - 24.dp.toPx(), 800.dp.toPx()).coerceAtLeast(150.dp.toPx())
        val pageLeft = (size.width - pageWidth) / 2
        val padding = min(52.dp.toPx(), pageWidth * .1f)
        val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
        val style = TextStyle(color = ComposeColor(0xff202b3c), fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 30.sp, textDirection = TextDirection.Content)
        fun measure(frame: EditorFrame): Geometry {
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
                scroll = state.scroll
            )
        }
        state.measure = ::measure
        val frame = state.frame
        var geo = measure(frame)
        val contentHeight = geo.totalHeight + padding * 2 + 56.dp.toPx()
        val maxScroll = max(0f, (contentHeight - size.height) / scale)
        var scroll = state.scroll.coerceIn(0f, maxScroll)
        if (state.ensureCaret) {
            val caret = geo.caret(frame.selection.focus)
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
            for (p in geo.paragraphs) {
                val selStart = max(s.start, p.startOffset)
                val selEnd = min(s.end, p.endOffset)
                if (selStart < selEnd) {
                    val localA = p.toLocalOffset(selStart)
                    val localB = p.toLocalOffset(selEnd)
                    val path = p.layout.getPathForRange(localA, localB)
                    withTransform({ translate(geo.x, geo.y + p.top) }) {
                        drawPath(path, ComposeColor(if (state.focused) 0xffc8dcff else 0xffdce3ed))
                    }
                }
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
            val caret = geo.caret(s.focus, s.primary)
            drawRect(ComposeColor(0xff2559ba), caret.topLeft, caret.size)
        }
        if (maxScroll > 0) {
            val h = max(32.dp.toPx(), size.height * size.height / contentHeight)
            val y = (size.height - h) * state.scroll / maxScroll
            drawRoundRect(ComposeColor(0xffb1bac8), Offset(size.width - 7.dp.toPx(), y), androidx.compose.ui.geometry.Size(4.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

/** The transparent AWT root owns input; the Compose surface underneath only renders. */
class EditorSurface(val engine: EditorEngine, val view: ViewState, private val diagnostics: File? = null, private val trace: ImeTrace? = null) : JComponent(), InputMethodRequests {
    @Volatile private var published = engine.snapshot()
    private var dragAnchor = 0
    private var preferredX: Float? = null
    private var keyEvents = 0; private var imeEvents = 0
    private val blinkTimer = Timer(500) { if (view.focused) view.blink = !view.blink }
    var statusChanged: (EditorFrame) -> Unit = {}
    var requestSave: (Boolean) -> Unit = {}
    var requestOpen: () -> Unit = {}
    init {
        isOpaque = false; isFocusable = true; focusTraversalKeysEnabled = false
        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR); enableInputMethods(true)
        engine.onChange = { f ->
            val old = published; published = f; view.frame = f; view.blink = true; view.ensureCaret = true
            statusChanged(f); repaint()
            try {
                getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY, old.visible.text, f.visible.text)
                getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_CARET_PROPERTY, old.selection.focus, f.selection.focus)
            } finally { writeDiagnostics() }
        }
        engine.onSettlementRequested = { inputContext?.endComposition() }
        engine.onEvent = { event -> trace?.record("engine",event,engine.snapshot()); diagnostics?.parentFile?.let { it.mkdirs(); File(it, "input-events.log").appendText("${System.currentTimeMillis()} $event\n") } }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { view.focused = true; view.blink = true; trace?.record("focus-gained","locale=${inputContext?.locale}",engine.snapshot()); writeDiagnostics() }
            override fun focusLost(e: FocusEvent) { view.focused = false; trace?.record("focus-lost","temporary=${e.isTemporary}",engine.snapshot()); inputContext?.endComposition(); writeDiagnostics() }
        })
        addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(e: InputMethodEvent) {
                imeEvents++
                val text = e.text?.let { iterator -> buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) } } ?: ""
                val committed = e.committedCharacterCount
                val caret = e.caret?.insertionIndex
                trace?.record("awt-text","text=$text; committed=$committed; caret=$caret; leading=${e.caret?.isLeadingEdge}; locale=${inputContext?.locale}",engine.snapshot())
                if (committed in 0..text.length && (caret == null || caret in 0..text.length - committed)) engine.inputMethod(text, committed, caret)
                else runCatching { engine.onEvent("Rejected malformed native text/caret range") }
                e.consume(); preferredX = null
            }
            override fun caretPositionChanged(e: InputMethodEvent) { trace?.record("awt-caret","${e.caret}",engine.snapshot()); e.caret?.let { engine.inputMethodCaret(it.insertionIndex) }; e.consume() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (e.isConsumed || published.marked != null) return
                if ((e.isControlDown || e.isMetaDown || e.isAltDown) && !e.isAltGraphDown) return
                if (e.keyChar >= ' ' && e.keyChar != '\u007f' && e.keyChar != KeyEvent.CHAR_UNDEFINED) { keyEvents++; engine.replace(e.keyChar.toString()); preferredX = null; e.consume() }
            }
            override fun keyPressed(e: KeyEvent) {
                if (e.isConsumed) return
                trace?.record("key-pressed","code=${e.keyCode}; modifiers=${e.modifiersEx}; locale=${inputContext?.locale}",engine.snapshot())
                val command = (e.isControlDown || e.isMetaDown) && !e.isAltGraphDown
                when {
                    command && e.keyCode == KeyEvent.VK_S -> requestSave(e.isShiftDown)
                    command && e.keyCode == KeyEvent.VK_O -> requestOpen()
                    command && e.keyCode == KeyEvent.VK_B -> engine.toggleFormat(FormatKey.BOLD)
                    command && e.keyCode == KeyEvent.VK_I -> engine.toggleFormat(FormatKey.ITALIC)
                    command && e.keyCode == KeyEvent.VK_Z -> engine.perform(if (e.isShiftDown) EditAction.Redo else EditAction.Undo)
                    command && e.keyCode == KeyEvent.VK_Y -> engine.perform(EditAction.Redo)
                    command && e.keyCode == KeyEvent.VK_A -> engine.selectAll()
                    command && e.keyCode == KeyEvent.VK_C -> copySelection(false)
                    command && e.keyCode == KeyEvent.VK_X -> copySelection(true)
                    command && e.keyCode == KeyEvent.VK_V -> paste()
                    e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE -> { if (command && published.selection.collapsed) wordDelete(e.keyCode == KeyEvent.VK_BACK_SPACE) else engine.delete(e.keyCode == KeyEvent.VK_BACK_SPACE) }
                    e.keyCode == KeyEvent.VK_ENTER -> engine.replace("\n", "Enter", paragraphBreaks = !e.isShiftDown)
                    e.keyCode == KeyEvent.VK_TAB -> engine.replace("\t", "Typing")
                    e.keyCode in arrayOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END) -> navigate(e.keyCode, e.isShiftDown, command)
                    e.keyCode == KeyEvent.VK_ESCAPE && published.marked != null -> inputContext?.endComposition()
                    else -> return
                }
                e.consume(); writeDiagnostics()
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
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
            if (e.y < 12) view.scroll = max(0f, view.scroll - 18) else if (e.y > height - 12) view.scroll += 18
            hit(e.point)?.let { engine.move(Selection(dragAnchor, it)) }
        } })
        addMouseWheelListener { e -> view.ensureCaret = false; view.scroll = max(0f, view.scroll + e.preciseWheelRotation.toFloat() * 56) }
        blinkTimer.start()
    }
    override fun removeNotify() { blinkTimer.stop(); super.removeNotify() }
    private fun geometry(): Geometry? = view.geometry?.takeIf { it.revision == published.documentRevision && it.scroll == view.scroll } ?: view.measure?.invoke(published)?.also { view.geometry = it }
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
    private data class Stop(val offset: Int, val x: Float, val line: Int, val primary: Boolean)
    private fun stops(g: Geometry): List<Stop> = Graphemes.stops(published.visible.text).flatMap { offset ->
        val p = g.paragraphForOffset(offset)
        val local = p.toLocalOffset(offset)
        val localLine = if (p.textLength == 0) 0 else p.layout.getLineForOffset(local)
        val globalLine = p.startLineIndex + localLine
        val primary = p.layout.getHorizontalPosition(local, true)
        val secondary = p.layout.getHorizontalPosition(local, false)
        listOf(Stop(offset, primary, globalLine, true)) + if (abs(primary - secondary) > .5f) listOf(Stop(offset, secondary, globalLine, false)) else emptyList()
    }.sortedWith(compareBy<Stop> { it.line }.thenBy { it.x }.thenBy { it.offset })
    private fun navigate(key: Int, extend: Boolean, command: Boolean) {
        val g = geometry() ?: return; val s = published.selection; val text = published.visible.text
        val legalFocus = Graphemes.snap(text, s.focus, key in arrayOf(KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_END))
        val all = stops(g); val current = all.indexOfFirst { it.offset == legalFocus && it.primary == s.primary }.let { if (it < 0) all.indexOfFirst { p -> p.offset == legalFocus }.coerceAtLeast(0) else it }
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
        engine.move(Selection(if (extend) Graphemes.snap(text, s.anchor, false) else target.offset, target.offset, target.primary), target.primary, if (target.offset < s.focus) TypingSide.RIGHT else TypingSide.LEFT)
    }
    private fun wordDelete(back: Boolean) {
        val text = published.visible.text; val at = published.selection.focus
        val word = BreakIterator.getWordInstance().apply { setText(text) }
        val other = (if (back) word.preceding(at) else word.following(at)).let { if (it < 0) if (back) 0 else text.length else it }
        engine.replace("", "Delete", range = Selection(at, other), deleteSide = if (back) TypingSide.LEFT else TypingSide.RIGHT)
    }
    fun copySelection(cut: Boolean) {
        val s = published.selection; if (s.collapsed) return
        try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(published.visible.text.substring(s.start, s.end)), null); if (cut) engine.replace("", "Cut") }
        catch (_: IllegalStateException) { Toolkit.getDefaultToolkit().beep() }
    }
    fun paste() { try { val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: return; engine.replace(text.replace("\r\n", "\n").replace('\r', '\n'), "Paste", paragraphBreaks = true) } catch (_: Exception) { Toolkit.getDefaultToolkit().beep() } }
    override fun getInputMethodRequests(): InputMethodRequests = this
    override fun getCommittedText(beginIndex: Int, endIndex: Int, attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator {
        val text = committedContext(); require(beginIndex in 0..endIndex && endIndex <= text.length); return AttributedString(text.substring(beginIndex, endIndex)).iterator
    }
    private fun committedContext(): String = published.marked?.let { published.visible.text.removeRange(it.first, it.last + 1) } ?: published.visible.text
    override fun getCommittedTextLength() = committedContext().length
    override fun getInsertPositionOffset() = published.marked?.first ?: published.selection.start
    override fun getSelectedText(attributes: Array<out AttributedCharacterIterator.Attribute>?) = AttributedString(published.visible.text.substring(published.selection.start, published.selection.end)).iterator
    override fun cancelLatestCommittedText(attributes: Array<out AttributedCharacterIterator.Attribute>?): AttributedCharacterIterator? = null
    override fun getTextLocation(offset: TextHitInfo?): Rectangle {
        val g = geometry()
        val screen = try {
            if (!isShowing) null else locationOnScreen
        } catch (_: IllegalComponentStateException) {
            null
        }
        if (g == null || screen == null) {
            return Rectangle(0, 0, 0, 0)
        }
        val mark = published.marked; val s = published.selection
        val at = if (mark != null) mark.first + (offset?.insertionIndex ?: 0).also { require(it in 0..mark.count()) } else s.focus
        val caret = g.caret(at, if (mark != null) offset?.isLeadingEdge ?: true else s.primary)
        var x = caret.left; var y = caret.top; var bottom = caret.bottom
        if (mark == null && !s.collapsed) {
            val p = g.paragraphForOffset(s.end)
            val localEnd = p.toLocalOffset(s.end)
            val line = p.layout.getLineForOffset(localEnd)
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
        val s=published
        f.writeText("{\"text\":${quoted(s.visible.text)},\"anchor\":${s.selection.anchor},\"focus\":${s.selection.focus},\"undo\":${s.undoCount},\"redo\":${s.redoCount},\"documentRevision\":${s.documentRevision},\"sessionRevision\":${s.sessionRevision},\"composing\":${s.marked!=null},\"waiting\":${s.waiting},\"focused\":$isFocusOwner,\"keyTypedEvents\":$keyEvents,\"imeTextEvents\":$imeEvents,\"notice\":${quoted(s.notice)}}")
    }
}

fun showEditor(diagnostics: File? = null, initial: Document = Document(emptyList()), initialPath: java.nio.file.Path? = null, trace: ImeTrace? = null, traceLanguage: String? = null, offscreenTest: Boolean = false, windowLocation: Point? = null): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    val engine=EditorEngine(initial);val state=ViewState(engine.snapshot());val surface=EditorSurface(engine,state,diagnostics,trace)
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
            val accepted=surface.inputContext.selectInputMethod(java.util.Locale.forLanguageTag(tag))
            val compositionEnabled = if(accepted && tag=="ja-JP") runCatching { surface.inputContext.isCompositionEnabled=true; surface.inputContext.isCompositionEnabled }.getOrNull() else null
            result.text="Input: $tag — ${if(accepted)"selected" else "unavailable"}"
            trace.record("select-input","requested=$tag; accepted=$accepted; actual=${surface.inputContext.locale}; compositionEnabled=$compositionEnabled",engine.snapshot())
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
    val bold=button("Bold","Toggle bold (Ctrl+B)"){engine.toggleFormat(FormatKey.BOLD)}
    val italic=button("Italic","Toggle italic (Ctrl+I)"){engine.toggleFormat(FormatKey.ITALIC)}
    button("Copy","Copy selected text (Ctrl+C)"){surface.copySelection(false)};button("Paste","Paste plain text (Ctrl+V)"){surface.paste()};header.add(buttons,BorderLayout.EAST)
    val footer=JPanel(BorderLayout()).apply {background=bg;border=EmptyBorder(10,24,10,24)}
    val position=JLabel();val notice=JLabel();listOf(position,notice).forEach {it.font=Font("Segoe UI",Font.PLAIN,12);it.foreground=Color(0x64748b)}
    footer.add(position,BorderLayout.WEST);footer.add(notice,BorderLayout.EAST)
    files=WindowFiles(window,engine,initialPath){surface.statusChanged(engine.snapshot())}
    surface.requestSave={files.save(it)};surface.requestOpen={files.open()}
    surface.statusChanged={f->val before=f.visible.text.take(f.selection.focus);val line=before.count {it=='\n'}+1;val col=Graphemes.stops(before.substringAfterLast('\n')).size;position.text="Line $line · Column $col"+(if(f.selection.collapsed)"" else " · ${f.selection.end-f.selection.start} selected");notice.text=files.status+(if(f.notice=="Ready")"" else " · ${f.notice}");title.text=files.name;window.title="${files.name} — Astra Editor";open.isEnabled=!files.busy;save.isEnabled=!files.busy;undo.isEnabled=f.undoCount>0||f.marked!=null;redo.isEnabled=f.redoCount>0&&f.marked==null
        fun label(name: String, key: FormatKey) = when(engine.formatState(key)) { true -> "$name on"; false -> name; null -> "$name mixed" }
        bold.text=label("Bold",FormatKey.BOLD);italic.text=label("Italic",FormatKey.ITALIC)
    }
    surface.statusChanged(engine.snapshot())
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
        addWindowListener(object:WindowAdapter(){override fun windowClosed(e:WindowEvent){compose.dispose()}})
        SwingUtilities.invokeLater {if(!offscreenTest && windowLocation == null) surface.requestFocusInWindow();surface.writeDiagnostics()}
    }
}
