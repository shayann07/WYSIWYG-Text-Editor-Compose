package editor

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

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
    val formattingSpans: List<UrduFormattingSpan>
)

internal class SimpleJson(private val src: String) {
    private var pos = 0

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    fun parse(): Any? {
        skipWhitespace()
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> if (ch == '-' || ch.isDigit()) parseNumber() else error("Unexpected char '$ch' at pos $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        check(src[pos++] == '{')
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') { pos++; return map }
        while (pos < src.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            check(src[pos++] == ':')
            val value = parse()
            map[key] = value
            skipWhitespace()
            if (peek() == ',') { pos++; continue }
            if (peek() == '}') { pos++; break }
            error("Expected ',' or '}' at pos $pos")
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        check(src[pos++] == '[')
        val list = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') { pos++; return list }
        while (pos < src.length) {
            list.add(parse())
            skipWhitespace()
            if (peek() == ',') { pos++; continue }
            if (peek() == ']') { pos++; break }
            error("Expected ',' or ']' at pos $pos")
        }
        return list
    }

    private fun parseString(): String {
        check(src[pos++] == '"')
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> error("Unknown escape '\\$esc' at pos $pos")
                }
            } else {
                sb.append(c)
            }
        }
        error("Unterminated string")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) {
            pos++
        }
        val s = src.substring(start, pos)
        return if (s.contains('.') || s.contains('e') || s.contains('E')) s.toDouble() else s.toLong()
    }

    private fun parseBoolean(): Boolean {
        if (src.startsWith("true", pos)) { pos += 4; return true }
        if (src.startsWith("false", pos)) { pos += 5; return false }
        error("Expected boolean at pos $pos")
    }

    private fun parseNull(): Any? {
        if (src.startsWith("null", pos)) { pos += 4; return null }
        error("Expected null at pos $pos")
    }
}

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
                italic = sMap["italic"] == true
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
            formattingSpans = spans
        )
    }
}

enum class FaultInjection {
    NONE,
    SUBSTITUTE_LETTER,   // Silently substitutes Urdu Keheh U+06A9 with Arabic Kaf U+0643
    LOSE_STYLE_ON_UNDO,  // Simulates undo failing to restore plain style (leaves bold lingering)
    MANGLE_SURROGATE     // Silently mangles lone surrogate U+D83D to replacement character U+FFFD
}

