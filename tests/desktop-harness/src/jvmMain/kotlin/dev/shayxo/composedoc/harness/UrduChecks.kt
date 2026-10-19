package dev.shayxo.composedoc.harness

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.SimpleJson
import dev.shayxo.composedoc.model.TextStyleFlags
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.roundToInt

data class UrduFormattingSpan(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

data class UrduFixture(
    val id: String,
    val category: String,
    val title: String,
    val text: String,
    val scalarCount: Int,
    val utf16Length: Int,
    val utf16CodeUnits: List<Int>,
    val executionScope: String,
    val operations: List<String>,
    val forbiddenInCanonical: List<String>,
    val formattingSpans: List<UrduFormattingSpan>,
)

fun parseUrduFixtures(file: File): List<UrduFixture> {
    val jsonText = Files.readString(file.toPath(), StandardCharsets.UTF_8)
    val parsed = SimpleJson(jsonText).parse() as? List<*> ?: error("Expected JSON array of fixtures")
    return parsed.map { raw ->
        @Suppress("UNCHECKED_CAST")
        val map = raw as Map<String, Any?>
        val id = map["id"] as String
        val category = map["category"] as String
        val title = map["title"] as String
        val scalarCount = (map["scalarCount"] as Number).toInt()
        val utf16Length = (map["utf16Length"] as Number).toInt()
        val rawUnits = (map["utf16CodeUnits"] as? List<*>)?.map { (it as Number).toInt() } ?: emptyList()
        val textFromUnits = String(rawUnits.map { it.toChar() }.toCharArray())
        val text = (map["text"] as? String) ?: textFromUnits
        val executionScope = map["executionScope"] as String
        val operations = (map["operations"] as? List<*>)?.map { it.toString() } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val expected = map["expectedOutcome"] as? Map<String, Any?>
        val forbidden = (expected?.get("forbiddenInCanonical") as? List<*>)?.map { it.toString() } ?: emptyList()
        val spansRaw = (expected?.get("formattingSpans") as? List<*>) ?: emptyList<Any?>()
        val spans = spansRaw.map { sRaw ->
            @Suppress("UNCHECKED_CAST")
            val sMap = sRaw as Map<String, Any?>
            UrduFormattingSpan(
                start = (sMap["start"] as Number).toInt(),
                end = (sMap["end"] as Number).toInt(),
                bold = sMap["bold"] == true,
                italic = sMap["italic"] == true,
            )
        }
        UrduFixture(
            id = id,
            category = category,
            title = title,
            text = text,
            scalarCount = scalarCount,
            utf16Length = utf16Length,
            utf16CodeUnits = rawUnits,
            executionScope = executionScope,
            operations = operations,
            forbiddenInCanonical = forbidden,
            formattingSpans = spans,
        )
    }
}

/** Executes the declared operation list. Coverage is never inferred from category names. */
fun runUrduEngineHarness(
    fixtures: List<UrduFixture>,
    perFixtureStatus: MutableMap<String, String> = LinkedHashMap(),
): Map<String, Any> {
    val executed = LinkedHashMap<String, List<String>>()
    val counts = LinkedHashMap<String, Int>()
    for (f in fixtures) {
        if (f.executionScope != "EXECUTABLE_PROTOTYPE") {
            perFixtureStatus[f.id] = f.executionScope
            continue
        }
        check(f.operations.isNotEmpty()) { "${f.id}: Missing operations" }
        val composing = f.operations.any { it.startsWith("ime_") }
        val seed = if (composing) TextStyleFlags(bold = true, italic = true) else TextStyleFlags()
        val engine = EditorEngine(Document(emptyList(), seed))
        val baseline = engine.snapshot()
        var unformatted: EditorFrame? = null
        var formatted: EditorFrame? = null
        var styleSteps = 0
        val done = ArrayList<String>()
        fun verifyUnits() {
            check(engine.snapshot().visible.units.map { it.char.code } == f.utf16CodeUnits) { "${f.id}: UTF-16 code units mismatch" }
            val scalarCount = engine.snapshot().visible.text.codePoints().filter { it !in 0xD800..0xDFFF }.count().toInt()
            check(scalarCount == f.scalarCount) { "${f.id}: Unicode scalar metadata mismatch; raw surrogate units must remain preserved" }
        }
        fun verifySpans() {
            for (span in f.formattingSpans) {
                for (i in span.start until span.end) {
                    val unit = engine.snapshot().visible.units[i]
                    check(!span.bold || unit.style.bold) { "${f.id}: Unit $i expected bold" }
                    check(!span.italic || unit.style.italic) { "${f.id}: Unit $i expected italic" }
                }
            }
            check(engine.snapshot().visible.text == f.text) { "${f.id}: Formatting changed text" }
        }
        for (op in f.operations) {
            when (op) {
                "insert_text", "insert_raw_units" -> {
                    engine.replace(f.text, "UrduFixture", paragraphBreaks = true)
                    unformatted = engine.snapshot()
                }
                "verify_text" -> check(engine.snapshot().visible.text == f.text) { "${f.id}: Visible text mismatch" }
                "verify_code_units", "verify_utf16_code_units", "verify_code_units_preserved" -> verifyUnits()
                "verify_no_crash" -> check(engine.snapshot().notice == "Ready") { "${f.id}: Engine rejected edit" }
                "save_load_roundtrip" -> {
                    val document = engine.snapshot().visible
                    check(DocumentFiles.decode(DocumentFiles.encode(document)).same(document)) { "${f.id}: Attributed save/load mismatch" }
                }
                "apply_formatting" -> {
                    for (span in f.formattingSpans) {
                        engine.move(Selection(span.start, span.end))
                        if (span.bold && engine.formatState(FormatKey.BOLD) != true) {
                            engine.toggleFormat(FormatKey.BOLD)
                            styleSteps++
                        }
                        if (span.italic && engine.formatState(FormatKey.ITALIC) != true) {
                            engine.toggleFormat(FormatKey.ITALIC)
                            styleSteps++
                        }
                    }
                    formatted = engine.snapshot()
                }
                "verify_spans" -> verifySpans()
                "undo" -> repeat(styleSteps) { engine.perform(EditAction.Undo) }
                "verify_baseline" -> check(engine.snapshot().visible.same(checkNotNull(unformatted).visible)) { "${f.id}: Undo failed to restore unstyled baseline" }
                "redo" -> {
                    repeat(styleSteps) { engine.perform(EditAction.Redo) }
                    check(engine.snapshot().visible.same(checkNotNull(formatted).visible)) { "${f.id}: Redo formatting mismatch" }
                }
                "ime_preedit" -> {
                    engine.inputMethod(f.text, 0, f.text.length)
                    check(engine.snapshot().committed == baseline.committed) { "${f.id}: Provisional text leaked" }
                    check(engine.snapshot().visible.text == f.text)
                }
                "ime_partial_commit" -> {
                    require(f.text.length > 1)
                    engine.inputMethod(f.text, 1, f.text.length - 1)
                    check(engine.snapshot().committed.text == f.text.take(1)) { "${f.id}: Partial commit mismatch" }
                    check(engine.snapshot().visible.text == f.text)
                    check(engine.snapshot().selection == Selection(f.text.length, f.text.length))
                    check(engine.snapshot().undoCount == baseline.undoCount) { "${f.id}: Session split before settlement" }
                }
                "ime_commit_remaining" -> {
                    val remaining = f.text.substring(1)
                    engine.inputMethod(remaining, remaining.length, null)
                    val frame = engine.snapshot()
                    check(frame.committed.text == f.text && frame.visible.text == f.text) { "${f.id}: Accepted prefix lost or duplicated" }
                    check(frame.visible.units.all { it.style == seed }) { "${f.id}: Composition attributes lost" }
                    check(frame.selection == Selection(f.text.length, f.text.length) && frame.marked == null)
                    check(frame.undoCount == baseline.undoCount + 1) { "${f.id}: One session must produce one undo group" }
                    formatted = frame
                }
                "ime_undo_session" -> {
                    engine.perform(EditAction.Undo)
                    check(engine.snapshot().committed.same(baseline.committed) && engine.snapshot().selection == baseline.selection) { "${f.id}: Session Undo mismatch" }
                }
                "ime_redo_session" -> {
                    engine.perform(EditAction.Redo)
                    check(engine.snapshot().committed == checkNotNull(formatted).committed && engine.snapshot().selection == formatted.selection) { "${f.id}: Session Redo mismatch" }
                }
                else -> error("${f.id}: Unsupported fixture operation: $op")
            }
            done += op
            counts[op] = (counts[op] ?: 0) + 1
        }
        executed[f.id] = done
        perFixtureStatus[f.id] = "EXECUTED_PASS"
    }
    return mapOf(
        "executableCount" to executed.size,
        "testedCount" to executed.size,
        "passedCount" to executed.size,
        "executedOperations" to executed,
        "operationCounts" to counts,
        "fixtureExecutionStatus" to perFixtureStatus,
        "status" to "PASS",
    )
}

fun runUrduComposeLayoutProbe(fontsDir: File, outputDir: File): Map<String, Any> {
    outputDir.mkdirs()
    val resolver = createFontFamilyResolver()
    val density = Density(1f)

    val fontTargets = listOf(
        Triple("Noto Nastaliq Urdu", File(fontsDir, "NotoNastaliqUrdu[wght].ttf"), "PRIMARY_CANDIDATE"),
        Triple("Gulzar", File(fontsDir, "Gulzar-Regular.ttf"), "SECONDARY_CANDIDATE"),
        Triple("Noto Sans Arabic", File(fontsDir, "NotoSansArabic[wdth,wght].ttf"), "FALLBACK"),
    )

    val specimens = listOf(
        "prose" to "یہ ایک اردو دستاویز ہے جو کمپیوٹر پر نستعلیق خط میں لکھی گئی ہے۔",
        "ligatures" to "نستعلیق خط میں تحقیق اور تشکیل کی خوبصورتی",
        "tashkeel" to "مُحَمَّدٌ رَسُولُ اللّٰهِ صَلَّى اللّٰهُ عَلَيْهِ وَسَلَّمَ",
        "mixed" to "قیمت: ۱۲۳ روپے (123 USD) برائے سال ۲۰۲۶ء",
        "poetry" to "دل ناداں تجھے ہوا کیا ہے\nآخر اس درد کی دوا کیا ہے",
    )

    val measurements = ArrayList<Map<String, Any>>()
    val renderedImages = ArrayList<String>()

    for ((fontName, fontFile, role) in fontTargets) {
        check(fontFile.exists()) { "Font file missing: ${fontFile.absolutePath}" }
        val font = Font(fontFile)
        val family = FontFamily(font)

        for ((specimenKey, specimenText) in specimens) {
            val style = TextStyle(
                color = androidx.compose.ui.graphics.Color.Black,
                fontFamily = family,
                fontSize = 26.sp,
                textDirection = TextDirection.Rtl,
            )
            val paragraph = Paragraph(
                text = specimenText,
                style = style,
                constraints = Constraints(minWidth = 0, maxWidth = 800),
                density = density,
                fontFamilyResolver = resolver,
            )

            val width = paragraph.width
            val height = paragraph.height
            val lineCount = paragraph.lineCount
            val minIntr = paragraph.minIntrinsicWidth
            val maxIntr = paragraph.maxIntrinsicWidth

            // Measure cursor rect at start and end
            val startCursor = paragraph.getCursorRect(0)
            val endCursor = paragraph.getCursorRect(specimenText.length)

            val linesInfo = (0 until lineCount).map { l ->
                mapOf(
                    "lineIndex" to l,
                    "top" to paragraph.getLineTop(l),
                    "bottom" to paragraph.getLineBottom(l),
                    "left" to paragraph.getLineLeft(l),
                    "right" to paragraph.getLineRight(l),
                )
            }

            measurements.add(
                mapOf(
                    "font" to fontName,
                    "role" to role,
                    "specimen" to specimenKey,
                    "width" to width,
                    "height" to height,
                    "lineCount" to lineCount,
                    "minIntrinsicWidth" to minIntr,
                    "maxIntrinsicWidth" to maxIntr,
                    "cursorAt0" to mapOf("left" to startCursor.left, "top" to startCursor.top, "bottom" to startCursor.bottom),
                    "cursorAtEnd" to mapOf("left" to endCursor.left, "top" to endCursor.top, "bottom" to endCursor.bottom),
                    "lines" to linesInfo,
                ),
            )

            // Render to Skia Bitmap -> PNG
            val bitmapWidth = width.roundToInt().coerceAtLeast(1) + 20
            val bitmapHeight = height.roundToInt().coerceAtLeast(1) + 20
            val bitmap = Bitmap().apply { allocN32Pixels(bitmapWidth, bitmapHeight) }
            val skiaCanvas = org.jetbrains.skia.Canvas(bitmap)
            skiaCanvas.clear(0xFFFFFFFF.toInt())
            val composeCanvas = skiaCanvas.asComposeCanvas()
            paragraph.paint(composeCanvas, color = androidx.compose.ui.graphics.Color.Black)
            var inkSamples = 0
            for (y in 0 until bitmapHeight step 2) {
                for (x in 0 until bitmapWidth step 2) {
                    if (bitmap.getColor(x, y) and 0x00FFFFFF != 0x00FFFFFF) inkSamples++
                }
            }
            check(inkSamples > 20) { "Blank typography raster: $fontName/$specimenKey ($inkSamples ink samples)" }
            measurements[measurements.lastIndex] = measurements.last() + ("nonWhiteInkSamples" to inkSamples)

            val safeFontSlug = fontName.replace(" ", "_").lowercase()
            val pngFile = File(outputDir, "layout-probe-$safeFontSlug-$specimenKey.png")
            val image = Image.makeFromBitmap(bitmap)
            val data = image.encodeToData(EncodedImageFormat.PNG)
            if (data != null) {
                Files.write(pngFile.toPath(), data.bytes)
                renderedImages.add(pngFile.name)
            }
            data?.close()
            image.close()
            skiaCanvas.close()
            bitmap.close()
        }
    }

    return mapOf(
        "fontsTested" to fontTargets.size,
        "specimensPerFont" to specimens.size,
        "totalMeasurements" to measurements.size,
        "renderedImages" to renderedImages,
        "measurements" to measurements,
        "status" to "PASS",
    )
}

fun runUrduChecks(args: Array<String>) {
    fun option(name: String, fallback: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=') ?: fallback
    val fixtures = parseUrduFixtures(File(option("urdu-fixtures", "tests/suites/urdu/u0/fixtures.json")))
    val output = File(option("urdu-output", "tests/local-evidence/desktop/urdu-u0"))
    output.mkdirs()
    val result = linkedMapOf<String, Any>(
        "gate" to "U0",
        "timestamp" to java.time.Instant.now().toString(),
        "totalFixtures" to fixtures.size,
        "engineHarness" to runUrduEngineHarness(fixtures),
    )
    result["typographyProfiles"] = runTypographyProfileChecks(File(option("urdu-fonts", "tests/suites/urdu/u0/fonts")))
    if ("--urdu-engine-only" !in args) {
        result["composeLayoutProbe"] = runUrduComposeLayoutProbe(File(option("urdu-fonts", "tests/suites/urdu/u0/fonts")), output)
    }
    result["implementationMutationTesting"] = "Separate source-mutant runner; see mutation-results.json"
    result["status"] = "PASS"
    fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> if (c.code < 32 || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
    fun json(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is String -> "\"${escape(value)}\""
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { "${json(it.key.toString())}:${json(it.value)}" }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
        else -> error("Unsupported JSON value")
    }
    File(output, "urdu-harness-results.json").writeText(json(result), Charsets.UTF_8)
    println("PASS urdu-engine-harness; fixture operations executed; native qualification NOT RUN")
}
