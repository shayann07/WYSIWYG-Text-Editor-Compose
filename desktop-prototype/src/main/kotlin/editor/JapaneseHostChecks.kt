package editor

import java.awt.Point
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.font.TextHitInfo
import java.text.AttributedString

/**
 * Attached AWT EditorSurface host checks covering the complete Microsoft Japanese IME lifecycle:
 * romaji -> hiragana -> candidate conversion, candidate popup placement (getTextLocation / getLocationOffset),
 * single-group composition Undo/Redo, multi-clause navigation and partial commits, Escape cancellation,
 * focus loss settlement, selection replacement, queued formatting/Undo during preedit, and paragraph boundaries.
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

    // 1. Provisional composition, candidate conversion, candidate popup placement, commit and exact Undo/Redo
    // Simulate typing "nihon" -> Hiragana "にほん" -> candidate conversion to Kanji "日本"
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

    // Step 1d: Candidate conversion (Space bar) -> "日本"
    imeEvent("日本", 0, 2)
    check(e.snapshot().visible.text == "日本")
    check(e.snapshot().marked == 0..1)
    check(e.snapshot().committed.text.isEmpty())

    // Step 1e: Confirm candidate popup placement relative to caret (getTextLocation & getLocationOffset)
    val loc0 = surface.getTextLocation(TextHitInfo.leading(0))
    val loc1 = surface.getTextLocation(TextHitInfo.leading(1))
    val locEnd = surface.getTextLocation(TextHitInfo.leading(2))
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
    // "わたしのなまえ" -> "私の名前" (Clause 1: "私の", Clause 2: "名前")
    imeEvent("わたしのなまえ", 0, 7)
    check(e.snapshot().visible.text == "わたしのなまえ")
    // Convert to 2 clauses: "私の名前"
    imeEvent("私の名前", 0, 4)
    check(e.snapshot().visible.text == "私の名前")
    check(e.snapshot().marked == 0..3)

    // Move IME caret between clauses via CARET_POSITION_CHANGED
    caretEvent(2)
    check(e.snapshot().selection.focus == 2) { "IME caret event must move selection to clause boundary" }
    val clauseCaretLoc = surface.getTextLocation(TextHitInfo.leading(2))
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

    // 3. Cancellation during composition (Escape key / unmark without assuming rollback)
    e.replace("東京 ", "Prefix fixture")
    imeEvent("とうきょう", 0, 5)
    check(e.snapshot().visible.text == "東京 とうきょう")
    check(e.snapshot().marked == 3..7)
    check(e.snapshot().committed.text == "東京 ")
    // User presses Escape
    key(KeyEvent.VK_ESCAPE)
    // Native IME terminates composition with empty replacement
    imeEvent("", 0, null)
    check(e.snapshot().visible.text == "東京 ")
    check(e.snapshot().committed.text == "東京 ")
    check(e.snapshot().marked == null)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 4. Focus loss during composition without assuming rollback
    e.replace("開始 ", "Prefix fixture")
    imeEvent("きょう", 0, 3)
    check(e.snapshot().visible.text == "開始 きょう")
    check(e.snapshot().marked == 3..5)
    // Focus lost event dispatched to surface
    surface.dispatchEvent(FocusEvent(surface, FocusEvent.FOCUS_LOST, false))
    check(!surface.view.focused)
    // Native IME settles current preedit on focus loss
    imeEvent("今日", 2, null)
    check(e.snapshot().visible.text == "開始 今日")
    check(e.snapshot().committed.text == "開始 今日")
    check(e.snapshot().marked == null)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "開始 ")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

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

    // 6. Formatting shortcut (Ctrl+B) and queued Undo during composition
    e.replace("テキスト", "Fixture")
    e.move(Selection(2, 2))
    imeEvent("に", 0, 1)
    check(e.snapshot().visible.text == "テキにスト")
    check(e.snapshot().marked == 2..2)
    // User triggers Ctrl+B while composing
    key(KeyEvent.VK_B, ctrl = true)
    check(e.snapshot().waiting) { "Shortcut during composition must set waiting flag" }
    // User also presses Ctrl+Z (queued Undo) while composing
    key(KeyEvent.VK_Z, ctrl = true)
    check(e.snapshot().waiting)
    // Native IME commits
    imeEvent("日", 1, null)
    check(!e.snapshot().waiting)
    check(e.snapshot().marked == null)
    // Cleanup with Undo
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

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

    println("PASS Japanese candidate conversion (nihon -> にほん -> 日本), single-group Undo/Redo")
    println("PASS Japanese candidate popup placement (getTextLocation / getLocationOffset monotonic coordinates)")
    println("PASS Japanese multi-clause conversion, caret traversal and partial commit")
    println("PASS Japanese composition Escape cancellation and focus-loss settlement")
    println("PASS Japanese selection replacement with exact selection restoration on Undo")
    println("PASS Japanese queued formatting and Undo during preedit with safe settlement")
    println("PASS Japanese paragraph-boundary composition and cross-paragraph selection replacement")
}
