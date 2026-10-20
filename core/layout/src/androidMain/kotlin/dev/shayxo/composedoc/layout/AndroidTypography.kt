package dev.shayxo.composedoc.layout

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import dev.shayxo.composedoc.model.SimpleJson
import dev.shayxo.composedoc.model.StoredFont
import java.io.InputStream
import java.security.MessageDigest

/** Android-only pinned asset loader. Requested stored identities are never rewritten on failure. */
class AndroidTypography private constructor(
    private val families: Map<StoredFont, FontFamily>,
    val status: String,
    val unavailable: Set<StoredFont>,
) {
    fun family(font: StoredFont): FontFamily = when (font) {
        StoredFont.DEFAULT -> families[StoredFont.NOTO_NASTALIQ] ?: FontFamily.Default
        StoredFont.SYSTEM -> FontFamily.Default
        else -> families[font] ?: FontFamily.Default
    }

    companion object {
        private const val ROOT = "dev/shayxo/composedoc/layout/fonts/"
        private const val MAX_FONT_BYTES = 2_000_000
        private const val MAX_MANIFEST_BYTES = 64_000
        private const val RESOURCE_READ_BUFFER_BYTES = 8192
        private const val MANIFEST_SHA = "60c6f496fad4805dfdf6889b59e037d31a7d3287f5a6fc483f7dca4808bb372c"
        private val files = linkedMapOf(
            StoredFont.NOTO_NASTALIQ to "NotoNastaliqUrdu[wght].ttf",
            StoredFont.GULZAR to "Gulzar-Regular.ttf",
            StoredFont.NASKH to "NotoSansArabic[wdth,wght].ttf",
        )

        /** Call on an IO worker. [open] is an explicit asset IO port, useful for offline/failure qualification. */
        fun load(context: Context, open: (String) -> InputStream = { context.assets.open(it) }): AndroidTypography {
            val manifest = open(ROOT + "fonts.json").use { bounded(it, MAX_MANIFEST_BYTES) }
            require(hash(manifest) == MANIFEST_SHA) { "Pinned Android font manifest identity mismatch" }
            val parsed = SimpleJson(manifest.toString(Charsets.UTF_8)).parse() as Map<*, *>
            val entries = (parsed["candidateFonts"] as List<*>) + (parsed["fallbackFonts"] as List<*>)
            val missing = linkedSetOf<StoredFont>()
            val available = linkedMapOf<StoredFont, FontFamily>()
            val notices = mutableListOf<String>()
            files.forEach { (font, filename) ->
                // Reason: the resource boundary exposes any IO/validation/decoder failure as explicit fallback status.
                @Suppress("TooGenericExceptionCaught")
                try {
                    val bytes = open(ROOT + filename).use { bounded(it, MAX_FONT_BYTES) }
                    val entry = entries.map { it as Map<*, *> }.single { it["fileName"] == filename }
                    val lengthMatches = bytes.size.toLong() == (entry["sizeBytes"] as Number).toLong()
                    require(lengthMatches && hash(bytes) == entry["sha256"]) {
                        "Pinned bytes corrupt: $filename"
                    }
                    available[font] = FontFamily(Font(ROOT + filename, context.assets))
                } catch (e: Exception) {
                    missing += font
                    notices += "$font unavailable: ${e.message}; system fallback, stored choice retained"
                }
            }
            return AndroidTypography(
                available,
                if (notices.isEmpty()) "Pinned Android font bytes verified" else notices.joinToString("; "),
                missing,
            )
        }

        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun bounded(input: InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(RESOURCE_READ_BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
                require(output.size() <= limit) { "Pinned Android resource exceeds size limit" }
            }
            return output.toByteArray()
        }
    }
}
