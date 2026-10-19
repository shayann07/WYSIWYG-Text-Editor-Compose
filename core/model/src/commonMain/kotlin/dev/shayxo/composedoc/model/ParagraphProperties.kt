package dev.shayxo.composedoc.model

import kotlin.jvm.JvmOverloads

private const val MIN_MARKER_SIZE = 8
private const val MAX_MARKER_SIZE = 96
private const val DEFAULT_MARKER_SIZE = 26
private const val MAX_HEADING_LEVEL = 6
private const val MAX_LIST_RESTART = 9999
private const val MAX_LIST_IDENTITY_LENGTH = 64
private const val MAX_PARAGRAPH_SPACE = 200
private const val MIN_LINE_PERMILLE = 1000
private const val MAX_LINE_PERMILLE = 3000
private const val MAX_PARAGRAPH_BITS = 15
private const val DIRECTION_MASK = 3
private const val ALIGNMENT_SHIFT = 2
private const val DIRECTION_COUNT = 3
private const val ALIGNMENT_COUNT = 3
private const val LEFT_TO_RIGHT_ISOLATE = '\u2066'
private const val RIGHT_TO_LEFT_ISOLATE = '\u2067'
private const val FIRST_STRONG_ISOLATE = '\u2068'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'
private const val STRONG_LTR = 1
private const val STRONG_RTL = 2

enum class ParagraphDirection { AUTO, RTL, LTR }

enum class ParagraphAlignment { START, CENTER, END }

/** Private prototype semantic seed; DEFAULT is resolved once to the Urdu document default. */
data class MarkerTypography(val font: StoredFont = StoredFont.NOTO_NASTALIQ, val size: Int = DEFAULT_MARKER_SIZE) {
    init {
        require(font != StoredFont.DEFAULT && size in MIN_MARKER_SIZE..MAX_MARKER_SIZE)
    }

    companion object {
        fun from(style: TextStyleFlags) = MarkerTypography(
            if (style.font ==
                StoredFont.DEFAULT
            ) {
                StoredFont.NOTO_NASTALIQ
            } else {
                style.font
            },
            if (style.size == 0)DEFAULT_MARKER_SIZE else style.size,
        )
    }
}

data class ParagraphProperties
@JvmOverloads
constructor(
    val direction: ParagraphDirection = ParagraphDirection.AUTO,
    val alignment: ParagraphAlignment = ParagraphAlignment.START,
    val spaceBefore: Int = 0,
    val spaceAfter: Int = 0,
    val linePermille: Int = 0,
    val heading: Int = 0,
    val item: String = "",
    val parent: String = "",
    val list: ListKind = ListKind.NONE,
    val restart: Int = 0,
    val markerTypography: MarkerTypography = MarkerTypography(),
    val pagination: PaginationRules = PaginationRules(),
) {
    init {
        require(item.isNotEmpty() || markerTypography == MarkerTypography()) {
            "Marker typography belongs to a list item"
        }
        require(
            heading in 0..MAX_HEADING_LEVEL && restart in 0..MAX_LIST_RESTART,
        )
        require(item.length <= MAX_LIST_IDENTITY_LENGTH && parent.length <= MAX_LIST_IDENTITY_LENGTH)
        require((list == ListKind.NONE) == item.isEmpty())
        require(
            item.isNotEmpty() || parent.isEmpty(),
        )
        require(spaceBefore in 0..MAX_PARAGRAPH_SPACE && spaceAfter in 0..MAX_PARAGRAPH_SPACE)
        require(
            linePermille == 0 || linePermille in MIN_LINE_PERMILLE..MAX_LINE_PERMILLE,
        )
    }

    val bits get() = direction.ordinal or (alignment.ordinal shl ALIGNMENT_SHIFT)

    companion object {
        fun fromBits(bits: Int): ParagraphProperties {
            require(
                bits in 0..MAX_PARAGRAPH_BITS &&
                    bits and DIRECTION_MASK < DIRECTION_COUNT &&
                    bits shr ALIGNMENT_SHIFT < ALIGNMENT_COUNT,
            ) { "Invalid paragraph properties" }
            return ParagraphProperties(
                ParagraphDirection.entries[bits and DIRECTION_MASK],
                ParagraphAlignment.entries[bits shr ALIGNMENT_SHIFT],
            )
        }
    }
}

