package dev.shayxo.composedoc.harness

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.layout.PageCursor
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.layout.paragraphAttributedDisplay
import dev.shayxo.composedoc.layout.partitionParagraphs
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DynamicMemoryWStream
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGCanvas
import java.io.File
import java.security.MessageDigest

/** GP0 experiment types are private: no schema/API freeze or production pagination claim. */
private data class ProbeParagraph(val flow: String, val start: Int, val end: Int, val hasBreak: Boolean, val paragraph: Paragraph)
private data class ProbeFragment(
    val source: ProbeParagraph,
    val line: Int,
    val start: Int,
    val end: Int,
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val top: Float,
    val bottom: Float,
)
private data class ProbeImage(val page: Int, val y: Float)
private class ProbePlan(val profile: TypographyProfile, val flows: Map<String, Document>, val width: Int = 600) {
    val height = 800
    val margin = 48f
    val fragments = ArrayList<ProbeFragment>()
    val images = ArrayList<ProbeImage>()
    private val cursor = PageCursor(height.toFloat(), margin)
    val pageCount get() = cursor.page + 1
    private val resolver = createFontFamilyResolver()

    private fun paragraphs(flow: String, contentWidth: Int): List<ProbeParagraph> = partitionParagraphs(flows.getValue(flow)).map { span ->
        val annotated = paragraphAttributedDisplay(span.units)
        val p = Paragraph(
            annotated.text,
            TextStyle(color = androidx.compose.ui.graphics.Color.Black, fontFamily = profile.family, fontSize = 20.sp, textDirection = TextDirection.Rtl),
            Constraints(maxWidth = contentWidth),
            Density(1f),
            resolver,
            spanStyles = annotated.spanStyles,
        )
        ProbeParagraph(flow, span.startOffset, span.endOffset, span.hasBreak, p)
    }

    fun body() {
        val contentWidth = (width - 2 * margin).toInt()
        for (source in paragraphs("body", contentWidth)) {
            val p = source.paragraph
            for (line in 0 until p.lineCount) {
                val top = p.getLineTop(line)
                val bottom = if (line + 1 < p.lineCount) p.getLineTop(line + 1) else p.height
                val slot = cursor.place(bottom - top)
                val end = if (line == p.lineCount - 1 && source.hasBreak) source.end + 1 else source.start + p.getLineEnd(line)
                fragments += ProbeFragment(
                    source, line, source.start + p.getLineStart(line), end,
                    slot.page, margin, slot.y, contentWidth.toFloat(), top, bottom,
                )
            }
        }
    }

    fun image() {
        val slot = cursor.place(88f)
        images += ProbeImage(slot.page, slot.y)
    }

    fun table(rows: Int) {
        val cellWidth = ((width - 2 * margin) / 2).toInt()
        for (row in 0 until rows) {
            val cells = (0..1).map { col ->
                paragraphs("cell-$row-$col", cellWidth - 12).flatMap { source ->
                    (0 until source.paragraph.lineCount).map { line -> source to line }
                }
            }
            for (slot in 0 until cells.maxOf { it.size }) {
                val lineHeight = cells.mapNotNull { it.getOrNull(slot) }.maxOf { (s, line) ->
                    (if (line + 1 < s.paragraph.lineCount) s.paragraph.getLineTop(line + 1) else s.paragraph.height) - s.paragraph.getLineTop(line)
                }
                val placed = cursor.place(lineHeight)
                for (col in 0..1) {
                    cells[col].getOrNull(slot)?.let { (source, line) ->
                        val p = source.paragraph
                        val end = if (line == p.lineCount - 1 && source.hasBreak) source.end + 1 else source.start + p.getLineEnd(line)
                        fragments += ProbeFragment(
                            source, line, source.start + p.getLineStart(line), end,
                            placed.page, margin + (1 - col) * cellWidth + 6, placed.y, (cellWidth - 12).toFloat(), p.getLineTop(line),
                            if (line + 1 < p.lineCount) p.getLineTop(line + 1) else p.height,
                        )
                    }
                }
            }
        }
    }

    /** Exact offset-to-fragment mapping; upstream is explicit at a wrapped page boundary. */
    fun fragment(flow: String, offset: Int, upstream: Boolean = false): ProbeFragment {
        val options = fragments.filter { it.source.flow == flow && offset in it.start..it.end }
        require(options.isNotEmpty()) { "Missing logical mapping: $flow/$offset" }
        return if (upstream) options.first() else options.last()
    }

