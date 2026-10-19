package dev.shayxo.composedoc.layout

import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.paragraphs

/** Derived planning only; whole-paragraph shaping survives every fragment. */
class PageCursor(
    private val height: Float,
    private val margin: Float,
    private val header: Float = 0f,
    private val footer: Float = 0f,
) {
    data class Slot(val page: Int, val y: Float)
    var page = 0
        private set
    private val top get() = margin + header
    private val bottom get() = height - margin - footer
    private var y = top
    val capacity get() = bottom - top
    val remaining get() = bottom - y
    val atTop get() = kotlin.math.abs(y - top) < .01f
    init {
        require(capacity > 0) { "Running content and margins leave no body area" }
    }
    fun newPage(force: Boolean = false) {
        if (force || !atTop) {
            page++
            y = top
        }
    }
    fun space(amount: Float) {
        require(amount.isFinite() && amount >= 0)
        y = (y + amount).coerceAtMost(bottom)
    }
    fun place(lineHeight: Float): Slot {
        require(lineHeight.isFinite() && lineHeight > 0 && lineHeight <= capacity + .01f) {
            "A line/image exceeds the body area; reduce running content, font size or margins"
        }
        if (y + lineHeight > bottom + .01f)newPage()
        return Slot(page, y).also { y += lineHeight }
    }
}
data class ParagraphFragment(
    val paragraphIndex: Int,
    val firstLine: Int,
    val lastLine: Int,
    val sourceTop: Float,
    val sourceBottom: Float,
    val top: Float,
    val page: Int,
) {
    val height get() = sourceBottom - sourceTop
}
data class FragmentPlan(
    val fragments: List<ParagraphFragment>,
    val pages: Int,
    val totalHeight: Float,
    val relaxations: List<String> = emptyList(),
)

/** Already-shaped source line boxes. Pagination never remeasures or reshapes their text. */
data class PageParagraphMetrics(val index: Int, val lineTops: List<Float>, val height: Float) {
    val lineCount get() = lineTops.size
    init {
        require(
            lineTops.isNotEmpty() &&
                height.isFinite() &&
                height > 0 &&
                lineTops.all { it.isFinite() } &&
                lineTops.zipWithNext().all { (a, b) -> b > a } &&
                lineTops.last() < height,
        )
    }
}

fun pageFragments(
    paragraphs: List<ParagraphLayout>,
    pageHeight: Float,
    margin: Float,
    gap: Float,
    properties: List<ParagraphProperties> = emptyList(),
    density: Float = 1f,
    explicitBefore: Set<Int> = emptySet(),
    header: Float = 0f,
    footer: Float = 0f,
): FragmentPlan = planPageFragments(
    paragraphs.map { p ->
        PageParagraphMetrics(p.index, List(p.lineCount) { p.layout.getLineTop(it) }, p.height)
    },
    pageHeight, margin, gap, properties, density, explicitBefore, header, footer,
)