fun runUrduEngineHarness(
    fixtures: List<UrduFixture>,
    fault: FaultInjection = FaultInjection.NONE,
    perFixtureStatus: MutableMap<String, String>? = null
): Map<String, Any> {
    val execFixtures = fixtures.filter { it.executionScope == "EXECUTABLE_PROTOTYPE" }
    var tested = 0
    var passed = 0
    var formattingChecked = 0
    var undoRedoChecked = 0
    var compositionChecked = 0
    var saveLoadChecked = 0
    var surrogateChecked = 0

    for (f in execFixtures) {
        tested++
        val engine = EditorEngine()
        val textToInsert = when (fault) {
            FaultInjection.SUBSTITUTE_LETTER -> if (f.text.contains("ک")) f.text.replace("ک", "ك") else f.text
            FaultInjection.MANGLE_SURROGATE -> if (f.text.contains("\uD83D")) f.text.replace("\uD83D", "\uFFFD") else f.text
            else -> f.text
        }

        engine.replace(textToInsert, "UrduFixture", paragraphBreaks = true)
        val snap = engine.snapshot()

        // 1. Exact text and UTF-16 code units fidelity
        check(snap.visible.text == f.text) { "${f.id}: Visible text does not match fixture text!" }
        check(snap.visible.units.size == f.utf16Length) { "${f.id}: Unit count ${snap.visible.units.size} != expected ${f.utf16Length}" }
        val actualUnits = snap.visible.units.map { it.char.code }
        check(actualUnits == f.utf16CodeUnits) { "${f.id}: UTF-16 code units mismatch!" }

        // 2. Binary DocumentFiles save/load persistence
        val encoded = DocumentFiles.encode(snap.visible)
        val decoded = DocumentFiles.decode(encoded)
        check(decoded.text == f.text) { "${f.id}: DocumentFiles decoded text mismatch!" }
        check(decoded.units.map { it.char.code } == f.utf16CodeUnits) { "${f.id}: DocumentFiles decoded code units mismatch!" }
        check(decoded.same(snap.visible)) { "${f.id}: DocumentFiles same() check failed!" }
        saveLoadChecked++

        // 3. Formatting and exact Undo/Redo reversibility
        if (f.category == "formatting_and_undo_redo") {
            formattingChecked++
            if (f.formattingSpans.isNotEmpty()) {
                val initialUnits = engine.snapshot().visible.units
                for (span in f.formattingSpans) {
                    engine.move(Selection(span.start, span.end))
                    if (span.bold && engine.formatState(FormatKey.BOLD) != true) engine.toggleFormat(FormatKey.BOLD)
                    if (span.italic && engine.formatState(FormatKey.ITALIC) != true) engine.toggleFormat(FormatKey.ITALIC)
                }
                val formattedSnap = engine.snapshot()
                for (span in f.formattingSpans) {
                    for (i in span.start until span.end) {
                        val u = formattedSnap.visible.units[i]
                        if (span.bold) check(u.style.bold) { "${f.id}: Unit $i expected bold" }
                        if (span.italic) check(u.style.italic) { "${f.id}: Unit $i expected italic" }
                    }
                }
                // Verify formatted save/load
                val formattedEnc = DocumentFiles.encode(formattedSnap.visible)
                val formattedDec = DocumentFiles.decode(formattedEnc)
                check(formattedDec.same(formattedSnap.visible)) { "${f.id}: Formatted DocumentFiles same() failed" }

                // Undo styles
                val undoCount = f.formattingSpans.count { it.bold } + f.formattingSpans.count { it.italic }
                for (step in 1..undoCount) {
                    engine.perform(EditAction.Undo)
                }
                val undoneSnap = if (fault == FaultInjection.LOSE_STYLE_ON_UNDO) {
                    // Injected behavioral fault: Undo failed to revert bold formatting
                    engine.snapshot().copy(
                        visible = engine.snapshot().visible.copy(
                            units = engine.snapshot().visible.units.map { it.copy(style = TextStyleFlags(bold = true)) }
                        )
                    )
                } else {
                    engine.snapshot()
                }
                check(undoneSnap.visible.same(Document(initialUnits))) { "${f.id}: Undo failed to restore unstyled baseline!" }
                undoRedoChecked++

                // Redo styles
                for (step in 1..undoCount) {
                    engine.perform(EditAction.Redo)
                }
                val redoneSnap = engine.snapshot()
                check(redoneSnap.visible.same(formattedSnap.visible)) { "${f.id}: Redo failed to reapply formatting!" }
            }
        }

        // 4. Input & IME provisional composition, partial commitment, and full settlement
        if (f.category == "input_and_mutation") {
            compositionChecked++
            val testEngine = EditorEngine()
            // Step 1: Provisional composition overlay
            testEngine.inputMethod("ک‍", 0, 1)
            check(testEngine.snapshot().visible.text == "ک‍") { "${f.id}: IME provisional display mismatch" }
            check(testEngine.snapshot().committed.text.isEmpty()) { "${f.id}: IME provisional leaked into committed text!" }

            // Step 2: Partial commit (commit 1 character, remaining provisional)
            testEngine.inputMethod("ک‍", 1, 1)
            check(testEngine.snapshot().committed.text == "ک") { "${f.id}: IME partial commit mismatch" }
            check(testEngine.snapshot().visible.text == "ک‍") { "${f.id}: IME visible text after partial commit mismatch" }

            // Step 3: Full commit with fixture text
            testEngine.inputMethod(f.text, f.text.length, null)
            check(testEngine.snapshot().visible.text == f.text) { "${f.id}: IME committed display mismatch" }
            check(testEngine.snapshot().committed.text == f.text) { "${f.id}: IME committed text mismatch" }

            // Step 4: Multi-step Undo reverting partial commit steps
            testEngine.perform(EditAction.Undo)
            check(testEngine.snapshot().committed.text == "ک") { "${f.id}: IME undo failed to revert to partial commit" }
            testEngine.perform(EditAction.Undo)
            check(testEngine.snapshot().committed.text.isEmpty()) { "${f.id}: IME undo failed to restore baseline" }

            // Step 5: Redo re-applying steps
            testEngine.perform(EditAction.Redo)
            check(testEngine.snapshot().committed.text == "ک")
            testEngine.perform(EditAction.Redo)
            check(testEngine.snapshot().committed.text == f.text) { "${f.id}: IME redo failed to re-commit" }
        }

        // 5. Malformed and raw surrogate unit preservation
        if (f.category == "malformed_and_utf16_edge_cases") {
            surrogateChecked++
            val testEngine = EditorEngine()
            testEngine.replace(f.text, "Malformed")
            check(testEngine.snapshot().visible.units.map { it.char.code } == f.utf16CodeUnits)
            testEngine.selectAll()
            testEngine.delete(true)
            check(testEngine.snapshot().visible.text.isEmpty())
            testEngine.perform(EditAction.Undo)
            check(testEngine.snapshot().visible.units.map { it.char.code } == f.utf16CodeUnits)
        }

        perFixtureStatus?.set(f.id, "EXECUTED_PASS")
        passed++
    }

    return mapOf(
        "executableCount" to execFixtures.size,
        "testedCount" to tested,
        "passedCount" to passed,
        "formattingChecked" to formattingChecked,
        "undoRedoChecked" to undoRedoChecked,
        "compositionChecked" to compositionChecked,
        "saveLoadChecked" to saveLoadChecked,
        "surrogateChecked" to surrogateChecked,
        "fixtureExecutionStatus" to (perFixtureStatus ?: emptyMap<String, String>()),
        "status" to "PASS"
    )
}

