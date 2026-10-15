package editor

import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Deterministic protocol stress, separate from real OS IME qualification. */
fun runStressChecks() {
    val random = Random(516)
    val alphabet = listOf("a", "b", "😀", "👩‍💻", "\ud800", "\uffff", "\n", "\u0301", "你", "م")
    fun text() = (0 until random.nextInt(1, 12)).joinToString("") { alphabet.random(random) }
    repeat(2000) { iteration ->
        val original = text()
        val initial = Document(original.mapIndexed { i, c -> TextUnit(i.toLong(), c) })
        val e = EditorEngine(initial)
        val a = random.nextInt(original.length + 1); val b = random.nextInt(original.length + 1)
        val selection = Selection(a, b); e.move(selection)
        repeat(random.nextInt(1, 7)) {
            val candidate = text(); val caret = random.nextInt(candidate.length + 1)
            e.inputMethod(candidate, 0, caret)
            check(e.snapshot().committed.text == original)
            check(e.snapshot().visible.text == original.replaceRange(selection.start, selection.end, candidate))
            check(e.snapshot().selection.focus == selection.start + caret)
        }
        val final = text()
        val prefix = random.nextInt(final.length) // Leave at least one provisional code unit.
        e.inputMethod(final, prefix, 0)
        val abort = random.nextBoolean()
        val expected = if (abort && prefix == 0) original else original.replaceRange(selection.start, selection.end, if (abort) final.take(prefix) else final)
        if (abort) e.abortComposition() else e.inputMethod(final.drop(prefix), final.length - prefix, null)
        check(e.snapshot().visible.text == expected) { "Protocol mismatch at $iteration" }
        check(e.snapshot().committed.text == expected && e.snapshot().marked == null)
        val roundtrip = DocumentFiles.decode(DocumentFiles.encode(e.snapshot().committed))
        check(roundtrip.same(e.snapshot().committed))
        if (expected != original) {
            check(e.snapshot().undoCount == 1)
            e.perform(EditAction.Undo); check(e.snapshot().visible.same(initial)); check(e.snapshot().selection == selection)
            e.perform(EditAction.Redo); check(e.snapshot().visible.text == expected)
        } else check(e.snapshot().undoCount == 0)
    }
    println("PASS 2000 generated raw-UTF16 composition sessions: detours, partial acceptance, abort/commit, Save/Open, exact Undo/Redo")
    check(Graphemes.stops("🇦🇦\u0301🇦🇦") == listOf(0, 5, 9))
    check(Graphemes.stops("🇦\n🇦🇦") == listOf(0, 2, 3, 7))
    println("PASS regional-indicator parity resets across extending marks and hard breaks")
    // Timings are observations, not flaky pass/fail gates. This deliberately stresses GB12/13.
    Graphemes.stops("warm up")
    for (count in listOf(2_000, 8_000, 32_000)) {
        val text = "🇦".repeat(count)
        var stops: List<Int> = emptyList()
        val ms = measureNanoTime { stops = Graphemes.stops(text) } / 1_000_000.0
        check(stops == (0..text.length step 4).toList())
        println("Regional indicators: $count scalars / ${text.length} UTF-16 units; ${"%.3f".format(java.util.Locale.ROOT, ms)} ms")
    }
}
