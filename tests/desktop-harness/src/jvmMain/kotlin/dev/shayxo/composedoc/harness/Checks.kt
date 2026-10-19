package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.ViewState
import kotlin.random.Random

fun runChecks() {
    val run = KernelChecksRun()
    run.basicHistoryChecks()
    run.test("partial acceptance with frozen retained/replacement overlay: abcd fixtures") {
        run.checkPartialAcceptancePlainFixtures()
        run.checkPartialAcceptanceAttributedFixtures()
    }
    run.compositionDetourChecks()
    run.structureAndOwnerChecks()
    run.test(
        "settlement watchdog deadline persists across candidate updates, repeated preedits, partial commits, " +
            "and repeated commands",
    ) { run.checkSettlementWatchdog() }
    run.test(
        "ordinary AWT callback retirement, reentrant abort/settlement, stale caret, identical subsequent input, " +
            "partial-commit recovery, and disposal guards",
    ) {
        run.checkRetiredSurfaceCallbacks()
        run.checkReentrantAbortAndSettlement()
        run.checkPartialCommitRecovery()
        run.checkDisposalGuards()
    }
    run.oracleAndUnicodeChecks()
    println("ALL ${run.cases} kernel checks passed. No real OS IME claim is made by these tests.")
}

private class KernelChecksRun {
    var cases = 0
        private set

    fun test(name: String, body: () -> Unit) {
        body()
        cases++
        println("PASS $name")
    }

    private fun seeded(text: String): EditorEngine =
        EditorEngine().apply { replace(text, "Fixture", paragraphBreaks = true) }

