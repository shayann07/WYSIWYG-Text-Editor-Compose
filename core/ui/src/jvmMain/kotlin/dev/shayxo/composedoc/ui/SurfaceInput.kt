package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import java.awt.Color
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.io.File
import java.text.BreakIterator
import kotlin.math.max

/** Image resize handle colour (RGB). */
private const val IMAGE_HANDLE_COLOR = 0x174ea6

/** Resize hint placement above the image, and its smallest baseline, in AWT pixels. */
private const val RESIZE_HINT_RISE = 8
private const val RESIZE_HINT_MIN_BASELINE = 16

/** Click count that selects a whole paragraph. */
private const val PARAGRAPH_CLICK_COUNT = 3

/** Drag auto-scroll: edge band height in pixels and scroll step per drag event. */
private const val AUTOSCROLL_EDGE = 12
private const val AUTOSCROLL_STEP = 18

/** Scroll distance per mouse-wheel notch. */
private const val WHEEL_SCROLL_STEP = 56

internal fun EditorSurface.paintImageHandles(g: java.awt.Graphics) {
    val h = imageHandles(this) ?: return
    g.color = Color(IMAGE_HANDLE_COLOR)
    g.fillRect(h.left.x, h.left.y, h.left.width, h.left.height)
    g.fillRect(h.right.x, h.right.y, h.right.width, h.right.height)
    imageResize?.let { drag ->
        val n = drag.width(imageResizeX)
        val w = (h.bounds.width * n.toFloat() / drag.image.width).toInt()
        val hh = (h.bounds.height * n.toFloat() / drag.image.width).toInt()
        val x = if (drag.rightHandle)h.bounds.x else h.bounds.x + h.bounds.width - w
        g.drawRect(x, h.bounds.y, w, hh)
        g.drawString(
            "Width $n dp · Escape cancels",
            x.coerceAtLeast(0),
            (h.bounds.y - RESIZE_HINT_RISE).coerceAtLeast(RESIZE_HINT_MIN_BASELINE),
        )
    }
}

internal fun EditorSurface.bindViewModelCallbacks() {
    viewModel.onStatePublished = { f ->
        if (!disposed && (!retired || replacementSurface == null)) {
            val old = published
            published = f
            view.frame = f
            view.blink = true
            view.ensureCaret = true
            statusChanged(f)
            repaint()
            try {
                publishAccessibleChange(old, f)
            } finally {
                writeDiagnostics()
            }
        }
    }
    viewModel.onSettlementRequested = { if (!retired && !disposed) requestEndComposition() }
    viewModel.onEngineEvent = { event ->
        trace?.record("engine", event, viewModel.snapshot())
        diagnostics?.parentFile?.let {
            it.mkdirs()
            File(it, "input-events.log").appendText("${System.currentTimeMillis()} $event\n")
        }
    }
}

internal fun EditorSurface.installInputListeners() {
    addFocusListener(object : FocusAdapter() {
        override fun focusGained(e: FocusEvent) = onFocusGained()
        override fun focusLost(e: FocusEvent) = onFocusLost(e)
    })
    addInputMethodListener(object : InputMethodListener {
        override fun inputMethodTextChanged(e: InputMethodEvent) = onInputMethodTextChanged(e)
        override fun caretPositionChanged(e: InputMethodEvent) = onCaretPositionChanged(e)
    })
    addKeyListener(object : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) = onKeyTyped(e)
        override fun keyPressed(e: KeyEvent) = onKeyPressed(e)
    })
    addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = onMousePressed(e)
        override fun mouseReleased(e: MouseEvent) = onMouseReleased(e)
    })
    addMouseMotionListener(object : MouseMotionAdapter() {
        override fun mouseDragged(e: MouseEvent) = onMouseDragged(e)
    })
    addMouseWheelListener { e -> onMouseWheel(e) }
}

private fun EditorSurface.onFocusGained() {
    if (retired || disposed) return
    view.focused = true
    view.blink = true
    trace?.record("focus-gained", "locale=${inputContext?.locale}", viewModel.snapshot())
    writeDiagnostics()
}