data class ParagraphBlock(
    val index: Int,
    val start: Int,
    val end: Int,
    val anchor: Long?,
    val properties: ParagraphProperties,
    val hasBreak: Boolean,
)

/** Owner-scoped paragraph identity can travel independently of the separator during block insertion/move.
 * Long.MIN_VALUE on a newly created separator means use its fresh unit identity; null is a valid original root
 * handle. */
val TextUnit.paragraphAnchor: Long? get() = if (followingBlockId == Long.MIN_VALUE) id else followingBlockId

/** The first paragraph is rooted in Document; each hard separator owns the following paragraph. */
fun Document.paragraphs(): List<ParagraphBlock> {
    val result = ArrayList<ParagraphBlock>()
    var start = 0
    var anchor: Long? = rootBlockId
    var properties = startParagraph
    units.forEachIndexed { i, u ->
        if (u.kind == UnitKind.PARAGRAPH_BREAK) {
            result += ParagraphBlock(result.size, start, i, anchor, properties, true)
            start = i + 1
            anchor = u.paragraphAnchor
            properties = u.followingParagraph
        }
    }
    result += ParagraphBlock(result.size, start, units.size, anchor, properties, false)
    return result
}

fun Document.targetParagraphs(selection: Selection): List<ParagraphBlock> {
    require(selection.start >= 0 && selection.end <= units.size)
    val blocks = paragraphs()
    return if (selection.collapsed) {
        listOf(blocks.last { it.start <= selection.focus })
    } else {
        blocks.filter { it.start < selection.end && (it.end + if (it.hasBreak) 1 else 0) > selection.start }
    }
}

fun Document.paragraphAt(offset: Int): ParagraphBlock = targetParagraphs(Selection(offset, offset)).single()

/** Auto's existing first-strong rule excludes isolate contents; neutrals inherit prior resolved direction. */
fun resolveParagraphDirection(
    units: List<TextUnit>,
    properties: ParagraphProperties,
    fallback: ParagraphDirection,
): ParagraphDirection {
    val resolved =
        if (properties.direction != ParagraphDirection.AUTO) properties.direction else firstStrongDirection(units)
    return resolved ?: if (fallback == ParagraphDirection.RTL) ParagraphDirection.RTL else ParagraphDirection.LTR
}

private fun strongParagraphDirection(strong: Int): ParagraphDirection? = when (strong) {
    STRONG_RTL -> ParagraphDirection.RTL
    STRONG_LTR -> ParagraphDirection.LTR
    else -> null
}

private fun firstStrongDirection(units: List<TextUnit>): ParagraphDirection? {
    var i = 0
    var isolates = 0
    var found: ParagraphDirection? = null
    while (found == null && i < units.size) {
        when (units[i].char) {
            LEFT_TO_RIGHT_ISOLATE, RIGHT_TO_LEFT_ISOLATE, FIRST_STRONG_ISOLATE -> {
                isolates++
                i++
            }
            POP_DIRECTIONAL_ISOLATE -> {
                if (isolates > 0) isolates--
                i++
            }
            else -> {
                val cp =
                    if (units[i].char.isHighSurrogate() && i + 1 < units.size && units[i + 1].char.isLowSurrogate()) {
                        scalarValue(units[i++].char, units[i].char)
                    } else {
                        units[i].char.code
                    }
                if (isolates == 0) found = strongParagraphDirection(strongDirection(cp))
                i++
            }
        }
    }
    return found
}
