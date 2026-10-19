package dev.shayxo.composedoc.layout

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.model.*
import java.io.File
import java.security.MessageDigest

/** Candidate profiles for the desktop qualification harness; no production font decision. */
data class TypographyProfile(
    val id: String,
    val label: String,
    val family: FontFamily,
    val sizeSp: Int,
    val key: String,
    val requestedFamilyName: String = "SansSerif",
) {
    companion object {
        val system = TypographyProfile("system", "System (legacy)", FontFamily.SansSerif, 18, "system-unpinned")
        val labels = linkedMapOf(
            "system" to "System (legacy)",
            "noto-nastaliq" to "Noto Nastaliq (candidate)",
            "gulzar" to "Gulzar (candidate)",
            "naskh" to "Naskh (fallback specimen)",
        )

        fun load(id: String, fontsDir: File = File("tests/suites/urdu/u0/fonts")): TypographyProfile {
            if (id == "system") return system
            val filename = when (id) {
                "noto-nastaliq" -> "NotoNastaliqUrdu[wght].ttf"
                "gulzar" -> "Gulzar-Regular.ttf"
                "naskh" -> "NotoSansArabic[wdth,wght].ttf"
                else -> error("Unknown typography profile: $id")
            }
            val manifest = SimpleJson(File(fontsDir.parentFile, "fonts.json").readText(Charsets.UTF_8)).parse() as Map<*, *>
            val entries = (manifest["candidateFonts"] as List<*>) + (manifest["fallbackFonts"] as List<*>)
            val entry = entries.map { it as Map<*, *> }.single { it["fileName"] == filename }
            val file = File(fontsDir, filename)
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(bytes.size.toLong() == (entry["sizeBytes"] as Number).toLong() && hash == entry["sha256"]) {
                "Pinned font identity mismatch: $filename; current profile retained"
            }
            val requestedFamily = checkNotNull(org.jetbrains.skia.FontMgr.default.makeFromFile(file.path)).use { it.familyName }
            return TypographyProfile(id, labels.getValue(id), FontFamily(Font(file)), 26, "$id:$hash", requestedFamily)
        }
    }
}

object StoredFonts {
    private val cache = HashMap<StoredFont, TypographyProfile>()
    val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<StoredFont>()

    @Synchronized fun profile(font: StoredFont): TypographyProfile? {
        if (font == StoredFont.DEFAULT) return null
        return cache[font] ?: runCatching {
            TypographyProfile.load(
                when (font) {
                    StoredFont.SYSTEM -> "system"
                    StoredFont.NOTO_NASTALIQ -> "noto-nastaliq"
                    StoredFont.GULZAR -> "gulzar"
                    StoredFont.NASKH -> "naskh"
                    else -> "system"
                },
            )
        }.getOrElse {
            unavailable.add(font)
            TypographyProfile.system
        }.also { cache[font] = it }
    }
}
fun storedSpan(s: TextStyleFlags) = androidx.compose.ui.text.SpanStyle(
    fontWeight = if (s.bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
    fontStyle = if (s.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
    fontFamily = StoredFonts.profile(s.font)?.family,
    fontSize = if (s.size == 0) androidx.compose.ui.unit.TextUnit.Unspecified else with(androidx.compose.ui.unit.Density(1f)) { s.size.toSp() },
    color = if (s.color < 0 && s.link != null) {
        androidx.compose.ui.graphics.Color(0xff174ea6)
    } else if (s.color < 0) {
        androidx.compose.ui.graphics.Color.Unspecified
    } else {
        androidx.compose.ui.graphics.Color(0xff000000L or s.color.toLong())
    },
    textDecoration = androidx.compose.ui.text.style.TextDecoration.combine(
        listOfNotNull(
            if (s.underline || s.link != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            if (s.strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        ),
    ),
)
