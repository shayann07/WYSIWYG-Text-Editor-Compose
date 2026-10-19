package dev.shayxo.composedoc.layout

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextPainter
import dev.shayxo.composedoc.model.documentToPoints
import dev.shayxo.composedoc.model.outputUnicodeIssues
import dev.shayxo.composedoc.model.paragraphs
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.awt.print.PageFormat
import java.awt.print.Pageable
import java.awt.print.Paper
import java.awt.print.Printable
import java.awt.print.PrinterAbortException
import java.io.ByteArrayInputStream

/** Lowest and highest raster resolution accepted for rendered pages. */
private const val MIN_RENDER_DPI = 72
private const val MAX_RENDER_DPI = 300

/** Resolution used when rasterizing a page for the printer. */
private const val PRINT_DPI = 300

/** Document units per inch (CSS pixels). */
private const val DOCUMENT_UNITS_PER_INCH = 96f

/** Upper bound on the pixel count of one rendered page. */
private const val MAX_RENDER_PIXELS = 16_000_000

/** Allowed difference, in points, between printer paper and the captured page size. */
private const val PAPER_SIZE_TOLERANCE_POINTS = .25

/** Bounded preview/print page count. */
private const val MAX_SNAPSHOT_PAGES = 200

/** Number of Unicode issues quoted when output is refused. */
private const val REPORTED_UNICODE_ISSUES = 8

/** Upper bound on the memory held by captured page pictures (64 MiB). */
private const val MAX_PICTURE_BYTES = 64L * 1024 * 1024

/** Extra clip around a fragment so ascenders/descenders at its edges are not cut. */
private const val FRAGMENT_CLIP_BLEED = 2f

/** Opaque white page background. */
private const val PAGE_BACKGROUND = 0xffffffff

/** Bullet marker ink colour. */
private const val BULLET_COLOR = 0xff202b3c

