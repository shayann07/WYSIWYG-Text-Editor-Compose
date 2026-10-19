package dev.shayxo.composedoc.harness

import androidx.compose.ui.text.TextLayoutResult
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/** Read the installed backend's actual unresolved-codepoint result. Family matching
 * below is an independent fallback diagnostic, not a claim of per-glyph Compose runs. */
fun observedFontEvidence(f: LanguageFixture, layout: TextLayoutResult): Map<String, Any> {
    val infos = layout.multiParagraph.javaClass.getDeclaredField("paragraphInfoList").also {
        it.isAccessible = true
    }.get(layout.multiParagraph) as List<*>
    val actual = infos.map { info ->
        val part = checkNotNull(info).javaClass.getMethod("getParagraph").invoke(info)
        val native = part.javaClass.getDeclaredField("paragraph").also {
            it.isAccessible = true
        }.get(part) as org.jetbrains.skia.paragraph.Paragraph
        val default = part.javaClass.methods.single { it.name.startsWith("getDefaultFont") }.invoke(part) as Font
        val family = default.typeface!!.use { it.familyName }
        mapOf(
            "defaultFamily" to family,
            "unresolvedCodePoints" to native.unresolvedCodepoints.toList(),
            "unresolvedGlyphs" to native.unresolvedGlyphsCount,
        )
    }
    val primary = checkNotNull(FontMgr.default.makeFromFile("tests/suites/urdu/u0/fonts/NotoNastaliqUrdu[wght].ttf"))
    val fallback = primary.use { face ->
        f.text.codePoints().distinct().toArray().map { cp ->
            if (face.getUTF32Glyph(cp).toInt() != 0) {
                mapOf("cp" to cp, "family" to face.familyName, "fallback" to false, "glyphPresent" to true)
            } else {
                val match = FontMgr.default.matchFamilyStyleCharacter(
                    face.familyName,
                    FontStyle.NORMAL,
                    arrayOf(f.id),
                    cp,
                )
                if (match == null) {
                    mapOf("cp" to cp, "family" to "MISSING", "fallback" to true, "glyphPresent" to false)
                } else {
                    match.use {
                        mapOf(
                            "cp" to cp,
                            "family" to it.familyName,
                            "fallback" to true,
                            "glyphPresent" to (it.getUTF32Glyph(cp).toInt() != 0),
                        )
                    }
                }
            }
        }
    }
    return mapOf(
        "language" to f.id,
        "composeObserved" to actual,
        "perCodepointFontMgrResolution" to fallback,
        "scope" to
            "Actual Compose default font and unresolved result; FontMgr fallback diagnostic. " +
            "Exact per-glyph fallback family API unavailable; do not infer linguistic or IME qualification.",
    )
}