fun runUrduComposeLayoutProbe(fontsDir: File, outputDir: File): Map<String, Any> {
    outputDir.mkdirs()
    val resolver = createFontFamilyResolver()
    val density = Density(1f)

    val fontTargets = listOf(
        Triple("Noto Nastaliq Urdu", File(fontsDir, "NotoNastaliqUrdu[wght].ttf"), "PRIMARY_CANDIDATE"),
        Triple("Gulzar", File(fontsDir, "Gulzar-Regular.ttf"), "SECONDARY_CANDIDATE"),
        Triple("Noto Sans Arabic", File(fontsDir, "NotoSansArabic[wdth,wght].ttf"), "FALLBACK")
    )

    val specimens = listOf(
        "prose" to "یہ ایک اردو دستاویز ہے جو کمپیوٹر پر نستعلیق خط میں لکھی گئی ہے۔",
        "ligatures" to "نستعلیق خط میں تحقیق اور تشکیل کی خوبصورتی",
        "tashkeel" to "مُحَمَّدٌ رَسُولُ اللّٰهِ صَلَّى اللّٰهُ عَلَيْهِ وَسَلَّمَ",
        "mixed" to "قیمت: ۱۲۳ روپے (123 USD) برائے سال ۲۰۲۶ء",
        "poetry" to "دل ناداں تجھے ہوا کیا ہے\nآخر اس درد کی دوا کیا ہے"
    )

    val measurements = ArrayList<Map<String, Any>>()
    val renderedImages = ArrayList<String>()

    for ((fontName, fontFile, role) in fontTargets) {
        check(fontFile.exists()) { "Font file missing: ${fontFile.absolutePath}" }
        val font = Font(fontFile)
        val family = FontFamily(font)

        for ((specimenKey, specimenText) in specimens) {
            val style = TextStyle(
                fontFamily = family,
                fontSize = 26.sp,
                textDirection = TextDirection.Rtl
            )
            val paragraph = Paragraph(
                text = specimenText,
                style = style,
                constraints = Constraints(minWidth = 0, maxWidth = 800),
                density = density,
                fontFamilyResolver = resolver
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
                    "right" to paragraph.getLineRight(l)
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
                    "lines" to linesInfo
                )
            )

            // Render to Skia Bitmap -> PNG
            val bitmapWidth = width.roundToInt().coerceAtLeast(1) + 20
            val bitmapHeight = height.roundToInt().coerceAtLeast(1) + 20
            val bitmap = Bitmap().apply { allocN32Pixels(bitmapWidth, bitmapHeight) }
            val skiaCanvas = org.jetbrains.skia.Canvas(bitmap)
            skiaCanvas.clear(0xFFFFFFFF.toInt())
            val composeCanvas = skiaCanvas.asComposeCanvas()
            paragraph.paint(composeCanvas)

            val safeFontSlug = fontName.replace(" ", "_").lowercase()
            val pngFile = File(outputDir, "layout-probe-${safeFontSlug}-${specimenKey}.png")
            val image = Image.makeFromBitmap(bitmap)
            val data = image.encodeToData(EncodedImageFormat.PNG)
            if (data != null) {
                Files.write(pngFile.toPath(), data.bytes)
                renderedImages.add(pngFile.name)
            }
        }
    }

    return mapOf(
        "fontsTested" to fontTargets.size,
        "specimensPerFont" to specimens.size,
        "totalMeasurements" to measurements.size,
        "renderedImages" to renderedImages,
        "measurements" to measurements,
        "status" to "PASS"
    )
}

