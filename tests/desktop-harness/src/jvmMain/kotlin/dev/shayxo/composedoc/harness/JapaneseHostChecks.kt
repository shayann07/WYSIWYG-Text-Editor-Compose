package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
import java.awt.Dimension
import java.awt.Frame
import java.awt.Point
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.font.TextHitInfo
import java.io.File
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
fun runJapaneseHostChecks(surface: EditorSurface): EditorSurface {
    val e = surface.engine
    var activeSurface = surface

    fun imeEvent(text: String, committedCount: Int, caret: Int?, target: EditorSurface = activeSurface): InputMethodEvent {
        val iterator = if (text.isEmpty()) null else AttributedString(text).iterator
        val ev = InputMethodEvent(target, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, iterator, committedCount, caret?.let(TextHitInfo::leading), null)
        target.dispatchEvent(ev)
        return ev
    }

    fun caretEvent(caret: Int, target: EditorSurface = activeSurface): InputMethodEvent {
        val ev = InputMethodEvent(target, InputMethodEvent.CARET_POSITION_CHANGED, null, 0, TextHitInfo.leading(caret), null)
        target.dispatchEvent(ev)
        return ev
    }

    fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false, target: EditorSurface = activeSurface) {
        var mod = 0
        if (ctrl) mod = mod or KeyEvent.CTRL_DOWN_MASK
        if (shift) mod = mod or KeyEvent.SHIFT_DOWN_MASK
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mod, code, KeyEvent.CHAR_UNDEFINED))
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
    e.perform(EditAction.Undo)
    e.perform(EditAction.Undo)
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

    // 9. Concrete AWT host/context retirement and replacement via ordinary AWT dispatchEvent
    // 9a. Ordinary delayed AWT callback rejection on retired host + identical text in consecutive legitimate sessions
    val retiredHost1 = activeSurface
    imeEvent("あ", 0, 1, target = retiredHost1)
    val retiredEpoch = retiredHost1.activeImeEpoch
    retiredHost1.abortAndRetireComposition()
    check(retiredHost1.retired && retiredHost1.inputContext == null)
    check(retiredHost1.activeImeEpoch == retiredEpoch + 1)
    activeSurface = checkNotNull(retiredHost1.replacementSurface) { "abortAndRetireComposition must install replacement host" }
    check((activeSurface.parent as javax.swing.JLayeredPane).getLayer(activeSurface) == 100) { "Replacement host must occupy layer 100 in JLayeredPane" }
    check(e.snapshot().marked == null && e.snapshot().visible.text.isEmpty())

    // Late ordinary AWT commit and caret callbacks targeting retiredHost1 (no epoch tag!) must be consumed and ignored
    val lateCommit = imeEvent("あ", 1, null, target = retiredHost1)
    val latePreedit = imeEvent("遅い", 0, 2, target = retiredHost1)
    val lateCaret = caretEvent(0, target = retiredHost1)
    check(lateCommit.isConsumed && latePreedit.isConsumed && lateCaret.isConsumed) { "Ordinary AWT events on retired host must be consumed" }
    check(e.snapshot().visible.text.isEmpty() && e.snapshot().committed.text.isEmpty() && e.snapshot().undoCount == 0)

    // Legitimate subsequent input on replacement host, including identical text "あ", must succeed
    imeEvent("あ", 0, 1)
    // Stale caret/text on retiredHost1 while activeSurface is composing must not affect active composition
    caretEvent(0, target = retiredHost1)
    imeEvent("無視", 2, null, target = retiredHost1)
    check(e.snapshot().visible.text == "あ" && e.snapshot().selection == Selection(1, 1))
    imeEvent("あ", 1, null)
    check(e.snapshot().committed.text == "あ" && e.snapshot().marked == null && e.snapshot().undoCount == 1)
    // Consecutive legitimate session with identical text "あ"
    imeEvent("あ", 0, 1)
    imeEvent("あ", 1, null)
    check(e.snapshot().committed.text == "ああ" && e.snapshot().undoCount == 2)
    e.perform(EditAction.Undo)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 9b. Reentrant ordinary AWT commit during abortAndRetireComposition
    val retiredHost2 = activeSurface
    imeEvent("再入", 0, 2, target = retiredHost2)
    retiredHost2.onEndCompositionRequested = {
        imeEvent("再入", 2, null, target = retiredHost2)
        caretEvent(0, target = retiredHost2)
    }
    retiredHost2.abortAndRetireComposition()
    activeSurface = checkNotNull(retiredHost2.replacementSurface)
    check(e.snapshot().committed.text.isEmpty() && e.snapshot().visible.text.isEmpty()) { "Reentrant ordinary commit during abort must be ignored" }
    check(e.snapshot().marked == null && e.snapshot().undoCount == 0)

    // 9c. Partial-commit recovery on attached host under reentrant and delayed ordinary AWT callbacks
    e.replace("abcd", "Fixture")
    e.selectAll()
    val retiredHost3 = activeSurface
    imeEvent("XYcd", 1, 3, target = retiredHost3) // 'X' accepted, 'Y' provisional, "cd" retained
    check(e.snapshot().committed.text == "Xcd" && e.snapshot().visible.text == "XYcd")
    retiredHost3.onEndCompositionRequested = {
        imeEvent("XYcd", 4, null, target = retiredHost3)
    }
    retiredHost3.abortAndRetireComposition()
    imeEvent("LATE", 4, null, target = retiredHost3)
    activeSurface = checkNotNull(retiredHost3.replacementSurface)
    check(e.snapshot().visible.text == "Xcd" && e.snapshot().committed.text == "Xcd" && e.snapshot().marked == null)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "abcd" && e.snapshot().selection == Selection(0, 4))
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "Xcd" && e.snapshot().selection == Selection(1, 1))
    e.perform(EditAction.Undo)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 10. Watchdog timeout across candidate updates and Escape recovery without auto-commit
    imeEvent("とう", 0, 2)
    check(e.snapshot().marked != null)
    check(!e.snapshot().waiting)
    key(KeyEvent.VK_B, ctrl = true)
    check(e.snapshot().waiting) { "Shortcut during composition must set waiting flag" }
    val deadlineStart = checkNotNull(e.settlementRequestedAt) { "Shortcut during composition must record settlementRequestedAt" }
    // Non-terminal candidate update while command is pending must preserve deadlineStart and waiting state
    imeEvent("とうきょう", 0, 5)
    check(e.snapshot().waiting) { "Candidate update must not clear waiting state" }
    check(e.settlementRequestedAt == deadlineStart) { "Candidate update must preserve original settlementRequestedAt deadline" }
    check(!e.settlementTimedOut)
    val timedOut = e.checkSettlementWatchdog(timeoutMs = 0L)
    check(timedOut) { "Watchdog must report timeout" }
    check(e.settlementTimedOut)
    check(e.settlementRequestedAt == null)
    check(!e.snapshot().waiting) { "Watchdog timeout must cancel pending queue so editor is not wedged" }
    check(e.snapshot().marked != null) { "Watchdog timeout must NOT auto-commit unaccepted text" }
    val preEscapeHost = activeSurface
    key(KeyEvent.VK_ESCAPE)
    activeSurface = checkNotNull(preEscapeHost.replacementSurface) { "Escape after watchdog timeout must retire host and install replacement" }
    imeEvent("とうきょう", 5, null, target = preEscapeHost)
    check(e.snapshot().marked == null) { "Escape must abort timed-out composition" }
    check(e.snapshot().visible.text.isEmpty() && e.snapshot().committed.text.isEmpty())

    // 11. 100-Iteration Mounted Editor Host Replacement Lifecycle Qualification
    activeSurface = runMountedHostReplacementLifecycleChecks(activeSurface)

    // 12. Failure-Path Injection & Conservative Recovery Qualification
    runHostReplacementFailurePathChecks()

    println("PASS Synthetic Japanese IME callback sequence (preedit -> conversion -> commit), single-group Undo/Redo")
    println("PASS Attached Compose IME candidate geometry (getTextLocation / getLocationOffset monotonic coordinates under stated conditions)")
    println("PASS Screen coordinates and unavailable geometry fallback distinction (offscreen vs not-showing vs unavailable transform)")
    println("PASS Synthetic Japanese multi-clause conversion, caret traversal and partial commit")
    println("PASS Synthetic Japanese composition Escape cancellation, focus-loss settlement, and reentrant/delayed callback handling")
    println("PASS Synthetic Japanese selection replacement with exact selection restoration on Undo")
    println("PASS Synthetic Japanese queued formatting and Undo command ordering with attributed history")
    println("PASS Synthetic Japanese paragraph-boundary composition and cross-paragraph selection replacement")
    println("PASS Synthetic Japanese duplicate terminal callbacks, ordinary AWT host retirement/replacement and watchdog timeout across candidate updates")
    println("PASS 100-iteration mounted editor host replacement lifecycle (layer 100 invariant, callback isolation, locale/composition preservation, timers/contexts/callbacks counters, toolbar/Save/Open/a11y/diagnostics routing, close/discard/removal during replacement)")
    println("PASS Host replacement failure-path injection (endComposition, removeNotify, dispose, replacement creation, context binding, container attachment) and conservative recovery mode")
    return activeSurface
}