    fun validate() {
        check(pageCount in 3..12) { "Expected bounded multipage workload, got $pageCount" }
        for ((flow, doc) in flows) {
            val owned = fragments.filter { it.source.flow == flow }
            check(owned.isNotEmpty())
            val covered = IntArray(doc.units.size)
            owned.forEach { f ->
                check(f.y >= margin && f.y + f.bottom - f.top <= height - margin + .01f)
                for (i in f.start until f.end) covered[i]++
            }
            check(covered.all { it == 1 }) { "Missing/duplicate canonical units: $flow" }
            for (offset in 0..doc.units.size) {
                val f = fragment(flow, offset)
                val local = (offset - f.source.start).coerceIn(0, f.source.end - f.source.start)
                val rect = f.source.paragraph.getCursorRect(local)
                check(rect.left.isFinite() && rect.top.isFinite())
            }
        }
    }

    fun paint(canvas: Canvas, pageIndex: Int, image: Image) {
        canvas.clear(0xFFFFFFFF.toInt())
        for (f in fragments.filter { it.page == pageIndex }) {
            canvas.save()
            canvas.clipRect(Rect.makeXYWH(f.x, f.y, f.width, f.bottom - f.top))
            canvas.translate(f.x, f.y - f.top)
            // Paint the original whole paragraph. A fragment never reshapes a substring.
            f.source.paragraph.paint(canvas.asComposeCanvas(), color = androidx.compose.ui.graphics.Color.Black)
            canvas.restore()
            if (f.source.flow.startsWith("cell-")) {
                Paint().use { border ->
                    border.color = 0xFFBBC9D9.toInt()
                    border.strokeWidth = .6f
                    canvas.drawLine(f.x - 3, f.y + f.bottom - f.top, f.x + f.width + 3, f.y + f.bottom - f.top, border)
                }
            }
        }
        images.filter { it.page == pageIndex }.forEach { canvas.drawImageRect(image, Rect.makeXYWH(margin, it.y + 8, 72f, 64f)) }
        for ((text, top) in listOf("Urdu page experiment / اردو" to 10f, "Page ${pageIndex + 1} / $pageCount" to (height - 35f))) {
            val p = Paragraph(
                text,
                TextStyle(color = androidx.compose.ui.graphics.Color.Black, fontFamily = profile.family, fontSize = 12.sp, textDirection = TextDirection.Rtl),
                Constraints(maxWidth = width - 96),
                Density(1f),
                resolver,
            )
            canvas.save()
            canvas.translate(margin, top)
            p.paint(canvas.asComposeCanvas(), color = androidx.compose.ui.graphics.Color.Black)
            canvas.restore()
        }
    }

    fun semanticRuns(): List<Map<String, Any>> {
        fun run(
            text: String,
            p: Paragraph,
            page: Int,
            x: Float,
            y: Float,
            sourceTop: Float,
            line: Int,
            localStart: Int,
            localEnd: Int,
            flow: String,
            offset: Int,
            size: Int,
        ): Map<String, Any> = mapOf(
            "flow" to flow,
            "offset" to offset,
            "page" to page,
            "utf16" to text.map { it.code },
            "fontSize" to size,
            "baseline" to y - sourceTop + p.getLineBaseline(line),
            "characters" to (localStart until localEnd).map { index ->
                val box = p.getBoundingBox(index)
                mapOf(
                    "unit" to text[index - localStart].code,
                    "rtl" to (p.getBidiRunDirection(index) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl),
                    "bbox" to listOf(x + box.left, y - sourceTop + box.top, x + box.right, y - sourceTop + box.bottom),
                )
            },
        )
        val result = ArrayList<Map<String, Any>>()
        for (pageIndex in 0 until pageCount) {
            fun decoration(text: String, top: Float, owner: String) {
                val p = Paragraph(
                    text,
                    TextStyle(color = androidx.compose.ui.graphics.Color.Black, fontFamily = profile.family, fontSize = 12.sp, textDirection = TextDirection.Rtl),
                    Constraints(maxWidth = width - 96),
                    Density(1f),
                    resolver,
                )
                result += run(text, p, pageIndex, margin, top, 0f, 0, 0, text.length, owner, 0, 12)
            }
            decoration("Urdu page experiment / اردو", 10f, "generated-header")
            for (f in fragments.filter { it.page == pageIndex }) {
                val text = flows.getValue(f.source.flow).text.substring(f.start, f.end)
                val localStart = f.start - f.source.start
                val localEnd = minOf(f.end, f.source.end) - f.source.start
                result += run(
                    text, f.source.paragraph, pageIndex, f.x, f.y, f.top, f.line,
                    localStart, localEnd, f.source.flow, f.start, 20,
                )
            }
            decoration("Page ${pageIndex + 1} / $pageCount", height - 35f, "generated-footer")
        }
        return result
    }
}

