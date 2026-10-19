import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.ManagedString
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.shaper.IcuBidiRunIterator

private fun millis(start: Long) = (System.nanoTime() - start) / 1_000_000.0
private fun summary(samples: List<Double>): String {
    val s = samples.sorted()
    return "{\"median\":${s[s.size / 2]},\"p95\":${s[kotlin.math.ceil(s.size * .95).toInt() - 1]},\"samples\":${s.size}}"
}

// Test-only reflection: disable the actual FontCollection paragraph cache so
// alternating two equal texts does not time previously shaped paragraph hits.
private fun collectionOf(resolver: FontFamily.Resolver): FontCollection {
    val field = resolver.javaClass.getDeclaredField("platformFontLoader").apply { isAccessible = true }
    val loader = field.get(resolver)
    val getter = loader.javaClass.getDeclaredMethod("getFontCollection").apply { isAccessible = true }
    return getter.invoke(loader) as FontCollection
}

fun main(args: Array<String>) {
    val sizes = args.getOrNull(0)?.split(',')?.map { it.toInt() } ?: listOf(32, 1024, 4096, 8192)
    val trials = args.getOrNull(1)?.toInt() ?: 9
    require(trials >= 4 && sizes.all { it >= 32 })
    val resolver = createFontFamilyResolver()
    val collection = collectionOf(resolver)
    collection.paragraphCache.setEnabled(false)
    val style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    val density = Density(1f)
    val constraints = Constraints(maxWidth = 800)
    // Real native libraries, no window, drawing or native IME session.
    Paragraph("a(\u05d0)", style, constraints, density, resolver)
    val rows = mutableListOf<String>()
    for (groups in sizes) {
      for (brackets in listOf(true, false)) {
        for (prefix in listOf("a", "\u05d0")) {
            val text = prefix + (if (brackets) "(\u05d0)" else ".\u05d0.").repeat(groups)
            val makeMs = mutableListOf<Double>()
            val enumerateMs = mutableListOf<Double>()
            val intrinsicsMs = mutableListOf<Double>()
            val wrapMs = mutableListOf<Double>()
            var runCount = 0
            var lines = 0
            var height = 0f
            // Managed UTF-8/native string construction is outside iterator
            // timings. Paragraph construction receives the JVM UTF-16 string.
                repeat(trials) { trial ->
                    val nativeText = ManagedString(text)
                    var t = System.nanoTime()
                    // The published non-owning path leaves its conversion text
                    // null and crashes next(). Give this iterator ownership of
                    // a freshly prepared native string instead.
                    val iterator = IcuBidiRunIterator(nativeText, true, 0)
                    val make = millis(t)
                    t = System.nanoTime()
                    var lastEnd = 0
                    var count = 0
                    iterator.use {
                        while (it.hasNext() && count < 32) {
                            val run = it.next()
                            check(run.end > lastEnd)
                            check(run.level in 0..2)
                            lastEnd = run.end
                            count++
                        }
                    }
                    val enumerate = millis(t)
                    check(lastEnd <= text.length)
                    if (prefix == "a" && brackets) check(count == 32)
                    if (prefix == "\u05d0" && brackets) check(count == 1 && lastEnd == text.length)
                    if (!brackets) check(count in 2..3 && lastEnd == text.length)
                    runCount = count
                    t = System.nanoTime()
                    val intrinsics = ParagraphIntrinsics(text, style, density = density, fontFamilyResolver = resolver)
                    val intrinsic = millis(t)
                    t = System.nanoTime()
                    val paragraph = Paragraph(intrinsics, constraints)
                    val wrap = millis(t)
                    lines = paragraph.lineCount
                    height = paragraph.height
                    check(lines > 0 && height.isFinite() && height > 0f)
                    check(collection.paragraphCache.count == 0) { "shaped paragraph cache was populated" }
                    if (trial >= 3) {
                        makeMs.add(make); enumerateMs.add(enumerate)
                        intrinsicsMs.add(intrinsic); wrapMs.add(wrap)
                    }
                }
            rows.add("{\"length\":${text.length},\"brackets\":$brackets,\"prefixDirection\":\"${if (prefix == "a") "L" else "R"}\",\"inspectedRuns\":$runCount,\"lines\":$lines,\"height\":$height,\"nativeBidiConstructionMs\":${summary(makeMs)},\"nativeBidiFirst32RunsAndCloseMs\":${summary(enumerateMs)},\"composeIntrinsicsMs\":${summary(intrinsicsMs)},\"composeWrappingMs\":${summary(wrapMs)}}")
            System.err.println("Completed length=${text.length} brackets=$brackets prefix=$prefix")
        }
      }
    }
    println("{\"compose\":\"1.12.1 Desktop\",\"skiko\":\"0.150.1 Windows x64\",\"cache\":\"paragraph cache disabled, fonts warmed\",\"rows\":[${rows.joinToString(",")}],\"limits\":\"ICU iterator is a separate native API measurement, not instrumentation inside Paragraph. Only the first 32 runs are enumerated. Intrinsics includes shaping and infinite-width layout; wrapping reuses that shaped paragraph. Native string preparation excluded from ICU timings. No drawing, OS input, or four-platform claim.\"}")
    // This standalone benchmark owns its JVM; avoid Compose background-thread
    // shutdown interacting with Maven exec's thread-group interruption.
    kotlin.system.exitProcess(0)
}
