package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
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
        val a = random.nextInt(original.length + 1)
        val b = random.nextInt(original.length + 1)
        val selection = Selection(a, b)
        e.move(selection)
        val ref = RefOverlayModel(original, selection.start, selection.end)
        repeat(random.nextInt(1, 7)) {
            val candidate = text()
            val caret = random.nextInt(candidate.length + 1)
            e.inputMethod(candidate, 0, caret)
            ref.inputMethod(candidate, 0)
            check(e.snapshot().committed.text == original) { "iter=$iteration committed='${e.snapshot().committed.text}' orig='$original' notice='${e.snapshot().notice}'" }
            check(e.snapshot().visible.text == ref.visibleText()) { "iter=$iteration vis='${e.snapshot().visible.text}' ref='${ref.visibleText()}' sel=$selection cand='$candidate' caret=$caret notice='${e.snapshot().notice}'" }
            check(e.snapshot().selection.focus == selection.start + caret) { "iter=$iteration focus=${e.snapshot().selection.focus} expected=${selection.start + caret} notice='${e.snapshot().notice}'" }
        }
        val final = text()
        val prefix = random.nextInt(final.length) // Leave at least one provisional code unit.
        e.inputMethod(final, prefix, 0)
        ref.inputMethod(final, prefix)
        val abort = random.nextBoolean()
        if (abort) {
            e.abortComposition()
        } else {
            val dropText = final.drop(prefix)
            e.inputMethod(dropText, dropText.length, null)
            ref.inputMethod(dropText, dropText.length)
            ref.finalCommit()
        }
        val expected = ref.dc
        check(e.snapshot().visible.text == expected) { "Protocol mismatch at $iteration: got '${e.snapshot().visible.text}', expected '$expected'" }
        check(e.snapshot().committed.text == expected && e.snapshot().marked == null)
        val roundtrip = DocumentFiles.decode(DocumentFiles.encode(e.snapshot().committed))
        check(roundtrip.same(e.snapshot().committed))
        if (expected != original) {
            check(e.snapshot().undoCount == 1)
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.same(initial))
            check(e.snapshot().selection == selection)
            e.perform(EditAction.Redo)
            check(e.snapshot().visible.text == expected)
        } else {
            check(e.snapshot().undoCount == 0)
        }
    }
    println("PASS 2000 generated raw-UTF16 composition sessions: detours, partial acceptance, abort/commit, Save/Open, exact Undo/Redo")
    check(Graphemes.stops("🇦🇦\u0301🇦🇦") == listOf(0, 5, 9))
    check(Graphemes.stops("🇦\n🇦🇦") == listOf(0, 2, 3, 7))
    println("PASS regional-indicator parity resets across extending marks and hard breaks")
    repeat(200) {
        val raw = "abcdefghijklmnopqrstuvwxyz"
        var expected = raw.map { TextStyleFlags(random.nextBoolean(), random.nextBoolean()) }
        val initial = Document(raw.mapIndexed { i, c -> TextUnit(i.toLong(), c, style = expected[i]) })
        val e = EditorEngine(initial)
        val history = ArrayList<Pair<List<TextStyleFlags>, Selection>>()
        repeat(60) {
            var a = random.nextInt(raw.length + 1)
            var b = random.nextInt(raw.length + 1)
            if (a == b) {
                a = 0
                b = raw.length
            }
            val s = Selection(a, b)
            val key = FormatKey.entries.random(random)
            val value = !expected.subList(s.start, s.end).all { it.get(key) }
            val next = expected.mapIndexed { index, style -> if (index in s.start until s.end) style.patch(key, value) else style }
            if (next != expected)history += expected to s
            e.move(s)
            e.toggleFormat(key)
            expected = next
            check(e.snapshot().visible.units.map { it.style } == expected)
            check(DocumentFiles.decode(DocumentFiles.encode(e.snapshot().committed)).same(e.snapshot().committed))
        }
        for ((styles, selection) in history.asReversed()) {
            e.perform(EditAction.Undo)
            check(e.snapshot().visible.units.map { it.style } == styles)
            check(e.snapshot().selection == selection)
        }
        check(e.snapshot().visible.same(initial))
    }
    println("PASS 12000 randomized range-format commands against an independent style-array oracle, file roundtrip and exact Undo")
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