private fun EditorSurface.onFocusLost(e: FocusEvent) {
    if (retired || disposed) return
    view.focused = false
    trace?.record("focus-lost", "temporary=${e.isTemporary}", viewModel.snapshot())
    requestEndComposition()
    writeDiagnostics()
}

private fun eventText(e: InputMethodEvent): String = e.text?.let { iterator ->
    buildString { for (index in iterator.beginIndex until iterator.endIndex) append(iterator.setIndex(index)) }
} ?: ""

private fun EditorSurface.onInputMethodTextChanged(e: InputMethodEvent) {
    when {
        retired || disposed -> {
            trace?.record("awt-text-ignored", "retired=$retired; disposed=$disposed", viewModel.snapshot())
            e.consume()
        }
        !admissionAllowed -> quarantineNativeText(e)
        else -> admitNativeText(e)
    }
}

private fun EditorSurface.quarantineNativeText(e: InputMethodEvent) {
    val text = eventText(e)
    val committed = e.committedCharacterCount
    val caret = e.caret?.insertionIndex
    quarantineInput(QuarantinedCallback(text, committed, caret, activeImeEpoch))
    trace?.record(
        "awt-text-quarantined",
        "text=$text; committed=$committed; caret=$caret; status=$recoveryStatus; admissionAllowed=false",
        viewModel.snapshot(),
    )
    recordDiagnosticEvent(
        "Input method callback quarantined: recovery status is $recoveryStatus; ambiguous native text not admitted",
    )
    e.consume()
    writeDiagnostics()
    statusChanged(viewModel.snapshot())
}

private fun EditorSurface.admitNativeText(e: InputMethodEvent) {
    imeEvents++
    val text = eventText(e)
    val committed = e.committedCharacterCount
    val caret = e.caret?.insertionIndex
    latestRecoveryProposal = QuarantinedCallback(text, committed, caret, activeImeEpoch)
    trace?.record(
        "awt-text",
        "text=$text; committed=$committed; caret=$caret; leading=${e.caret?.isLeadingEdge}; " +
            "locale=${inputContext?.locale}",
        viewModel.snapshot(),
    )
    if (committed in 0..text.length && (caret == null || caret in 0..text.length - committed)) {
        if (viewModel.nativeStructureAdmission(text)) {
            viewModel.dispatch(EditorIntent.NativeText(text, committed, caret))
        } else {
            pauseForRefusedNativeStructure(QuarantinedCallback(text, committed, caret, activeImeEpoch))
        }
    } else {
        runCatching { viewModel.onEngineEvent("Rejected malformed native text/caret range") }
    }
    e.consume()
    preferredX = null
}

private fun EditorSurface.pauseForRefusedNativeStructure(callback: QuarantinedCallback) {
    quarantineInput(callback)
    structuralInputPaused = true
    admissionAllowed = false
    contextQualified = false
    recoveryStatus = RecoveryStatus.DEGRADED_RECOVERY_FAILED
    recordDiagnosticEvent(
        "Native image seam refused; input paused until explicit host recovery. " +
            "Inspect capture status; callback is never replayed.",
    )
    writeDiagnostics()
    statusChanged(viewModel.snapshot())
}

private fun EditorSurface.onCaretPositionChanged(e: InputMethodEvent) {
    when {
        retired || disposed -> {
            trace?.record("awt-caret-ignored", "retired=$retired; disposed=$disposed", viewModel.snapshot())
            e.consume()
        }
        !admissionAllowed -> {
            trace?.record("awt-caret-quarantined", "${e.caret}", viewModel.snapshot())
            e.consume()
        }
        else -> {
            trace?.record("awt-caret", "${e.caret}", viewModel.snapshot())
            e.caret?.let { viewModel.dispatch(EditorIntent.NativeCaret(it.insertionIndex)) }
            e.consume()
        }
    }
}

private fun isTypedCharacter(c: Char): Boolean = c >= ' ' && c != '\u007f' && c != KeyEvent.CHAR_UNDEFINED

private fun EditorSurface.onKeyTyped(e: KeyEvent) {
    if (retired || disposed || !admissionAllowed) {
        e.consume()
        return
    }
    val modified = (e.isControlDown || e.isMetaDown || e.isAltDown) && !e.isAltGraphDown
    val accepted = !e.isConsumed && published.marked == null && !modified && isTypedCharacter(e.keyChar)
    if (accepted) {
        keyEvents++
        viewModel.dispatch(EditorIntent.Type(e.keyChar.toString()))
        preferredX = null
        e.consume()
    }
}

