package dev.shayxo.composedoc.ui

import android.content.Context
import android.graphics.Matrix
import android.os.Bundle
import android.text.InputType
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection

/** Transparent Android input/AT host. Compose draws the document; this View never owns editable text. */
class AndroidEditorView(context: Context, val model: EditorViewModel) : View(context) {
    var onNativeEvent: (String) -> Unit = {}
    internal var onEditorFocusChanged: (Boolean) -> Unit = {}
    var onUnfinishedInput: (dev.shayxo.composedoc.domain.RecoveredDraft) -> Unit = {}
    private var generation = 0
    private var createdConnections = 0L
    private var acceptedConnection = 0L
    private var admittingConnection = false
    private var retired = false
    private var connection: AndroidEditorInputConnection? = null
    internal var cursorMonitoring = false
    internal var geometry: AndroidEditorGeometry? = null
    internal var scrollOffset: Float = 0f
    internal var scrollBy: (Float) -> Unit = {}
    private var selecting = false
    private var anchor = 0
    private var lastKey: String? = null
    private var lastRevision = -1L
    private var lastCommitted = model.snapshot().committed
    private val manager get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val at = hit(
                    e.x,
                    e.y,
                )
                model.dispatch(
                    EditorIntent.Select(
                        Selection(
                            at,
                            at,
                        ),
                    ),
                )
                showKeyboard()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val at = hit(
                    e.x,
                    e.y,
                )
                val text = model.snapshot().visible.text
                var a = at
                var b = at
                while (a > 0 &&
                    !text[a - 1].isWhitespace()
                ) {
                    a--
                }
                while (b < text.length &&
                    !text[b].isWhitespace()
                ) {
                    b++
                }
                a = Graphemes.snap(
                    text,
                    a,
                    after = false,
                )
                b = Graphemes.snap(
                    text,
                    b,
                    after = true,
                )
                anchor = a
                selecting = true
                model.dispatch(
                    EditorIntent.Select(
                        Selection(
                            a,
                            b,
                        ),
                    ),
                )
                startActionMode(
                    AndroidSelectionActions(this@AndroidEditorView),
                    ActionMode.TYPE_FLOATING,
                )
            }
            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!selecting) scrollBy(distanceY)
                return true
            }
        },
    )
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        tag = "ComposeDocInput"
        contentDescription = "ComposeDoc document editor"
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection {
        createdConnections++
        onNativeEvent("createInputConnection generation=$generation sequence=$createdConnections")
        retired = false
        val textFlags = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or textFlags
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = model.snapshot().selection.anchor
        outAttrs.initialSelEnd = model.snapshot().selection.focus
        return AndroidEditorInputConnection(
            this,
            generation,
            createdConnections,
        )
    }
    internal fun accepts(source: AndroidEditorInputConnection, value: Int) = !retired &&
        generation == value &&
        connection === source

    /** Android may create and discard a probe; only its first callback adopts a replacement. */
    internal fun admit(source: AndroidEditorInputConnection, value: Int): Boolean {
        val sameLifetime = !retired && generation == value
        val canAdopt = source.available && !admittingConnection
        return when {
            !sameLifetime || !canAdopt -> false
            connection === source -> true
            source.sequence <= acceptedConnection -> false
            else -> adoptConnection(source, value)
        }
    }
    private fun adoptConnection(source: AndroidEditorInputConnection, value: Int): Boolean {
        admittingConnection = true
        try {
            val draft = model.captureRecoveryDraft()
            connection?.retire()
            connection = source
            acceptedConnection = source.sequence
            draft?.let { onUnfinishedInput(it) }
            onNativeEvent("adoptInputConnection generation=$generation sequence=${source.sequence}")
            return accepts(source, value) && source.available
        } finally {
            admittingConnection = false
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        val handled = gestures.onTouchEvent(event)
        // GestureDetector suppresses onScroll after onLongPress; route selection moves here.
        val selectionMove = event.actionMasked == MotionEvent.ACTION_MOVE && selecting
        if (selectionMove) {
            model.dispatch(EditorIntent.Select(Selection(anchor, hit(event.x, event.y))))
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            selecting = false
            performClick()
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) selecting = false
        return handled ||
            selectionMove ||
            event.actionMasked == MotionEvent.ACTION_UP
    }
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
    private fun hit(x: Float, y: Float): Int {
        val g = geometry?.takeIf { it.basisRevision == model.snapshot().documentRevision }
            ?: return model.snapshot().selection.focus
        val at = g.hit(
            x,
            y + scrollOffset,
        )
        return Graphemes.snap(
            model.snapshot().visible.text,
            at,
            after = true,
        )
    }
    fun showKeyboard() {
        requestFocus()
        // A closed framework connection stays stale; explicit activation requests a new generation.
        if (retired || connection?.active != true) manager.restartInput(this)
        manager.showSoftInput(
            this,
            InputMethodManager.SHOW_IMPLICIT,
        )
    }
    internal fun hideKeyboard() {
        manager.hideSoftInputFromWindow(
            windowToken,
            0,
        )
    }
    fun cancelProvisionalInput() {
        captureUnfinished()
        connection?.retire()
        generation++
        manager.restartInput(this)
    }
    private fun captureUnfinished() {
        model.captureRecoveryDraft()?.let { onUnfinishedInput(it) }
    }
    fun retireNativeSession() {
        captureUnfinished()
        connection?.retire()
        connection = null
        generation++
        retired = true
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onEditorFocusChanged(gainFocus && hasWindowFocus() && isShown)
        if (!gainFocus) retireNativeSession()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        onNativeEvent("windowFocus=$hasWindowFocus retired=$retired generation=$generation")
        onEditorFocusChanged(hasWindowFocus && hasFocus() && isShown)
        if (!hasWindowFocus) retireNativeSession()
    }
    override fun onDetachedFromWindow() {
        onEditorFocusChanged(false)
        retireNativeSession()
        super.onDetachedFromWindow()
    }
    fun ownerPublished() {
        val frame = model.snapshot()
        val oldUnits = lastCommitted.units
        val newUnits = frame.committed.units
        val equalLength = oldUnits.size == newUnits.size
        val sameText = equalLength && oldUnits.indices.all { oldUnits[it].char == newUnits[it].char }
        lastCommitted = frame.committed
        val client = connection
        val settled = frame.marked == null && model.compositionCaptureToken() == null
        val externalChange = !sameText && settled
        if (externalChange && client != null && !client.mutating) invalidateAfterExternalEdit(client)
        connection?.published()
        if (model.snapshot().documentRevision != lastRevision) {
            lastRevision = model.snapshot().documentRevision
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        }
        invalidate()
    }

    /** A programmatic text transaction invalidates old IME coordinates before scheduling a fresh connection. */
    internal fun invalidateAfterExternalEdit(source: AndroidEditorInputConnection) {
        if (connection !== source ||
            retired ||
            model.compositionCaptureToken() != null
        ) {
            return
        }
        generation++
        source.retire()
        val fence = generation
        post {
            val currentGeneration = !retired && generation == fence
            if (currentGeneration && isAttachedToWindow && hasFocus()) manager.restartInput(this)
        }
    }
    internal fun notifyInput(source: AndroidEditorInputConnection) {
        if (source !== connection) return
        val frame = model.snapshot()
        val marked = frame.marked
        manager.updateSelection(
            this,
            frame.selection.anchor,
            frame.selection.focus,
            marked?.first ?: -1,
            marked?.let { it.last + 1 } ?: -1,
        )
        source.extractedToken?.let {
            manager.updateExtractedText(
                this,
                it,
                source.extracted(),
            )
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        notifyCursor()
    }
    internal fun notifyCursor() {
        if (connection == null ||
            !isAttachedToWindow
        ) {
            return
        }
        val s = model.snapshot()
        val g = geometry?.takeIf { it.basisRevision == s.documentRevision } ?: return
        val caret = g.caret(s.selection.focus)
        val location = IntArray(2)
        getLocationOnScreen(location)
        val matrix = Matrix().apply {
            setTranslate(
                location[0].toFloat(),
                location[1].toFloat() - scrollOffset,
            )
        }
        val info = CursorAnchorInfo.Builder().setMatrix(matrix).setSelectionRange(
            s.selection.anchor,
            s.selection.focus,
        )
        s.marked?.let {
            info.setComposingText(
                it.first,
                s.visible.text.substring(
                    it.first,
                    it.last + 1,
                ),
            )
        }
        val outsideViewport = caret.bottom - scrollOffset < 0 || caret.top - scrollOffset > height
        val visibility = if (outsideViewport) {
            CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION
        } else {
            CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION
        }
        info.setInsertionMarkerLocation(
            caret.left,
            caret.top,
            g.baseline(s.selection.focus),
            caret.bottom,
            visibility,
        )
        manager.updateCursorAnchorInfo(
            this,
            info.build(),
        )
    }
    internal fun enter() {
        model.dispatch(
            EditorIntent.Type(
                "\n",
                "Enter",
                paragraphBreaks = true,
            ),
        )
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) ||
        super.onKeyDown(
            keyCode,
            event,
        )
    internal fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return event.action == KeyEvent.ACTION_UP
        val identity = "${event.deviceId}:${event.eventTime}:${event.keyCode}:${event.repeatCount}"
        val duplicate = lastKey == identity
        lastKey = identity
        return duplicate || dispatchKeyDown(event)
    }
    private fun dispatchKeyDown(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DEL -> {
            model.dispatch(EditorIntent.Delete(true))
            true
        }
        KeyEvent.KEYCODE_FORWARD_DEL -> {
            model.dispatch(EditorIntent.Delete(false))
            true
        }
        KeyEvent.KEYCODE_ENTER -> {
            enter()
            true
        }
        KeyEvent.KEYCODE_Z -> if (event.isCtrlPressed) {
            val action = if (event.isShiftPressed) EditAction.Redo else EditAction.Undo
            model.dispatch(EditorIntent.Command(action))
            true
        } else {
            hardwareText(event)
        }
        else -> hardwareText(event)
    }
    private fun hardwareText(event: KeyEvent): Boolean {
        // Soft IMEs insert text through commitText/setComposingText, not a duplicate printable key path.
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 ||
            event.isCtrlPressed ||
            event.isAltPressed
        ) {
            return false
        }
        val code = event.unicodeChar
        val validCharacter = code != 0 && code and KeyCharacterMap.COMBINING_ACCENT == 0
        return if (validCharacter) {
            model.dispatch(EditorIntent.Type(String(Character.toChars(code))))
            true
        } else {
            false
        }
    }

    /** Read-only local geometry for scrolling/native overlays; never changes document measurements. */
    fun caretRectangle(): android.graphics.RectF? = geometry
        ?.takeIf { it.basisRevision == model.snapshot().documentRevision }
        ?.caret(model.snapshot().selection.focus)?.let {
            android.graphics.RectF(
                it.left,
                it.top - scrollOffset,
                it.right,
                it.bottom - scrollOffset,
            )
        }

    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
    override fun addExtraDataToAccessibilityNodeInfo(
        info: AccessibilityNodeInfo,
        extraDataKey: String,
        arguments: Bundle?,
    ) {
        if (extraDataKey != AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) return
        val start = arguments?.getInt(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
            -1,
        ) ?: -1
        val count = arguments?.getInt(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
            -1,
        ) ?: -1
        val g = geometry
        val current = g?.basisRevision == model.snapshot().documentRevision
        val coordinatesValid = start >= 0 &&
            count >= 0 &&
            start.toLong() + count <= model.snapshot().visible.units.size
        if (current && coordinatesValid && g != null) populateCharacterBounds(info, g, start, count)
    }
    private fun populateCharacterBounds(info: AccessibilityNodeInfo, g: AndroidEditorGeometry, start: Int, count: Int) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val bounds = Array<android.graphics.RectF?>(count) { index ->
            val at = start + index
            val paragraph = g.paragraph(at)
            if (at >= paragraph.block.end) {
                null
            } else {
                val box = g.character(at)
                android.graphics.RectF(
                    box.left + location[0],
                    box.top + location[1] - scrollOffset,

                    box.right + location[0],
                    box.bottom + location[1] - scrollOffset,
                )
            }
        }
        info.extras.putParcelableArray(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            bounds,
        )
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val s = model.snapshot()
        info.className = "android.widget.EditText"
        info.isEditable = true
        info.isMultiLine = true
        info.text = s.visible.text
        info.setTextSelection(
            s.selection.anchor,
            s.selection.focus,
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return when (action) {
            AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                val text = arguments
                    ?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE) ?: return false
                model.dispatch(
                    EditorIntent.Type(
                        text.toString(),
                        "Accessibility replacement",
                        range = Selection(
                            0,
                            model.snapshot().visible.units.size,
                        ),
                        paragraphBreaks = true,
                    ),
                )
                true
            }
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
                val a = arguments?.getInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    -1,
                ) ?: -1
                val b = arguments?.getInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    -1,
                ) ?: -1
                if (a !in 0..model.snapshot().visible.units.size ||
                    b !in 0..model.snapshot().visible.units.size
                ) {
                    false
                } else {
                    model.dispatch(
                        EditorIntent.Select(
                            Selection(
                                a,
                                b,
                            ),
                        ),
                    )
                    true
                }
            }
            AccessibilityNodeInfo.ACTION_COPY -> contextAction(android.R.id.copy)
            AccessibilityNodeInfo.ACTION_CUT -> contextAction(android.R.id.cut)
            AccessibilityNodeInfo.ACTION_PASTE -> contextAction(android.R.id.paste)
            else -> super.performAccessibilityAction(
                action,
                arguments,
            )
        }
    }
}
