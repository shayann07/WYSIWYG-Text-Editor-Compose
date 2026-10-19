package editor

enum class ParagraphDirection { AUTO, RTL, LTR }
enum class ParagraphAlignment { START, CENTER, END }
/** Private prototype semantic seed; DEFAULT is resolved once to the Urdu document default. */
data class MarkerTypography(val font: StoredFont = StoredFont.NOTO_NASTALIQ, val size: Int = 26) {
    init { require(font != StoredFont.DEFAULT && size in 8..96) }
    companion object { fun from(style: TextStyleFlags) = MarkerTypography(if(style.font==StoredFont.DEFAULT) StoredFont.NOTO_NASTALIQ else style.font, if(style.size==0)26 else style.size) }
}
data class ParagraphProperties @JvmOverloads constructor(val direction: ParagraphDirection = ParagraphDirection.AUTO,
    val alignment: ParagraphAlignment = ParagraphAlignment.START,
    val spaceBefore: Int = 0, val spaceAfter: Int = 0, val linePermille: Int = 0, val heading: Int = 0, val item: String = "", val parent: String = "", val list: ListKind = ListKind.NONE, val restart: Int = 0, val markerTypography: MarkerTypography = MarkerTypography(),val pagination:PaginationRules=PaginationRules()) {
    init { require(item.isNotEmpty() || markerTypography==MarkerTypography()) { "Marker typography belongs to a list item" }; require(heading in 0..6 && restart in 0..9999); require(item.length<=64 && parent.length<=64); require((list==ListKind.NONE)==item.isEmpty()); require(item.isNotEmpty() || parent.isEmpty()); require(spaceBefore in 0..200 && spaceAfter in 0..200); require(linePermille == 0 || linePermille in 1000..3000) }
    val bits get() = direction.ordinal or (alignment.ordinal shl 2)
    companion object { fun fromBits(bits: Int): ParagraphProperties {
        require(bits in 0..15 && bits and 3 < 3 && bits shr 2 < 3) { "Invalid paragraph properties" }
        return ParagraphProperties(ParagraphDirection.entries[bits and 3], ParagraphAlignment.entries[bits shr 2])
    } }
}
data class ParagraphBlock(val index: Int, val start: Int, val end: Int, val anchor: Long?, val properties: ParagraphProperties, val hasBreak: Boolean)

/** Owner-scoped paragraph identity can travel independently of the separator during block insertion/move.
 * Long.MIN_VALUE on a newly created separator means use its fresh unit identity; null is a valid original root handle. */
val TextUnit.paragraphAnchor: Long? get()=if(followingBlockId==Long.MIN_VALUE) id else followingBlockId
/** The first paragraph is rooted in Document; each hard separator owns the following paragraph. */
fun Document.paragraphs(): List<ParagraphBlock> {
    val result = ArrayList<ParagraphBlock>(); var start = 0; var anchor: Long? = rootBlockId; var properties = startParagraph
    units.forEachIndexed { i, u -> if (u.kind == UnitKind.PARAGRAPH_BREAK) {
        result += ParagraphBlock(result.size, start, i, anchor, properties, true)
        start = i + 1; anchor = u.paragraphAnchor; properties = u.followingParagraph
    } }
    result += ParagraphBlock(result.size, start, units.size, anchor, properties, false)
    return result
}
fun Document.targetParagraphs(selection: Selection): List<ParagraphBlock> {
    require(selection.start >= 0 && selection.end <= units.size)
    val blocks = paragraphs()
    return if (selection.collapsed) listOf(blocks.last { it.start <= selection.focus })
    else blocks.filter { it.start < selection.end && (it.end + if (it.hasBreak) 1 else 0) > selection.start }
}
fun Document.paragraphAt(offset: Int): ParagraphBlock = targetParagraphs(Selection(offset, offset)).single()

/** Auto's existing first-strong rule excludes isolate contents; neutrals inherit prior resolved direction. */
fun resolveParagraphDirection(units: List<TextUnit>, properties: ParagraphProperties, fallback: ParagraphDirection): ParagraphDirection {
    if (properties.direction != ParagraphDirection.AUTO) return properties.direction
    var i = 0; var isolates = 0
    while (i < units.size) {
        when (units[i].char) {
            '\u2066', '\u2067', '\u2068' -> { isolates++; i++; continue }
            '\u2069' -> { if (isolates > 0) isolates--; i++; continue }
        }
        val cp = if (Character.isHighSurrogate(units[i].char) && i + 1 < units.size && Character.isLowSurrogate(units[i + 1].char)) {
            Character.toCodePoint(units[i++].char, units[i].char)
        } else units[i].char.code
        if (isolates == 0) when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return ParagraphDirection.RTL
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return ParagraphDirection.LTR
        }
        i++
    }
    return if (fallback == ParagraphDirection.RTL) ParagraphDirection.RTL else ParagraphDirection.LTR
}
