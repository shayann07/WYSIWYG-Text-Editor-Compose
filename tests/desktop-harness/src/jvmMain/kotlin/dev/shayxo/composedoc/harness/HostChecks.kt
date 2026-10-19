package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.font.TextHitInfo
import java.io.File
import java.text.AttributedCharacterIterator
import java.text.AttributedString

private fun string(iterator: AttributedCharacterIterator): String = buildString {
    for (index in iterator.beginIndex until
        iterator.endIndex) {
        append(iterator.setIndex(index))
    }
}

/** Actual attached host + real Compose layout, but injected AWT IME events, not an OS IME. */
fun runHostChecks(surface: EditorSurface, resultFile: File = File("apps/desktop-workspace/target/host-checks.json")) {
    runAccessibilityHostChecks(surface)
    val e = surface.viewModel.verificationEngine
    check((surface.parent as javax.swing.JLayeredPane).getLayer(surface) == 100)
    check(surface.parent.findComponentAt(200, 100) === surface) {
        "Pointer events must reach the input host, not the render layer"
    }
    var baselineImeCallbacks = 0
    fun event(text: String, count: Int, caret: Int?) {
        baselineImeCallbacks++
        surface.dispatchEvent(
            InputMethodEvent(
                surface,
                InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                AttributedString(text).iterator,
                count,
                caret?.let(TextHitInfo::leading),
                null,
            ),
        )
    }
    e.replace("Hello", "Fixture")
    e.move(Selection(1, 2))
    event("ni", 0, 2)
    check(e.snapshot().visible.text == "Hnillo")
    check(e.snapshot().committed.text == "Hello")
    var queries = 0
    val context = "Hllo"
    for (a in 0..context.length) {
        for (b in a..context.length) {
            check(string(surface.getCommittedText(a, b, null)) == context.substring(a, b))
            queries++
        }
    }
    check(surface.committedTextLength == 4)
    check(surface.insertPositionOffset == 1)
    val start = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    val alias = checkNotNull(surface.getTextLocation(TextHitInfo.beforeOffset(0)))
    check(start.width == 0 && start.height > 0)
    check(start.x == alias.x && start.y == alias.y)
    event("你hao", 1, 1)
    check(e.snapshot().committed.text == "H你llo")
    check(e.snapshot().selection.focus == 3)
    check(string(surface.getCommittedText(0, surface.committedTextLength, null)) == "H你llo")
    event("好", 1, null)
    check(e.snapshot().visible.text == "H你好llo")
    check(e.snapshot().marked == null)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "Hello")
    check(e.snapshot().selection == Selection(1, 2))
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    e.replace("abc😀z", "Fixture")
    e.move(Selection(3, 5))
    event("😀", 0, 1)
    check(e.snapshot().selection.focus == 4) // Raw native caret inside a valid surrogate pair.
    surface.dispatchEvent(
        KeyEvent(
            surface,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_LEFT,
            KeyEvent.CHAR_UNDEFINED,
        ),
    )
    event("😀", 2, null)
    check(e.snapshot().selection.focus == 3) { "User Left should snap to the emoji start, not the document start" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    event("A\uffffZ", 3, null)
    check(e.snapshot().visible.text == "A\uffffZ") { "U+FFFF is text, not the end of an iterator" }
    check(string(surface.getCommittedText(0, 3, null)) == "A\uffffZ")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    val beforeInvalid = e.snapshot()
    event("abc", 0, 99)
    check(e.snapshot() == beforeInvalid) { "Invalid native caret must reject atomically, not silently clamp" }
    e.replace("abc", "Fixture")
    e.move(Selection(1, 1))
    surface.dispatchEvent(
        KeyEvent(
            surface,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.ALT_GRAPH_DOWN_MASK,
            KeyEvent.VK_A,
            'a',
        ),
    )
    check(e.snapshot().selection == Selection(1, 1)) { "AltGr+A must not run Select All" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    e.replace("Styled", "Fixture")
    e.selectAll()
    fun shortcut(key: Int) {
        surface.dispatchEvent(
            KeyEvent(
                surface,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                KeyEvent.CTRL_DOWN_MASK,
                key,
                KeyEvent.CHAR_UNDEFINED,
            ),
        )
    }
    shortcut(KeyEvent.VK_B)
    shortcut(KeyEvent.VK_I)
    check(e.snapshot().visible.units.all { it.style == TextStyleFlags(true, true) })
    val styledLayout = checkNotNull(surface.view.measure).invoke(e.snapshot()).layout
    check(
        styledLayout.layoutInput.text.spanStyles.single().item.fontWeight ==
            androidx.compose.ui.text.font.FontWeight.Bold,
    )
    check(
        styledLayout.layoutInput.text.spanStyles.single().item.fontStyle ==
            androidx.compose.ui.text.font.FontStyle.Italic,
    )
    check(checkNotNull(surface.getTextLocation(null)).height > 0)
    shortcut(KeyEvent.VK_Z)
    check(e.snapshot().visible.units.all { it.style == TextStyleFlags(bold = true) })
    shortcut(KeyEvent.VK_Z)
    check(e.snapshot().visible.units.all { it.style == TextStyleFlags() })
    shortcut(KeyEvent.VK_Z)
    check(e.snapshot().visible.text.isEmpty())
    runRtlHostChecks(surface)
    val finalSurface = runJapaneseHostChecks(surface)
    val report = evidenceJson(
        mapOf(
            "status" to "PASS", "committedSliceQueries" to queries,
            "baselineImeCallbacksExecuted" to baselineImeCallbacks,
            "mainHostImeTextCallbacksObserved" to finalSurface.observedImeTextCallbacks,
            "mainHostTypedKeyCallbacksObserved" to finalSurface.observedTypedKeyCallbacks,
            "rawFFFF" to "PASS", "invalidCaretRejected" to "PASS", "altGrShortcutIsolation" to "PASS",
            "formatShortcutsLayoutUndo" to "PASS", "accessibleEditing" to "AUTOMATED_API_PASS_OS_AT_NOT_RUN",
            "urduArabicRtl" to "PASS", "japaneseImeHost" to "PASS", "paragraphLayout" to "PASS",
            "hostReplacementLifecycle100x" to "PASS", "hostReplacementFailurePaths" to "PASS",
            "scope" to
                "Attached AWT host and real Compose layout; synthetic IME stream, not OS IME qualification. Observed counters cover the main host/replacement chain, not separate temporary test hosts.",
        ),
    )
    resultFile.apply {
        parentFile.mkdirs()
        writeText(report)
    }
    println(report)
    finalSurface.writeDiagnostics()
}
