package editor

import kotlin.random.Random

fun runChecks() {
    var cases = 0
    fun test(name: String, body: () -> Unit) { body(); cases++; println("PASS $name") }
    fun seeded(text: String): EditorEngine = EditorEngine().apply { replace(text, "Fixture", paragraphBreaks = true) }
    test("typing, replacement, exact selection restoration and redo") {
        val e = seeded("hello"); e.move(Selection(4, 1)); e.replace("X", "Replace")
        check(e.snapshot().visible.text == "hXo")
        e.perform(EditAction.Undo); check(e.snapshot().visible.text == "hello"); check(e.snapshot().selection == Selection(4, 1))
        e.perform(EditAction.Redo); check(e.snapshot().visible.text == "hXo")
    }
    test("typing coalesces, relocation breaks the group") {
        var time = 1000L; val e = EditorEngine { time }; e.replace("a"); time += 30; e.replace("b"); check(e.snapshot().undoCount == 1)
        e.move(Selection(0, 0)); e.replace("X"); check(e.snapshot().undoCount == 2)
        e.perform(EditAction.Undo); check(e.snapshot().visible.text == "ab"); e.perform(EditAction.Undo); check(e.snapshot().visible.text == "")
    }
    test("composition remains provisional and ends in one undo group") {
        val e = seeded("Hello"); e.move(Selection(0, 2)); val count = e.snapshot().undoCount
        e.inputMethod("ni", 0, 2); check(e.snapshot().visible.text == "nillo"); check(e.snapshot().committed.text == "Hello")
        e.inputMethod("nihao", 0, 3); e.inputMethod("你好", 2, null)
        check(e.snapshot().visible.text == "你好llo"); check(e.snapshot().undoCount == count + 1)
        e.perform(EditAction.Undo); check(e.snapshot().visible.text == "Hello"); check(e.snapshot().selection == Selection(0, 2))
    }
    test("partial acceptance survives explicit suffix abort") {
        val e = seeded("Hello"); e.move(Selection(0, 2)); e.inputMethod("你hao", 1, 2)
        check(e.snapshot().committed.text == "你llo"); e.abortComposition(); check(e.snapshot().visible.text == "你llo")
        e.perform(EditAction.Undo); check(e.snapshot().visible.text == "Hello")
    }
    test("candidate detour is path independent, no-op commit does not bump document revision") {
        val e = seeded("abcd"); e.move(Selection(0, 3)); val count = e.snapshot().undoCount
        e.inputMethod("X", 0, 1); e.inputMethod("abc", 0, 2); val rev = e.snapshot().documentRevision
        e.inputMethod("abc", 3, null); check(e.snapshot().documentRevision == rev); check(e.snapshot().undoCount == count)
    }
    test("Undo waits for real terminal input; no timeout settlement") {
        val e = seeded("abcd"); e.move(Selection(0, 3)); e.inputMethod("abc", 0, 0); e.perform(EditAction.Undo)
        check(e.snapshot().waiting); check(e.snapshot().visible.text == "abcd")
        e.inputMethod("X", 1, null); check(e.snapshot().visible.text == "abcd"); check(!e.snapshot().waiting)
    }
    test("cancelled queued edit consumes its own undo credit") {
        val e = seeded("abcd"); e.move(Selection(0, 3)); e.inputMethod("XYZ", 0, 3)
        e.replace("paste", "Paste", range = Selection(0, 1)); e.perform(EditAction.Undo)
        e.inputMethod("Q", 1, null); check(e.snapshot().visible.text == "Qd")
    }
    test("accepted prefix plus new composed suffix stays on the native caret") {
        val e = seeded("tail"); e.move(Selection(0, 0)); e.inputMethod("你hao", 1, 1)
        check(e.snapshot().visible.text == "你haotail"); check(e.snapshot().selection.focus == 2); check(e.surroundingCommittedText() == "你tail")
        e.inputMethod("好", 1, null); check(e.snapshot().visible.text == "你好tail")
    }
    test("paragraph tags survive IME no-op and history; native LF is a soft break") {
        val e = seeded("a\nb"); val before = e.snapshot().visible
        e.selectAll(); e.inputMethod("a\nb", 0, 3); e.inputMethod("a\nb", 3, null); check(e.snapshot().visible.same(before))
        e.move(Selection(3, 3)); e.inputMethod("\n", 1, null); check(e.snapshot().visible.units.last().kind == UnitKind.TEXT)
        e.perform(EditAction.Undo); check(e.snapshot().visible.same(before))
    }
    test("grapheme deletion handles emoji ZWJ and isolated UTF-16") {
        val e = seeded("A👩‍💻B"); e.move(Selection(6, 6)); e.delete(true); check(e.snapshot().visible.text == "AB")
        val x = seeded("a\ud800\u0301b"); x.move(Selection(3, 3)); x.delete(true); check(x.snapshot().visible.text == "a\ud800b")
    }
    test("snapshot immutability and reentrant requests") {
        val e = EditorEngine(); val old = e.snapshot(); var once = false
        e.onChange = { if (!once) { once = true; e.replace("B") } }; e.replace("A")
        check(old.visible.text == ""); check(e.snapshot().visible.text == "AB")
    }
    test("invalid IME range rejects before mutation") {
        val e = seeded("abc"); val before = e.snapshot(); check(runCatching { e.inputMethod("X", 2, 0) }.isFailure); check(e.snapshot() == before)
    }
    test("1000 deterministic edits match a flat oracle and unwind exactly") {
        val random = Random(501); val e = EditorEngine(); var expected = ""; val history = ArrayList<String>()
        repeat(1000) {
            val a = random.nextInt(expected.length + 1); val b = random.nextInt(expected.length + 1)
            val replacement = if (random.nextBoolean()) "x" else ""
            val next = expected.replaceRange(minOf(a, b), maxOf(a, b), replacement)
            if (next != expected) history += expected
            e.move(Selection(a, b)); e.replace(replacement, "Edit"); expected = next
            check(e.snapshot().visible.text == expected)
        }
        for (before in history.asReversed()) { e.perform(EditAction.Undo); check(e.snapshot().visible.text == before) }
    }
    test("official Unicode 16 GraphemeBreakTest: 1093 fixtures") {
        var count = 0
        for (line in Graphemes.verified("GraphemeBreakTest-16.0.0.txt").lineSequence()) {
            val body = line.substringBefore('#').trim(); if (body.isEmpty()) continue
            val text = StringBuilder(); val expected = ArrayList<Int>()
            for (token in body.split(Regex("\\s+"))) when (token) { "÷" -> expected += text.length; "×" -> {}; else -> text.appendCodePoint(token.toInt(16)) }
            check(Graphemes.stops(text.toString()) == expected) { "Unicode fixture failed: $line" }; count++
        }
        check(count == 1093)
    }
    println("ALL $cases kernel checks passed. No real OS IME claim is made by these tests.")
}
