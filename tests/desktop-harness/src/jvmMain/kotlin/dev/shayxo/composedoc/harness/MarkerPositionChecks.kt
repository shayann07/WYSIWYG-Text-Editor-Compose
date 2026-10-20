package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.layout.DocumentPagePlan
import dev.shayxo.composedoc.layout.ParagraphLayout
import dev.shayxo.composedoc.layout.PrintSnapshot
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.validateStructure
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.showEditor
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Pixel oracle: a numeral beside a line must align with identical inline numeral ink.
 * The expected rows are read from the body pixels, not calculated from marker.baseline.
 */
object MarkerPositionChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val rows = run(File(args.single()))
            println("PASS marker position pixel checks ${rows.size}")
            kotlin.system.exitProcess(0)
        } catch (failure: Throwable) {
            System.err.print(failure.stackTraceToString())
            kotlin.system.exitProcess(1)
        }
    }

    fun run(output: File): List<Map<String, Any>> {
        output.mkdirs()
        val document = fixture()
        var window: JFrame? = null
        lateinit var surface: EditorSurface
        try {
            SwingUtilities.invokeAndWait {
                window = showEditor(
                    initial = document,
                    offscreenTest = true,
                    fontProfile = "noto-nastaliq",
                    paged = true,
                    recoveryDirectory = Files.createTempDirectory("marker-position-recovery-"),
                )
                surface = findSurface(window!!)!!
            }
            awaitGeometry(surface)
            val rows = checkViews(surface)
            File(output, "results.json").writeText(evidenceJson(mapOf("status" to "PASS", "rows" to rows)))
            return rows
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
        }
    }

    private fun awaitGeometry(surface: EditorSurface) {
        repeat(200) {
            var ready = false
            SwingUtilities.invokeAndWait { ready = surface.geometry()?.documentPagePlan != null }
            if (ready) return
            Thread.sleep(25)
        }
        error("Marker fixture layout timed out")
    }

    private fun checkViews(surface: EditorSurface): List<Map<String, Any>> {
        val rows = ArrayList<Map<String, Any>>()
        val before = surface.viewModel.snapshot()
        for (paged in listOf(false, true)) {
            for (zoom in listOf(.5f, .75f, 1f)) {
                SwingUtilities.invokeAndWait {
                    surface.view.setPagedMode(paged)
                    surface.view.changeZoom(zoom)
                }
                awaitGeometry(surface)
                SwingUtilities.invokeAndWait { rows += checkPlan(surface.geometry()!!.documentPagePlan!!, paged, zoom) }
            }
        }
        check(surface.viewModel.snapshot() == before) { "View/raster checks mutated document or history" }
        return rows
    }

    private fun checkPlan(plan: DocumentPagePlan, paged: Boolean, zoom: Float): List<Map<String, Any>> {
        val rows = ArrayList<Map<String, Any>>()
        val snapshot = PrintSnapshot.Builder(plan) { null }.use { builder ->
            repeat(plan.pageCount) { builder.next() }
            builder.finish()
        }
        snapshot.use {
            val pages = HashMap<Int, BufferedImage>()
            for (p in plan.paragraphs.filter { it.marker != null && it.text.isNotEmpty() }) {
                val fragment = plan.fragments.first { it.paragraphIndex == p.index }
                val raster = pages.getOrPut(fragment.page) { snapshot.render(fragment.page, 96) }
                rows += checkInk(plan, p, raster, paged, zoom)
            }
        }
        return rows
    }

    private fun checkInk(
        plan: DocumentPagePlan,
        p: ParagraphLayout,
        image: BufferedImage,
        paged: Boolean,
        zoom: Float,
    ): Map<String, Any> {
        val m = p.marker!!
        val f = plan.fragments.first { it.paragraphIndex == p.index }
        val top = plan.margin + f.top - f.page * (plan.height + plan.gap)
        val origin = top - f.sourceTop
        val lineBottom = minOf(top + f.height, origin + p.layout.getLineBottom(0))
        val a = p.layout.getBoundingBox(0)
        val inline = inkRows(
            image,
            plan.margin + p.contentLeft + a.left,
            plan.margin + p.contentLeft + a.right,
            top,
            lineBottom,
        )
        val digit = m.layout?.getBoundingBox(0)
        val marker = inkRows(
            image,
            plan.margin + m.left + (digit?.left ?: 0f),
            plan.margin + m.left + (digit?.right ?: m.width),
            top,
            lineBottom,
        )
        val label = "${m.fontKey}/${m.nominalSize}/${p.baseDirection}/$paged/$zoom/${m.text}"
        if (m.layout != null) {
            // Negative first-line ink bleed keeps the approved paragraph-origin marker position.
            // Allow that explicit compatibility displacement plus one raster edge pixel.
            val bleed = ceil((-f.sourceTop).coerceAtLeast(0f)).toInt()
            check(abs(marker.first - inline.first) <= bleed + 1 && abs(marker.last - inline.last) <= bleed + 1) {
                "Number ink must align with identical inline label: $label marker=$marker inline=$inline bleed=$bleed"
            }
        } else {
            // Optical judgment has a declared .08em tolerance, not an equality to our baseline formula.
            val distance = abs((marker.first + marker.last) / 2f - (inline.first + inline.last) / 2f)
            check(distance <= maxOf(2f, m.nominalSize * .08f)) {
                "Bullet must sit near first-line x-height ink center: $label distance=$distance"
            }
        }
        check(origin.isFinite())
        return mapOf(
            "case" to label,
            "markerInk" to listOf(marker.first, marker.last),
            "inlineInk" to listOf(inline.first, inline.last),
            "sourceTop" to f.sourceTop,
        )
    }

    private fun inkRows(image: BufferedImage, left: Float, right: Float, top: Float, bottom: Float): IntRange {
        val rows = (
            floor(
                top,
            ).toInt().coerceAtLeast(0) until ceil(bottom).toInt().coerceAtMost(image.height)
            ).filter { y ->
            (floor(left).toInt().coerceAtLeast(0) until ceil(right).toInt().coerceAtMost(image.width)).any { x ->
                image.getRGB(x, y) and 0xffffff != 0xffffff
            }
        }
        check(rows.isNotEmpty()) { "Missing ink in marker/reference box $left,$top,$right,$bottom" }
        return rows.first()..rows.last()
    }

    private data class Row(val text: String, val style: TextStyleFlags, val props: ParagraphProperties)

    private fun fixture(): Document {
        val rows = ArrayList<Row>()
        for (font in listOf(StoredFont.NOTO_NASTALIQ, StoredFont.GULZAR, StoredFont.NASKH, StoredFont.SYSTEM)) {
            for (size in listOf(12, 26, 48)) {
                for (rtl in listOf(false, true)) {
                    val style = TextStyleFlags(font = font, size = size)
                    val root = "marker-${rows.size}"
                    val props = ParagraphProperties(
                        direction = if (rtl) ParagraphDirection.RTL else ParagraphDirection.LTR,
                        item = root,
                        list = ListKind.NUMBER,
                        restart = 9999,
                        markerTypography = MarkerTypography(font, size),
                    )
                    rows += Row("1. English العربية کتاب", style, props.copy(item = "$root-single", restart = 1))
                    rows += Row("9999. کتاب العربية English", style, props)
                    rows += Row("Continuation کِتاب", style, props)
                    rows += Row(
                        "x English child — العربية",
                        style,
                        props.copy(
                            direction = if (rtl) ParagraphDirection.LTR else ParagraphDirection.RTL,
                            item = "$root-child",
                            parent = root,
                            list = ListKind.BULLET,
                            restart = 0,
                        ),
                    )
                    val emptyProps = props.copy(item = "$root-empty", parent = "", list = ListKind.BULLET, restart = 0)
                    rows += Row("", style, emptyProps)
                }
            }
        }
        val units = ArrayList<TextUnit>()
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                units += TextUnit(
                    (units.size + 1).toLong(),
                    '\n',
                    UnitKind.PARAGRAPH_BREAK,
                    row.style,
                    paragraphStyle = row.style,
                    followingParagraph = row.props,
                )
            }
            for (c in row.text) units += TextUnit((units.size + 1).toLong(), c, style = row.style)
        }
        return Document(
            units,
            startStyle = rows.first().style,
            startParagraph = rows.first().props,
        ).also(::validateStructure)
    }
}
