import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph as NativeParagraph
import org.jetbrains.skia.paragraph.RectHeightMode
import org.jetbrains.skia.paragraph.RectWidthMode

// Isolate scheduling, using the SAME published Compose builder and native
// paragraph implementation in both paths. No chunking or bidi substitution.
fun main(args: Array<String>) {
    val sizes = args.getOrNull(0)?.split(',')?.map(String::toInt) ?: listOf(32, 1024, 4096, 8192)
    val resolver = createFontFamilyResolver()
    val loaderField = resolver.javaClass.getDeclaredField("platformFontLoader").apply { isAccessible = true }
    val loader = loaderField.get(resolver)
    val getter = loader.javaClass.getDeclaredMethod("getFontCollection").apply { isAccessible = true }
    val collection = getter.invoke(loader) as FontCollection
    collection.paragraphCache.setEnabled(false)
    val clazz = Class.forName("androidx.compose.ui.text.platform.ParagraphLayouter")
    val ctor = clazz.declaredConstructors.single()
    val layout = clazz.getDeclaredMethod("layoutParagraph", Float::class.javaPrimitiveType)
    val style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    fun create(text: String): Any = ctor.newInstance(text, ResolvedTextDirection.Ltr, style,
        emptyList<Any>(), emptyList<Any>(), Density(1f), resolver, { Unit })
    fun at(owner: Any, width: Float) = layout.invoke(owner, width) as NativeParagraph
    fun paintSlice(p: NativeParagraph, y: Float): ByteArray = Surface.makeRasterN32Premul(800, 64).use { surface ->
        surface.canvas.clear(0)
        p.paint(surface.canvas, 0f, -y)
        surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { it.bytes } }
    }
    var comparisons = 0
    fun equal(a: NativeParagraph, b: NativeParagraph, text: String) {
        check(a.height == b.height)
        check(a.minIntrinsicWidth == b.minIntrinsicWidth && a.maxIntrinsicWidth == b.maxIntrinsicWidth)
        check(a.lineMetrics.contentEquals(b.lineMetrics))
        comparisons += 4
        for (y in listOf(0f, (a.height / 2).coerceAtLeast(0f), (a.height - 64).coerceAtLeast(0f))) {
            check(paintSlice(a, y).contentEquals(paintSlice(b, y)))
            comparisons++
            for (x in 0..800 step 40) {
                check(a.getGlyphPositionAtCoordinate(x.toFloat(), y) == b.getGlyphPositionAtCoordinate(x.toFloat(), y))
                comparisons++
            }
        }
        for (i in listOf(0, 1, text.length / 2, text.length - 1)) {
            check(a.getRectsForRange(i, i + 1, RectHeightMode.TIGHT, RectWidthMode.TIGHT)
                .contentEquals(b.getRectsForRange(i, i + 1, RectHeightMode.TIGHT, RectWidthMode.TIGHT)))
            comparisons++
        }
    }
    at(create("a(\u05d0)"), 800f).close()
    val rows = mutableListOf<String>()
    for (groups in sizes) {
        val text = "a" + "(\u05d0)".repeat(groups)
        val baseline = mutableListOf<Double>()
        val finite = mutableListOf<Double>()
        repeat(4) { trial ->
            val results = arrayOfNulls<NativeParagraph>(2)
            val order = if (trial % 2 == 0) listOf(0, 1) else listOf(1, 0)
            for (mode in order) {
                val start = System.nanoTime()
                val owner = create(text)
                if (mode == 0) at(owner, Float.POSITIVE_INFINITY)
                val p = at(owner, 800f)
                val ms = (System.nanoTime() - start) / 1e6
                if (trial > 0) (if (mode == 0) baseline else finite).add(ms)
                results[mode] = p
            }
            equal(results[0]!!, results[1]!!, text)
            results.forEach { it!!.close() }
            check(collection.paragraphCache.count == 0)
        }
        rows.add("{\"length\":${text.length},\"infiniteThenFiniteMs\":${baseline.sorted()[1]},\"finiteOnlyMs\":${finite.sorted()[1]},\"baselineSamples\":$baseline,\"finiteSamples\":$finite}")
        System.err.println("Completed length=${text.length}")
    }
    println("{\"rows\":[${rows.joinToString(",")}],\"comparisons\":$comparisons,\"limits\":\"Same Compose ParagraphLayouter via reflection. Cache disabled. Three measured samples after one warmup, interleaved order. Geometry, hit tests, selection rectangles and three raster slices compared. Single repeated mixed-direction fixture; not general conformance.\"}")
    kotlin.system.exitProcess(0)
}