private fun findButtons(component: java.awt.Component): List<javax.swing.JButton> {
    if (component is javax.swing.JButton) return listOf(component)
    val container = component as? java.awt.Container ?: return emptyList()
    return container.components.flatMap(::findButtons)
}

private fun runMountedHostReplacementLifecycleChecks(initialSurface: EditorSurface): EditorSurface {
    var activeSurface = initialSurface
    val e = activeSurface.engine
    val mainWindow = checkNotNull(javax.swing.SwingUtilities.getWindowAncestor(activeSurface) as? javax.swing.JFrame) {
        "Active EditorSurface must be mounted inside showEditor JFrame"
    }
    val mainLayers = checkNotNull(windowLayeredPane(mainWindow)) { "showEditor must expose JLayeredPane" }
    check(windowCurrentSurface(mainWindow) === activeSurface)
    check(findSurface(mainWindow) === activeSurface)

    fun imeEvent(text: String, committedCount: Int, caret: Int?, target: EditorSurface = activeSurface): InputMethodEvent {
        val iterator = if (text.isEmpty()) null else AttributedString(text).iterator
        val ev = InputMethodEvent(target, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, iterator, committedCount, caret?.let(TextHitInfo::leading), null)
        target.dispatchEvent(ev)
        return ev
    }

    fun caretEvent(caret: Int, target: EditorSurface = activeSurface): InputMethodEvent {
        val ev = InputMethodEvent(target, InputMethodEvent.CARET_POSITION_CHANGED, null, 0, TextHitInfo.leading(caret), null)
        target.dispatchEvent(ev)
        return ev
    }

    fun keyPress(code: Int, ctrl: Boolean = false, shift: Boolean = false, target: EditorSurface = activeSurface): KeyEvent {
        var mod = 0
        if (ctrl) mod = mod or KeyEvent.CTRL_DOWN_MASK
        if (shift) mod = mod or KeyEvent.SHIFT_DOWN_MASK
        val ev = KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mod, code, KeyEvent.CHAR_UNDEFINED)
        target.dispatchEvent(ev)
        return ev
    }

    fun keyType(ch: Char, target: EditorSurface = activeSurface): KeyEvent {
        val ev = KeyEvent(target, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch)
        target.dispatchEvent(ev)
        return ev
    }

    e.replace("", "Reset before 100x lifecycle")
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    val countersBefore = EditorSurface.LifecycleCounters.snapshot()
    check(countersBefore.activeHosts >= 1 && countersBefore.activeTimers >= 1 && countersBefore.activeInputContexts >= 1)

    val retiredHosts = ArrayList<EditorSurface>(100)
    val locales = listOf(
        java.util.Locale.forLanguageTag("en-US") to false,
        java.util.Locale.forLanguageTag("ja-JP") to true,
        java.util.Locale.forLanguageTag("ur-PK") to false,
        java.util.Locale.forLanguageTag("ar-SA") to false,
    )

    for (i in 0 until 100) {
        val (targetLocale, targetCompEnabled) = locales[i % locales.size]
        activeSurface.selectInputLocale(targetLocale, compositionEnabled = targetCompEnabled)
        check(activeSurface.preferredLocale == targetLocale)
        check(activeSurface.preferredCompositionEnabled == targetCompEnabled)

        val oldHost = activeSurface
        val epochBefore = oldHost.activeImeEpoch

        when (i % 4) {
            0 -> {
                // Standard preedit -> abort & replace
                val baseline = e.snapshot().committed.text
                imeEvent("仮$i", 0, 2, target = oldHost)
                check(e.snapshot().marked != null)
                val next = oldHost.abortAndRetireComposition(replaceHost = true)
                activeSurface = checkNotNull(next) { "Iteration $i must produce replacement surface" }
                check(e.snapshot().committed.text == baseline && e.snapshot().visible.text == baseline && e.snapshot().marked == null)
            }
            1 -> {
                // Partial commit + remaining preedit -> abort & replace
                val prefixBefore = e.snapshot().committed.text
                e.replace("ab", "Seed-$i")
                e.move(Selection(prefixBefore.length, prefixBefore.length + 2))
                imeEvent("X${i % 10}b", 1, 2, target = oldHost) // 'X' committed, digit provisional, 'b' retained
                val next = oldHost.abortAndRetireComposition(replaceHost = true)
                activeSurface = checkNotNull(next) { "Iteration $i must produce replacement surface" }
                check(e.snapshot().visible.text == "${prefixBefore}Xb" && e.snapshot().committed.text == "${prefixBefore}Xb")
                // Undo partial commit + Undo seed to keep fixture bounded
                e.perform(EditAction.Undo)
                check(e.snapshot().visible.text == "${prefixBefore}ab")
                e.perform(EditAction.Undo)
                check(e.snapshot().visible.text == prefixBefore)
            }
            2 -> {
                // Reentrant ordinary AWT commit/caret during onEndCompositionRequested -> abort & replace
                val baseline = e.snapshot().committed.text
                imeEvent("再入$i", 0, 3, target = oldHost)
                oldHost.onEndCompositionRequested = {
                    imeEvent("再入$i", 3, null, target = oldHost)
                    caretEvent(0, target = oldHost)
                }
                val next = oldHost.abortAndRetireComposition(replaceHost = true)
                activeSurface = checkNotNull(next) { "Iteration $i must produce replacement surface" }
                check(e.snapshot().committed.text == baseline && e.snapshot().visible.text == baseline)
            }
            else -> {
                // Watchdog timeout with queued command -> Escape triggers abortAndRetireComposition
                val baseline = e.snapshot().committed.text
                imeEvent("待$i", 0, 2, target = oldHost)
                keyPress(KeyEvent.VK_B, ctrl = true, target = oldHost)
                check(e.snapshot().waiting)
                check(e.checkSettlementWatchdog(timeoutMs = 0L))
                check(e.settlementTimedOut && !e.snapshot().waiting && e.snapshot().marked != null)
                keyPress(KeyEvent.VK_ESCAPE, target = oldHost)
                activeSurface = checkNotNull(oldHost.replacementSurface) { "Escape after watchdog timeout at iteration $i must install replacement" }
                check(e.snapshot().committed.text == baseline && e.snapshot().visible.text == baseline && e.snapshot().marked == null)
            }
        }

        retiredHosts.add(oldHost)

        // Verify RecoveryReport and qualification state
        val report = checkNotNull(oldHost.lastRecoveryReport) { "Retired host $i must record RecoveryReport" }
        check(report.succeeded) { "Iteration $i recovery must succeed: $report" }
        check(report.status == RecoveryStatus.RECOVERED_QUALIFIED)
        check(report.contextQualified)
        check(report.documentAbortedCleanly)
        check(report.replacementCreated && report.containerAttached && !report.removedDuringTeardown)
        check(report.failures.isEmpty())
        check(report.preservedLocale == targetLocale)
        check(report.preservedCompositionEnabled == targetCompEnabled)

        check(activeSurface.contextQualified) { "Active replacement $i must be contextQualified" }
        check(activeSurface.recoveryStatus == RecoveryStatus.RECOVERED_QUALIFIED)
        check(activeSurface.activeImeEpoch == epochBefore + 1)
        check(activeSurface.preferredLocale == targetLocale) { "Locale preference must transfer at iteration $i" }
        check(activeSurface.preferredCompositionEnabled == targetCompEnabled) { "CompositionEnabled preference must transfer at iteration $i" }
        if (activeSurface.lastLocaleTransferSupported == true) {
            check(activeSurface.inputContext?.locale?.language == targetLocale.language) {
                "Accepted locale must match ${targetLocale.language}, got ${activeSurface.inputContext?.locale}"
            }
        }
        if (activeSurface.lastCompositionTransferSupported == true) {
            val actualComp = runCatching { activeSurface.inputContext?.isCompositionEnabled }.getOrNull()
            check(actualComp == targetCompEnabled) { "Accepted compositionEnabled must match $targetCompEnabled, got $actualComp" }
        }

        // Verify container & layer 100 invariants
        val surfacesInLayers = mainLayers.components.filterIsInstance<EditorSurface>()
        check(surfacesInLayers.size == 1 && surfacesInLayers.single() === activeSurface) {
            "Exactly one EditorSurface must remain in JLayeredPane at iteration $i, found ${surfacesInLayers.size}"
        }
        check(javax.swing.JLayeredPane.getLayer(activeSurface) == 100) {
            "Active EditorSurface must remain at layer 100 at iteration $i"
        }
        val layer100Components = mainLayers.getComponentsInLayer(100).toList()
        check(layer100Components.size == 1 && layer100Components.single() === activeSurface) {
            "Layer 100 must contain only the active EditorSurface at iteration $i"
        }
        check(windowCurrentSurface(mainWindow) === activeSurface) { "showEditor surface reference must track activeSurface at iteration $i" }
        check(findSurface(mainWindow) === activeSurface)

        // Verify retired host state & predecessor chain breaking
        check(oldHost.retired && oldHost.disposed) { "Old host $i must be retired and disposed" }
        check(!oldHost.contextQualified) { "Retired host $i must not remain contextQualified" }
        check(oldHost.parent == null) { "Old host $i must be detached from parent container" }
        check(oldHost.inputContext == null) { "Old host $i must return null inputContext" }
        check(!oldHost.isTimerRunning) { "Old host $i blinkTimer must be stopped" }
        check(!oldHost.hasRetainedCallbacks) { "Old host $i must clear all callback closures and timer listeners" }
        if (i >= 1) {
            check(retiredHosts[i - 1].replacementSurface == null) {
                "Predecessor retired host ${i - 1} replacementSurface link must be broken when host $i retires"
            }
        }

        // Verify old host rejects ordinary text, caret, key, and focus callbacks
        activeSurface.processFocusEvent(FocusEvent(activeSurface, FocusEvent.FOCUS_GAINED, false))
        check(activeSurface.view.focused)
        val snapBeforeStale = e.snapshot()
        val staleText = imeEvent("STALE_$i", 6, null, target = oldHost)
        val staleCaret = caretEvent(0, target = oldHost)
        keyType('Z', target = oldHost)
        keyPress(KeyEvent.VK_B, ctrl = true, target = oldHost)
        val directKeyTyped = KeyEvent(oldHost, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'Z')
        val directKeyPressed = KeyEvent(oldHost, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_B, KeyEvent.CHAR_UNDEFINED)
        oldHost.processKeyEvent(directKeyTyped)
        oldHost.processKeyEvent(directKeyPressed)
        oldHost.processFocusEvent(FocusEvent(oldHost, FocusEvent.FOCUS_LOST, false))
        check(staleText.isConsumed && staleCaret.isConsumed && directKeyTyped.isConsumed && directKeyPressed.isConsumed) {
            "Old host $i must consume/reject ordinary text, caret, and key events"
        }
        check(activeSurface.view.focused) { "FOCUS_LOST on retired host $i must not clear active focus state" }
        activeSurface.processFocusEvent(FocusEvent(activeSurface, FocusEvent.FOCUS_LOST, false))
        check(!activeSurface.view.focused)
        oldHost.processFocusEvent(FocusEvent(oldHost, FocusEvent.FOCUS_GAINED, false))
        check(!activeSurface.view.focused) { "FOCUS_GAINED on retired host $i must not set active focus state" }
        check(e.snapshot() == snapBeforeStale) { "Events on retired host $i must not mutate engine state" }

        // Verify current host receives subsequent input, including repeated identical text
        imeEvent("同", 0, 1, target = activeSurface)
        imeEvent("同", 1, null, target = activeSurface)
        imeEvent("同", 0, 1, target = activeSurface)
        imeEvent("同", 1, null, target = activeSurface)
        check(e.snapshot().committed.text.endsWith("同同")) { "Current host $i must accept repeated identical text" }
        e.perform(EditAction.Undo)
        e.perform(EditAction.Undo)
        val snapAfterUndo = e.snapshot()
        check(
            snapAfterUndo.committed.same(snapBeforeStale.committed) &&
                snapAfterUndo.visible.same(snapBeforeStale.visible) &&
                snapAfterUndo.selection == snapBeforeStale.selection &&
                snapAfterUndo.undoCount == snapBeforeStale.undoCount,
        ) { "Undoing repeated identical text at iteration $i must restore prior document and selection" }
    }

    // Check explicit lifecycle counters after 100 replacements
    val countersAfter = EditorSurface.LifecycleCounters.snapshot()
    check(countersAfter.hostsCreated - countersBefore.hostsCreated == 100) {
        "Expected 100 hosts created, got ${countersAfter.hostsCreated - countersBefore.hostsCreated}"
    }
    check(countersAfter.hostsRetired - countersBefore.hostsRetired == 100) {
        "Expected 100 hosts retired, got ${countersAfter.hostsRetired - countersBefore.hostsRetired}"
    }
    check(countersAfter.hostsDisposed - countersBefore.hostsDisposed == 100) {
        "Expected 100 hosts disposed, got ${countersAfter.hostsDisposed - countersBefore.hostsDisposed}"
    }
    check(countersAfter.timersStarted - countersBefore.timersStarted == 100) {
        "Expected 100 timers started, got ${countersAfter.timersStarted - countersBefore.timersStarted}"
    }
    check(countersAfter.timersStopped - countersBefore.timersStopped == 100) {
        "Expected 100 timers stopped, got ${countersAfter.timersStopped - countersBefore.timersStopped}"
    }
    check(countersAfter.inputContextsCreated - countersBefore.inputContextsCreated == 100) {
        "Expected 100 InputContexts created, got ${countersAfter.inputContextsCreated - countersBefore.inputContextsCreated}"
    }
    check(countersAfter.inputContextsDetached - countersBefore.inputContextsDetached == 100) {
        "Expected 100 InputContexts detached, got ${countersAfter.inputContextsDetached - countersBefore.inputContextsDetached}"
    }
    check(countersAfter.inputContextsDisposed - countersBefore.inputContextsDisposed == 100) {
        "Expected 100 InputContexts disposed, got ${countersAfter.inputContextsDisposed - countersBefore.inputContextsDisposed}"
    }
    check(countersAfter.recoveryAttempts - countersBefore.recoveryAttempts == 100)
    check(countersAfter.recoverySucceededCount - countersBefore.recoverySucceededCount == 100)
    check(countersAfter.recoveryDegradedCount - countersBefore.recoveryDegradedCount == 0)
    check(countersAfter.teardownFailuresCount - countersBefore.teardownFailuresCount == 0)
    check(countersAfter.activeHosts == countersBefore.activeHosts) {
        "Active host count must remain constant (${countersBefore.activeHosts}), got ${countersAfter.activeHosts}"
    }
    check(countersAfter.activeTimers == countersBefore.activeTimers) {
        "Active timer count must remain constant (${countersBefore.activeTimers}), got ${countersAfter.activeTimers}"
    }
    check(countersAfter.activeInputContexts == countersBefore.activeInputContexts) {
        "Active InputContext count must remain constant (${countersBefore.activeInputContexts}), got ${countersAfter.activeInputContexts}"
    }

    // Verify all 100 retired hosts have zero retained callbacks and only the 99th holds a direct reference to activeSurface
    for (idx in retiredHosts.indices) {
        val h = retiredHosts[idx]
        check(h.retired && h.disposed && h.parent == null && h.inputContext == null && !h.isTimerRunning && !h.hasRetainedCallbacks)
        if (idx < retiredHosts.lastIndex) {
            check(h.replacementSurface == null) { "Retired host $idx must not retain replacementSurface reference" }
        } else {
            check(h.replacementSurface === activeSurface)
        }
    }

    // Verify Save/Open callbacks, toolbar actions, accessibility notifications, diagnostics, and engine observers target activeSurface
    val sampleOldHost = retiredHosts.last()
    val origSave = activeSurface.requestSave
    val origOpen = activeSurface.requestOpen
    var saveCalls = 0
    var saveAsCalls = 0
    var openCalls = 0
    try {
        activeSurface.requestSave = { asNew -> if (asNew) saveAsCalls++ else saveCalls++ }
        activeSurface.requestOpen = { openCalls++ }
        keyPress(KeyEvent.VK_S, ctrl = true, target = sampleOldHost)
        keyPress(KeyEvent.VK_O, ctrl = true, target = sampleOldHost)
        check(saveCalls == 0 && openCalls == 0) { "Save/Open shortcuts on retired host must be ignored" }
        keyPress(KeyEvent.VK_S, ctrl = true, target = activeSurface)
        keyPress(KeyEvent.VK_S, ctrl = true, shift = true, target = activeSurface)
        keyPress(KeyEvent.VK_O, ctrl = true, target = activeSurface)
        check(saveCalls == 1 && saveAsCalls == 1 && openCalls == 1) {
            "Save/SaveAs/Open shortcuts on active host must invoke current callbacks: save=$saveCalls, saveAs=$saveAsCalls, open=$openCalls"
        }
    } finally {
        activeSurface.requestSave = origSave
        activeSurface.requestOpen = origOpen
    }

    // Accessibility notifications on activeSurface vs retiredHost
    var activeTextNotifications = 0
    var activeCaretNotifications = 0
    var oldTextNotifications = 0
    var oldCaretNotifications = 0
    val activeA11yListener = java.beans.PropertyChangeListener { evt ->
        if (evt.propertyName == javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_PROPERTY) activeTextNotifications++
        if (evt.propertyName == javax.accessibility.AccessibleContext.ACCESSIBLE_CARET_PROPERTY) activeCaretNotifications++
    }
    val oldA11yListener = java.beans.PropertyChangeListener { evt ->
        if (evt.propertyName == javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_PROPERTY) oldTextNotifications++
        if (evt.propertyName == javax.accessibility.AccessibleContext.ACCESSIBLE_CARET_PROPERTY) oldCaretNotifications++
    }
    activeSurface.accessibleContext.addPropertyChangeListener(activeA11yListener)
    sampleOldHost.accessibleContext.addPropertyChangeListener(oldA11yListener)
    try {
        keyType('A', target = activeSurface)
        keyType('B', target = activeSurface)
        check(e.snapshot().visible.text == "AB")
        check(activeTextNotifications >= 2 && activeCaretNotifications >= 2) {
            "Active host AccessibleContext must fire text/caret notifications ($activeTextNotifications, $activeCaretNotifications)"
        }
        check(oldTextNotifications == 0 && oldCaretNotifications == 0) {
            "Retired host AccessibleContext must not fire notifications ($oldTextNotifications, $oldCaretNotifications)"
        }
    } finally {
        activeSurface.accessibleContext.removePropertyChangeListener(activeA11yListener)
        sampleOldHost.accessibleContext.removePropertyChangeListener(oldA11yListener)
    }

    // Toolbar actions in showEditor window targeting current host
    val buttons = findButtons(mainWindow.contentPane)
    fun buttonNamed(prefix: String) = checkNotNull(buttons.firstOrNull { it.text == prefix || it.text.startsWith("$prefix ") }) {
        "Missing toolbar button '$prefix' in ${buttons.map { it.text }}"
    }
    e.selectAll()
    buttonNamed("Bold").doClick()
    check(e.snapshot().visible.units.all { it.style.bold }) { "Toolbar Bold button must format selection on current host" }
    check(buttonNamed("Bold").text == "Bold on") { "Current host statusChanged must update Bold button text to 'Bold on'" }
    buttonNamed("Italic").doClick()
    check(e.snapshot().visible.units.all { it.style.bold && it.style.italic })
    check(buttonNamed("Italic").text == "Italic on")
    buttonNamed("Undo").doClick()
    check(e.snapshot().visible.units.all { it.style.bold && !it.style.italic })
    buttonNamed("Redo").doClick()
    check(e.snapshot().visible.units.all { it.style.bold && it.style.italic })
    buttonNamed("Undo").doClick()
    buttonNamed("Undo").doClick()
    buttonNamed("Undo").doClick()
    check(e.snapshot().visible.text.isEmpty())

    // Partial commit + attributed Undo/Redo across host replacement
    e.replace("ABCD", "StyledFixture")
    e.move(Selection(0, 2))
    e.toggleFormat(FormatKey.BOLD)
    e.move(Selection(2, 4))
    e.toggleFormat(FormatKey.ITALIC)
    check(e.snapshot().visible.text == "ABCD")
    check(e.snapshot().visible.units[0].style == TextStyleFlags(bold = true))
    check(e.snapshot().visible.units[2].style == TextStyleFlags(italic = true))
    e.selectAll()
    val preAttribHost = activeSurface
    imeEvent("XYCD", 1, 3, target = preAttribHost) // 'X' committed (inherits Bold), 'Y' provisional, "CD" retained (Italic)
    activeSurface = checkNotNull(preAttribHost.abortAndRetireComposition(replaceHost = true))
    check(e.snapshot().visible.text == "XCD" && e.snapshot().committed.text == "XCD")
    check(e.snapshot().visible.units[0].char == 'X' && e.snapshot().visible.units[0].style == TextStyleFlags(bold = true))
    check(e.snapshot().visible.units[1].char == 'C' && e.snapshot().visible.units[1].style == TextStyleFlags(italic = true))
    check(e.snapshot().visible.units[2].char == 'D' && e.snapshot().visible.units[2].style == TextStyleFlags(italic = true))
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "ABCD" && e.snapshot().selection == Selection(0, 4))
    check(e.snapshot().visible.units[0].style == TextStyleFlags(bold = true) && e.snapshot().visible.units[2].style == TextStyleFlags(italic = true))
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "XCD" && e.snapshot().selection == Selection(1, 1))
    check(e.snapshot().visible.units[0].style == TextStyleFlags(bold = true) && e.snapshot().visible.units[1].style == TextStyleFlags(italic = true))
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // Mounted lifecycle tests for: (a) Ordinary close, (b) Discard-close, (c) Removal during replacement
    val lifecycleBaseline = EditorSurface.LifecycleCounters.snapshot()

    // (a) Ordinary close after replacements and diagnostics verification
    val tempDir = java.nio.file.Files.createTempDirectory("astra-lifecycle-check")
    try {
        val diagFile = tempDir.resolve("diag.json").toFile()
        val savedPath = tempDir.resolve("doc.astra")
        val cleanDoc = Document("Clean doc".mapIndexed { i, c -> TextUnit(i + 1L, c) })
        DocumentFiles.write(savedPath, cleanDoc, null)
        val winOrdinary = showEditor(diagnostics = diagFile, initial = cleanDoc, initialPath = savedPath, offscreenTest = true)
        var surfOrdinary = checkNotNull(windowCurrentSurface(winOrdinary))
        surfOrdinary = checkNotNull(surfOrdinary.abortAndRetireComposition(replaceHost = true))
        surfOrdinary = checkNotNull(surfOrdinary.abortAndRetireComposition(replaceHost = true))
        surfOrdinary.writeDiagnostics()
        val diagJson = diagFile.readText()
        check("\"recoveryStatus\":\"RECOVERED_QUALIFIED\"" in diagJson) { "Diagnostics must record RECOVERED_QUALIFIED: $diagJson" }
        check("\"contextQualified\":true" in diagJson) { "Diagnostics must record contextQualified=true: $diagJson" }
        check("\"activeImeEpoch\":2" in diagJson) { "Diagnostics must record activeImeEpoch=2: $diagJson" }
        val filesOrdinary = checkNotNull(windowFiles(winOrdinary))
        filesOrdinary.close()
        check(!winOrdinary.isDisplayable) { "Ordinary close on clean document must dispose window" }
        check(surfOrdinary.disposed && !surfOrdinary.isTimerRunning && surfOrdinary.inputContext == null)

        // (b) Discard-close while composing
        val winDiscard = showEditor(offscreenTest = true)
        val surfDiscard = checkNotNull(windowCurrentSurface(winDiscard))
        val engDiscard = surfDiscard.engine
        engDiscard.replace("Committed ", "Seed")
        surfDiscard.dispatchEvent(InputMethodEvent(surfDiscard, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未確定").iterator, 0, TextHitInfo.leading(3), null))
        check(engDiscard.snapshot().marked != null)
        val filesDiscard = checkNotNull(windowFiles(winDiscard))
        filesDiscard.close(choice = 1) // Discard
        val discardReport = checkNotNull(surfDiscard.lastRecoveryReport) { "Discard-close with active preedit must run conservative abort" }
        check(discardReport.status == RecoveryStatus.CONSERVATIVE_ABORT_ONLY)
        check(!discardReport.contextQualified && !discardReport.replacementCreated && discardReport.succeeded)
        check(engDiscard.snapshot().marked == null && engDiscard.snapshot().committed.text == "Committed ")
        check(!winDiscard.isDisplayable && surfDiscard.retired && surfDiscard.disposed && !surfDiscard.isTimerRunning)

        // (c) Removal during replacement (component removed from parent during onEndCompositionRequested)
        val winRemoval = showEditor(offscreenTest = true)
        try {
            val surfRemoval = checkNotNull(windowCurrentSurface(winRemoval))
            val layersRemoval = checkNotNull(windowLayeredPane(winRemoval))
            val engRemoval = surfRemoval.engine
            engRemoval.replace("KeepMe", "Seed")
            surfRemoval.dispatchEvent(InputMethodEvent(surfRemoval, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("消える").iterator, 0, TextHitInfo.leading(3), null))
            check(engRemoval.snapshot().marked != null)
            surfRemoval.onEndCompositionRequested = {
                layersRemoval.remove(surfRemoval)
            }
            val replacementOnRemoved = surfRemoval.abortAndRetireComposition(replaceHost = true)
            check(replacementOnRemoved == null) { "Host removed during teardown must NOT create or re-attach a replacement surface" }
            check(layersRemoval.components.none { it is EditorSurface }) { "No EditorSurface should remain in container after removal during replacement" }
            val removalReport = checkNotNull(surfRemoval.lastRecoveryReport)
            check(removalReport.removedDuringTeardown) { "RecoveryReport must flag removedDuringTeardown=true" }
            check(removalReport.status == RecoveryStatus.CONSERVATIVE_ABORT_ONLY)
            check(!removalReport.contextQualified && !removalReport.replacementCreated && !removalReport.containerAttached)
            check(removalReport.documentAbortedCleanly && engRemoval.snapshot().marked == null && engRemoval.snapshot().committed.text == "KeepMe")
        } finally {
            winRemoval.dispose()
        }
    } finally {
        tempDir.toFile().deleteRecursively()
    }

    val afterCloseSnap = EditorSurface.LifecycleCounters.snapshot()
    check(afterCloseSnap.activeHosts == lifecycleBaseline.activeHosts) {
        "All temporary windows/surfaces must be disposed after close/discard/removal tests: expected ${lifecycleBaseline.activeHosts}, got ${afterCloseSnap.activeHosts}"
    }
    check(afterCloseSnap.activeTimers == lifecycleBaseline.activeTimers) {
        "All temporary timers must be stopped: expected ${lifecycleBaseline.activeTimers}, got ${afterCloseSnap.activeTimers}"
    }
    check(afterCloseSnap.activeInputContexts == lifecycleBaseline.activeInputContexts) {
        "All temporary InputContexts must be disposed: expected ${lifecycleBaseline.activeInputContexts}, got ${afterCloseSnap.activeInputContexts}"
    }

    return activeSurface
}

private fun runHostReplacementFailurePathChecks() {
    val baselineCounters = EditorSurface.LifecycleCounters.snapshot()
    val tempDir = java.nio.file.Files.createTempDirectory("astra-failure-paths")
    try {
        // Helper to mount a standalone container + EditorSurface with diagnostics and verify document recoverability
        fun withTestHost(
            hooks: EditorSurface.RecoveryHooks,
            block: (javax.swing.JLayeredPane, EditorSurface, File) -> Unit,
        ) {
            val diagFile = tempDir.resolve("diag-${System.nanoTime()}.json").toFile()
            val engine = EditorEngine()
            engine.replace("Baseline", "Init")
            val view = ViewState(engine.snapshot())
            val layers = javax.swing.JLayeredPane()
            val surface = EditorSurface(engine, view, diagnostics = diagFile, recoveryHooks = hooks)
            layers.add(surface)
            layers.setLayer(surface, 100)
            try {
                block(layers, surface, diagFile)
            } finally {
                for (comp in layers.components.toList()) {
                    layers.remove(comp)
                    if (comp is EditorSurface) {
                        if (!comp.disposed) comp.removeNotify()
                        comp.recoveryHooks = EditorSurface.RecoveryHooks()
                        comp.retryCleanup()
                    }
                }
                var cur: EditorSurface? = surface
                while (cur != null) {
                    if (!cur.disposed) cur.removeNotify()
                    cur.recoveryHooks = EditorSurface.RecoveryHooks()
                    cur.retryCleanup()
                    cur = cur.replacementSurface
                }
            }
        }

        // 1. Failure in endComposition (context completion)
        withTestHost(
            EditorSurface.RecoveryHooks(
                endComposition = { ctx ->
                    ctx.endComposition()
                    throw IllegalStateException("Injected endComposition failure")
                },
            ),
        ) { _, surface, diagFile ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded) { "Recovery must NOT report success when endComposition failed" }
            check(report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED)
            check(!report.contextQualified) { "RecoveryReport must NOT mark contextQualified when endComposition failed" }
            check(!report.setupSucceeded && !report.admissionAllowed && !report.nativeQualified)
            check(report.documentAbortedCleanly)
            check(report.failures.any { "endComposition: IllegalStateException: Injected endComposition failure" in it })
            check(next != null && !next.contextQualified && !next.admissionAllowed && next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED) {
                "Replacement surface must NEVER be marked contextQualified or admissionAllowed when old context teardown failed"
            }
            check(surface.engine.snapshot().visible.text == "Baseline" && surface.engine.snapshot().marked == null)

            // Retained preimage preserved!
            check(next.retainedPreimage != null && next.retainedPreimage?.draftText == "未完")

            // DegradedAudit probe scenario: ordinary callback to degraded replacement must be quarantined and NOT admitted!
            val lateCommit = InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("LATE").iterator, 4, null, null)
            next.dispatchEvent(lateCommit)
            check(surface.engine.snapshot().committed.text == "Baseline" && surface.engine.snapshot().visible.text == "Baseline") {
                "Degraded replacement must NOT admit ambiguous native text into the committed document!"
            }
            check(next.quarantinedCallbacks.size == 1 && next.quarantinedCallbacks[0].text == "LATE") {
                "Ambiguous callback must be quarantined for explicit recovery"
            }

            // Also verify mutating keystrokes (Enter, Backspace, Delete, Tab) are blocked on degraded host
            next.dispatchEvent(KeyEvent(next, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'))
            next.dispatchEvent(KeyEvent(next, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED))
            next.dispatchEvent(KeyEvent(next, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED))
            next.dispatchEvent(KeyEvent(next, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_TAB, '\t'))
            check(surface.engine.snapshot().committed.text == "Baseline" && surface.engine.snapshot().visible.text == "Baseline") {
                "Degraded replacement must NOT admit mutating keystrokes (Enter, Backspace, Delete, Tab) into the document!"
            }

            val diagText = diagFile.readText()
            check("\"recoveryStatus\":\"DEGRADED_RECOVERY_FAILED\"" in diagText && "\"contextQualified\":false" in diagText && "\"admissionAllowed\":false" in diagText)
            check("Injected endComposition failure" in diagText)
            val saveFile = tempDir.resolve("recovered-1.astra")
            DocumentFiles.write(saveFile, surface.engine.snapshot().committed, null)
            check(DocumentFiles.read(saveFile).text == "Baseline")

            // Explicit recovery action
            val recovered = next.recoverExplicitly(admitQuarantined = false)
            check(recovered && next.admissionAllowed && next.recoveryStatus == RecoveryStatus.RECOVERED_QUALIFIED)
            check(next.quarantinedCallbacks.size == 1 && next.quarantinedCallbacks[0].text == "LATE")
            next.discardQuarantinedCallbacks()
            check(next.quarantinedCallbacks.isEmpty())
            // Now subsequent typing is admitted cleanly
            next.dispatchEvent(InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("OK").iterator, 2, null, null))
            check(surface.engine.snapshot().committed.text == "BaselineOK")
        }

        // 2a. Genuine failure in removeNotifyContext BEFORE removal (context detachment)
        withTestHost(
            EditorSurface.RecoveryHooks(
                removeNotifyContext = { _, _ ->
                    throw RuntimeException("Injected genuine removeNotify failure before removal")
                },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified && !report.admissionAllowed)
            check(report.failures.any { "removeNotifyContext: RuntimeException: Injected genuine removeNotify failure before removal" in it })
            check(surface.unresolvedContext != null) { "Undetached context must be tracked as unresolved resource" }

            // Production explicit cleanup / retry
            surface.recoveryHooks = EditorSurface.RecoveryHooks()
            val cleaned = surface.retryCleanup()
            check(cleaned && surface.unresolvedContext == null) { "Explicit retryCleanup must resolve undisposed context" }
        }

        // 2b. Failure in removeNotifyContext AFTER removal (removeNotify succeeded, then threw)
        withTestHost(
            EditorSurface.RecoveryHooks(
                removeNotifyContext = { ctx, comp ->
                    ctx.removeNotify(comp)
                    throw RuntimeException("Injected removeNotify failure after removal")
                },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified && !report.admissionAllowed)
            check(report.failures.any { "removeNotifyContext: RuntimeException: Injected removeNotify failure after removal" in it })
            check(surface.unresolvedContext != null) { "Context was detached but disposal was not reached; tracked as unresolved resource" }

            // Production explicit cleanup / retry
            surface.recoveryHooks = EditorSurface.RecoveryHooks()
            val cleaned = surface.retryCleanup()
            check(cleaned && surface.unresolvedContext == null) { "Explicit retryCleanup must dispose remaining unresolved context" }
        }

        // 3a. Genuine failure in disposeContext BEFORE dispose() (context disposal)
        withTestHost(
            EditorSurface.RecoveryHooks(
                disposeContext = { _ ->
                    throw RuntimeException("Injected genuine disposeContext failure before dispose()")
                },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified && !report.admissionAllowed)
            check(report.failures.any { "disposeContext: RuntimeException: Injected genuine disposeContext failure before dispose()" in it })
            check(surface.unresolvedContext != null) { "Undisposed context must be tracked as unresolved resource" }

            // Explicit production retry/cleanup
            surface.recoveryHooks = EditorSurface.RecoveryHooks()
            val cleaned = surface.retryCleanup()
            check(cleaned && surface.unresolvedContext == null) { "Explicit retryCleanup must dispose unresolved context honestly" }
        }

        // 3b. Failure in disposeContext AFTER ctx.dispose() (post-call exception)
        withTestHost(
            EditorSurface.RecoveryHooks(
                disposeContext = { ctx ->
                    ctx.dispose()
                    throw RuntimeException("Injected post-call exception after dispose()")
                },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified && !report.admissionAllowed)
            check(report.failures.any { "disposeContext: RuntimeException: Injected post-call exception after dispose()" in it })
            check(surface.unresolvedContext != null) { "When disposeContext throws, context is tracked as unresolved until explicit retryCleanup" }

            // Explicit retry cleanup
            surface.recoveryHooks = EditorSurface.RecoveryHooks()
            val cleaned = surface.retryCleanup()
            check(cleaned && surface.unresolvedContext == null) { "Explicit retryCleanup must resolve undisposed context" }
        }

        // 3c. Failure in disableInputMethods after clean context disposal
        withTestHost(
            EditorSurface.RecoveryHooks(
                disableInputMethods = { surf ->
                    surf.enableInputMethods(false)
                    throw RuntimeException("Injected disableInputMethods failure")
                },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified && !report.admissionAllowed)
            check(report.failures.any { "disableInputMethods: RuntimeException: Injected disableInputMethods failure" in it })
            check(surface.unresolvedContext == null) { "Context was cleanly disposed before disableInputMethods" }
        }

        // 4. Failure in replacement creation
        withTestHost(
            EditorSurface.RecoveryHooks(
                createReplacement = { throw IllegalStateException("Injected replacement creation failure") },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(next == null)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified)
            check(!report.replacementCreated && !report.containerAttached)
            check(report.failures.any { "replacementCreation: IllegalStateException: Injected replacement creation failure" in it })
            check(surface.engine.snapshot().visible.text == "Baseline" && surface.engine.snapshot().marked == null)
        }

        // 5a. Failure in replacement InputContext binding (returns null)
        var nullOnReplacement = false
        withTestHost(
            EditorSurface.RecoveryHooks(
                createInputContext = {
                    if (nullOnReplacement) null else java.awt.im.InputContext.getInstance()
                },
            ),
        ) { _, surface, _ ->
            nullOnReplacement = true
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified)
            check(report.failures.any { "contextBinding:" in it && "returned null" in it })
            check(next != null && next.inputContext == null && !next.contextQualified && next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED)
            check(surface.engine.snapshot().visible.text == "Baseline" && surface.engine.snapshot().marked == null)
        }

        // 5b. Failure in replacement InputContext binding (throws exception)
        var throwOnReplacement = false
        withTestHost(
            EditorSurface.RecoveryHooks(
                createInputContext = {
                    if (throwOnReplacement) {
                        throw RuntimeException("Injected InputContext binding exception")
                    } else {
                        java.awt.im.InputContext.getInstance()
                    }
                },
            ),
        ) { _, surface, _ ->
            throwOnReplacement = true
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified)
            check(report.failures.any { "contextBinding:" in it && "Injected InputContext binding exception" in it })
            check(next != null && next.inputContext == null && !next.contextQualified && next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED)
            check(surface.engine.snapshot().visible.text == "Baseline" && surface.engine.snapshot().marked == null)
        }

        // 6. Failure in container attachment (throws without attaching newHost)
        withTestHost(
            EditorSurface.RecoveryHooks(
                attachReplacement = { parentContainer, oldHost, _ ->
                    parentContainer.remove(oldHost)
                    throw IllegalStateException("Injected container attachment failure")
                },
            ),
        ) { _, surface, diagFile ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("未完").iterator, 0, TextHitInfo.leading(2), null))
            val next = surface.abortAndRetireComposition(replaceHost = true)
            val report = checkNotNull(surface.lastRecoveryReport)
            check(next == null) { "abortAndRetireComposition must return null when replacement failed to attach" }
            check(!report.succeeded && report.status == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !report.contextQualified)
            check(report.replacementCreated && !report.containerAttached)
            check(report.failures.any { "containerAttachment: IllegalStateException: Injected container attachment failure" in it })
            val orphaned = checkNotNull(surface.replacementSurface)
            check(orphaned.disposed && !orphaned.isTimerRunning && orphaned.inputContext == null && !orphaned.contextQualified) {
                "Unattached replacement host must be cleaned up immediately so its Timer and InputContext do not leak"
            }
            check(surface.engine.snapshot().visible.text == "Baseline" && surface.engine.snapshot().marked == null)
            // Verify engine Undo still updates view.frame and diagnostics via fallback observer
            surface.engine.perform(EditAction.Undo)
            check(surface.view.frame.visible.text.isEmpty())
            check("\"text\":\"\"" in diagFile.readText())
        }

        // 7. Explicit conservative recovery (abortCompositionConservatively / replaceHost = false)
        withTestHost(EditorSurface.RecoveryHooks()) { _, surface, diagFile ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("保守").iterator, 0, TextHitInfo.leading(2), null))
            check(surface.engine.snapshot().marked != null)
            val report = surface.abortCompositionConservatively()
            check(report.succeeded)
            check(!report.replaceRequested && report.status == RecoveryStatus.CONSERVATIVE_ABORT_ONLY)
            check(!report.contextQualified && !report.replacementCreated)
            check(surface.retired && surface.inputContext == null && !surface.contextQualified)
            check(surface.recoveryStatus == RecoveryStatus.CONSERVATIVE_ABORT_ONLY)
            // Late IME event on conservatively retired surface must be rejected
            val lateEv = InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("保守").iterator, 2, null, null)
            surface.dispatchEvent(lateEv)
            check(lateEv.isConsumed && surface.engine.snapshot().visible.text == "Baseline")
            // Document state remains clean, undoable, and saveable
            val savePath = tempDir.resolve("conservative.astra")
            DocumentFiles.write(savePath, surface.engine.snapshot().committed, null)
            check(DocumentFiles.read(savePath).text == "Baseline")
            surface.engine.perform(EditAction.Undo)
            check(surface.view.frame.visible.text.isEmpty())
            check("\"recoveryStatus\":\"CONSERVATIVE_ABORT_ONLY\"" in diagFile.readText())
        }

        // Basis-bound attributed recovery: exercise the actual host API, including failure retention.
        withTestHost(EditorSurface.RecoveryHooks()) { _, surface, _ ->
            val engine = surface.engine
            engine.selectAll()
            engine.replace("ک\nتاب", "Fixture", paragraphBreaks = true)
            engine.move(Selection(0, 1))
            engine.toggleFormat(FormatKey.BOLD)
            engine.move(Selection(2, 5))
            engine.toggleFormat(FormatKey.ITALIC)
            engine.selectAll()
            engine.inputMethod("ق\nتاب", 0, 5)
            val desired = engine.snapshot()
            val next = checkNotNull(surface.abortAndRetireComposition(replaceHost = true))
            engine.move(Selection(0, 0))
            val before = engine.snapshot()
            check(next.applyRecoveredDraft())
            check(next.retainedPreimage == null)
            check(engine.snapshot().committed == desired.visible && engine.snapshot().selection == desired.selection)
            check(engine.snapshot().committed.units[1].kind == UnitKind.PARAGRAPH_BREAK)
            check(engine.snapshot().undoCount == before.undoCount + 1)
            engine.perform(EditAction.Undo)
            check(engine.snapshot().committed == before.committed && engine.snapshot().selection == before.selection)
            engine.perform(EditAction.Redo)
            check(engine.snapshot().committed == desired.visible)
        }
        withTestHost(EditorSurface.RecoveryHooks()) { _, surface, _ ->
            surface.engine.inputMethod("قلم", 0, 3)
            val next = checkNotNull(surface.abortAndRetireComposition(replaceHost = true))
            val retained = checkNotNull(next.retainedPreimage)
            next.engine.replace("X", "ChangedBasis")
            val changed = next.engine.snapshot()
            check(!next.applyRecoveredDraft())
            check(next.retainedPreimage === retained)
            check(next.engine.snapshot().committed == changed.committed)
            check(next.engine.snapshot().notice.startsWith("RecoveryBaseChanged"))
            next.discardRecoveredDraft()
            check(next.retainedPreimage == null)
        }
        withTestHost(EditorSurface.RecoveryHooks()) { _, surface, _ ->
            val before = surface.engine.snapshot()
            val deletion = surface.engine.captureRecoveryReplacement(Document(emptyList()), Selection(0, 0))
            surface.retainedPreimage = RetainedCompositionPreimage(
                "",
                before.selection,
                IntRange.EMPTY,
                before.committed.text,
                surface.activeImeEpoch,
                deletion,
            )
            surface.engine.inputMethod("候補", 0, 2)
            val retained = surface.retainedPreimage
            check(!surface.applyRecoveredDraft() && surface.retainedPreimage === retained)
            surface.engine.abortComposition()
            // Aborting unaccepted preedit leaves the captured committed basis intact.
            check(surface.retainedPreimage === retained)
            check(surface.applyRecoveredDraft() && surface.retainedPreimage == null)
            check(surface.engine.snapshot().committed.text.isEmpty())
            surface.engine.perform(EditAction.Undo)
            check(surface.engine.snapshot().committed == before.committed)
            surface.engine.perform(EditAction.Redo)
            check(surface.engine.snapshot().committed.text.isEmpty())
        }
        withTestHost(EditorSurface.RecoveryHooks()) { _, origin, _ ->
            origin.engine.selectAll()
            origin.engine.inputMethod("کتاب", 0, 4)
            val replacement = checkNotNull(origin.abortAndRetireComposition(replaceHost = true))
            val retained = checkNotNull(replacement.retainedPreimage)
            val capsule = checkNotNull(retained.recoveryDraft)
            withTestHost(EditorSurface.RecoveryHooks()) { _, foreign, _ ->
                foreign.engine.selectAll()
                foreign.engine.inputMethod("قلم", 0, 3)
                foreign.engine.abortComposition()
                val before = foreign.engine.snapshot()
                check(before.committed == capsule.basis && before.recoveryBasisRevision == capsule.basisRevision && before.identityGeneration == capsule.basisIdentityGeneration)
                foreign.retainedPreimage = retained
                check(!foreign.applyRecoveredDraft())
                check(foreign.retainedPreimage === retained && foreign.engine.snapshot().committed == before.committed && foreign.engine.snapshot().undoCount == before.undoCount)
                check(foreign.engine.snapshot().notice.startsWith("RecoveryBaseChanged"))
                check(replacement.applyRecoveredDraft() && replacement.retainedPreimage == null)
                check(replacement.engine.snapshot().committed == capsule.document && foreign.retainedPreimage === retained)
            }
        }

        // 8. Repeated failure, retry, and explicit recovery
        withTestHost(
            EditorSurface.RecoveryHooks(
                endComposition = { throw RuntimeException("Failure 1") },
            ),
        ) { _, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("ドラフト").iterator, 0, TextHitInfo.leading(4), null))
            val next1 = checkNotNull(surface.abortAndRetireComposition(replaceHost = true))
            check(next1.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !next1.admissionAllowed)
            check(next1.retainedPreimage?.draftText == "ドラフト")

            // Ordinary callback to degraded host is quarantined, document unaffected
            next1.dispatchEvent(InputMethodEvent(next1, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("保留").iterator, 2, null, null))
            check(next1.engine.snapshot().committed.text == "Baseline")
            check(next1.quarantinedCallbacks.size == 1 && next1.quarantinedCallbacks[0].text == "保留")

            // Second retry with failure still fails gracefully
            next1.recoveryHooks = EditorSurface.RecoveryHooks(endComposition = { throw RuntimeException("Failure 2") })
            val next2 = checkNotNull(next1.abortAndRetireComposition(replaceHost = true))
            check(next2.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !next2.admissionAllowed)
            check(next2.engine.snapshot().committed.text == "Baseline")
            check(next2.quarantinedCallbacks.size == 1 && next2.quarantinedCallbacks[0].text == "保留")
            check(next2.retainedPreimage?.draftText == "ドラフト")

            // Third retry with clean hooks succeeds
            next2.recoveryHooks = EditorSurface.RecoveryHooks()
            val next3 = checkNotNull(next2.abortAndRetireComposition(replaceHost = true))
            check(next3.recoveryStatus == RecoveryStatus.RECOVERED_QUALIFIED && next3.admissionAllowed && next3.contextQualified)
            check(next3.quarantinedCallbacks.size == 1 && next3.quarantinedCallbacks[0].text == "保留")
            check(next3.inspectRetainedDraft() == "ドラフト")

            // Inspect and export draft without mutating current document
            val exportFile = tempDir.resolve("exported-draft.txt").toFile()
            check(next3.exportRetainedDraft(exportFile))
            check(exportFile.readText() == "ドラフト")
            check(next3.engine.snapshot().committed.text == "Baseline")

            // Explicitly apply recovered draft with attributed Undo/Redo
            check(next3.applyRecoveredDraft())
            check(next3.engine.snapshot().committed.text == "Baselineドラフト")
            next3.engine.perform(EditAction.Undo)
            check(next3.engine.snapshot().committed.text == "Baseline")
            next3.engine.perform(EditAction.Redo)
            check(next3.engine.snapshot().committed.text == "Baselineドラフト")
            next3.engine.perform(EditAction.Undo)
            next3.discardQuarantinedCallbacks()
            check(next3.quarantinedCallbacks.isEmpty())

            // Subsequent input works cleanly
            next3.dispatchEvent(InputMethodEvent(next3, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("成功").iterator, 2, null, null))
            check(next3.engine.snapshot().committed.text == "Baseline成功")
        }

        // 9. Partial commit with degraded recovery preserves accepted prefix and exact Undo/Redo
        withTestHost(
            EditorSurface.RecoveryHooks(
                endComposition = { throw RuntimeException("Partial commit teardown failure") },
            ),
        ) { _, surface, _ ->
            surface.engine.selectAll()
            surface.engine.replace("abcd", "Init")
            surface.engine.selectAll()
            // Partial commit: 'X' accepted, "Y" provisional, "cd" retained
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("XYcd").iterator, 1, TextHitInfo.leading(2), null))
            check(surface.engine.snapshot().committed.text == "Xcd" && surface.engine.snapshot().visible.text == "XYcd")

            val next = checkNotNull(surface.abortAndRetireComposition(replaceHost = true))
            check(next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !next.admissionAllowed)
            check(surface.engine.snapshot().committed.text == "Xcd" && surface.engine.snapshot().visible.text == "Xcd" && surface.engine.snapshot().marked == null)

            // Ambiguous callback on next is quarantined, not admitted
            next.dispatchEvent(InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("CORRUPT").iterator, 7, null, null))
            check(surface.engine.snapshot().committed.text == "Xcd")

            // Undo reverts partial commit back to original "abcd"
            surface.engine.perform(EditAction.Undo)
            check(surface.engine.snapshot().visible.text == "abcd" && surface.engine.snapshot().selection == Selection(0, 4))
            // Redo restores accepted partial commit "Xcd"
            surface.engine.perform(EditAction.Redo)
            check(surface.engine.snapshot().visible.text == "Xcd")
        }

        // 10. Discard-close and disposal on degraded host
        withTestHost(
            EditorSurface.RecoveryHooks(
                endComposition = { throw RuntimeException("Teardown failure for discard-close") },
            ),
        ) { layers, surface, _ ->
            surface.dispatchEvent(InputMethodEvent(surface, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("破棄").iterator, 0, TextHitInfo.leading(2), null))
            val next = checkNotNull(surface.abortAndRetireComposition(replaceHost = true))
            check(next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED)
            // Discard close simulation: remove from container and removeNotify
            layers.remove(next)
            next.removeNotify()
            check(next.disposed && !next.isTimerRunning && next.inputContext == null)
        }

        // 11. Unresolved context obligation blocks admission across host replacement (Codex reproducer regression)
        var detachCallCount = 0
        withTestHost(
            EditorSurface.RecoveryHooks(
                removeNotifyContext = { ctx, comp ->
                    detachCallCount++
                    ctx.removeNotify(comp)
                },
                disposeContext = { ctx ->
                    throw RuntimeException("dispose failed before release")
                },
            ),
        ) { _, oldSurface, _ ->
            oldSurface.engine.inputMethod("draft", 0, 5)
            val next = checkNotNull(oldSurface.abortAndRetireComposition(replaceHost = true))

            // Ordinary callback route: callback is quarantined, not admitted
            next.dispatchEvent(InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("Q").iterator, 1, null, null))
            check(oldSurface.unresolvedContext != null) { "Old context must remain tracked as unresolved obligation" }
            check(next.unresolvedContext != null) { "Replacement host must retain unresolved context obligation attached to recovery session" }
            check(next.quarantinedCallbacks.size == 1 && next.quarantinedCallbacks[0].text == "Q")
            check(next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED)
            check(!next.admissionAllowed)

            // Repeated failure testing: multiple user recovery attempts MUST fail while obligation is unresolved
            for (attempt in 1..3) {
                val recoverResult = next.recoverExplicitly(false)
                check(!recoverResult) { "recoverExplicitly MUST return false on attempt $attempt when unresolved cleanup fails" }
                check(!next.admissionAllowed) { "Admission must NOT be allowed on attempt $attempt when unresolved cleanup fails" }
                check(next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED)
                check(next.quarantinedCallbacks.size == 1) { "Quarantined data must NOT be silently cleared on attempt $attempt" }
            }

            // Detachment idempotency: detach was executed cleanly during initial abort, so retryCleanup should not re-detach
            check(detachCallCount == 1) { "detachContext should not be called redundantly once marked detached: expected 1, got $detachCallCount" }

            // Ambiguous late native callback dispatched during degraded state must NOT mutate committed document
            next.dispatchEvent(InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("LATE").iterator, 4, null, null))
            check(oldSurface.engine.snapshot().committed.text == "Baseline") {
                "Committed text must not be corrupted by ambiguous native callback while unresolved cleanup exists"
            }
            check(next.quarantinedCallbacks.size == 2) { "Both ambiguous callbacks must remain quarantined" }

            // Inspect retained draft preimage before application
            check(next.inspectRetainedDraft() == "draft") { "Draft preimage must be preserved across failed recovery attempts" }

            // Later successful cleanup: repair hooks and explicitly recover
            val cleanHooks = EditorSurface.RecoveryHooks()
            next.recoveryHooks = cleanHooks
            oldSurface.recoveryHooks = cleanHooks
            val cleanRecoverResult = next.recoverExplicitly(false)
            check(cleanRecoverResult) { "recoverExplicitly must succeed once cleanup successfully resolves obligations" }
            check(next.admissionAllowed) { "Admission must be enabled after successful cleanup" }
            check(next.recoveryStatus == RecoveryStatus.RECOVERED_QUALIFIED)
            check(oldSurface.unresolvedContext == null) { "Unresolved context must be resolved after clean recovery" }
            check(next.unresolvedContext == null)

            // Quarantined callbacks preserved for explicit inspection or discard
            check(next.quarantinedCallbacks.size == 2)
            next.discardQuarantinedCallbacks()
            check(next.quarantinedCallbacks.isEmpty())

            // Explicitly apply recovered draft with attributed Undo/Redo
            check(next.applyRecoveredDraft()) { "applyRecoveredDraft must succeed when draft is retained" }
            check(next.engine.snapshot().committed.text == "Baselinedraft")
            next.engine.perform(EditAction.Undo)
            check(next.engine.snapshot().committed.text == "Baseline") { "Undo must revert applied draft" }
            next.engine.perform(EditAction.Redo)
            check(next.engine.snapshot().committed.text == "Baselinedraft") { "Redo must reapply draft" }
            next.engine.perform(EditAction.Undo)

            // Subsequent normal input admitted cleanly
            next.dispatchEvent(InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("OK").iterator, 2, null, null))
            check(next.engine.snapshot().committed.text == "BaselineOK")
        }

        // 11b. Safe isolation fence enables admission when cleanup cannot succeed
        withTestHost(
            EditorSurface.RecoveryHooks(
                disposeContext = { _ ->
                    throw RuntimeException("Permanent native handle leak / hang")
                },
            ),
        ) { _, oldSurface, _ ->
            oldSurface.engine.inputMethod("fencedDraft", 0, 11)
            val next = checkNotNull(oldSurface.abortAndRetireComposition(replaceHost = true))
            check(!next.admissionAllowed && next.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED)

            // Without safe isolation boundary, recovery fails
            check(!next.recoverExplicitly(false))
            check(!next.admissionAllowed)

            // Establish safe isolation fence
            next.establishSafeIsolationFence()
            check(next.recoverySession.isolationFenceEstablished)

            // Explicit recovery now succeeds because isolation boundary is established
            val fencedRecover = next.recoverExplicitly(false)
            check(fencedRecover) { "recoverExplicitly must succeed when safe isolation fence is established" }
            check(next.admissionAllowed && next.recoveryStatus == RecoveryStatus.RECOVERED_QUALIFIED)

            // Explicitly discard draft
            check(next.inspectRetainedDraft() == "fencedDraft")
            next.discardRecoveredDraft()
            check(next.inspectRetainedDraft() == null)

            // Normal typing admitted
            next.dispatchEvent(InputMethodEvent(next, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("FENCE_OK").iterator, 8, null, null))
            check(next.engine.snapshot().committed.text == "BaselineFENCE_OK")
        }

        // 12. Actual mounted Recover button and draft controls regression in live UI hierarchy
        val runBlock = {
            val diagFile = tempDir.resolve("ui-recover-diag.json").toFile()
            val failingHooks = EditorSurface.RecoveryHooks(
                disposeContext = { ctx ->
                    throw RuntimeException("Injected window disposeContext failure")
                },
            )
            val win = showEditor(diagFile, offscreenTest = true)
            try {
                val surf = checkNotNull(findSurface(win))
                surf.recoveryHooks = failingHooks
                surf.engine.replace("Mounted", "Init")
                surf.engine.inputMethod("uncommitted", 0, 11)

                val nextHost = checkNotNull(surf.abortAndRetireComposition(replaceHost = true))
                check(nextHost.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED && !nextHost.admissionAllowed)

                fun findButtonByText(container: java.awt.Container, text: String): javax.swing.JButton? {
                    for (comp in container.components) {
                        if (comp is javax.swing.JButton && comp.text == text) return comp
                        if (comp is java.awt.Container) {
                            val f = findButtonByText(comp, text)
                            if (f != null) return f
                        }
                    }
                    return null
                }

                val recoverBtn = checkNotNull(findButtonByText(win.contentPane, "Recover")) {
                    "Mounted Recover button must be present in the UI when degraded"
                }
                val applyDraftBtn = checkNotNull(findButtonByText(win.contentPane, "Apply Draft")) {
                    "Mounted Apply Draft button must be present when draft exists"
                }
                val discardDraftBtn = checkNotNull(findButtonByText(win.contentPane, "Discard Draft")) {
                    "Mounted Discard Draft button must be present when draft exists"
                }
                check(recoverBtn.isVisible) { "Recover button must be visible when admission is blocked" }
                check(applyDraftBtn.isVisible) { "Apply Draft button must be visible when draft is retained" }
                check(discardDraftBtn.isVisible) { "Discard Draft button must be visible when draft is retained" }

                // Click the actual Recover button repeatedly: must fail while disposeContext throws
                recoverBtn.doClick()
                check(!nextHost.admissionAllowed) { "Clicking Recover button must NOT enable admission while cleanup fails" }
                check(recoverBtn.isVisible) { "Recover button must remain visible when recovery fails" }
                recoverBtn.doClick()
                check(!nextHost.admissionAllowed) { "Repeated Recover click must also fail" }

                // Send ordinary callback while degraded: must be quarantined
                nextHost.dispatchEvent(InputMethodEvent(nextHost, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("BLOCK").iterator, 5, null, null))
                check(nextHost.engine.snapshot().committed.text == "Mounted") { "Blocked callback must not enter committed text" }
                check(nextHost.quarantinedCallbacks.any { it.text == "BLOCK" })

                // Now restore clean hooks
                val cleanHooks = EditorSurface.RecoveryHooks()
                nextHost.recoveryHooks = cleanHooks
                surf.recoveryHooks = cleanHooks

                // Click the actual Recover button again: now it succeeds!
                recoverBtn.doClick()
                check(nextHost.admissionAllowed) { "Clicking Recover button after cleanup must enable admission" }
                check(!recoverBtn.isVisible) { "Recover button must be hidden after successful recovery" }

                // Retained draft button remains visible until explicitly applied or discarded
                check(applyDraftBtn.isVisible)
                applyDraftBtn.doClick()
                check(nextHost.engine.snapshot().committed.text == "Mounteduncommitted") { "Apply Draft button must apply draft text" }
                check(!applyDraftBtn.isVisible) { "Apply Draft button must be hidden after application" }

                // Undo reverts applied draft
                nextHost.engine.perform(EditAction.Undo)
                check(nextHost.engine.snapshot().committed.text == "Mounted")

                // Subsequent typing works
                nextHost.dispatchEvent(InputMethodEvent(nextHost, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString("DONE").iterator, 4, null, null))
                check(nextHost.engine.snapshot().committed.text == "MountedDONE")
            } finally {
                win.dispose()
            }
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            runBlock()
        } else {
            javax.swing.SwingUtilities.invokeAndWait(runBlock)
        }
    } finally {
        tempDir.toFile().deleteRecursively()
    }

    val afterCounters = EditorSurface.LifecycleCounters.snapshot()
    check(afterCounters.activeHosts == baselineCounters.activeHosts) {
        "Failure-path tests must not leak active hosts: expected ${baselineCounters.activeHosts}, got ${afterCounters.activeHosts}"
    }
    check(afterCounters.activeTimers == baselineCounters.activeTimers) {
        "Failure-path tests must not leak active timers: expected ${baselineCounters.activeTimers}, got ${afterCounters.activeTimers}"
    }
    check(afterCounters.activeInputContexts == baselineCounters.activeInputContexts) {
        "Failure-path tests must not leak active InputContexts: expected ${baselineCounters.activeInputContexts}, got ${afterCounters.activeInputContexts}"
    }
}
