package dev.shayxo.composedoc.harness

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.layout.StoredFonts
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.evidenceJson
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.Paragraph
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Standalone consumer run from outside the checkout using actual distribution jars. */
object FontPortabilityChecks {
    private val filenames = linkedMapOf(
        "noto-nastaliq" to "NotoNastaliqUrdu[wght].ttf",
        "gulzar" to "Gulzar-Regular.ttf",
        "naskh" to "NotoSansArabic[wdth,wght].ttf",
    )
    private val fixtures = listOf(
        "کتاب، قلم اور تحقیق — مُحَمَّد قُرآن نَستَعلیق۔",
        "اردو English café e\u0301 12.5 (۲۰۲۶) example.org — العربية لَا، می\u200cروم۔",
        "\u0650کِ ک\u200dتاب \u2066English [42]\u2069 اردو 👩🏽‍💻",
        List(7) { "یہ عبارت مختلف سطروں میں لپٹتی ہے، نشانات اور English 123 محفوظ رہتے ہیں۔" }.joinToString(" "),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val result = File(args.single { it.startsWith("--result=") }.substringAfter('='))
        val expectedFailure = args.firstOrNull { it.startsWith("--expect-packaged-failure=") }?.substringAfter('=')
        val receipt = if (expectedFailure == null) {
            checkPackagedFonts(File(args.single { it.startsWith("--reference-fonts=") }.substringAfter('=')))
        } else {
            checkPackagedFailure(expectedFailure)
        }
        result.writeText(evidenceJson(receipt), Charsets.UTF_8)
        println("PASS packaged font portability")
    }

    private fun checkPackagedFonts(reference: File): Map<String, Any> {
        check(!File("tests/suites/urdu/u0/fonts").exists()) { "Run this consumer outside the checkout" }
        val source = TypographyProfile::class.java.protectionDomain.codeSource.location.toExternalForm()
        check(source.endsWith(".jar")) { "Expected actual packaged layout jar, found $source" }
        val rows = filenames.map { (id, filename) -> checkFont(id, filename, reference) }
        val failures = checkStrictOverrides(reference)
        return mapOf(
            "status" to "PASS",
            "layoutArtifact" to source,
            "workingDirectory" to File("").absolutePath,
            "profiles" to rows,
            "strictOverrideFailures" to failures,
            "scope" to
                "Packaged pinned font identity, exact file-route geometry and raster parity; not expert typography",
        )
    }

    private fun checkFont(id: String, filename: String, reference: File): Map<String, Any> {
        val packaged = TypographyProfile.load(id)
        val explicit = TypographyProfile.load(id, reference)
        check(packaged.key == explicit.key && packaged.sizeSp == explicit.sizeSp)
        check(packaged.requestedFamilyName == explicit.requestedFamilyName)
        val legacy = packaged.copy(family = FontFamily(Font(File(reference, filename))))
        val cases = fixtures.mapIndexed { index, raw ->
            val actual = measure(packaged, raw)
            val previous = measure(legacy, raw)
            check(geometry(actual) == geometry(previous)) { "Geometry changed: $id fixture$index" }
            val image = raster(actual)
            check(image.contentEquals(raster(previous))) { "Rendered pixels changed: $id fixture$index" }
            mapOf("case" to index + 1, "lines" to actual.lineCount, "pngSha256" to sha256(image))
        }
        val stored = StoredFonts.profile(
            when (id) {
                "noto-nastaliq" -> StoredFont.NOTO_NASTALIQ
                "gulzar" -> StoredFont.GULZAR
                else -> StoredFont.NASKH
            },
        )
        check(stored?.key == packaged.key && StoredFonts.unavailable.isEmpty())
        return mapOf(
            "id" to id,
            "key" to packaged.key,
            "family" to packaged.requestedFamilyName,
            "size" to packaged.sizeSp,
            "exactGeometryAndRasterCases" to cases,
        )
    }

