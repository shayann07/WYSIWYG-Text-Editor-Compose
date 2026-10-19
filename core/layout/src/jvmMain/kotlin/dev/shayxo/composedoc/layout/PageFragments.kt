package dev.shayxo.composedoc.layout

import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.paragraphs

/** Tolerance for comparing page positions and line heights. */
private const val LAYOUT_EPSILON = .01f

/** Maximum number of following paragraphs a keep-with-next chain may pull along. */
private const val KEEP_CHAIN_BOUND = 32

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
    val atTop get() = kotlin.math.abs(y - top) < LAYOUT_EPSILON
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
        require(lineHeight.isFinite() && lineHeight > 0 && lineHeight <= capacity + LAYOUT_EPSILON) {
            "A line/image exceeds the body area; reduce running content, font size or margins"
        }
        if (y + lineHeight > bottom + LAYOUT_EPSILON)newPage()
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

// Reason: public API (see apiCheck); grouping these parameters would change the signature.
@Suppress("LongParameterList")
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

// Reason: public API (see apiCheck); grouping these parameters would change the signature.
@Suppress("LongParameterList")
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
    val planner = PageFragmentPlanner(paragraphs, properties, density, explicitBefore, cursor) { slot ->
        slot.page * (pageHeight + gap) + slot.y - margin
    }
    for ((at, p) in paragraphs.withIndex()) {
        planner.planParagraph(at, p)
    }
    val fragments = ArrayList<ParagraphFragment>()
    for (line in planner.lines) {
        val prior = fragments.lastOrNull()
        if (prior != null && prior.continuesWith(line)) {
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
        planner.relaxed.distinct(),
    )
}

private fun ParagraphFragment.continuesWith(line: ParagraphFragment): Boolean = paragraphIndex == line.paragraphIndex &&
    page == line.page &&
    lastLine + 1 == line.firstLine &&
    kotlin.math.abs(sourceBottom - line.sourceTop) < LAYOUT_EPSILON &&
    kotlin.math.abs(top + height - line.top) < LAYOUT_EPSILON

private fun isOrphanedStart(line: Int, count: Int, lineCount: Int, orphans: Int): Boolean =
    line == 0 && count < lineCount && count < orphans