fun runPageProbe(args: Array<String>) {
    val output = File(args.firstOrNull { it.startsWith("--page-output=") }?.substringAfter('=') ?: "tests/local-evidence/desktop/page-probe-${System.currentTimeMillis()}")
    check(output.mkdirs()) { "Use a new page evidence directory" }
    val profile = TypographyProfile.load("noto-nastaliq")
    val flows = linkedMapOf<String, Document>()
    val body = EditorEngine()
    val prose = "یہ ایک اردو دستاویز ہے۔ مُحَمَّد اور تحقیق — English 123 (۲۰۲۶)۔ "
    body.replace(prose.repeat(15) + "\nکتاب اور قلم — mixed ending", "Fixture", paragraphBreaks = true)
    body.move(Selection(0, 4))
    body.toggleFormat(FormatKey.BOLD)
    flows["body"] = body.snapshot().committed
    for (row in 0 until 10) {
        for (col in 0..1) {
            val e = EditorEngine()
            e.replace("قطار ${row + 1} / ${col + 1}\nاردو کتاب English", "CellFixture", paragraphBreaks = true)
            e.move(Selection(0, 4))
            e.toggleFormat(if (col == 0) FormatKey.BOLD else FormatKey.ITALIC)
            flows["cell-$row-$col"] = e.snapshot().committed
        }
    }
    fun makePlan(width: Int = 600) = ProbePlan(profile, flows.toMap(), width).apply {
        body()
        image()
        table(10)
        validate()
    }
    val plan = makePlan()
    val boundary = plan.fragments.first { it.source.flow == "body" && it.page == 1 }.start
    val upstream = plan.fragment("body", boundary, true)
    val downstream = plan.fragment("body", boundary)
    check(upstream.page == 0 && downstream.page == 1)
    body.move(Selection(boundary, boundary))
    val before = body.snapshot()
    body.inputMethod("ک候補", 1, 2)
    check(body.snapshot().committed.text == before.committed.text.take(boundary) + "ک" + before.committed.text.drop(boundary))
    flows["body"] = body.snapshot().visible
    makePlan() // Synthetic composition can span a boundary without adding page gaps to text.
    body.inputMethod("تاب", 3, null)
    val after = body.snapshot()
    check(after.committed.text == before.committed.text.take(boundary) + "کتاب" + before.committed.text.drop(boundary))
    check(after.undoCount == before.undoCount + 1)
    check(after.selection == Selection(boundary + 4, boundary + 4))
    check(after.committed.units.subList(boundary, boundary + 4).all { it.style == before.typingStyle })
    check(after.committed.units.take(boundary) == before.committed.units.take(boundary))
    check(after.committed.units.drop(boundary + 4) == before.committed.units.drop(boundary))
    body.perform(EditAction.Undo)
    check(body.snapshot().committed == before.committed && body.snapshot().selection == before.selection)
    body.perform(EditAction.Redo)
    check(body.snapshot().committed == after.committed)
    flows["body"] = body.snapshot().committed
    val edited = makePlan()
    val resized = makePlan(560)
    val repeated = makePlan()
    check(edited.fragments.map { listOf(it.start, it.end, it.page, it.y) } == repeated.fragments.map { listOf(it.start, it.end, it.page, it.y) })
    check(body.snapshot().committed == after.committed) // Reflow is presentation only.

    val bitmap = Bitmap().apply { allocN32Pixels(72, 64) }
    Canvas(bitmap).use { c ->
        c.clear(0xFFE4EEF5.toInt())
        Paint().use { p ->
            p.color = 0xFF305870.toInt()
            c.drawCircle(36f, 32f, 20f, p)
        }
    }
    Image.makeFromBitmap(bitmap).use { resource ->
        val imageBytes = checkNotNull(resource.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
        File(output, "resource.png").writeBytes(imageBytes)
        for (pageIndex in 0 until edited.pageCount) {
            Bitmap().use { pageBitmap ->
                pageBitmap.allocN32Pixels(edited.width, edited.height)
                Canvas(pageBitmap).use { edited.paint(it, pageIndex, resource) }
                Image.makeFromBitmap(pageBitmap).use { image ->
                    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { File(output, "page-${pageIndex + 1}.png").writeBytes(it.bytes) }
                }
            }
            DynamicMemoryWStream().use { stream ->
                SVGCanvas.make(Rect.makeWH(edited.width.toFloat(), edited.height.toFloat()), stream).use { edited.paint(it, pageIndex, resource) }
                val bytes = ByteArray(stream.bytesWritten())
                check(stream.read(bytes, 0, bytes.size))
                File(output, "page-${pageIndex + 1}.svg").writeBytes(bytes)
            }
            DynamicMemoryWStream().use { stream ->
                SVGCanvas.make(Rect.makeWH(edited.width.toFloat(), edited.height.toFloat()), stream, convertTextToPaths = true, prettyXML = false).use { edited.paint(it, pageIndex, resource) }
                val bytes = ByteArray(stream.bytesWritten())
                check(stream.read(bytes, 0, bytes.size))
                File(output, "page-${pageIndex + 1}.outlines.svg").writeBytes(bytes)
            }
        }
        val svg = File(output, "page-1.svg").readText()
        val directPdf = runCatching { Class.forName("org.jetbrains.skia.Document") }.isSuccess
        val result = linkedMapOf<String, Any>(
            "status" to "EXPERIMENT_EXECUTED_GATE_OPEN", "gate" to "GP0", "timestamp" to java.time.Instant.now().toString(),
            "fontKey" to profile.key, "pageSize" to listOf(edited.width, edited.height), "pageCount" to edited.pageCount,
            "layoutDensity" to 1, "fontSizeSp" to 20, "exportUnitMapping" to "One captured plan unit to one PDF point in the rejected tooling candidate",
            "resizedPageCount" to resized.pageCount, "canonicalFlows" to flows.size,
            "canonicalUnits" to flows.values.sumOf { it.units.size },
            "fragments" to edited.fragments.map { f ->
                mapOf(
                    "flow" to f.source.flow, "start" to f.start, "end" to f.end, "page" to f.page,
                    "line" to f.line, "x" to f.x, "y" to f.y, "sourceLineTop" to f.top, "sourceLineBottom" to f.bottom,
                )
            },
            "mappingCoverage" to "PASS_EXACTLY_ONCE", "wholeParagraphShaping" to true,
            "syntheticBoundaryInput" to "PASS_TEXT_ATTRIBUTES_SELECTION_ONE_SESSION_HISTORY", "deterministicReflow" to "PASS",
            "resourceSha256" to MessageDigest.getInstance("SHA-256").digest(imageBytes).joinToString("") { "%02x".format(it) },
            "canonicalText" to flows.mapValues { it.value.text },
            "semanticRuns" to edited.semanticRuns(),
            "images" to edited.images.map { mapOf("page" to it.page, "bbox" to listOf(edited.margin, it.y + 8, edited.margin + 72, it.y + 72)) },
            "directSkikoPdfBindingAvailable" to directPdf, "svgContainsTextElements" to svg.contains("<text"),
            "requiredFollowUp" to listOf(
                "Independent SVG/PDF semantic and geometry inspection",
                "Live editable fragment surface and native page-boundary IME",
                "Font/resource arrival",
                "Production rich-table/section schema S0",
                "Human Urdu ink/clipping review",
                "Physical printing and other platforms",
            ),
        )
        File(output, "results.json").writeText(evidenceJson(result), Charsets.UTF_8)
        println("PASS bounded page-plan experiment: ${edited.pageCount} pages, ${edited.fragments.size} fragments; GP0 OPEN; evidence ${output.absolutePath}")
    }
    bitmap.close()
}
