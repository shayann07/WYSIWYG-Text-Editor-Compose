package dev.shayxo.composedoc.model

/** Prototype document units are96/inch. Display density/zoom are separate transforms. */
const val DOCUMENT_UNITS_PER_INCH = 96f

fun mmToDocument(mm: Float) = mm * DOCUMENT_UNITS_PER_INCH / 25.4f

fun documentToPoints(value: Float) = value * 72f / DOCUMENT_UNITS_PER_INCH

enum class StructuralBreak { PARAGRAPH, PAGE }

data class PaginationRules(
    val breakBefore: Boolean = false,
    val keepWithNext: Boolean = false,
    val keepLines: Boolean = false,
    val widows: Int = 2,
    val orphans: Int = 2,
) {
    init {
        require(widows in 1..5 && orphans in 1..5)
    }
}

enum class RunningField { NONE, PAGE, PAGE_TOTAL }

enum class PageDigits { LATIN, ARABIC_INDIC, EASTERN_ARABIC }

data class RunningFlow(
    val id: String = newStructureIdentity(),
    val text: String = "",
    val style: TextStyleFlags = TextStyleFlags(font = StoredFont.NOTO_NASTALIQ, size = 18),
    val direction: ParagraphDirection = ParagraphDirection.RTL,
    val alignment: ParagraphAlignment = ParagraphAlignment.START,
    val field: RunningField = RunningField.NONE,
) {
    init {
        require(isStructureIdentity(id))
        require(text.length <= 512 && text.count { it == '\n' } <= 3) { "Running text supports512 UTF-16 units and up to4 soft lines" }
        require(
            style.size == 0 || style.size in 8..32,
        ) { "Running text size must be8-32" }
        require(style.link == null)
    }

    fun display(
        page: Int,
        total: Int,
        digits: PageDigits,
    ): String {
        require(page > 0 && total >= page)

        fun number(n: Int): String {
            val zero =
                when (digits) {
                    PageDigits.LATIN -> '0'
                    PageDigits.ARABIC_INDIC -> '\u0660'
                    PageDigits.EASTERN_ARABIC -> '\u06f0'
                }
            return n
                .toString()
                .map {
                    zero +
                        (it - '0')
                }.joinToString("")
        }
        val value =
            when (field) {
                RunningField.NONE -> ""
                RunningField.PAGE -> number(page)
                RunningField.PAGE_TOTAL ->
                    number(page) + " / " +
                        number(total)
            }
        return text + (if (value.isNotEmpty() && text.isNotEmpty())" " else "") + value
    }

    fun same(other: RunningFlow) = copy(id = other.id) == other
}

data class RunningContent(
    val header: RunningFlow? = null,
    val footer: RunningFlow? = null,
    val differentFirst: Boolean = false,
    val digits: PageDigits = PageDigits.LATIN,
) {
    init {
        require(header == null || footer == null || header.id != footer.id) { "Running flows need distinct identities" }
    }

    fun same(other: RunningContent): Boolean {
        fun equal(
            a: RunningFlow?,
            b: RunningFlow?,
        ) = if (a == null || b == null)a == b else a.same(b)
        return differentFirst == other.differentFirst &&
            digits == other.digits &&
            equal(
                header,
                other.header,
            ) &&
            equal(footer, other.footer)
    }

    fun fresh() = copy(header = header?.copy(id = newStructureIdentity()), footer = footer?.copy(id = newStructureIdentity()))
}

fun outputUnicodeIssues(d: Document): List<String> {
    fun invalid(
        label: String,
        text: String,
    ): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i += 2
            } else {
                if (c.isSurrogate())result += "$label UTF-16 offset$i: isolated surrogate U+${c.code.toString(16).uppercase()}"
                i++
            }
        }
        return result
    }
    return invalid("Body", d.text) +
        listOfNotNull(
            d.running.header?.let {
                invalid("Header ${it.id}", it.text)
            },
            d.running.footer?.let { invalid("Footer ${it.id}", it.text) },
        ).flatten()
}