    fun basicHistoryChecks() {
        test("typing, replacement, exact selection restoration and redo") {
            val e = seeded("hello")
            e.move(Selection(4, 1))
            e.replace("X", "Replace")
            check(e.snapshot().visible.text == "hXo")
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.text == "hello")
            check(e.snapshot().selection == Selection(4, 1))
            e.perform(EditAction.Redo)
            check(e.snapshot().visible.text == "hXo")
        }
        test("typing coalesces, relocation breaks the group") {
            var time = 1000L
            val e = EditorEngine { time }
            e.replace("a")
            time += 30
            e.replace("b")
            check(e.snapshot().undoCount == 1)
            e.move(Selection(0, 0))
            e.replace("X")
            check(e.snapshot().undoCount == 2)
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.text == "ab")
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.text == "")
        }
        test("composition remains provisional and ends in one undo group") {
            val e = seeded("Hello")
            e.move(Selection(0, 2))
            val count = e.snapshot().undoCount
            e.inputMethod("ni", 0, 2)
            check(e.snapshot().visible.text == "nillo")
            check(e.snapshot().committed.text == "Hello")
            e.inputMethod("nihao", 0, 3)
            e.inputMethod("你好", 2, null)
            check(e.snapshot().visible.text == "你好llo")
            check(e.snapshot().undoCount == count + 1)
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.text == "Hello")
            check(e.snapshot().selection == Selection(0, 2))
        }
        test("partial acceptance survives explicit suffix abort") {
            val e = seeded("Hello")
            e.move(Selection(0, 2))
            e.inputMethod("你hao", 1, 2)
            check(e.snapshot().committed.text == "你llo")
            e.abortComposition()
            check(e.snapshot().visible.text == "你llo")
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.text == "Hello")
        }
    }

    fun checkPartialAcceptancePlainFixtures() {
        // Example 1: select all "abcd", inputMethod("Xcd", 1, 2) + abort -> "Xcd"
        val e1 = seeded("abcd")
        e1.selectAll()
        e1.inputMethod("Xcd", 1, 2)
        check(e1.snapshot().visible.text == "Xcd")
        check(e1.snapshot().committed.text == "Xcd") // retained "cd" remains in dc
        e1.abortComposition()
        check(e1.snapshot().visible.text == "Xcd")
        e1.perform(EditAction.Undo)
        check(e1.snapshot().visible.text == "abcd")

        // Example 2: select all "abcd", inputMethod("abX", 1, 2) + abort -> "abcd"
        val e2 = seeded("abcd")
        e2.selectAll()
        e2.inputMethod("abX", 1, 2)
        check(e2.snapshot().visible.text == "abX")
        check(e2.snapshot().committed.text == "abcd") // retained "bcd" remains in dc
        e2.abortComposition()
        check(e2.snapshot().visible.text == "abcd")
        check(e2.snapshot().undoCount == 1) // no spurious undo step for no-op commit
        e2.perform(EditAction.Undo)
        check(e2.snapshot().visible.text.isEmpty()) // fixture undone
    }

    fun checkPartialAcceptanceAttributedFixtures() {
        // Attributed variant: 'a' bold, 'b' italic, 'c' normal, 'd' bold
        val bold = TextStyleFlags(bold = true)
        val italic = TextStyleFlags(italic = true)
        val styledDoc = Document(
            listOf(
                TextUnit(1, 'a', style = bold),
                TextUnit(2, 'b', style = italic),
                TextUnit(3, 'c', style = TextStyleFlags()),
                TextUnit(4, 'd', style = bold),
            ),
        )
        val e3 = EditorEngine(styledDoc)
        e3.selectAll()
        e3.inputMethod("Xcd", 1, 2)
        e3.abortComposition()
        check(e3.snapshot().visible.text == "Xcd")
        val units3 = e3.snapshot().visible.units
        check(units3[0].style == bold) // 'X' inherits seed from replacement style at start (bold)
        check(units3[1].style == TextStyleFlags()) // 'c' retained
        check(units3[2].style == bold) // 'd' retained
        e3.perform(EditAction.Undo)
        check(e3.snapshot().visible.same(styledDoc))

        // Structural break retention across partial commits
        val paraDoc = Document(
            listOf(
                TextUnit(1, 'P'),
                TextUnit(2, '1'),
                TextUnit(3, '\n', kind = UnitKind.PARAGRAPH_BREAK),
                TextUnit(4, 'P'),
                TextUnit(5, '2'),
            ),
        )
        val e4 = EditorEngine(paraDoc)
        e4.selectAll()
        e4.inputMethod("P1\nQ", 3, 1) // commits "P1\n"
        check(e4.snapshot().committed.text == "P1\nP2") // retained "P2" remains in dc
        check(e4.snapshot().committed.units[2].kind == UnitKind.PARAGRAPH_BREAK)
        e4.abortComposition()
        check(e4.snapshot().visible.text == "P1\nP2")
        check(e4.snapshot().visible.units[2].kind == UnitKind.PARAGRAPH_BREAK)
        check(e4.snapshot().undoCount == 0) // initial document unchanged, no spurious undo entry
        check(e4.snapshot().visible.same(paraDoc))
    }

    fun compositionDetourChecks() {
        test("candidate detour is path independent, no-op commit does not bump document revision") {
            val e = seeded("abcd")
            e.move(Selection(0, 3))
            val count = e.snapshot().undoCount
            e.inputMethod("X", 0, 1)
            e.inputMethod("abc", 0, 2)
            val rev = e.snapshot().documentRevision
            e.inputMethod("abc", 3, null)
            check(e.snapshot().documentRevision == rev)
            check(e.snapshot().undoCount == count)
        }
        test("Undo waits for real terminal input; no timeout settlement") {
            val e = seeded("abcd")
            e.move(Selection(0, 3))
            e.inputMethod("abc", 0, 0)
            e.perform(EditAction.Undo)
            check(e.snapshot().waiting)
            check(e.snapshot().visible.text == "abcd")
            e.inputMethod("X", 1, null)
            check(e.snapshot().visible.text == "abcd")
            check(!e.snapshot().waiting)
        }
        test("cancelled queued edit consumes its own undo credit") {
            val e = seeded("abcd")
            e.move(Selection(0, 3))
            e.inputMethod("XYZ", 0, 3)
            e.replace("paste", "Paste", range = Selection(0, 1))
            e.perform(EditAction.Undo)
            e.inputMethod("Q", 1, null)
            check(e.snapshot().visible.text == "Qd")
        }
        test("accepted prefix plus new composed suffix stays on the native caret") {
            val e = seeded("tail")
            e.move(Selection(0, 0))
            e.inputMethod("你hao", 1, 1)
            check(e.snapshot().visible.text == "你haotail")
            check(e.snapshot().selection.focus == 2)
            check(e.surroundingCommittedText() == "你tail")
            e.inputMethod("好", 1, null)
            check(e.snapshot().visible.text == "你好tail")
        }
    }

    fun structureAndOwnerChecks() {
        test("paragraph tags survive IME no-op and history; native LF is a soft break") {
            val e = seeded("a\nb")
            val before = e.snapshot().visible
            e.selectAll()
            e.inputMethod("a\nb", 0, 3)
            e.inputMethod("a\nb", 3, null)
            check(e.snapshot().visible.same(before))
            e.move(Selection(3, 3))
            e.inputMethod("\n", 1, null)
            check(e.snapshot().visible.units.last().kind == UnitKind.TEXT)
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.same(before))
        }
        test("grapheme deletion handles emoji ZWJ and isolated UTF-16") {
            val e = seeded("A👩‍💻B")
            e.move(Selection(6, 6))
            e.delete(true)
            check(e.snapshot().visible.text == "AB")
            val x = seeded("a\ud800\u0301b")
            x.move(Selection(3, 3))
            x.delete(true)
            check(x.snapshot().visible.text == "a\ud800b")
        }
        test("snapshot immutability and reentrant requests") {
            val e = EditorEngine()
            val old = e.snapshot()
            var once = false
            e.onChange = {
                if (!once) {
                    once = true
                    e.replace("B")
                }
            }
            e.replace("A")
            check(old.visible.text == "")
            check(e.snapshot().visible.text == "AB")
        }
        test("invalid IME range rejects before mutation") {
            val e = seeded("abc")
            val before = e.snapshot()
            check(runCatching { e.inputMethod("X", 2, 0) }.isFailure)
            check(e.snapshot() == before)
        }
        test("Redo refusal during composition stays on owner and isolates observers") {
            val e = seeded("abc")
            e.inputMethod("preedit", 0, 2)
            val before = e.snapshot().visible
            var offOwnerRejected = false
            val thread = Thread { offOwnerRejected = runCatching { e.perform(EditAction.Redo) }.isFailure }
            thread.start()
            thread.join()
            check(offOwnerRejected)
            e.onChange = { error("observer failure") }
            e.perform(EditAction.Redo)
            check(e.snapshot().visible.same(before))
            check(e.snapshot().marked != null)
        }
    }

    fun checkSettlementWatchdog() {
        // Exact RecoveryAudit case 3: candidate update while command is pending must preserve deadline
        var clock = 0L
        val e = EditorEngine(Document(emptyList())) { clock }
        e.inputMethod("a", 0, 1)
        e.toggleFormat(FormatKey.BOLD)
        check(e.snapshot().waiting)
        check(e.settlementRequestedAt == 0L)
        clock = 1000L
        e.inputMethod("ab", 0, 2) // non-terminal candidate update
        check(e.snapshot().waiting) { "Engine must remain waiting while composition is still active" }
        check(e.settlementRequestedAt == 0L) {
            "Non-terminal candidate update must preserve original settlementRequestedAt deadline"
        }
        check(!e.checkSettlementWatchdog(2000L)) { "Watchdog must not time out before 2000ms deadline" }
        // Repeated identical preedit and repeated queued command must also preserve original deadline
        clock = 1500L
        e.inputMethod("ab", 0, 2)
        e.toggleFormat(FormatKey.ITALIC)
        check(e.settlementRequestedAt == 0L)
        // Partial commit with remaining composed text ("a" committed, "b" still composed) preserves deadline
        clock = 1800L
        e.inputMethod("ab", 1, 1)
        check(e.snapshot().committed.text == "a")
        check(e.snapshot().marked == 1..1)
        check(e.snapshot().waiting)
        check(e.settlementRequestedAt == 0L)
        // Advance past deadline -> watchdog times out once, clears pending and deadline, sets settlementTimedOut
        clock = 10000L
        check(e.checkSettlementWatchdog(2000L))
        check(!e.snapshot().waiting)
        check(e.settlementRequestedAt == null)
        check(e.settlementTimedOut)
        // Subsequent watchdog check does not repeatedly fire
        clock = 11000L
        check(!e.checkSettlementWatchdog(2000L))
        // Non-terminal candidate update after timeout keeps settlementTimedOut=true and waiting=false
        e.inputMethod("bc", 0, 2)
        check(e.settlementTimedOut && !e.snapshot().waiting && e.settlementRequestedAt == null)
        // If composition later commits, cancelled commands never execute
        e.inputMethod("bc", 2, null)
        check(e.snapshot().visible.text == "abc")
        check(
            e.snapshot().visible.units.none {
                it.style.bold || it.style.italic
            },
        ) { "Timed-out cancelled commands must not execute on later commit" }
        check(!e.settlementTimedOut && e.settlementRequestedAt == null)

        // Queuing a new command after a timeout while composition is still active starts a fresh deadline
        clock = 20000L
        e.inputMethod("xy", 0, 2)
        e.toggleFormat(FormatKey.BOLD)
        check(e.settlementRequestedAt == 20000L)
        clock = 23000L
        check(e.checkSettlementWatchdog(2000L))
        check(e.settlementTimedOut && !e.snapshot().waiting && e.settlementRequestedAt == null)
        clock = 24000L
        e.toggleFormat(FormatKey.ITALIC)
        check(!e.settlementTimedOut && e.snapshot().waiting && e.settlementRequestedAt == 24000L)
        clock = 26500L
        check(e.checkSettlementWatchdog(2000L))
        e.abortComposition()
        check(e.snapshot().visible.text == "abc")
    }

    private fun dispatchCommit(target: EditorSurface, text: String) {
        val it = if (text.isEmpty()) null else java.text.AttributedString(text).iterator
        target.dispatchEvent(
            java.awt.event.InputMethodEvent(
                target,
                java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                it,
                text.length,
                java.awt.font.TextHitInfo.leading(0),
                null,
            ),
        )
    }

    private fun dispatchIme(target: EditorSurface, text: String, committed: Int, caret: Int?) {
        val it = if (text.isEmpty()) null else java.text.AttributedString(text).iterator
        target.dispatchEvent(
            java.awt.event.InputMethodEvent(
                target,
                java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                it,
                committed,
                caret?.let(java.awt.font.TextHitInfo::leading),
                null,
            ),
        )
    }

    private fun dispatchCaret(target: EditorSurface, caret: Int) {
        target.dispatchEvent(
            java.awt.event.InputMethodEvent(
                target,
                java.awt.event.InputMethodEvent.CARET_POSITION_CHANGED,
                null,
                0,
                java.awt.font.TextHitInfo.leading(caret),
                null,
            ),
        )
    }

    fun checkRetiredSurfaceCallbacks() {
        // 1 & 4 & 5: Ordinary delayed AWT commit after abort + identical text "X" in consecutive legitimate sessions
        val e1 = EditorEngine(Document(emptyList())) { 0L }
        val s1 = EditorSurface(EditorViewModel(e1), ViewState(e1.snapshot()))
        try {
            e1.inputMethod("X", 0, 1)
            s1.abortAndRetireComposition()
            check(s1.retired && s1.inputContext == null)
            // Late ordinary commit on retired s1 must be ignored
            dispatchCommit(s1, "X")
            dispatchIme(s1, "X", 0, 1)
            check(e1.snapshot().committed.text == "") {
                "Late ordinary AWT commit after abort must not mutate document"
            }
            check(e1.snapshot().visible.text == "")
            check(e1.snapshot().undoCount == 0)

            // Valid new input with identical text "X" on replacement surface must succeed
            val s1Next = checkNotNull(s1.replacementSurface)
            check(!s1Next.retired && !s1Next.disposed && s1Next.inputContext != null)
            dispatchIme(s1Next, "X", 0, 1)
            check(e1.snapshot().visible.text == "X" && e1.snapshot().marked == 0..0)
            // Stale caret and text events targeting retired s1 while s1Next is composing must be ignored
            dispatchCaret(s1, 0)
            dispatchCommit(s1, "STALE")
            check(e1.snapshot().visible.text == "X" && e1.snapshot().selection == Selection(1, 1))
            dispatchCommit(s1Next, "X")
            check(e1.snapshot().committed.text == "X" && e1.snapshot().marked == null && e1.snapshot().undoCount == 1)
            // Second consecutive legitimate session with identical text "X"
            dispatchIme(s1Next, "X", 0, 1)
            dispatchCommit(s1Next, "X")
            check(e1.snapshot().committed.text == "XX" && e1.snapshot().undoCount == 2)
            e1.perform(EditAction.Undo)
            check(e1.snapshot().committed.text == "X")
            e1.perform(EditAction.Undo)
            check(e1.snapshot().committed.text == "")
            s1Next.removeNotify()
        } finally {
            s1.removeNotify()
        }
    }

    fun checkReentrantAbortAndSettlement() {
        // 2a: Reentrant ordinary AWT commit during abortAndRetireComposition
        val e2 = EditorEngine(Document(emptyList())) { 0L }
        val s2 = EditorSurface(EditorViewModel(e2), ViewState(e2.snapshot()))
        try {
            e2.inputMethod("Y", 0, 1)
            s2.onEndCompositionRequested = {
                dispatchCommit(s2, "Y")
                dispatchCaret(s2, 0)
            }
            s2.abortAndRetireComposition()
            check(e2.snapshot().committed.text == "") { "Reentrant commit during abort must be rejected" }
            check(e2.snapshot().visible.text == "")
            check(e2.snapshot().marked == null && e2.snapshot().undoCount == 0)
            s2.replacementSurface?.removeNotify()
        } finally {
            s2.removeNotify()
        }

        // 2b: Reentrant ordinary AWT commit during normal settlement request (Ctrl+B / toggleFormat)
        val eNorm = EditorEngine(Document(emptyList())) { 0L }
        val sNorm = EditorSurface(EditorViewModel(eNorm), ViewState(eNorm.snapshot()))
        try {
            dispatchIme(sNorm, "Z", 0, 1)
            sNorm.onEndCompositionRequested = { dispatchCommit(sNorm, "Z") }
            eNorm.toggleFormat(FormatKey.BOLD)
            check(eNorm.snapshot().committed.text == "Z")
            check(eNorm.snapshot().marked == null && !eNorm.snapshot().waiting && eNorm.settlementRequestedAt == null)
            check(eNorm.snapshot().visible.units.single().style.bold)
            check(eNorm.snapshot().undoCount == 2) // Composition + Format
        } finally {
            sNorm.removeNotify()
        }
    }

    fun checkPartialCommitRecovery() {
        // 6: Partial-commit recovery under reentrant and delayed AWT callbacks
        val ePartial = seeded("abcd")
        val sPartial = EditorSurface(EditorViewModel(ePartial), ViewState(ePartial.snapshot()))
        try {
            ePartial.selectAll()
            dispatchIme(sPartial, "XYcd", 1, 3) // 'X' accepted, 'Y' provisional replacement, "cd" retained
            check(ePartial.snapshot().committed.text == "Xcd")
            check(ePartial.snapshot().visible.text == "XYcd")
            sPartial.onEndCompositionRequested = { dispatchCommit(sPartial, "XYcd") }
            sPartial.abortAndRetireComposition()
            dispatchCommit(sPartial, "LATE")
            check(ePartial.snapshot().visible.text == "Xcd")
            check(ePartial.snapshot().committed.text == "Xcd")
            check(ePartial.snapshot().marked == null)
            check(ePartial.snapshot().undoCount == 2) // Fixture + partial-commit session
            ePartial.perform(EditAction.Undo)
            check(ePartial.snapshot().visible.text == "abcd" && ePartial.snapshot().selection == Selection(0, 4))
            ePartial.perform(EditAction.Redo)
            check(ePartial.snapshot().visible.text == "Xcd" && ePartial.snapshot().selection == Selection(1, 1))
            sPartial.replacementSurface?.removeNotify()
        } finally {
            sPartial.removeNotify()
        }
    }

    fun checkDisposalGuards() {
        // 8: Disposal prevents callbacks from mutating document
        val eDisp = seeded("keep")
        val sDisp = EditorSurface(EditorViewModel(eDisp), ViewState(eDisp.snapshot()))
        dispatchIme(sDisp, "temp", 0, 4)
        eDisp.toggleFormat(FormatKey.BOLD)
        check(eDisp.snapshot().waiting)
        sDisp.removeNotify()
        check(sDisp.disposed && sDisp.inputContext == null)
        check(!eDisp.snapshot().waiting && eDisp.settlementRequestedAt == null)
        dispatchCommit(sDisp, "committed-after-dispose")
        dispatchCaret(sDisp, 2)
        sDisp.dispatchEvent(
            java.awt.event.KeyEvent(
                sDisp,
                java.awt.event.KeyEvent.KEY_TYPED,
                0L,
                0,
                java.awt.event.KeyEvent.VK_UNDEFINED,
                'Q',
            ),
        )
        check(eDisp.snapshot().committed.text == "keep") {
            "Callbacks after disposal must not mutate committed document"
        }
    }

    fun oracleAndUnicodeChecks() {
        test("1000 deterministic edits match a flat oracle and unwind exactly") {
            val random = Random(501)
            val e = EditorEngine()
            var expected = ""
            val history = ArrayList<String>()
            repeat(1000) {
                val a = random.nextInt(expected.length + 1)
                val b = random.nextInt(expected.length + 1)
                val replacement = if (random.nextBoolean()) "x" else ""
                val next = expected.replaceRange(minOf(a, b), maxOf(a, b), replacement)
                if (next != expected) history += expected
                e.move(Selection(a, b))
                e.replace(replacement, "Edit")
                expected = next
                check(e.snapshot().visible.text == expected)
            }
            for (before in history.asReversed()) {
                e.perform(EditAction.Undo)
                check(e.snapshot().visible.text == before)
            }
        }
        test("official Unicode 16 GraphemeBreakTest: 1093 fixtures") {
            var count = 0
            for (line in Graphemes.verified("GraphemeBreakTest-16.0.0.txt").lineSequence()) {
                val body = line.substringBefore('#').trim()
                if (body.isEmpty()) continue
                val text = StringBuilder()
                val expected = ArrayList<Int>()
                for (token in body.split(Regex("\\s+"))) {
                    when (token) {
                        "÷" -> expected += text.length
                        "×" -> {}
                        else -> text.appendCodePoint(token.toInt(16))
                    }
                }
                check(Graphemes.stops(text.toString()) == expected) { "Unicode fixture failed: $line" }
                count++
            }
            check(count == 1093)
        }
    }
}
