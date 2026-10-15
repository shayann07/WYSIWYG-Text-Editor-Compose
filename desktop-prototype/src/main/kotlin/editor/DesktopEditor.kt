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
data class Geometry(val layout: TextLayoutResult, val x: Float, val y: Float, val scale: Float, val revision: Long) {
    fun caret(offset: Int, primary: Boolean = true): androidx.compose.ui.geometry.Rect {
        val at = offset.coerceIn(0, layout.layoutInput.text.length)
        val r = layout.getCursorRect(at)
        val cx = layout.getHorizontalPosition(at, primary)
        return androidx.compose.ui.geometry.Rect(x + cx, y + r.top, x + cx + scale * 1.5f, y + r.bottom)
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
            val layout = synchronized(measurer) { measurer.measure(AnnotatedString(displayText(frame.visible.text)), style = style, constraints = Constraints(maxWidth = textWidth)) }
            return Geometry(layout, pageLeft + padding, 28.dp.toPx() + padding - state.scroll * scale, scale, frame.documentRevision)
        }
        state.measure = ::measure
        val frame = state.frame
        var geo = measure(frame)
        val contentHeight = geo.layout.size.height + padding * 2 + 56.dp.toPx()
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
        val pageHeight = max(size.height - 56.dp.toPx(), geo.layout.size.height + padding * 2)
        drawRect(ComposeColor(0xffe1e6ed), Offset(pageLeft + 2.dp.toPx(), pageY + 3.dp.toPx()), androidx.compose.ui.geometry.Size(pageWidth, pageHeight))
        drawRect(ComposeColor.White, Offset(pageLeft, pageY), androidx.compose.ui.geometry.Size(pageWidth, pageHeight))
        val s = frame.selection
        if (!s.collapsed) {
            val path = geo.layout.getPathForRange(s.start, s.end)
            withTransform({ translate(geo.x, geo.y) }) { drawPath(path, ComposeColor(if (state.focused) 0xffc8dcff else 0xffdce3ed)) }
        }
        drawText(geo.layout, topLeft = Offset(geo.x, geo.y))
        if (frame.visible.text.isEmpty() && frame.marked == null) {
            val placeholder = measurer.measure(AnnotatedString("Start typing…"), style = style.copy(color = ComposeColor(0xff9aa4b2)))
            drawText(placeholder, topLeft = Offset(geo.x, geo.y))
        }
        frame.marked?.takeUnless { it.isEmpty() }?.let { range ->
            val first = geo.layout.getLineForOffset(range.first)
            val last = geo.layout.getLineForOffset(range.last + 1)
            for (line in first..last) {
                val a = max(range.first, geo.layout.getLineStart(line)); val b = min(range.last + 1, geo.layout.getLineEnd(line, true))
                if (a <= b) {
                    val x1 = geo.layout.getHorizontalPosition(a, true); val x2 = geo.layout.getHorizontalPosition(b, true)
                    val y = geo.y + geo.layout.getLineBottom(line) - 2.dp.toPx()
                    drawLine(ComposeColor(0xff3467d5), Offset(geo.x + min(x1, x2), y), Offset(geo.x + max(x1, x2), y), 1.5.dp.toPx())
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
class EditorSurface(val engine: EditorEngine, val view: ViewState, private val diagnostics: File? = null) : JComponent(), InputMethodRequests {
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
        engine.onEvent = { event -> diagnostics?.parentFile?.let { it.mkdirs(); File(it, "input-events.log").appendText("${System.currentTimeMillis()} $event\n") } }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { view.focused = true; view.blink = true; writeDiagnostics() }
            override fun focusLost(e: FocusEvent) { view.focused = false; inputContext?.endComposition(); writeDiagnostics() }
        })
        addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(e: InputMethodEvent) {
                imeEvents++
                val text = e.text?.let { iterator -> buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) } } ?: ""
                val committed = e.committedCharacterCount
                val caret = e.caret?.insertionIndex
                if (committed in 0..text.length && (caret == null || caret in 0..text.length - committed)) engine.inputMethod(text, committed, caret)
                else runCatching { engine.onEvent("Rejected malformed native text/caret range") }
                e.consume(); preferredX = null
            }
            override fun caretPositionChanged(e: InputMethodEvent) { e.caret?.let { engine.inputMethodCaret(it.insertionIndex) }; e.consume() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (e.isConsumed || published.marked != null) return
                if ((e.isControlDown || e.isMetaDown || e.isAltDown) && !e.isAltGraphDown) return
                if (e.keyChar >= ' ' && e.keyChar != '\u007f' && e.keyChar != KeyEvent.CHAR_UNDEFINED) { keyEvents++; engine.replace(e.keyChar.toString()); preferredX = null; e.consume() }
            }
            override fun keyPressed(e: KeyEvent) {
                if (e.isConsumed) return
                val command = (e.isControlDown || e.isMetaDown) && !e.isAltGraphDown
                when {
                    command && e.keyCode == KeyEvent.VK_S -> requestSave(e.isShiftDown)
                    command && e.keyCode == KeyEvent.VK_O -> requestOpen()
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
                } else { dragAnchor = if (e.isShiftDown) published.selection.anchor else pos; engine.move(Selection(dragAnchor, pos)) }
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
    private fun geometry(): Geometry? = view.geometry?.takeIf { it.revision == published.documentRevision } ?: view.measure?.invoke(published)?.also { view.geometry = it }
    private fun hit(point: Point): Int? {
        val g = geometry() ?: return null
        val at = g.layout.getOffsetForPosition(Offset(point.x * g.scale - g.x, point.y * g.scale - g.y)).coerceIn(0, published.visible.units.size)
        val a = Graphemes.snap(published.visible.text, at, false); val b = Graphemes.snap(published.visible.text, at, true)
        return if (abs(g.caret(a).left - point.x * g.scale) < abs(g.caret(b).left - point.x * g.scale)) a else b
    }
    private data class Stop(val offset: Int, val x: Float, val line: Int, val primary: Boolean)
    private fun stops(g: Geometry): List<Stop> = Graphemes.stops(published.visible.text).flatMap { offset ->
        val line = g.layout.getLineForOffset(offset); val primary = g.layout.getHorizontalPosition(offset, true); val secondary = g.layout.getHorizontalPosition(offset, false)
        listOf(Stop(offset, primary, line, true)) + if (abs(primary - secondary) > .5f) listOf(Stop(offset, secondary, line, false)) else emptyList()
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
                if (!extend && !s.collapsed) target = all.first { it.offset == if (key == KeyEvent.VK_LEFT) s.start else s.end }
                else target = if (legalFocus != s.focus) all[current] else all[(current + if (key == KeyEvent.VK_LEFT) -1 else 1).coerceIn(0, all.lastIndex)]
            }
            key == KeyEvent.VK_HOME -> target = all.first { it.line == here.line }
            key == KeyEvent.VK_END -> target = all.last { it.line == here.line }
            else -> { val x = preferredX ?: here.x.also { preferredX = it }; val line = (here.line + if (key == KeyEvent.VK_UP) -1 else 1).coerceIn(0, g.layout.lineCount - 1); target = all.filter { it.line == line }.minByOrNull { abs(it.x - x) } ?: here }
        }
        if (key !in arrayOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN)) preferredX = null
        engine.move(Selection(if (extend) Graphemes.snap(text, s.anchor, false) else target.offset, target.offset, target.primary), target.primary)
    }
    private fun wordDelete(back: Boolean) {
        val text = published.visible.text; val at = published.selection.focus
        val word = BreakIterator.getWordInstance().apply { setText(text) }
        val other = (if (back) word.preceding(at) else word.following(at)).let { if (it < 0) if (back) 0 else text.length else it }
        engine.replace("", "Delete", range = Selection(at, other))
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
        val g = geometry() ?: return Rectangle(runCatching { locationOnScreen }.getOrDefault(Point()), Dimension(0, 20))
        val mark = published.marked; val s = published.selection
        val at = if (mark != null) mark.first + (offset?.insertionIndex ?: 0).also { require(it in 0..mark.count()) } else s.focus
        val caret = g.caret(at, if (mark != null) offset?.isLeadingEdge ?: true else s.primary)
        var x = caret.left; var y = caret.top; var bottom = caret.bottom
        if (mark == null && !s.collapsed) {
            val line = g.layout.getLineForOffset(s.end); val a = max(s.start, g.layout.getLineStart(line)); val b = min(s.end, g.layout.getLineEnd(line, true))
            val x1 = g.layout.getHorizontalPosition(a, true); val x2 = g.layout.getHorizontalPosition(b, true)
            x = g.x + if (g.layout.getParagraphDirection(a) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl) max(x1, x2) else min(x1, x2)
            y = g.y + g.layout.getLineTop(line); bottom = g.y + g.layout.getLineBottom(line)
        }
        val screen = runCatching { locationOnScreen }.getOrDefault(Point())
        return Rectangle(screen.x + (x / g.scale).roundToInt(), screen.y + (y / g.scale).roundToInt(), 0, ((bottom - y) / g.scale).roundToInt().coerceAtLeast(1))
    }
    override fun getLocationOffset(x: Int, y: Int): TextHitInfo? {
        val mark = published.marked ?: return null; val g = geometry() ?: return null; val screen = locationOnScreen
        val p = Offset((x - screen.x) * g.scale - g.x, (y - screen.y) * g.scale - g.y)
        if (mark.none { i -> g.layout.getBoundingBox(i).contains(p) }) return null
        val offset = g.layout.getOffsetForPosition(p).coerceIn(mark.first, mark.last + 1) - mark.first
        return TextHitInfo.leading(offset)
    }
    override fun getAccessibleContext(): AccessibleContext { if (accessibleContext == null) accessibleContext = AccessibleEditor(); return accessibleContext }
    protected inner class AccessibleEditor : AccessibleJComponent(), AccessibleText {
        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.TEXT
        override fun getAccessibleName(): String = "Document editor"
        override fun getAccessibleDescription(): String = "Editable document. Type text; use Control Z to undo."
        override fun getAccessibleText(): AccessibleText = this
        override fun getIndexAtPoint(p: Point): Int = hit(p) ?: -1
        override fun getCharacterBounds(i: Int): Rectangle? { val g = geometry() ?: return null; if (i !in published.visible.text.indices) return null; val r = g.layout.getBoundingBox(i); return Rectangle(((g.x+r.left)/g.scale).toInt(),((g.y+r.top)/g.scale).toInt(),(r.width/g.scale).toInt(),(r.height/g.scale).toInt()) }
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

fun showEditor(diagnostics: File? = null, initial: Document = Document(emptyList()), initialPath: java.nio.file.Path? = null): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    val engine=EditorEngine(initial);val state=ViewState(engine.snapshot());val surface=EditorSurface(engine,state,diagnostics)
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
    val buttons=JPanel(FlowLayout(FlowLayout.RIGHT,8,0)).apply {isOpaque=false}
    fun button(label:String,tooltip:String,action:()->Unit)=JButton(label).apply {isFocusable=false;font=Font("Segoe UI",Font.PLAIN,13);toolTipText=tooltip;addActionListener {action();surface.requestFocusInWindow()};buttons.add(this)}
    val open=button("Open","Open a document in a new window (Ctrl+O)"){files.open()}
    val save=button("Save","Save document (Ctrl+S); Save As: Ctrl+Shift+S"){files.save()}
    val undo=button("Undo","Ctrl+Z"){engine.perform(EditAction.Undo)};val redo=button("Redo","Ctrl+Shift+Z or Ctrl+Y"){engine.perform(EditAction.Redo)}
    button("Copy","Copy selected text (Ctrl+C)"){surface.copySelection(false)};button("Paste","Paste plain text (Ctrl+V)"){surface.paste()};header.add(buttons,BorderLayout.EAST)
    val footer=JPanel(BorderLayout()).apply {background=bg;border=EmptyBorder(10,24,10,24)}
    val position=JLabel();val notice=JLabel();listOf(position,notice).forEach {it.font=Font("Segoe UI",Font.PLAIN,12);it.foreground=Color(0x64748b)}
    footer.add(position,BorderLayout.WEST);footer.add(notice,BorderLayout.EAST)
    files=WindowFiles(window,engine,initialPath){surface.statusChanged(engine.snapshot())}
    surface.requestSave={files.save(it)};surface.requestOpen={files.open()}
    surface.statusChanged={f->val before=f.visible.text.take(f.selection.focus);val line=before.count {it=='\n'}+1;val col=Graphemes.stops(before.substringAfterLast('\n')).size;position.text="Line $line · Column $col"+(if(f.selection.collapsed)"" else " · ${f.selection.end-f.selection.start} selected");notice.text=if(f.notice=="Ready")files.status else f.notice;title.text=files.name;window.title="${files.name} — Astra Editor";open.isEnabled=!files.busy;save.isEnabled=!files.busy;undo.isEnabled=f.undoCount>0||f.marked!=null;redo.isEnabled=f.redoCount>0&&f.marked==null}
    surface.statusChanged(engine.snapshot())
    return window.apply {
        minimumSize=Dimension(760,400)
        contentPane=JPanel(BorderLayout()).apply {add(header,BorderLayout.NORTH);add(layers,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH)}
        pack();setLocationRelativeTo(null);isVisible=true
        addWindowListener(object:WindowAdapter(){override fun windowClosed(e:WindowEvent){compose.dispose()}})
        SwingUtilities.invokeLater {surface.requestFocusInWindow();surface.writeDiagnostics()}
    }
}