fun runUrduFaultDetection(fixtures: List<UrduFixture>): Map<String, Any> {
    var letterSubCaught = false
    var undoStyleCaught = false
    var surrogateAlterCaught = false
    var letterSubError = ""
    var undoStyleError = ""
    var surrogateAlterError = ""

    // Mutation 1: Letter substitution fault (Urdu Keheh U+06A9 silently substituted with Arabic Kaf U+0643)
    try {
        runUrduEngineHarness(fixtures, fault = FaultInjection.SUBSTITUTE_LETTER)
    } catch (e: IllegalStateException) {
        letterSubCaught = true
        letterSubError = e.message ?: ""
    }

    // Mutation 2: Undo style loss fault (engine undo reverts text but leaves bold style lingering)
    try {
        runUrduEngineHarness(fixtures, fault = FaultInjection.LOSE_STYLE_ON_UNDO)
    } catch (e: IllegalStateException) {
        undoStyleCaught = true
        undoStyleError = e.message ?: ""
    }

    // Mutation 3: Lone surrogate alteration fault (lone surrogate U+D83D mangled to U+FFFD)
    try {
        runUrduEngineHarness(fixtures, fault = FaultInjection.MANGLE_SURROGATE)
    } catch (e: IllegalStateException) {
        surrogateAlterCaught = true
        surrogateAlterError = e.message ?: ""
    }

    check(letterSubCaught && undoStyleCaught && surrogateAlterCaught) {
        "Fault detection failed: letterSub=$letterSubCaught ($letterSubError), undoStyle=$undoStyleCaught ($undoStyleError), surrogateAlter=$surrogateAlterCaught ($surrogateAlterError)"
    }

    return mapOf(
        "letterSubstitutionCaught" to letterSubCaught,
        "letterSubstitutionError" to letterSubError,
        "undoStyleLossCaught" to undoStyleCaught,
        "undoStyleLossError" to undoStyleError,
        "surrogateAlterationCaught" to surrogateAlterCaught,
        "surrogateAlterationError" to surrogateAlterError,
        "mutationsTested" to 3,
        "status" to "PASS"
    )
}