private enum class RefOrigin { RETAINED, REPLACEMENT }
private data class RefSeg(val dcStart: Int, val dcEnd: Int, val text: String, val origin: RefOrigin)

private class RefOverlayModel(initialText: String, val selStart: Int, val selEnd: Int) {
    var dc = initialText
    var segments: List<RefSeg> = if (selStart == selEnd) {
        listOf(RefSeg(selStart, selStart, "", RefOrigin.REPLACEMENT))
    } else {
        (selStart until selEnd).map { i ->
            RefSeg(i, i + 1, initialText[i].toString(), RefOrigin.RETAINED)
        }
    }
    var baseSegments: List<RefSeg> = segments

    fun inputMethod(text: String, committedCount: Int) {
        val baselineText = baseSegments.joinToString("") { it.text }
        var p = 0
        while (p < text.length && p < baselineText.length && text[p] == baselineText[p]) p++
        var s = 0
        while (s < text.length - p && s < baselineText.length - p && text[text.length - 1 - s] == baselineText[baselineText.length - 1 - s]) s++

        val newSegs: List<RefSeg>
        if (p == baselineText.length && s == 0 && text.length == baselineText.length) {
            newSegs = baseSegments
        } else {
            val midText = text.substring(p, text.length - s)
            val aMid = p
            val bMid = baselineText.length - s
            val segList = ArrayList<RefSeg>()
            var firstTouched = -1
            var lastTouched = -1
            var leftText = ""
            var rightText = ""

            if (aMid == bMid) {
                var cur = 0
                for (i in baseSegments.indices) {
                    val seg = baseSegments[i]
                    val sStart = cur
                    val sEnd = cur + seg.text.length
                    cur = sEnd
                    if (sStart < aMid && aMid < sEnd) {
                        firstTouched = i
                        lastTouched = i
                        leftText = seg.text.substring(0, aMid - sStart)
                        rightText = seg.text.substring(aMid - sStart)
                        break
                    }
                }
            } else {
                var cur = 0
                for (i in baseSegments.indices) {
                    val seg = baseSegments[i]
                    val sStart = cur
                    val sEnd = cur + seg.text.length
                    cur = sEnd
                    val touches = if (seg.text.isEmpty()) {
                        aMid <= sStart && sStart <= bMid
                    } else {
                        maxOf(sStart, aMid) < minOf(sEnd, bMid)
                    }
                    if (touches) {
                        if (firstTouched == -1) {
                            firstTouched = i
                            val lEnd = (aMid - sStart).coerceIn(0, seg.text.length)
                            leftText = seg.text.substring(0, lEnd)
                        }
                        lastTouched = i
                        val rStart = (bMid - sStart).coerceIn(0, seg.text.length)
                        rightText = seg.text.substring(rStart)
                    }
                }
            }

            if (firstTouched == -1) {
                var insIdx = 0
                var cur = 0
                while (insIdx < baseSegments.size && cur + baseSegments[insIdx].text.length <= aMid) {
                    cur += baseSegments[insIdx].text.length
                    insIdx++
                }
                for (i in 0 until insIdx) segList.add(baseSegments[i])
                val dcPos = if (insIdx < baseSegments.size) {
                    baseSegments[insIdx].dcStart
                } else if (baseSegments.isNotEmpty()) {
                    baseSegments.last().dcEnd
                } else {
                    0
                }
                segList.add(RefSeg(dcPos, dcPos, midText, RefOrigin.REPLACEMENT))
                for (i in insIdx until baseSegments.size) segList.add(baseSegments[i])
            } else {
                for (i in 0 until firstTouched) segList.add(baseSegments[i])
                val dcStartMid = if (leftText.isNotEmpty()) baseSegments[firstTouched].dcEnd else baseSegments[firstTouched].dcStart
                val dcEndMid = baseSegments[lastTouched].dcEnd
                if (leftText.isNotEmpty()) segList.add(RefSeg(baseSegments[firstTouched].dcStart, baseSegments[firstTouched].dcEnd, leftText, baseSegments[firstTouched].origin))
                if (midText.isNotEmpty() || dcStartMid < dcEndMid) segList.add(RefSeg(dcStartMid, dcEndMid, midText, RefOrigin.REPLACEMENT))
                if (rightText.isNotEmpty()) segList.add(RefSeg(baseSegments[lastTouched].dcEnd, baseSegments[lastTouched].dcEnd, rightText, baseSegments[lastTouched].origin))
                for (i in (lastTouched + 1) until baseSegments.size) segList.add(baseSegments[i])
            }

            if (segList.isEmpty()) {
                val fallback = if (firstTouched in baseSegments.indices) {
                    baseSegments[firstTouched].dcStart
                } else if (baseSegments.isNotEmpty()) {
                    baseSegments[0].dcStart
                } else {
                    0
                }
                segList.add(RefSeg(fallback, fallback, "", RefOrigin.REPLACEMENT))
            }
            newSegs = segList
        }

        if (committedCount > 0) {
            val pCut = committedCount
            val sc = newSegs.first().dcStart
            var cur = 0
            val consumed = ArrayList<RefSeg>()
            val remaining = ArrayList<RefSeg>()
            var splitTail: Pair<String, Pair<Int, RefOrigin>>? = null
            var dCut = sc
            val totalUnits = newSegs.sumOf { it.text.length }
            for (seg in newSegs) {
                val segLen = seg.text.length
                val sStart = cur
                val sEnd = cur + segLen
                cur = sEnd
                if (segLen == 0) {
                    val hasRemainingUnitsAfter = pCut < totalUnits
                    if (sStart < pCut || (sStart == pCut && committedCount > 0 && !hasRemainingUnitsAfter)) {
                        consumed.add(seg)
                        dCut = seg.dcEnd
                    } else {
                        remaining.add(seg)
                    }
                    continue
                }
                if (sEnd <= pCut) {
                    consumed.add(seg)
                    dCut = seg.dcEnd
                } else if (sStart < pCut) {
                    val off = pCut - sStart
                    val origSpan = seg.dcEnd - seg.dcStart
                    val consumedSpan = if (seg.origin == RefOrigin.RETAINED) off else origSpan
                    val segCut = seg.dcStart + consumedSpan
                    consumed.add(RefSeg(seg.dcStart, segCut, seg.text.take(off), seg.origin))
                    dCut = segCut
                    val rText = seg.text.drop(off)
                    splitTail = rText to (seg.dcEnd to seg.origin)
                } else {
                    remaining.add(seg)
                }
            }
            val commText = consumed.joinToString("") { it.text }
            val oldDc = dc
            dc = oldDc.take(sc) + commText + oldDc.drop(dCut)
            val delta = commText.length - (dCut - sc)
            val newSc = sc + commText.length
            val finalRem = ArrayList<RefSeg>()
            if (splitTail != null && splitTail.first.isNotEmpty()) {
                finalRem.add(RefSeg(newSc, splitTail.second.first + delta, splitTail.first, splitTail.second.second))
            }
            for (seg in remaining) {
                finalRem.add(RefSeg(seg.dcStart + delta, seg.dcEnd + delta, seg.text, seg.origin))
            }
            if (finalRem.isEmpty()) {
                finalRem.add(RefSeg(newSc, newSc, "", RefOrigin.REPLACEMENT))
            }
            segments = finalRem
            baseSegments = finalRem
        } else {
            segments = newSegs
        }
    }

    fun visibleText(): String {
        val sc = segments.first().dcStart
        val ec = segments.last().dcEnd
        val overlayText = segments.joinToString("") { it.text }
        return dc.take(sc) + overlayText + dc.drop(ec)
    }

    fun finalCommit() {
        val sc = segments.first().dcStart
        val ec = segments.last().dcEnd
        if (ec > sc) {
            dc = dc.take(sc) + dc.drop(ec)
        }
    }
}
