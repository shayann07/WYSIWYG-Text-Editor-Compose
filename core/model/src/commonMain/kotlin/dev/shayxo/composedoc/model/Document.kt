package dev.shayxo.composedoc.model

import kotlin.jvm.JvmOverloads

enum class UnitKind { TEXT, PARAGRAPH_BREAK, IMAGE }

enum class FormatKey { BOLD, ITALIC, UNDERLINE, STRIKE }

enum class TypingSide { LEFT, RIGHT }

enum class WrapAffinity { DOWNSTREAM, UPSTREAM }

enum class SegmentOrigin { RETAINED, REPLACEMENT }

data class OverlaySegment(
    val dcStart: Int,
    val dcEnd: Int,
    val units: List<TextUnit>,
    val origin: SegmentOrigin,
)

enum class StoredFont { DEFAULT, SYSTEM, NOTO_NASTALIQ, GULZAR, NASKH }

data class TextStyleFlags
@JvmOverloads
constructor(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val font: StoredFont = StoredFont.DEFAULT,
    val size: Int = 0,
    val color: Int = -1,
    val link: LinkValue? = null,
) {
    init {
        require(size == 0 || size in 8..96)
        require(color in -1..0xffffff)
    }

    fun get(key: FormatKey) = when (key) {
        FormatKey.BOLD -> bold
        FormatKey.ITALIC -> italic
        FormatKey.UNDERLINE -> underline
        FormatKey.STRIKE -> strike
    }

    fun patch(
        key: FormatKey,
        value: Boolean,
    ) = when (key) {
        FormatKey.BOLD -> copy(bold = value)
        FormatKey.ITALIC -> copy(italic = value)
        FormatKey.UNDERLINE -> copy(underline = value)
        FormatKey.STRIKE -> copy(strike = value)
    }

    val bits get() = (if (bold) 1 else 0) or (if (italic) 2 else 0) or (if (underline) 4 else 0) or (if (strike) 8 else 0)

    companion object {
        fun fromBits(bits: Int): TextStyleFlags {
            require(bits in 0..15)
            return TextStyleFlags(
                bits and 1 != 0,
                bits and 2 != 0,
                bits and 4 != 0,
                bits and 8 != 0,
            )
        }
    }
}

data class TextStylePatch(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val font: StoredFont? = null,
    val size: Int? = null,
    val color: Int? = null,
) {
    fun apply(s: TextStyleFlags) = s.copy(
        bold = bold ?: s.bold,
        italic = italic ?: s.italic,
        underline = underline ?: s.underline,
        strike = strike ?: s.strike,
        font = font ?: s.font,
        size = size ?: s.size,
        color = color ?: s.color,
    )
}

data class TextUnit
@JvmOverloads
constructor(
    val id: Long,
    val char: Char,
    val kind: UnitKind = UnitKind.TEXT,
    val style: TextStyleFlags = TextStyleFlags(),
    val paragraphStyle: TextStyleFlags = TextStyleFlags(),
    val followingParagraph: ParagraphProperties = ParagraphProperties(),
    val image: ImageBlock? = null,
    val followingBlockId: Long? = Long.MIN_VALUE,
    val structuralBreak: StructuralBreak = StructuralBreak.PARAGRAPH,
)

data class Document
@JvmOverloads
constructor(
    val units: List<TextUnit>,
    val startStyle: TextStyleFlags = TextStyleFlags(),
    val startParagraph: ParagraphProperties = ParagraphProperties(),
    val resources: Map<String, ImageResource> = emptyMap(),
    val rootBlockId: Long? = null,
    val pageSettings: PageSettings = PageSettings(),
    val running: RunningContent = RunningContent(),
) {
    val text: String get() = units.joinToString("") { it.char.toString() }

    fun same(other: Document) = running.same(
        other.running,
    ) &&
        pageSettings == other.pageSettings &&
        resources == other.resources &&
        startStyle == other.startStyle &&
        startParagraph == other.startParagraph &&
        units.size == other.units.size &&
        units.indices.all {
            units[it].structuralBreak == other.units[it].structuralBreak &&
                units[it].char == other.units[it].char &&
                units[it].kind == other.units[it].kind &&
                units[it].style == other.units[it].style &&
                units[it].paragraphStyle == other.units[it].paragraphStyle &&
                units[it].followingParagraph == other.units[it].followingParagraph &&
                units[it].image == other.units[it].image
        }

    @JvmOverloads fun splice(
        a: Int,
        b: Int,
        inserted: List<TextUnit>,
        canonicalRetainedParagraphs: Boolean = false,
    ): Document {
        // Composition epochs retain raw attribution, but accepted structural moves
        // own paragraph metadata. A later partial commit must not revive old parents.
        val currentHeads =
            if (canonicalRetainedParagraphs) {
                units
                    .filter {
                        it.kind == UnitKind.PARAGRAPH_BREAK
                    }.associateBy { it.id }
            } else {
                emptyMap()
            }
        val admitted =
            if (canonicalRetainedParagraphs) {
                inserted.map { u ->
                    currentHeads[u.id]?.takeIf { u.kind == UnitKind.PARAGRAPH_BREAK }?.let {
                        u.copy(
                            followingParagraph = it.followingParagraph,
                            paragraphStyle = it.paragraphStyle,
                            followingBlockId = it.followingBlockId,
                            structuralBreak = it.structuralBreak,
                        )
                    } ?: u
                }
            } else {
                inserted
            }
        var result = copy(units = units.take(a) + admitted + units.drop(b))
        val removed = units.subList(a, b)
        if (removed.any { it.kind == UnitKind.IMAGE })result = result.repairRemovedImageHeads(this, b)
        if (removed.any { it.kind == UnitKind.PARAGRAPH_BREAK })result = result.repairListParents(this)
        return result
    }
}

data class Selection
@JvmOverloads
constructor(
    val anchor: Int,
    val focus: Int,
    val primary: Boolean = true,
    val wrapAffinity: WrapAffinity = WrapAffinity.DOWNSTREAM,
) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)
    val collapsed get() = anchor == focus
}
