package editor

import java.security.MessageDigest
import java.util.Properties

/** Small JVM port of the frozen v5 Unicode 16 reference. No platform Intl dependency. */
object Graphemes {
    private data class Range(val start: Int, val end: Int, val value: String)
    private val hashes by lazy { Properties().apply { resource("hashes.properties").inputStream().use(::load) } }
    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/unicode/$name")) { "Missing Unicode data: $name" }.use { it.readBytes() }
    fun verified(name: String): String {
        val bytes = resource(name)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == hashes.getProperty(name)) { "Unicode data checksum mismatch: $name" }
        return bytes.toString(Charsets.UTF_8)
    }
    private fun ranges(name: String, pick: (List<String>) -> String?): List<Range> = verified(name).lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.mapNotNull {
        val parts = it.split(';').map(String::trim)
        val value = pick(parts) ?: return@mapNotNull null
        val ends = parts[0].split("..").map { hex -> hex.toInt(16) }
        Range(ends[0], ends.last(), value)
    }.sortedBy { it.start }.toList()
    private val gcb by lazy { ranges("GraphemeBreakProperty.txt") { it[1] } }
    private val ep by lazy { ranges("emoji-data.txt") { if (it[1] == "Extended_Pictographic") "EP" else null } }
    private val incb by lazy { ranges("DerivedCoreProperties.txt") { if (it[1] == "InCB") it[2] else null } }
    private fun find(table: List<Range>, cp: Int, fallback: String): String {
        var lo = 0; var hi = table.lastIndex
        while (lo <= hi) { val mid = (lo + hi) ushr 1; val r = table[mid]; when { cp < r.start -> hi = mid - 1; cp > r.end -> lo = mid + 1; else -> return r.value } }
        return fallback
    }
    private data class Scalar(val offset: Int, val g: String, val pictograph: Boolean, val indic: String)
    fun stops(text: String): List<Int> {
        if (text.isEmpty()) return listOf(0)
        val scalars = ArrayList<Scalar>(); var offset = 0
        while (offset < text.length) {
            val cp = Character.codePointAt(text, offset)
            // Ill-formed UTF-16 has hard boundaries; retained storage is never rewritten.
            scalars += Scalar(offset, if (cp in 0xd800..0xdfff) "Control" else find(gcb, cp, "Other"), find(ep, cp, "") == "EP", find(incb, cp, "None"))
            offset += Character.charCount(cp)
        }
        val stops = arrayListOf(0)
        fun control(g: String) = g in setOf("CR", "LF", "Control")
        for (k in 1..scalars.lastIndex) {
            val l = scalars[k - 1]; val r = scalars[k]
            var join = when {
                l.g == "CR" && r.g == "LF" -> true
                control(l.g) || control(r.g) -> false
                l.g == "L" && r.g in setOf("L", "V", "LV", "LVT") -> true
                l.g in setOf("LV", "V") && r.g in setOf("V", "T") -> true
                l.g in setOf("LVT", "T") && r.g == "T" -> true
                r.g in setOf("Extend", "ZWJ", "SpacingMark") || l.g == "Prepend" -> true
                r.indic == "Consonant" -> { var j = k - 1; var linker = false; while (j >= 0 && scalars[j].indic in setOf("Extend", "Linker")) { linker = linker || scalars[j].indic == "Linker"; j-- }; linker && j >= 0 && scalars[j].indic == "Consonant" }
                else -> false
            }
            if (!join && !control(l.g) && !control(r.g) && r.pictograph && l.g == "ZWJ") { var j = k - 2; while (j >= 0 && scalars[j].g == "Extend") j--; join = j >= 0 && scalars[j].pictograph }
            if (!join && l.g == "Regional_Indicator" && r.g == "Regional_Indicator") { var count = 0; var j = k - 1; while (j >= 0 && scalars[j].g == "Regional_Indicator") { count++; j-- }; join = count % 2 == 1 }
            if (!join) stops += r.offset
        }
        stops += text.length
        return stops
    }
    fun snap(text: String, offset: Int, after: Boolean = true): Int { val stops = stops(text); return if (after) stops.first { it >= offset.coerceIn(0, text.length) } else stops.last { it <= offset.coerceIn(0, text.length) } }
}
