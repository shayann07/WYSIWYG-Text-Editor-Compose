package editor

import java.awt.Dimension
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.font.TextHitInfo
import java.text.AttributedString
import javax.swing.JPanel

/**
 * Attached AWT EditorSurface host checks covering synthetic IME lifecycle sequences:
 * preedit -> candidate conversion, candidate popup placement (getTextLocation / getLocationOffset),
 * screen geometry fallbacks and nonzero component origins, single-group composition Undo/Redo,
 * multi-clause navigation and partial commits, Escape cancellation and focus loss settlement,
 * selection replacement, queued formatting/Undo command ordering with attributed history,
 * and paragraph boundaries.
 *
 * NOTE: These checks exercise synthetic AWT InputMethodEvent dispatch and attached Compose text layout.
 * They do NOT certify physical romaji conversion or native candidate popups by the Microsoft Windows OS IME.
 */
fun runJapaneseHostChecks(surface: EditorSurface) {
    val e = surface.engine

    fun imeEvent(text: String, committedCount: Int, caret: Int?) {
        val iterator = if (text.isEmpty()) null else AttributedString(text).iterator
        surface.dispatchEvent(
            InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, iterator, committedCount, caret?.let(TextHitInfo::leading), null)
        )
    }

    fun caretEvent(caret: Int) {
        surface.dispatchEvent(
            InputMethodEvent(surface, InputMethodEvent.CARET_POSITION_CHANGED, null, 0, TextHitInfo.leading(caret), null)
        )
    }

    fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false) {
        var mod = 0
        if (ctrl) mod = mod or KeyEvent.CTRL_DOWN_MASK
        if (shift) mod = mod or KeyEvent.SHIFT_DOWN_MASK
        surface.dispatchEvent(KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mod, code, KeyEvent.CHAR_UNDEFINED))
    }

    // 1. Synthetic preedit, candidate conversion, popup coordinates, commit and exact Undo/Redo
    e.replace("", "Reset")
    // Step 1a: "ni" -> "に"
    imeEvent("に", 0, 1)
    check(e.snapshot().visible.text == "に")
    check(e.snapshot().committed.text.isEmpty())
    check(e.snapshot().marked == 0..0)
    check(surface.insertPositionOffset == 0)

    // Step 1b: "ho" -> "にほ"
    imeEvent("にほ", 0, 2)
    check(e.snapshot().visible.text == "にほ")
    check(e.snapshot().marked == 0..1)

    // Step 1c: "n" -> "にほん"
    imeEvent("にほん", 0, 3)
    check(e.snapshot().visible.text == "にほん")
    check(e.snapshot().marked == 0..2)
    check(e.snapshot().committed.text.isEmpty())

    // Step 1d: Candidate conversion event -> "日本"
    imeEvent("日本", 0, 2)
    check(e.snapshot().visible.text == "日本")
    check(e.snapshot().marked == 0..1)
    check(e.snapshot().committed.text.isEmpty())

    // Step 1e: Confirm candidate placement metrics relative to caret (getTextLocation & getLocationOffset)
    val loc0 = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    val loc1 = checkNotNull(surface.getTextLocation(TextHitInfo.leading(1)))
    val locEnd = checkNotNull(surface.getTextLocation(TextHitInfo.leading(2)))
    check(loc0.width == 0 && loc0.height > 0) { "Caret location must have 0 width and positive height" }
    check(loc1.width == 0 && loc1.height > 0)
    check(locEnd.width == 0 && locEnd.height > 0)
    check(loc0.x < loc1.x && loc1.x < locEnd.x) { "Japanese candidate popup X coordinates must advance strictly monotonically: $loc0, $loc1, $locEnd" }
    check(loc0.y == loc1.y && loc1.y == locEnd.y) { "Japanese candidate horizontal baseline Y coordinates must match: $loc0, $loc1, $locEnd" }

    // Verify getLocationOffset on composed candidate
    val hit0 = surface.getLocationOffset(loc0.x + 2, loc0.y + 4)
    check(hit0?.insertionIndex == 0) { "getLocationOffset inside first candidate glyph must map to offset 0" }
    val hitOutside = surface.getLocationOffset(loc0.x - 200, loc0.y - 200)
    check(hitOutside == null) { "getLocationOffset outside candidate bounds must return null" }

    // Step 1e-bis: Reproduce and distinguish screen geometry conditions:
    // (a) Attached, showing component positioned offscreen (window at -16000, -16000)
    check(surface.isShowing) { "Attached surface in offscreen test window must be showing" }
    val offscreenPoint = surface.locationOnScreen
    check(offscreenPoint.x < -10000 && offscreenPoint.y < -10000) { "Offscreen test window must report true negative screen coordinates: $offscreenPoint" }
    check(loc0.x < -10000 && loc0.y < -10000) { "getTextLocation must incorporate true offscreen screen coordinates: $loc0" }

    // (b) Component that is not showing
    val notShowingEngine = EditorEngine()
    val notShowingSurface = EditorSurface(notShowingEngine, ViewState(notShowingEngine.snapshot()))
    try {
        check(!notShowingSurface.isShowing) { "Detached surface must report isShowing = false" }
        var threwIllegalState = false
        try {
            notShowingSurface.locationOnScreen
        } catch (_: java.awt.IllegalComponentStateException) {
            threwIllegalState = true
        }
        check(threwIllegalState) { "Calling locationOnScreen on non-showing component must throw IllegalComponentStateException" }
        check(notShowingSurface.getLocationOffset(100, 100) == null) { "getLocationOffset on non-showing component must return null" }
        check(notShowingSurface.getTextLocation(null) == null) { "getTextLocation on non-showing component must return null per AWT contract" }
    } finally {
        notShowingSurface.removeNotify()
    }

    // (c) Component whose screen location is unavailable (isShowing == true, but screen transform throws)
    val unavailEngine = EditorEngine()
    val unavailSurface = object : EditorSurface(unavailEngine, ViewState(unavailEngine.snapshot())) {
        override fun isShowing(): Boolean = true
        override fun getLocationOnScreen(): Point = throw java.awt.IllegalComponentStateException("Screen location unavailable")
    }
    try {
        check(unavailSurface.isShowing) { "unavailSurface reports isShowing = true" }
        var threwUnavail = false
        try {
            unavailSurface.locationOnScreen
        } catch (_: java.awt.IllegalComponentStateException) {
            threwUnavail = true
        }
        check(threwUnavail) { "Calling locationOnScreen must throw IllegalComponentStateException when screen location is unavailable" }
        check(unavailSurface.getLocationOffset(100, 100) == null) { "getLocationOffset must return null when screen transform is unavailable" }
        check(unavailSurface.getTextLocation(null) == null) { "getTextLocation must return null when screen transform is unavailable" }
    } finally {
        unavailSurface.removeNotify()
    }

    // (d) Attached showing component with real nonzero positive component origin
    val originWindow = Frame("Origin Test Frame").apply {
        setLocation(250, 180)
        size = Dimension(400, 300)
    }
    val originEngine = EditorEngine()
    originEngine.replace("Origin", "Test")
    val originSurface = EditorSurface(originEngine, ViewState(originEngine.snapshot()))
    originSurface.view.measure = surface.view.measure
    val originPanel = JPanel(java.awt.BorderLayout())
    originPanel.add(originSurface, java.awt.BorderLayout.CENTER)
    originWindow.add(originPanel)
    originWindow.isVisible = true
    try {
        check(originSurface.isShowing) { "Surface in visible test frame must be showing" }
        val originPoint = originSurface.locationOnScreen
        check(originPoint.x >= 250 && originPoint.y >= 180) { "Origin window surface must have screen location >= (250, 180), got $originPoint" }
        val originCaretLoc = checkNotNull(originSurface.getTextLocation(TextHitInfo.leading(0)))
        check(originCaretLoc.x >= originPoint.x && originCaretLoc.y >= originPoint.y) { "Caret location must reflect nonzero screen origin: caret=$originCaretLoc, origin=$originPoint" }
        // Passing local coordinates (e.g. (2, 4)) mistakenly as screen coordinates must fail
        val badLocalHit = originSurface.getLocationOffset(2, 4)
        check(badLocalHit == null) { "Passing local coordinates (2, 4) to getLocationOffset must return null when component is at $originPoint" }
    } finally {
        originSurface.removeNotify()
        originWindow.dispose()
    }

    // Step 1f: Commit composition (Enter) -> committed "日本"
    imeEvent("日本", 2, null)
    check(e.snapshot().visible.text == "日本")
    check(e.snapshot().committed.text == "日本")
    check(e.snapshot().marked == null)
    check(e.snapshot().selection == Selection(2, 2))
    check(e.snapshot().undoCount == 1) { "Complete Japanese composition must produce exactly 1 Undo step" }

    // Step 1g: Undo restores empty document
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    check(e.snapshot().committed.text.isEmpty())
    check(e.snapshot().selection == Selection(0, 0))

    // Step 1h: Redo restores committed "日本"
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "日本")
    check(e.snapshot().committed.text == "日本")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 2. Multi-clause Japanese candidate conversion, caret navigation, and partial commit
    imeEvent("わたしのなまえ", 0, 7)
    check(e.snapshot().visible.text == "わたしのなまえ")
    // Convert to 2 clauses: "私の名前"
    imeEvent("私の名前", 0, 4)
    check(e.snapshot().visible.text == "私の名前")
    check(e.snapshot().marked == 0..3)

    // Move IME caret between clauses via CARET_POSITION_CHANGED
    caretEvent(2)
    check(e.snapshot().selection.focus == 2) { "IME caret event must move selection to clause boundary" }
    val clauseCaretLoc = checkNotNull(surface.getTextLocation(TextHitInfo.leading(2)))
    check(clauseCaretLoc.height > 0)

    // Partial commit: Clause 1 ("私の") committed (count = 2), Clause 2 ("名前") remains composed
    imeEvent("私の名前", 2, 2)
    check(e.snapshot().committed.text == "私の")
    check(e.snapshot().visible.text == "私の名前")
    check(e.snapshot().marked == 2..3)
    check(surface.insertPositionOffset == 2)
    check(surface.committedTextLength == 2)

    // Final commit of remaining clause ("名前")
    imeEvent("名前", 2, null)
    check(e.snapshot().committed.text == "私の名前")
    check(e.snapshot().visible.text == "私の名前")
    check(e.snapshot().marked == null)
    check(e.snapshot().undoCount == 1) { "Multi-clause partial commit sequence must settle into a single Undo group" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 3. Cancellation during composition (Escape key requests endComposition; synchronous terminal callback)
    e.replace("東京 ", "Prefix fixture")
    imeEvent("とうきょう", 0, 5)
    check(e.snapshot().visible.text == "東京 とうきょう")
    check(e.snapshot().marked == 3..7)
    check(e.snapshot().committed.text == "東京 ")
    var escapeEndCompositionRequested = 0
    surface.onEndCompositionRequested = { escapeEndCompositionRequested++ }
    // User presses Escape
    key(KeyEvent.VK_ESCAPE)
    check(escapeEndCompositionRequested == 1) { "Pressing Escape during composition must request endComposition" }
    // Test explicitly distinguishes what application requested from what test injected:
    // Synchronous terminal callback from IME clearing composition
    imeEvent("", 0, null)
    check(e.snapshot().visible.text == "東京 ")
    check(e.snapshot().committed.text == "東京 ")
    check(e.snapshot().marked == null)
    check(e.snapshot().undoCount == 1) { "Escape cancellation must not create spurious history" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    surface.onEndCompositionRequested = {}

    // 3b. Reentrant settlement callback: native IME synchronously clears preedit inside endComposition()
    e.replace("同期 ", "Prefix fixture")
    imeEvent("テスト", 0, 3)
    var reentrantCallbackRan = false
    surface.onEndCompositionRequested = {
        reentrantCallbackRan = true
        // Native IME dispatches terminal event synchronously during endComposition call
        imeEvent("完了", 2, null)
    }
    key(KeyEvent.VK_ESCAPE)
    check(reentrantCallbackRan) { "Reentrant settlement callback must be observed" }
    check(e.snapshot().visible.text == "同期 完了")
    check(e.snapshot().marked == null)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "同期 ")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    surface.onEndCompositionRequested = {}

    // 4. Focus loss during composition with delayed callback handling
    e.replace("開始 ", "Prefix fixture")
    imeEvent("きょう", 0, 3)
    check(e.snapshot().visible.text == "開始 きょう")
    check(e.snapshot().marked == 3..5)
    surface.processFocusEvent(FocusEvent(surface, FocusEvent.FOCUS_GAINED, false))
    check(surface.view.focused) { "FocusEvent must set focused state" }
    var focusLossEndCompositionRequested = 0
    surface.onEndCompositionRequested = { focusLossEndCompositionRequested++ }
    // Focus lost event processed by surface
    surface.processFocusEvent(FocusEvent(surface, FocusEvent.FOCUS_LOST, false))
    check(!surface.view.focused) { "FocusEvent must clear focused state" }
    check(focusLossEndCompositionRequested == 1) { "Focus loss during composition must request endComposition, got $focusLossEndCompositionRequested" }
    // Delayed callback: native IME commits current preedit upon focus loss
    imeEvent("今日", 2, null)
    check(e.snapshot().visible.text == "開始 今日")
    check(e.snapshot().committed.text == "開始 今日")
    check(e.snapshot().marked == null)
    check(e.snapshot().undoCount == 2) { "Commit on focus loss settles as exactly one composition undo step" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "開始 ")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    surface.onEndCompositionRequested = {}

    // 5. Replacing selected text with Japanese composition
    e.replace("ABCD漢字EF", "Selection fixture")
    e.move(Selection(4, 6)) // select "漢字"
    imeEvent("にほん", 0, 3)
    check(e.snapshot().visible.text == "ABCDにほんEF")
    check(e.snapshot().committed.text == "ABCD漢字EF")
    check(e.snapshot().marked == 4..6)
    // Candidate conversion
    imeEvent("日本", 0, 2)
    check(e.snapshot().visible.text == "ABCD日本EF")
    check(e.snapshot().marked == 4..5)
    // Commit
    imeEvent("日本", 2, null)
    check(e.snapshot().visible.text == "ABCD日本EF")
    check(e.snapshot().committed.text == "ABCD日本EF")
    check(e.snapshot().marked == null)
    // Undo restores original text AND exact selection
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "ABCD漢字EF")
    check(e.snapshot().selection == Selection(4, 6)) { "Undo must restore original selection range when composition replaced text" }
    // Redo restores committed Japanese replacement
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "ABCD日本EF")
    e.perform(EditAction.Undo); e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 6. Formatting shortcut (Ctrl+B) and queued Undo during composition: command ordering and attributed history
    e.replace("テキスト", "Fixture")
    check(e.snapshot().visible.text == "テキスト")
    check(e.snapshot().undoCount == 1)
    check(e.snapshot().redoCount == 0)
    check(e.snapshot().visible.units.all { !it.style.bold && !it.style.italic })

    e.move(Selection(2, 2))
    imeEvent("に", 0, 1)
    check(e.snapshot().visible.text == "テキにスト")
    check(e.snapshot().marked == 2..2)
    check(e.snapshot().committed.text == "テキスト")
    check(!e.snapshot().waiting)

    // User triggers Ctrl+B while composing
    var queuedSettlementCount = 0
    surface.onEndCompositionRequested = { queuedSettlementCount++ }
    key(KeyEvent.VK_B, ctrl = true)
    check(e.snapshot().waiting) { "Shortcut during composition must set waiting flag" }
    check(queuedSettlementCount == 1) { "Shortcut during composition must request settlement" }
    check(e.snapshot().undoCount == 1)
    check(e.snapshot().redoCount == 0)

    // User also presses Ctrl+Z (queued Undo) while composing
    key(KeyEvent.VK_Z, ctrl = true)
    check(e.snapshot().waiting)
    check(queuedSettlementCount == 2) { "Queued Undo during composition must request settlement" }
    check(e.snapshot().undoCount == 1)
    check(e.snapshot().redoCount == 0)

    // Native IME commits "日" (replaces preedit "に")
    imeEvent("日", 1, null)

    // Immediate post-settlement assertions:
    // Format was executed on committed text "日", then Undo immediately reverted the format.
    check(!e.snapshot().waiting) { "Settlement must clear waiting flag" }
    check(e.snapshot().marked == null) { "Settlement must clear marked preedit" }
    check(e.snapshot().visible.text == "テキ日スト")
    check(e.snapshot().committed.text == "テキ日スト")
    check(e.snapshot().selection == Selection(3, 3))
    check(e.snapshot().undoCount == 2) { "Undo count must be 2 (Fixture + Composition; Format was undone into Redo stack)" }
    check(e.snapshot().redoCount == 1) { "Redo count must be 1 (undone Format action)" }
    check(e.snapshot().visible.units.all { !it.style.bold }) { "All units must be normal style because Format was undone by queued Undo" }

    // Step 1: Redo re-applies the Format on "日"
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "テキ日スト")
    check(e.snapshot().visible.units[2].style.bold) { "Unit 2 ('日') must now be bold" }
    check(!e.snapshot().visible.units[0].style.bold && !e.snapshot().visible.units[1].style.bold && !e.snapshot().visible.units[3].style.bold)
    check(e.snapshot().undoCount == 3)
    check(e.snapshot().redoCount == 0)

    // Step 2: Undo reverts the Format
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "テキ日スト")
    check(e.snapshot().visible.units.all { !it.style.bold })
    check(e.snapshot().undoCount == 2)
    check(e.snapshot().redoCount == 1)

    // Step 3: Undo reverts the Composition
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "テキスト")
    check(e.snapshot().selection == Selection(2, 2)) { "Undo must restore selection prior to composition" }
    check(e.snapshot().undoCount == 1)
    check(e.snapshot().redoCount == 2)

    // Step 4: Undo reverts the Fixture
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    check(e.snapshot().undoCount == 0)
    check(e.snapshot().redoCount == 3)

    // Step 5: Redo chain restores all steps in exact order
    e.perform(EditAction.Redo) // Fixture
    check(e.snapshot().visible.text == "テキスト")
    e.perform(EditAction.Redo) // Composition
    check(e.snapshot().visible.text == "テキ日スト")
    check(e.snapshot().visible.units.all { !it.style.bold })
    e.perform(EditAction.Redo) // Format
    check(e.snapshot().visible.text == "テキ日スト")
    check(e.snapshot().visible.units[2].style.bold)

    // Cleanup back to empty
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    surface.onEndCompositionRequested = {}

    // 7. Paragraph boundary cases supported by the prototype
    // 7a. Japanese composition at line end immediately before paragraph break
    e.replace("第一段落\n第二段落", "Multi-paragraph fixture", paragraphBreaks = true)
    e.move(Selection(4, 4)) // caret right before '\n'
    imeEvent("とうきょう", 0, 5)
    check(e.snapshot().visible.text == "第一段落とうきょう\n第二段落")
    check(e.snapshot().committed.text == "第一段落\n第二段落")
    imeEvent("東京", 2, null)
    check(e.snapshot().visible.text == "第一段落東京\n第二段落")
    check(e.snapshot().committed.text == "第一段落東京\n第二段落")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "第一段落\n第二段落")

    // 7b. Composition replacing text spanning a paragraph break
    e.move(Selection(3, 6)) // selects "落\n第"
    imeEvent("にほん", 0, 3)
    check(e.snapshot().visible.text == "第一段にほん二段落")
    imeEvent("日本", 2, null)
    check(e.snapshot().visible.text == "第一段日本二段落")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "第一段落\n第二段落")
    check(e.snapshot().selection == Selection(3, 6))
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 8. Duplicate terminal callbacks
    imeEvent("テスト", 0, 3)
    imeEvent("テスト", 3, null)
    check(e.snapshot().marked == null)
    check(e.snapshot().visible.text == "テスト")
    val snapBeforeDup = e.snapshot()
    imeEvent("", 0, null)
    check(e.snapshot() == snapBeforeDup) { "Duplicate terminal clear must be an idempotent no-op" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 9. Explicit native context retirement and late callback rejection via activeImeEpoch
    imeEvent("あ", 0, 1)
    val retiredEpoch = surface.activeImeEpoch
    surface.abortAndRetireComposition()
    check(surface.activeImeEpoch == retiredEpoch + 1)
    check(e.snapshot().marked == null)
    check(e.snapshot().visible.text.isEmpty())
    val lateEvent = InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("遅い").iterator, 2, null, null)
    surface.dispatchInputMethodEventWithEpoch(lateEvent, retiredEpoch)
    check(lateEvent.isConsumed) { "Late callback targeting retired epoch must be consumed and discarded" }
    check(e.snapshot().visible.text.isEmpty()) { "Late callback must not affect engine state" }
    val validEvent = InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("有効").iterator, 2, null, null)
    surface.dispatchInputMethodEventWithEpoch(validEvent, surface.activeImeEpoch)
    check(e.snapshot().visible.text == "有効")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 10. Watchdog timeout and cancellation without auto-commit
    imeEvent("とうきょう", 0, 5)
    check(e.snapshot().marked != null)
    check(!e.snapshot().waiting)
    key(KeyEvent.VK_B, ctrl = true)
    check(e.snapshot().waiting) { "Shortcut during composition must set waiting flag" }
    check(!e.settlementTimedOut)
    val timedOut = e.checkSettlementWatchdog(timeoutMs = 0L)
    check(timedOut) { "Watchdog must report timeout" }
    check(e.settlementTimedOut)
    check(!e.snapshot().waiting) { "Watchdog timeout must cancel pending queue so editor is not wedged" }
    check(e.snapshot().marked != null) { "Watchdog timeout must NOT auto-commit unaccepted text" }
    key(KeyEvent.VK_ESCAPE)
    check(e.snapshot().marked == null) { "Escape must abort timed-out composition" }
    check(e.snapshot().visible.text.isEmpty())

    println("PASS Synthetic Japanese IME callback sequence (preedit -> conversion -> commit), single-group Undo/Redo")
    println("PASS Attached Compose IME candidate geometry (getTextLocation / getLocationOffset monotonic coordinates under stated conditions)")
    println("PASS Screen coordinates and unavailable geometry fallback distinction (offscreen vs not-showing vs unavailable transform)")
    println("PASS Synthetic Japanese multi-clause conversion, caret traversal and partial commit")
    println("PASS Synthetic Japanese composition Escape cancellation, focus-loss settlement, and reentrant/delayed callback handling")
    println("PASS Synthetic Japanese selection replacement with exact selection restoration on Undo")
    println("PASS Synthetic Japanese queued formatting and Undo command ordering with attributed history")
    println("PASS Synthetic Japanese paragraph-boundary composition and cross-paragraph selection replacement")
    println("PASS Synthetic Japanese duplicate terminal callbacks, retirement epoch isolation and watchdog timeout without auto-commit")
}
