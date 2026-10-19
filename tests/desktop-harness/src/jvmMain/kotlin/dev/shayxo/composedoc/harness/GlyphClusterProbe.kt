package dev.shayxo.composedoc.harness

import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Point
import org.jetbrains.skia.shaper.RunHandler
import org.jetbrains.skia.shaper.RunInfo
import org.jetbrains.skia.shaper.Shaper
import org.jetbrains.skia.shaper.ShapingOptions
import java.io.File
import kotlin.math.abs

/** Bounded existing-SkShaper cluster capability/parity trial; no new PDF or shaping engine. */
fun runGlyphClusterProbe(args: Array<String>) {
    val output = File(checkNotNull(args.firstOrNull { it.startsWith("--glyph-output=") }).substringAfter('='))
    check(output.mkdirs()) { "Use a new glyph evidence directory" }
    val profile = TypographyProfile.load("noto-nastaliq")
    val resolver = createFontFamilyResolver()
    val specimens = linkedMapOf(
        "urdu" to "کتاب اور قلم، یہ ایک اردو دستاویز ہے۔",
        "marks" to "مُحَمَّد اور تحقیق، قُرآن، نَستَعلیق۔",
        "mixed" to "اردو ABC کتاب English 123 (۲۰۲۶) — مُحَمَّد",
        "joining" to "ک\u200dتاب ک\u200cتاب لا لام الف یے ہا",
    )
    val factories: Map<String, () -> Shaper> = linkedMapOf(
        "default" to { Shaper.make() },
        "shape-then-wrap" to { Shaper.makeShapeThenWrap() },
        "shaper-driven" to { Shaper.makeShaperDrivenWrapper() },
    )
    val samples = ArrayList<Map<String, Any>>()
    var totalGlyphs = 0
    val typeface = checkNotNull(FontMgr.default.makeFromFile("tests/suites/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf"))
    try {
        for ((name, text) in specimens) {
            for (width in listOf(180, 420)) {
                val paragraph = Paragraph(
                    text,
                    TextStyle(fontFamily = profile.family, fontSize = 26.sp, textDirection = TextDirection.Rtl),
                    Constraints(maxWidth = width),
                    Density(1f),
                    resolver,
                )
                val expected = (0 until paragraph.lineCount).map { line -> listOf(paragraph.getLineStart(line), paragraph.getLineEnd(line)) }
                // getLineWidth omits trailing whitespace; the callback advance includes it.
                // Compare the full logical unit extent on both sides, retaining visible-width diagnostics.
                val logicalWidths = expected.map { range ->
                    val boxes = (range[0] until range[1]).map { paragraph.getBoundingBox(it) }
                    (boxes.maxOfOrNull { it.right } ?: 0f) - (boxes.minOfOrNull { it.left } ?: 0f)
                }
                val trials = ArrayList<Map<String, Any>>()
                for ((factoryName, factory) in factories) {
                    for (fractional in listOf(false, true)) {
                        val runs = ArrayList<Map<String, Any>>()
                        val lines = ArrayList<MutableList<Int>>()
                        val advances = ArrayList<Float>()
                        Font(typeface, 26f).use { font ->
                            font.isSubpixel = fractional
                            factory().use { shaper ->
                                shaper.shape(
                                    text,
                                    font,
                                    ShapingOptions.DEFAULT.withLeftToRight(false),
                                    width.toFloat(),
                                    object : RunHandler {
                                        override fun beginLine() {
                                            lines.add(mutableListOf())
                                            advances.add(0f)
                                        }
                                        override fun runInfo(info: RunInfo?) {
                                            val run = checkNotNull(info)
                                            lines.last().addAll(listOf(run.rangeBegin, run.rangeEnd))
                                            advances[advances.lastIndex] += run.advanceX
                                        }
                                        override fun commitRunInfo() {}
                                        override fun runOffset(info: RunInfo?) = Point(0f, 0f)
                                        override fun commitRun(info: RunInfo?, glyphs: ShortArray?, positions: Array<Point?>?, clusters: IntArray?) {
                                            val run = checkNotNull(info)
                                            val g = checkNotNull(glyphs)
                                            val p = checkNotNull(positions)
                                            val c = checkNotNull(clusters)
                                            check(g.size == run.glyphCount && p.size == g.size && c.size == g.size)
                                            check(run.rangeBegin >= 0 && run.rangeEnd <= text.length)
                                            // The installed adapter reports paragraph-global UTF-16 offsets, observed directly.
                                            check(c.all { it in run.rangeBegin until run.rangeEnd }) { "Unexpected cluster coordinate contract" }
                                            run.font.use { actual ->
                                                val family = actual.typeface!!.use { it.familyName }
                                                runs += mapOf(
                                                    "line" to lines.lastIndex,
                                                    "range" to listOf(run.rangeBegin, run.rangeEnd),
                                                    "bidiLevel" to run.bidiLevel,
                                                    "advance" to run.advanceX,
                                                    "fontFamily" to family,
                                                    "glyphs" to g.map { it.toInt() and 0xffff },
                                                    "clusters" to c.toList(),
                                                    "positions" to p.map { checkNotNull(it).let { point -> listOf(point.x, point.y) } },
                                                )
                                            }
                                            totalGlyphs += g.size
                                        }
                                        override fun commitLine() {}
                                    },
                                )
                            }
                        }
                        val observed = lines.map { listOf(it.minOrNull() ?: 0, it.maxOrNull() ?: 0) }
                        val lineParity = observed == expected
                        val widthErrors = if (lines.size == paragraph.lineCount) advances.mapIndexed { i, value -> abs(value - logicalWidths[i]) } else emptyList()
                        val widthParity = widthErrors.size == paragraph.lineCount && widthErrors.all { it <= 0.25f }
                        trials += mapOf(
                            "factory" to factoryName,
                            "subpixel" to fractional,
                            "lineRanges" to observed,
                            "lineAdvances" to advances.toList(),
                            "lineRangeParity" to lineParity,
                            "lineWidthErrors" to widthErrors,
                            "lineWidthParity" to widthParity,
                            "runs" to runs,
                        )
                    }
                }
                samples += mapOf(
                    "name" to name,
                    "utf16" to text.map { it.code },
                    "width" to width,
                    "composeLines" to expected,
                    "composeLineWidths" to (0 until paragraph.lineCount).map { paragraph.getLineWidth(it) },
                    "composeLogicalUnitExtentWidths" to logicalWidths,
                    "trials" to trials,
                )
            }
        }
    } finally {
        typeface.close()
    }
    check(totalGlyphs > 0 && samples.size == 8)
    val matching = factories.keys.flatMap { factory ->
        listOf(false, true).mapNotNull { subpixel ->
            val matches = samples.all { sample -> (sample["trials"] as List<*>).map { it as Map<*, *> }.single { it["factory"] == factory && it["subpixel"] == subpixel }.let { it["lineRangeParity"] == true && it["lineWidthParity"] == true } }
            if (matches) "$factory:subpixel=$subpixel" else null
        }
    }
    val result = mapOf(
        "status" to "CAPABILITY_TRIAL_EXECUTED_GATE_OPEN", "fontKey" to profile.key,
        "timestamp" to java.time.Instant.now().toString(),
        "glyphClusterCapabilityAvailable" to true, "glyphsObservedAcrossTrials" to totalGlyphs,
        "parityTolerancePt" to 0.25, "matchingLayoutConfigurations" to matching, "samples" to samples,
        "scope" to "Existing SkShaper RunHandler; independent re-shaping parity trial, not extraction of the actual Compose paragraph's glyph stream",
        "nextCondition" to if (matching.isEmpty()) "REJECT_LAYOUT_SUBSTITUTION; need actual paragraph glyph access or a separately qualified mapping" else "Ink/cluster geometry, font resources and PDF semantic tests still required; metrics alone do not qualify export",
    )
    File(output, "results.json").writeText(evidenceJson(result), Charsets.UTF_8)
    println("PASS existing glyph-cluster capability trial: ${samples.size} specimens/widths; ${matching.size} complete metric matches; GP0 OPEN")
}