fun planPageFragments(
    paragraphs: List<PageParagraphMetrics>,
    pageHeight: Float,
    margin: Float,
    gap: Float,
    properties: List<ParagraphProperties> = emptyList(),
    density: Float = 1f,
    explicitBefore: Set<Int> = emptySet(),
    header: Float = 0f,
    footer: Float = 0f,
): FragmentPlan {
    val cursor = PageCursor(pageHeight, margin, header, footer)
    val lines = ArrayList<ParagraphFragment>()
    val relaxed = ArrayList<String>()
    fun props(p: PageParagraphMetrics) = properties.getOrNull(p.index) ?: ParagraphProperties()
    fun heights(p: PageParagraphMetrics) = List(p.lineCount) { line ->
        (
            if (line + 1 <
                p.lineCount
            ) {
                p.lineTops[line + 1]
            } else {
                p.height
            }
            ) -
            p.lineTops[line]
    }
    for ((at, p) in paragraphs.withIndex()) {
        val v = props(p)
        val r = v.pagination
        val hs = heights(p)
        require(
            hs.all {
                it > 0 && it.isFinite() && it <= cursor.capacity + .01f
            },
        ) { "Paragraph${p.index + 1} has an indivisible line/image larger than the body area" }
        if (p.index in explicitBefore) {
            cursor.newPage(force = p.index > 0)
        } else if (r.breakBefore) {
            cursor.newPage()
        }
        if (r.keepWithNext && at + 1 < paragraphs.size) {
            var group = hs.sum() + v.spaceBefore * density + v.spaceAfter * density
            var end = at + 1
            var bounded = 0
            while (end < paragraphs.size && bounded++ < 32) {
                val next = paragraphs[end]
                val nv = props(next)
                if (next.index in explicitBefore || nv.pagination.breakBefore)break
                val nh = heights(next)
                group += nv.spaceBefore * density
                if (nv.pagination.keepWithNext) {
                    group += nh.sum() + nv.spaceAfter * density
                } else {
                    // Budget a fragment the following paragraph can actually keep on this page.
                    // A keep-lines paragraph, or one too short to satisfy both split minima,
                    // otherwise moves in its own pass and strands the preceding heading.
                    val indivisible = nv.pagination.keepLines || nh.size < nv.pagination.orphans + nv.pagination.widows
                    group += if (indivisible)nh.sum() else nh.take(nv.pagination.orphans).sum()
                    break
                }
                end++
            }
            if (group <= cursor.capacity && group > cursor.remaining) {
                cursor.newPage()
            } else if (group > cursor.capacity || bounded >= 32) {
                relaxed += "Paragraph${p.index + 1}: keep chain relaxed (capacity/32-paragraph bound)"
            }
        }
        if (r.keepLines &&
            hs.sum() + v.spaceBefore * density <= cursor.capacity &&
            hs.sum() + v.spaceBefore * density > cursor.remaining
        ) {
            cursor.newPage()
        } else if (r.keepLines && hs.sum() + v.spaceBefore * density > cursor.capacity) {
            relaxed += "Paragraph${p.index + 1}: keep-lines relaxed for oversized paragraph"
        }
        if (v.spaceBefore * density + hs.first() > cursor.remaining && !cursor.atTop)cursor.newPage()
        val before = (v.spaceBefore * density).coerceAtMost((cursor.remaining - hs.first()).coerceAtLeast(0f))
        if (before <
            v.spaceBefore * density
        ) {
            relaxed +=
                "Paragraph${p.index + 1}: excessive space-before constrained to preserve first line"
        }
        cursor.space(before)
        var line = 0
        while (line < hs.size) {
            var count = 0
            var used = 0f
            while (line + count < hs.size && used + hs[line + count] <= cursor.remaining + .01f) {
                used += hs[line + count]
                count++
            }
            if (count == 0) {
                cursor.newPage()
                continue
            }
            if (line == 0 && count < hs.size && count < r.orphans && !cursor.atTop) {
                cursor.newPage()
                continue
            }
            val tail = hs.size - line - count
            if (tail in 1 until r.widows) {
                val adjusted = count - (r.widows - tail)
                val minimum = if (line == 0)r.orphans else 1
                if (adjusted >= minimum) {
                    count = adjusted
                } else if (!cursor.atTop) {
                    cursor.newPage()
                    continue
                } else {
                    relaxed +=
                        "Paragraph${p.index + 1}: widow/orphan request cannot fit; forward progress takes priority"
                }
            }
            if (line == 0 &&
                count < hs.size &&
                count < r.orphans
            ) {
                relaxed += "Paragraph${p.index + 1}: orphan request relaxed for available body area"
            }
            repeat(count) {
                val top = p.lineTops[line]
                val slot = cursor.place(hs[line])
                lines +=
                    ParagraphFragment(
                        p.index,
                        line,
                        line,
                        top,
                        top + hs[line],
                        slot.page * (pageHeight + gap) + slot.y - margin,
                        slot.page,
                    )
                line++
            }
            if (line < hs.size)cursor.newPage()
        }
        cursor.space(v.spaceAfter * density)
    }
    val fragments = ArrayList<ParagraphFragment>()
    for (line in lines) {
        val prior = fragments.lastOrNull()
        if (prior != null &&
            prior.paragraphIndex == line.paragraphIndex &&
            prior.page == line.page &&
            prior.lastLine + 1 == line.firstLine &&
            kotlin.math.abs(prior.sourceBottom - line.sourceTop) < .01f &&
            kotlin.math.abs(prior.top + prior.height - line.top) < .01f
        ) {
            fragments[fragments.lastIndex] = prior.copy(lastLine = line.lastLine, sourceBottom = line.sourceBottom)
        } else {
            fragments += line
        }
    }
    val pages = cursor.page + 1
    return FragmentPlan(
        fragments.toList(),
        pages,
        pages * pageHeight + (pages - 1) * gap - 2 * margin,
        relaxed.distinct(),
    )
}