    private fun measure(profile: TypographyProfile, text: String): TextLayoutResult = TextMeasurer(
        createFontFamilyResolver(),
        Density(1f),
        LayoutDirection.Ltr,
        0,
    ).measure(
        AnnotatedString(text),
        style = TextStyle(fontFamily = profile.family, fontSize = profile.sizeSp.sp, textDirection = TextDirection.Rtl),
        constraints = Constraints(minWidth = 600, maxWidth = 600),
    )

    private fun geometry(layout: TextLayoutResult): List<Any> = listOf(
        layout.size,
        layout.lineCount,
        (0 until layout.lineCount).map {
            listOf(
                layout.getLineStart(it),
                layout.getLineEnd(it),
                layout.getLineTop(it),
                layout.getLineBottom(it),
                layout.getLineBaseline(it),
                layout.getLineLeft(it),
                layout.getLineRight(it),
            )
        },
        (0 until layout.layoutInput.text.length).map { layout.getBoundingBox(it) },
        (0..layout.layoutInput.text.length).map {
            listOf(
                layout.getCursorRect(it),
                layout.getHorizontalPosition(it, true),
                layout.getHorizontalPosition(it, false),
            )
        },
    )

    private fun raster(layout: TextLayoutResult): ByteArray = Bitmap().use { bitmap ->
        check(bitmap.allocN32Pixels(layout.size.width, layout.size.height))
        Canvas(bitmap).use { canvas ->
            canvas.clear(-1)
            nativeParagraphs(layout).forEach { it.paint(canvas, 0f, 0f) }
        }
        Image.makeFromBitmap(bitmap).use { image -> checkNotNull(image.encodeToData()).use { it.bytes } }
    }

    private fun nativeParagraphs(layout: TextLayoutResult): List<Paragraph> {
        val infos = layout.multiParagraph.javaClass.getDeclaredField("paragraphInfoList").also {
            it.isAccessible = true
        }.get(layout.multiParagraph) as List<*>
        check(infos.size == 1) { "Fixtures must remain a single canonical paragraph" }
        return infos.map { info ->
            val compose = checkNotNull(info).javaClass.getMethod("getParagraph").invoke(info)
            compose.javaClass.getDeclaredField("paragraph").also { it.isAccessible = true }.get(compose) as Paragraph
        }
    }

    private fun checkStrictOverrides(reference: File): List<String> {
        val root = Files.createTempDirectory("composedoc-font-overrides-").toFile()
        try {
            val fonts = File(root, "fonts").apply { mkdir() }
            val manifest = File(root, "fonts.json")
            manifest.writeBytes(File(reference.parentFile, "fonts.json").readBytes())
            expectFailure("file unavailable") { TypographyProfile.load("noto-nastaliq", fonts) }
            File(fonts, filenames.getValue("noto-nastaliq")).writeBytes(byteArrayOf(0, 1, 2))
            expectFailure("font identity mismatch") { TypographyProfile.load("noto-nastaliq", fonts) }
            manifest.writeText("{}")
            expectFailure("manifest identity mismatch") { TypographyProfile.load("noto-nastaliq", fonts) }
            expectFailure("Unknown typography profile") { TypographyProfile.load("unknown-profile") }
            return listOf("missing font", "corrupt font", "corrupt manifest", "unknown profile")
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun checkPackagedFailure(expected: String): Map<String, Any> {
        expectFailure(expected) { TypographyProfile.load("noto-nastaliq") }
        check(StoredFonts.profile(StoredFont.NOTO_NASTALIQ) == TypographyProfile.system)
        check(StoredFonts.unavailable == setOf(StoredFont.NOTO_NASTALIQ))
        return mapOf(
            "status" to "PASS",
            "expectedFailure" to expected,
            "storedFontUnavailableDisclosed" to true,
            "scope" to "Deliberately broken owned runtime jar, strict failure and existing explicit unavailable status",
        )
    }

    private fun expectFailure(message: String, operation: () -> Unit) {
        val failure = runCatching(operation).exceptionOrNull()
        check(failure != null && failure.message.orEmpty().contains(message, ignoreCase = true)) {
            "Expected '$message', received $failure"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
