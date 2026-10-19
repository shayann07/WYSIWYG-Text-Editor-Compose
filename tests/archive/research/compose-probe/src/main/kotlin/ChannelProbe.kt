@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

import androidx.compose.foundation.text.input.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import kotlin.random.Random

// Hypothesis: under append-only style overlays plus text insert/delete, the
// effective value of three expansion policies can be represented by three
// priority channels over characters, without retaining their original ranges.
// A stamp is the insertion order, not the numeric value of a real font weight.
// This prototype uses distinct weights solely to read stamps from Compose.
private val channelPolicies = listOf(ExpandPolicy.AtStart, ExpandPolicy.AtEnd, ExpandPolicy.AtBoth)
private class Channels(length: Int) {
    val lanes = Array(3) { MutableList(length) { 0 } }
    fun mark(policy: Int, start: Int, end: Int, stamp: Int) {
        for (i in start until end) lanes[policy][i] = stamp
    }
    fun insert(at: Int, count: Int) {
        val born = intArrayOf(
            lanes[0].getOrElse(at) { 0 },
            lanes[1].getOrElse(at - 1) { 0 },
            maxOf(lanes[2].getOrElse(at - 1) { 0 }, lanes[2].getOrElse(at) { 0 }),
        )
        for (p in 0..2) lanes[p].addAll(at, List(count) { born[p] })
    }
    fun delete(start: Int, end: Int) { for (p in 0..2) lanes[p].subList(start, end).clear() }
    fun visible() = lanes[0].indices.map { i -> maxOf(lanes[0][i], lanes[1][i], lanes[2][i]) }
}
private fun readStamps(s: TextFieldState): List<Int> {
    val ranges = s.textStyles.getSpanStyles(TextRange(0, s.text.length))
    return s.text.indices.map { i -> ranges.lastOrNull { it.start <= i && i < it.end }?.item?.fontWeight?.weight ?: 0 }
}

fun main() {
    insideOnlyWitness()
    val random = Random(0x13579B)
    var checks = 0
    for (allowReplacement in listOf(false, true)) {
        var failed = false
        scenario@ for (trial in 0 until 200) {
            val initialLength = random.nextInt(1, 17)
            val baseline = TextFieldState("a".repeat(initialLength))
            val channels = Channels(initialLength)
            val trace = mutableListOf("initial length=$initialLength")
            var stamp = 0
            for (step in 0 until 150) {
                val n = baseline.text.length
                val action = random.nextInt(if (allowReplacement) 4 else 3)
                if (action == 0 && n > 0) {
                    val start = random.nextInt(n)
                    val end = random.nextInt(start + 1, n + 1)
                    val p = random.nextInt(3)
                    stamp++
                    baseline.edit { addStyle(SpanStyle(fontWeight = FontWeight(stamp)), TextRange(start, end), channelPolicies[p]) }
                    channels.mark(p, start, end, stamp)
                    trace += "mark p=$p [$start,$end) stamp=$stamp"
                } else if (action == 1 || n == 0) {
                    val at = random.nextInt(n + 1)
                    val count = random.nextInt(1, 4)
                    baseline.edit { insert(at, "x".repeat(count)) }
                    channels.insert(at, count)
                    trace += "insert at=$at count=$count"
                } else {
                    val start = random.nextInt(n)
                    val end = random.nextInt(start + 1, n + 1)
                    val count = if (action == 3) random.nextInt(1, 4) else 0
                    baseline.edit { replace(start, end, "y".repeat(count)) }
                    channels.delete(start, end)
                    if (count > 0) channels.insert(start, count)
                    trace += "replace [$start,$end) count=$count"
                }
                val expected = readStamps(baseline)
                val actual = channels.visible()
                checks++
                if (expected != actual) {
                    println("MISMATCH allowReplacement=$allowReplacement trial=$trial step=$step")
                    println(trace.joinToString("\n"))
                    println("Compose: $expected\nChannels: $actual")
                    failed = true
                    break@scenario
                }
            }
        }
        println("allowReplacement=$allowReplacement matched=${!failed}")
        check(!failed) { "Expansion-channel result diverged from the published Compose baseline" }
    }
    println("Comparisons executed: $checks")
    println("Research probe only: randomized equivalence excludes InsideOnly, arbitrary span-handle removal, and style-range mutation. A separate InsideOnly counterexample is included. No IME or layout coverage.")
}

private fun insideOnlyWitness() {
    fun state(withHiddenSpan: Boolean) = TextFieldState("abc").also { s ->
        s.edit {
            if (withHiddenSpan) addStyle(SpanStyle(fontWeight = FontWeight(1)), TextRange(0, 3), ExpandPolicy.InsideOnly)
            addStyle(SpanStyle(fontWeight = FontWeight(2)), TextRange(0, 2), ExpandPolicy.InsideOnly)
            addStyle(SpanStyle(fontWeight = FontWeight(3)), TextRange(1, 3), ExpandPolicy.InsideOnly)
        }
    }
    val a = state(true)
    val b = state(false)
    check(readStamps(a) == readStamps(b))
    println("InsideOnly before edit: with-hidden=${readStamps(a)} without-hidden=${readStamps(b)}")
    a.edit { delete(1, 2); insert(1, "x") }
    b.edit { delete(1, 2); insert(1, "x") }
    check(readStamps(a) == listOf(2, 1, 3))
    check(readStamps(b) == listOf(2, 0, 3))
    println("InsideOnly delete-middle then insert: with-hidden=${readStamps(a)} without-hidden=${readStamps(b)}")
    println("InsideOnly visible-value compaction is unsound: a fully hidden span becomes the only style for the inserted character.")
}