/** Immutable Skia pictures recorded from the actual editor plan, not remeasured text. */
class PrintSnapshot private constructor(val plan: DocumentPagePlan, private val pictures: List<Picture>) :
    AutoCloseable {
    private var closed = false

    @Synchronized fun render(page: Int, dpi: Int = 120): BufferedImage {
        check(!closed)
        require(page in pictures.indices && dpi in MIN_RENDER_DPI..MAX_RENDER_DPI)
        val factor = dpi / DOCUMENT_UNITS_PER_INCH
        val width = kotlin.math.ceil(plan.width * factor).toInt()
        val height = kotlin.math.ceil(plan.height * factor).toInt()
        require(width.toLong() * height <= MAX_RENDER_PIXELS)
        return Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(PAGE_BACKGROUND.toInt())
            surface.canvas.scale(factor, factor)
            pictures[page].playback(surface.canvas)
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    javax.imageio.ImageIO.read(ByteArrayInputStream(data.bytes))
                }
            }
        }
    }
    fun paperFormat(): PageFormat {
        val paper = Paper()
        paper.setSize(documentToPoints(plan.width).toDouble(), documentToPoints(plan.height).toDouble())
        paper.setImageableArea(0.0, 0.0, paper.width, paper.height)
        return PageFormat().apply {
            this.paper = paper
            orientation = PageFormat.PORTRAIT
        }
    }
    fun warnings(format: PageFormat): List<String> = buildList {
        if (kotlin.math.abs(format.width - documentToPoints(plan.width)) > PAPER_SIZE_TOLERANCE_POINTS ||
            kotlin.math.abs(format.height - documentToPoints(plan.height)) > PAPER_SIZE_TOLERANCE_POINTS
        ) {
            add("Printer paper differs from the captured document; pages will not be repaginated")
        }
        if (hasUnprintableMargins(format)) {
            add("Printer has unprintable margins; actual size may clip. Fit scales uniformly to the printable area")
        }
    }
    private fun hasUnprintableMargins(format: PageFormat): Boolean = format.imageableX > 0 ||
        format.imageableY > 0 ||
        format.imageableWidth < format.width ||
        format.imageableHeight < format.height

    fun pageable(
        first: Int = 1,
        last: Int = plan.pageCount,
        format: PageFormat = paperFormat(),
        fit: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): Pageable {
        require(first in 1..plan.pageCount && last in first..plan.pageCount)
        return object : Pageable {
            override fun getNumberOfPages() = last - first + 1
            override fun getPageFormat(index: Int): PageFormat {
                require(index in 0 until numberOfPages)
                return format.clone() as PageFormat
            }
            override fun getPrintable(index: Int): Printable {
                require(index in 0 until numberOfPages)
                return Printable { graphics, actual, n ->
                    if (cancelled())throw PrinterAbortException("Cancelled")
                    if (n !in 0 until numberOfPages) {
                        Printable.NO_SUCH_PAGE
                    } else {
                        val image = render(first - 1 + n, PRINT_DPI)
                        val g = graphics.create() as java.awt.Graphics2D
                        try {
                            val width = documentToPoints(plan.width).toDouble()
                            val height = documentToPoints(plan.height).toDouble()
                            val factor = if (fit) {
                                minOf(
                                    actual.imageableWidth / width,
                                    actual.imageableHeight / height,
                                )
                            } else {
                                1.0
                            }
                            if (fit)g.translate(actual.imageableX, actual.imageableY)
                            g.scale(factor * width / image.width, factor * height / image.height)
                            g.drawImage(image, 0, 0, null)
                        } finally {
                            g.dispose()
                            image.flush()
                        }
                        Printable.PAGE_EXISTS
                    }
                }
            }
        }
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            pictures.forEach { it.close() }
        }
    }
    class Builder(val plan: DocumentPagePlan, bitmap: (String) -> androidx.compose.ui.graphics.ImageBitmap?) :
        AutoCloseable {
        private val pictures = ArrayList<Picture>()
        private var handedOff = false
        private val images = plan.document.units.mapNotNull {
            it.image?.resource
        }.distinct().associateWith { requireNotNull(bitmap(it)) { "Image decoding pending; wait and retry preview" } }
        val completed get() = pictures.size
        init {
            require(plan.pageCount <= MAX_SNAPSHOT_PAGES) { "Bounded preview/print supports at most200 pages" }
            require(outputUnicodeIssues(plan.document).isEmpty()) {
                val issues = outputUnicodeIssues(plan.document).take(REPORTED_UNICODE_ISSUES)
                "Output refused; source unchanged. ${issues.joinToString("; ")}"
            }
            plan.assertCoverage()
        }
        fun next() {
            check(javax.swing.SwingUtilities.isEventDispatchThread()) {
                "Capture paragraph pictures on the editor owner thread"
            }
            check(!handedOff && completed < plan.pageCount)
            val page = completed
            PictureRecorder().use { recorder ->
                val canvas = recorder.beginRecording(Rect.makeWH(plan.width, plan.height))
                canvas.clear(PAGE_BACKGROUND.toInt())
                for (f in plan.fragments.filter { it.page == page }) {
                    drawFragment(canvas, f, page)
                }
                for (r in plan.running.filter { it.page == page }) {
                    canvas.save()
                    canvas.translate(r.left, r.top)
                    synchronized(r.layout.multiParagraph) { TextPainter.paint(canvas.asComposeCanvas(), r.layout) }
                    canvas.restore()
                }
                pictures += recorder.finishRecordingAsPicture()
            }
            require(
                pictures.sumOf {
                    it.approximateBytesUsed.toLong()
                } <= MAX_PICTURE_BYTES,
            ) { "Captured print pictures exceed64MiB" }
        }
        private fun drawFragment(canvas: Canvas, f: ParagraphFragment, page: Int) {
            val p = plan.paragraphs[f.paragraphIndex]
            val y = plan.margin + f.top - page * (plan.height + plan.gap)
            canvas.save()
            canvas.clipRect(
                Rect.makeLTRB(
                    0f,
                    (y - FRAGMENT_CLIP_BLEED).coerceAtLeast(0f),
                    plan.width,
                    (y + f.height + FRAGMENT_CLIP_BLEED).coerceAtMost(plan.height),
                ),
            )
            if (p.image != null) {
                Image.makeFromBitmap(images.getValue(p.image.resource).asSkiaBitmap()).use { image ->
                    canvas.drawImageRect(
                        image,
                        Rect.makeXYWH(
                            plan.margin + p.imageLeft,
                            y,
                            p.imageWidth,
                            p.height,
                        ),
                    )
                }
            } else {
                canvas.save()
                canvas.translate(plan.margin + p.contentLeft, y - f.sourceTop)
                synchronized(p.layout.multiParagraph) { TextPainter.paint(canvas.asComposeCanvas(), p.layout) }
                canvas.restore()
            }
            if (f.firstLine == 0) {
                p.marker?.let { marker -> drawMarker(canvas, marker, y) }
            }
            canvas.restore()
        }
        private fun drawMarker(canvas: Canvas, marker: ListMarkerLayout, y: Float) {
            if (marker.layout != null) {
                canvas.save()
                canvas.translate(plan.margin + marker.left, y + marker.top)
                synchronized(marker.layout.multiParagraph) {
                    TextPainter.paint(canvas.asComposeCanvas(), marker.layout)
                }
                canvas.restore()
            } else {
                Paint().use { paint ->
                    paint.color = BULLET_COLOR.toInt()
                    canvas.drawCircle(
                        plan.margin + marker.left + marker.bulletRadius,
                        y + marker.opticalCenter,
                        marker.bulletRadius,
                        paint,
                    )
                }
            }
        }
        fun finish(): PrintSnapshot {
            check(completed == plan.pageCount && !handedOff)
            handedOff = true
            return PrintSnapshot(plan, pictures.toList())
        }
        override fun close() {
            if (!handedOff)pictures.forEach { it.close() }
            handedOff = true
        }
    }
}
