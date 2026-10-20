package dev.shayxo.composedoc.layout

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import dev.shayxo.composedoc.model.SimpleJson
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Text size used by the pinned candidate font profiles. */
private const val CANDIDATE_PROFILE_SIZE_SP = 26

/** Colour of hyperlinked text that has no explicit colour. */
private const val LINK_COLOR = 0xff174ea6

/** Opaque alpha channel combined with a stored RGB colour. */
private const val OPAQUE_ALPHA = 0xff000000L

private const val FONT_RESOURCE_ROOT = "/dev/shayxo/composedoc/layout/fonts/"
private const val FONT_MANIFEST_LIMIT = 64_000
private const val FONT_BYTES_LIMIT = 2_000_000
private const val FONT_MANIFEST_SHA256 = "60c6f496fad4805dfdf6889b59e037d31a7d3287f5a6fc483f7dca4808bb372c"

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

        /** Omitted/empty directory uses bundled resources; a supplied directory is a strict override. */
        fun load(id: String, fontsDir: File = File("")): TypographyProfile {
            if (id == "system") return system
            val filename = when (id) {
                "noto-nastaliq" -> "NotoNastaliqUrdu[wght].ttf"
                "gulzar" -> "Gulzar-Regular.ttf"
                "naskh" -> "NotoSansArabic[wdth,wght].ttf"
                else -> error("Unknown typography profile: $id")
            }
            val manifestBytes = typographyBytes(fontsDir, "fonts.json", FONT_MANIFEST_LIMIT)
            require(sha256(manifestBytes) == FONT_MANIFEST_SHA256) {
                "Pinned font manifest identity mismatch; current profile retained"
            }
            val manifest = SimpleJson(manifestBytes.toString(Charsets.UTF_8)).parse() as Map<*, *>
            val entries = (manifest["candidateFonts"] as List<*>) + (manifest["fallbackFonts"] as List<*>)
            val entry = entries.map { it as Map<*, *> }.single { it["fileName"] == filename }
            val bytes = typographyBytes(fontsDir, filename, FONT_BYTES_LIMIT)
            val hash = sha256(bytes)
            require(bytes.size.toLong() == (entry["sizeBytes"] as Number).toLong() && hash == entry["sha256"]) {
                "Pinned font identity mismatch: $filename; current profile retained"
            }
            val requestedFamily = org.jetbrains.skia.Data.makeFromBytes(bytes).use { data ->
                checkNotNull(org.jetbrains.skia.FontMgr.default.makeFromData(data)) {
                    "Pinned font could not be decoded: $filename; current profile retained"
                }.use { it.familyName }
            }
            return TypographyProfile(
                id,
                labels.getValue(id),
                FontFamily(Font("composedoc:$hash", bytes)),
                CANDIDATE_PROFILE_SIZE_SP,
                "$id:$hash",
                requestedFamily,
            )
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun typographyBytes(directory: File, filename: String, limit: Int): ByteArray {
    val input = if (directory.path.isEmpty()) {
        checkNotNull(TypographyProfile::class.java.getResourceAsStream(FONT_RESOURCE_ROOT + filename)) {
            "Pinned typography resource unavailable: $filename; current profile retained"
        }
    } else {
        val file = if (filename == "fonts.json") File(directory.parentFile, filename) else File(directory, filename)
        require(file.isFile) { "Pinned typography file unavailable: $file; current profile retained" }
        file.inputStream()
    }
    return input.use { boundedTypographyBytes(it, filename, limit) }
}

private fun boundedTypographyBytes(input: InputStream, filename: String, limit: Int): ByteArray =
    input.readNBytes(limit + 1).also {
        require(it.size <= limit) {
            "Pinned typography resource exceeds size limit: $filename; current profile retained"
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
    fontWeight = if (s.bold) {
        androidx.compose.ui.text.font.FontWeight.Bold
    } else {
        androidx.compose.ui.text.font.FontWeight.Normal
    },
    fontStyle = if (s.italic) {
        androidx.compose.ui.text.font.FontStyle.Italic
    } else {
        androidx.compose.ui.text.font.FontStyle.Normal
    },
    fontFamily = StoredFonts.profile(s.font)?.family,
    fontSize = if (s.size == 0) {
        androidx.compose.ui.unit.TextUnit.Unspecified
    } else {
        with(androidx.compose.ui.unit.Density(1f)) { s.size.toSp() }
    },
    color = if (s.color < 0 && s.link != null) {
        androidx.compose.ui.graphics.Color(LINK_COLOR)
    } else if (s.color < 0) {
        androidx.compose.ui.graphics.Color.Unspecified
    } else {
        androidx.compose.ui.graphics.Color(OPAQUE_ALPHA or s.color.toLong())
    },
    textDecoration = androidx.compose.ui.text.style.TextDecoration.combine(
        listOfNotNull(
            if (s.underline || s.link != null) {
                androidx.compose.ui.text.style.TextDecoration.Underline
            } else {
                null
            },
            if (s.strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        ),
    ),
)