private fun EditorSurface.onKeyPressed(e: KeyEvent) {
    if (retired || disposed) {
        e.consume()
    } else if (!e.isConsumed) {
        trace?.record(
            "key-pressed",
            "code=${e.keyCode}; modifiers=${e.modifiersEx}; locale=${inputContext?.locale}",
            viewModel.snapshot(),
        )
        if (handleKeyPressed(e)) {
            e.consume()
            writeDiagnostics()
        }
    }
}

/** Runs the key binding for [e]; false when the key is not an editor binding. */
private fun EditorSurface.handleKeyPressed(e: KeyEvent): Boolean {
    val command = (e.isControlDown || e.isMetaDown) && !e.isAltGraphDown
    return (command && handleCommandKey(e)) || handleEditingKey(e, command)
}

/** Command (Ctrl/Cmd) shortcuts; false when [e] is not one, so editing keys are tried next. */
private fun EditorSurface.handleCommandKey(e: KeyEvent): Boolean {
    when (e.keyCode) {
        KeyEvent.VK_S -> requestSave(e.isShiftDown)
        KeyEvent.VK_O -> requestOpen()
        KeyEvent.VK_B -> toggleFormat(FormatKey.BOLD)
        KeyEvent.VK_I -> toggleFormat(FormatKey.ITALIC)
        KeyEvent.VK_U -> toggleFormat(FormatKey.UNDERLINE)
        KeyEvent.VK_Z -> viewModel.dispatch(
            EditorIntent.Command(if (e.isShiftDown) EditAction.Redo else EditAction.Undo),
        )
        KeyEvent.VK_Y -> viewModel.dispatch(EditorIntent.Command(EditAction.Redo))
        KeyEvent.VK_A -> viewModel.dispatch(EditorIntent.SelectAll)
        KeyEvent.VK_C -> copySelection(false)
        KeyEvent.VK_X -> copySelection(admissionAllowed)
        KeyEvent.VK_V -> if (admissionAllowed) paste(e.isShiftDown)
        else -> return false
    }
    return true
}

/** Editing, focus and navigation keys; false when [e] is not an editor binding. */
private fun EditorSurface.handleEditingKey(e: KeyEvent, command: Boolean): Boolean {
    when {
        e.keyCode == KeyEvent.VK_BACK_SPACE || e.keyCode == KeyEvent.VK_DELETE -> deleteForKey(e, command)
        e.keyCode == KeyEvent.VK_ENTER -> if (admissionAllowed) {
            viewModel.dispatch(EditorIntent.Type("\n", "Enter", paragraphBreaks = !e.isShiftDown))
        }
        e.keyCode == KeyEvent.VK_TAB -> if (e.isShiftDown)transferFocusBackward() else transferFocus()
        e.keyCode in NAVIGATION_KEYS -> navigate(e.keyCode, e.isShiftDown, command)
        e.keyCode == KeyEvent.VK_ESCAPE && imageResize != null -> {
            imageResize = null
            repaint()
        }
        e.keyCode == KeyEvent.VK_ESCAPE && compositionPending() -> {
            if (published.waiting || viewModel.settlementTimedOut) {
                abortAndRetireComposition()
            } else {
                requestEndComposition()
            }
        }
        else -> return false
    }
    return true
}

private val NAVIGATION_KEYS = arrayOf(
    KeyEvent.VK_LEFT,
    KeyEvent.VK_RIGHT,
    KeyEvent.VK_UP,
    KeyEvent.VK_DOWN,
    KeyEvent.VK_HOME,
    KeyEvent.VK_END,
    KeyEvent.VK_PAGE_UP,
    KeyEvent.VK_PAGE_DOWN,
)

private fun EditorSurface.compositionPending(): Boolean =
    published.marked != null || published.waiting || viewModel.settlementTimedOut

private fun EditorSurface.toggleFormat(key: FormatKey) {
    if (admissionAllowed) viewModel.dispatch(EditorIntent.ToggleFormat(key))
}