/** Places one paragraph at a time onto [cursor], recording line fragments and relaxed requests. */
private class PageFragmentPlanner(
    private val paragraphs: List<PageParagraphMetrics>,
    private val properties: List<ParagraphProperties>,
    private val density: Float,
    private val explicitBefore: Set<Int>,
    private val cursor: PageCursor,
    private val slotTop: (PageCursor.Slot) -> Float,
) {
    val lines = ArrayList<ParagraphFragment>()
    val relaxed = ArrayList<String>()

    private fun props(p: PageParagraphMetrics) = properties.getOrNull(p.index) ?: ParagraphProperties()
    private fun heights(p: PageParagraphMetrics) = List(p.lineCount) { line ->
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

    fun planParagraph(at: Int, p: PageParagraphMetrics) {
        val v = props(p)
        val r = v.pagination
        val hs = heights(p)
        require(
            hs.all {
                it > 0 && it.isFinite() && it <= cursor.capacity + LAYOUT_EPSILON
            },
        ) { "Paragraph${p.index + 1} has an indivisible line/image larger than the body area" }
        if (p.index in explicitBefore) {
            cursor.newPage(force = p.index > 0)
        } else if (r.breakBefore) {
            cursor.newPage()
        }
        if (r.keepWithNext && at + 1 < paragraphs.size) {
            keepWithFollowing(at, p, v, hs)
        }
        keepLinesTogether(p, v, hs)
        placeSpaceBefore(p, v, hs)
        var line = 0
        while (line < hs.size) {
            val count = chunkCount(p, hs, line, r)
            if (count != null) {
                line = placeLines(p, hs, line, count)
                if (line < hs.size)cursor.newPage()
            }
        }
        cursor.space(v.spaceAfter * density)
    }

    private fun keepLinesTogether(p: PageParagraphMetrics, v: ParagraphProperties, hs: List<Float>) {
        val r = v.pagination
        if (r.keepLines &&
            hs.sum() + v.spaceBefore * density <= cursor.capacity &&
            hs.sum() + v.spaceBefore * density > cursor.remaining
        ) {
            cursor.newPage()
        } else if (r.keepLines && hs.sum() + v.spaceBefore * density > cursor.capacity) {
            relaxed += "Paragraph${p.index + 1}: keep-lines relaxed for oversized paragraph"
        }
    }

    private fun placeSpaceBefore(p: PageParagraphMetrics, v: ParagraphProperties, hs: List<Float>) {
        if (v.spaceBefore * density + hs.first() > cursor.remaining && !cursor.atTop)cursor.newPage()
        val before = (v.spaceBefore * density).coerceAtMost((cursor.remaining - hs.first()).coerceAtLeast(0f))
        if (before <
            v.spaceBefore * density
        ) {
            relaxed +=
                "Paragraph${p.index + 1}: excessive space-before constrained to preserve first line"
        }
        cursor.space(before)
    }

    private fun keepWithFollowing(at: Int, p: PageParagraphMetrics, v: ParagraphProperties, hs: List<Float>) {
        var group = hs.sum() + v.spaceBefore * density + v.spaceAfter * density
        var end = at + 1
        var bounded = 0
        var open = true
        while (open && end < paragraphs.size && bounded++ < KEEP_CHAIN_BOUND) {
            val next = paragraphs[end]
            val nv = props(next)
            open = !(next.index in explicitBefore || nv.pagination.breakBefore)
            if (open) {
                val nh = heights(next)
                group += nv.spaceBefore * density
                open = nv.pagination.keepWithNext
                group += if (open) nh.sum() + nv.spaceAfter * density else keptFragmentHeight(nv, nh)
                end++
            }
        }
        if (group <= cursor.capacity && group > cursor.remaining) {
            cursor.newPage()
        } else if (group > cursor.capacity || bounded >= KEEP_CHAIN_BOUND) {
            relaxed += "Paragraph${p.index + 1}: keep chain relaxed (capacity/32-paragraph bound)"
        }
    }

    /** Budget a fragment the following paragraph can actually keep on this page. */
    private fun keptFragmentHeight(nv: ParagraphProperties, nh: List<Float>): Float {
        // A keep-lines paragraph, or one too short to satisfy both split minima,
        // otherwise moves in its own pass and strands the preceding heading.
        val indivisible =
            nv.pagination.keepLines || nh.size < nv.pagination.orphans + nv.pagination.widows
        return if (indivisible)nh.sum() else nh.take(nv.pagination.orphans).sum()
    }

    /** Lines to place from [line] on the current page, or null after moving to a new page. */
    private fun chunkCount(p: PageParagraphMetrics, hs: List<Float>, line: Int, r: PaginationRules): Int? {
        var fitting = 0
        var used = 0f
        while (line + fitting < hs.size && used + hs[line + fitting] <= cursor.remaining + LAYOUT_EPSILON) {
            used += hs[line + fitting]
            fitting++
        }
        val count = when {
            fitting == 0 -> null
            isOrphanedStart(line, fitting, hs.size, r.orphans) && !cursor.atTop -> null
            else -> widowAdjusted(p, hs, line, fitting, r)
        }
        if (count == null) {
            cursor.newPage()
        } else if (isOrphanedStart(line, count, hs.size, r.orphans)) {
            relaxed += "Paragraph${p.index + 1}: orphan request relaxed for available body area"
        }
        return count
    }

    private fun widowAdjusted(
        p: PageParagraphMetrics,
        hs: List<Float>,
        line: Int,
        count: Int,
        r: PaginationRules,
    ): Int? {
        val tail = hs.size - line - count
        if (tail !in 1 until r.widows) return count
        val adjusted = count - (r.widows - tail)
        val minimum = if (line == 0)r.orphans else 1
        return when {
            adjusted >= minimum -> adjusted
            !cursor.atTop -> null
            else -> {
                relaxed +=
                    "Paragraph${p.index + 1}: widow/orphan request cannot fit; forward progress takes priority"
                count
            }
        }
    }

    private fun placeLines(p: PageParagraphMetrics, hs: List<Float>, first: Int, count: Int): Int {
        var line = first
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
                    slotTop(slot),
                    slot.page,
                )
            line++
        }
        return line
    }
}
