package dev.shayxo.composedoc.layout

import androidx.compose.ui.text.TextLayoutResult
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.RunningFlow
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.mmToDocument
import dev.shayxo.composedoc.model.paragraphs
data class RunningPlacement(val page: Int, val header: Boolean, val flowId: String, val text: String, val left: Float, val top: Float, val layout: TextLayoutResult)
data class PageBox(val page: Int, val width: Float, val height: Float, val contentLeft: Float, val contentTop: Float, val contentRight: Float, val contentBottom: Float)

/** Captures the actual editor's immutable paragraph layouts, never independent reshaping. */
data class DocumentPagePlan(
    val document: Document,
    val revision: Long,
    val typographyKey: String,
    val width: Float,
    val height: Float,
    val margin: Float,
    val gap: Float,
    val paragraphs: List<ParagraphLayout>,
    val fragments: List<ParagraphFragment>,
    val boxes: List<PageBox>,
    val running: List<RunningPlacement>,
    val relaxations: List<String>,
) {
    val pageCount get() = boxes.size
    fun assertCoverage() {
        val cells = fragments.flatMap { f -> (f.firstLine..f.lastLine).map { f.paragraphIndex to it } }
        require(cells.size == cells.distinct().size && cells.size == paragraphs.sumOf { it.lineCount }) { "Page plan dropped/duplicated body lines" }
        var offset = 0
        for (f in fragments) {
            val p = paragraphs[f.paragraphIndex]
            val start = p.startOffset + p.layout.getLineStart(f.firstLine)
            val end = if (f.lastLine == p.lineCount - 1) {
                p.endOffset + (if (p.hasBreak)1 else 0)
            } else {
                // Skia may omit a consumed wrap-space from getLineEnd while the
                // next line starts after it. Attribute that whitespace to the
                // preceding fragment; never discard it or bridge missing ink.
                val paintedEnd = p.layout.getLineEnd(f.lastLine, false)
                val nextStart = p.layout.getLineStart(f.lastLine + 1)
                require(nextStart >= paintedEnd && p.text.substring(paintedEnd, nextStart).all { it.isWhitespace() }) { "Non-whitespace gap/overlap in shaped line source ranges" }
                p.startOffset + nextStart
            }
            require(start == offset && end >= start) { "Page plan lost or repeated source units at$offset: paragraph=${p.index}, lines=${f.firstLine}..${f.lastLine}, start=$start, end=$end, text=${p.text}" }
            offset = end
        }
        require(offset == document.units.size) { "Page plan lost trailing source units" }
        require(
            fragments.all { f ->
                val p = paragraphs[f.paragraphIndex]
                val box = boxes[f.page]
                val local = margin + f.top - f.page * (height + gap)
                f.firstLine >= 0 && f.lastLine < p.lineCount && local >= box.contentTop - .02f && local + f.height <= box.contentBottom + .02f
            },
        ) { "Body fragment overlaps running content/page edge" }
    }
}

fun runningReserves(document: Document, measure: (RunningFlow, String) -> TextLayoutResult): List<Float> = listOf(document.running.header, document.running.footer).map { flow ->
    if (flow == null) {
        0f
    } else {
        (0..9).maxOf { digit ->
            val n = ("$digit".repeat(4)).toInt().coerceAtLeast(1)
            measure(flow, flow.display(n, n, document.running.digits)).size.height.toFloat()
        } + 12f
    }
}
fun buildDocumentPagePlan(
    document: Document,
    revision: Long,
    key: String,
    paragraphs: List<ParagraphLayout>,
    gap: Float,
    measure: (RunningFlow, String) -> TextLayoutResult,
): DocumentPagePlan {
    val settings = document.pageSettings
    val width = mmToDocument(settings.widthMm)
    val height = mmToDocument(settings.heightMm)
    val margin = mmToDocument(settings.marginMm.toFloat())
    val flows = listOf(document.running.header, document.running.footer)
    val reserves = runningReserves(document, measure).toMutableList()
    require(reserves.sum() < height - 2 * margin) { "Header/footer leave no body area; shorten them, reduce size or margins" }
    val explicit = document.paragraphs().filter { it.start > 0 && document.units[it.start - 1].structuralBreak == StructuralBreak.PAGE }.map { it.index }.toSet()
    var result: DocumentPagePlan? = null
    repeat(2) {
        val fragments = pageFragments(paragraphs, height, margin, gap, document.paragraphs().map { it.properties }, 1f, explicit, reserves[0], reserves[1])
        require(fragments.pages <= 2000) { "This bounded page profile supports at most2000 pages" }
        val running = ArrayList<RunningPlacement>()
        val needed = mutableListOf(0f, 0f)
        for (page in 0 until fragments.pages) {
            if (page != 0 || !document.running.differentFirst) {
                for ((index, flow) in flows.withIndex()) {
                    if (flow != null) {
                        val text = flow.display(page + 1, fragments.pages, document.running.digits)
                        val layout = measure(flow, text)
                        needed[index] = maxOf(needed[index], layout.size.height + 12f)
                        running += RunningPlacement(page, index == 0, flow.id, text, margin, if (index == 0)margin else height - margin - layout.size.height, layout)
                    }
                }
            }
        }
        if (needed.indices.all { needed[it] <= reserves[it] + .01f }) {
            result = DocumentPagePlan(document, revision, key, width, height, margin, gap, paragraphs.toList(), fragments.fragments.toList(), List(fragments.pages) { PageBox(it, width, height, margin, margin + reserves[0], width - margin, height - margin - reserves[1]) }, running.toList(), fragments.relaxations)
            result!!.assertCoverage()
            return result!!
        }
        for (index in needed.indices)reserves[index] = maxOf(reserves[index], needed[index])
    }
    error("Running fields did not stabilize within two page-plan passes; shorten running content")
}