private fun EditorSurface.deleteForKey(e: KeyEvent, command: Boolean) {
    if (admissionAllowed) {
        if (command && published.selection.collapsed) {
            wordDelete(e.keyCode == KeyEvent.VK_BACK_SPACE)
        } else {
            viewModel.dispatch(EditorIntent.Delete(e.keyCode == KeyEvent.VK_BACK_SPACE))
        }
    }
}

private fun EditorSurface.onMousePressed(e: MouseEvent) {
    if (retired || disposed) return
    requestFocusInWindow()
    preferredX = null
    if (admissionAllowed && beginImageResize(e)) return
    hit(e.point)?.let { pos -> selectAtPointer(e, pos) }
}

/** Starts a resize drag when [e] lands on an image handle; true when the press belongs to the handles. */
private fun EditorSurface.beginImageResize(e: MouseEvent): Boolean {
    val h = imageHandles(this) ?: return false
    val onHandle = h.left.contains(e.point) || h.right.contains(e.point)
    if (onHandle) startImageResize(e, h)
    return onHandle
}

private fun EditorSurface.startImageResize(e: MouseEvent, h: ImageHandleGeometry) {
    val c = viewModel.clipboardCapture() ?: return
    val im = c.basis.document.units[c.selection.start].image ?: return
    imageResize = ImageResizeGesture(c, im, e.x, 1f / checkNotNull(geometry()).scale, h.right.contains(e.point))
    imageResizeX = e.x
    imageResizeView = resizeViewKey()
    repaint()
}

private fun EditorSurface.selectAtPointer(e: MouseEvent, pos: Int) {
    if (published.visible.units.getOrNull(pos)?.kind == UnitKind.IMAGE) {
        dragAnchor = pos
        viewModel.dispatch(EditorIntent.Select(Selection(pos, pos + 1)))
    } else if (e.clickCount >= PARAGRAPH_CLICK_COUNT) {
        val text = published.visible.text
        val start = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(-1)) + 1
        val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it + 1 }
        dragAnchor = start
        viewModel.dispatch(EditorIntent.Select(Selection(start, end)))
    } else if (e.clickCount == 2) {
        val text = published.visible.text
        val words = BreakIterator.getWordInstance().apply { setText(text) }
        val a = words.preceding((pos + 1).coerceAtMost(text.length)).let { if (it < 0) 0 else it }
        val b = words.following(pos).let { if (it < 0) text.length else it }
        dragAnchor = a
        viewModel.dispatch(EditorIntent.Select(Selection(a, b)))
    } else {
        dragAnchor = if (e.isShiftDown) published.selection.anchor else pos
        viewModel.dispatch(
            EditorIntent.Select(
                Selection(dragAnchor, pos),
                side = pointerSide(e.point, pos),
                wrapAffinity = pointerWrapAffinity(e.point, pos),
            ),
        )
    }
}

private fun EditorSurface.onMouseReleased(e: MouseEvent) {
    val drag = imageResize ?: return
    imageResize = null
    val canFinish = admissionAllowed && !retired && !disposed && resizeViewKey() == imageResizeView
    if (canFinish)drag.finish(viewModel, e.x)
    repaint()
}

private fun EditorSurface.onMouseDragged(e: MouseEvent) {
    if (retired || disposed) return
    if (imageResize != null) {
        imageResizeX = e.x
        repaint()
        return
    }
    if (e.y < AUTOSCROLL_EDGE) {
        view.scroll = max(0f, view.scroll - AUTOSCROLL_STEP)
    } else if (e.y > height - AUTOSCROLL_EDGE) {
        view.scroll += AUTOSCROLL_STEP
    }
    hit(e.point)?.let {
        viewModel.dispatch(
            EditorIntent.Select(Selection(dragAnchor, it), wrapAffinity = pointerWrapAffinity(e.point, it)),
        )
    }
}

private fun EditorSurface.onMouseWheel(e: MouseWheelEvent) {
    if (retired || disposed) return
    view.ensureCaret = false
    view.scroll = max(0f, view.scroll + e.preciseWheelRotation.toFloat() * WHEEL_SCROLL_STEP)
}