fun runUrduChecks(args: Array<String>) {
    val fixturesPath = args.firstOrNull { it.startsWith("--urdu-fixtures=") }?.substringAfter('=') ?: "validation/urdu/u0/fixtures.json"
    val fontsPath = args.firstOrNull { it.startsWith("--urdu-fonts=") }?.substringAfter('=') ?: "validation/urdu/u0/fonts"
    val outputPath = args.firstOrNull { it.startsWith("--urdu-output=") }?.substringAfter('=') ?: "desktop-prototype/local-evidence/urdu-u0"

    val fixturesFile = File(fixturesPath)
    val fontsDir = File(fontsPath)
    val outputDir = File(outputPath)
    outputDir.mkdirs()

    println("=== Running Urdu Acceptance Gate U0 Kotlin Harness ===")
    println("Fixtures: ${fixturesFile.absolutePath}")
    println("Fonts:    ${fontsDir.absolutePath}")
    println("Output:   ${outputDir.absolutePath}")

    // 1. Load fixtures
    val fixtures = parseUrduFixtures(fixturesFile)
    println("Loaded ${fixtures.size} fixtures from JSON.")
    val scopeCounts = fixtures.groupBy { it.executionScope }.mapValues { it.value.size }
    println("Scope breakdown: $scopeCounts")

    // 2. Run Engine Harness with per-fixture tracking
    val perFixtureStatus = LinkedHashMap<String, String>()
    for (f in fixtures) {
        when (f.executionScope) {
            "EXECUTABLE_PROTOTYPE" -> {}
            "CORPUS_ONLY" -> perFixtureStatus[f.id] = "CORPUS_ONLY"
            "FUTURE_STRUCTURAL_PAGE" -> perFixtureStatus[f.id] = "FUTURE_STRUCTURAL_PAGE"
            else -> perFixtureStatus[f.id] = "UNKNOWN"
        }
    }
    val engineResult = runUrduEngineHarness(fixtures, fault = FaultInjection.NONE, perFixtureStatus = perFixtureStatus)
    println("PASS urdu-engine-harness: ${engineResult["passedCount"]}/${engineResult["executableCount"]} executable fixtures passed.")
    println("     Formatting checks: ${engineResult["formattingChecked"]}, Undo/Redo checks: ${engineResult["undoRedoChecked"]}, Composition checks: ${engineResult["compositionChecked"]}, Save/Load checks: ${engineResult["saveLoadChecked"]}, Surrogate checks: ${engineResult["surrogateChecked"]}")

    // 3. Run Compose Layout Probe
    val layoutResult = runUrduComposeLayoutProbe(fontsDir, outputDir)
    println("PASS urdu-compose-layout-probe: ${layoutResult["fontsTested"]} fonts measured across ${layoutResult["specimensPerFont"]} specimens.")
    val renderedImages = layoutResult["renderedImages"] as? List<*> ?: emptyList<Any?>()
    println("     Rendered ${renderedImages.size} specimen bitmaps to ${outputDir.name}.")

    // 4. Run Fault Detection / Mutation Testing against real harness
    val faultResult = runUrduFaultDetection(fixtures)
    println("PASS urdu-fault-detection: 3/3 mutation faults successfully caught by harness assertions:")
    println("     - Letter substitution caught: ${faultResult["letterSubstitutionError"]}")
    println("     - Undo style loss caught:    ${faultResult["undoStyleLossError"]}")
    println("     - Surrogate mangling caught:  ${faultResult["surrogateAlterationError"]}")

    // 5. Emit JSON summary
    val summary = mapOf(
        "gate" to "U0",
        "harness" to "Kotlin Compose & EditorEngine",
        "timestamp" to java.time.Instant.now().toString(),
        "totalFixtures" to fixtures.size,
        "scopeBreakdown" to scopeCounts,
        "engineHarness" to engineResult,
        "composeLayoutProbe" to layoutResult,
        "faultDetection" to faultResult,
        "status" to "PASS"
    )

    fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    fun serializeJson(obj: Any?): String = when (obj) {
        null -> "null"
        is Boolean -> obj.toString()
        is Number -> obj.toString()
        is String -> "\"${jsonEscape(obj)}\""
        is List<*> -> obj.joinToString(prefix = "[", postfix = "]") { serializeJson(it) }
        is Map<*, *> -> obj.entries.joinToString(prefix = "{", postfix = "}") { "\"${jsonEscape(it.key.toString())}\":${serializeJson(it.value)}" }
        else -> "\"${jsonEscape(obj.toString())}\""
    }

    val resultJson = serializeJson(summary)
    val resultFile = File(outputDir, "urdu-harness-results.json")
    Files.writeString(resultFile.toPath(), resultJson, StandardCharsets.UTF_8)
    println("Results written to: ${resultFile.absolutePath}")
    println("=== URDU U0 KOTLIN HARNESS COMPLETED SUCCESSFULLY ===")
}
