@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

import androidx.compose.foundation.text.input.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import kotlin.random.Random

// For every [s,e) of length >= 2, add one independent Boolean attribute.
// Add longer intervals before shorter ones. A strict enclosing interval then
// always has lower priority. All intervals use InsideOnly.
private data class HiddenInterval(val start: Int, val end: Int)
private val zeroStyle = SpanStyle(fontWeight = FontWeight(100))
private val oneStyle = SpanStyle(fontWeight = FontWeight(900))
private fun intervals(n: Int): List<HiddenInterval> = buildList {
    for (length in n downTo 2) for (start in 0..n - length) add(HiddenInterval(start, start + length))
}
private fun encodedState(n: Int, intervals: List<HiddenInterval>, bits: List<Boolean>) = TextFieldState("a".repeat(n)).also { s ->
    s.edit {
        intervals.forEachIndexed { i, r ->
            addStyle(if (bits[i]) oneStyle else zeroStyle, TextRange(r.start, r.end), ExpandPolicy.InsideOnly)
        }
    }
}
private fun valueAt(s: TextFieldState, p: Int): Boolean =
    s.textStyles.getSpanStyles(TextRange(p, p + 1)).lastOrNull()?.item?.fontWeight == FontWeight(900)

private fun expose(s: TextFieldState, r: HiddenInterval): Boolean {
    s.edit {
        // Preserve the first and last original character of the chosen interval.
        // A surviving span covers the new x iff it originally covered both.
        delete(r.start + 1, r.end - 1)
        insert(r.start + 1, "x")
    }
    return valueAt(s, r.start + 1)
}

fun main() {
    val random = Random(0x73B419)
    var queries = 0
    for (n in listOf(2, 3, 4, 8, 12, 16, 24)) {
        val ranges = intervals(n)
        val patterns = buildList<List<Boolean>> {
            add(List(ranges.size) { false })
            add(List(ranges.size) { true })
            add(List(ranges.size) { it % 2 == 0 })
            repeat(13) { add(List(ranges.size) { random.nextBoolean() }) }
        }
        for (bits in patterns) for ((i, r) in ranges.withIndex()) {
            check(expose(encodedState(n, ranges, bits), r) == bits[i]) {
                "Failed to recover independent interval bit n=$n range=$r"
            }
            queries++
        }

        // Fix the entire set of length-two intervals to zero. Longer intervals
        // are still independently recoverable, but none affects current display.
        val hiddenBits = ranges.map { r -> r.end - r.start > 2 && random.nextBoolean() }
        val original = encodedState(n, ranges, hiddenBits)
        check((0 until n).all { !valueAt(original, it) })
        var recoveredOnes = 0
        for ((i, r) in ranges.withIndex()) {
            val result = expose(encodedState(n, ranges, hiddenBits), r)
            check(result == hiddenBits[i])
            if (result) recoveredOnes++
            queries++
        }
        println("n=$n independently recoverable bits=${ranges.size} invisible independent bits=${(n - 1) * (n - 2) / 2} recovered hidden ones=$recoveredOnes")
    }
    println("Published Compose queries passed: $queries")
    println("This witnesses a quadratic worst-case information requirement for preserving these span semantics. It is not a corrective algorithm or a benchmark.")
}
