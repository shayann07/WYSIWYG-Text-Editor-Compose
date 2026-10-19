package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.TypingSide
import dev.shayxo.composedoc.model.UnitKind

/** Matches must be non-empty, ordered, disjoint, grapheme-aligned and confined to text units. */
internal fun findMatchesInvalid(document: Document, matches: List<FindMatch>): Boolean {
    val stops = Graphemes.stops(document.text).toHashSet()
    fun outOfBounds(m: FindMatch) = m.start < 0 || m.end > document.units.size || m.start >= m.end
    fun offGrapheme(m: FindMatch) = m.start !in stops || m.end !in stops
    val malformed = matches.isEmpty() ||
        matches.any { outOfBounds(it) || offGrapheme(it) } ||
        matches.zipWithNext().any { it.first.end > it.second.start }
    return malformed || matches.any { m -> document.units.subList(m.start, m.end).any { it.kind != UnitKind.TEXT } }
}

internal fun replacedUnitCount(document: Document, matches: List<FindMatch>, text: String): Long =
    document.units.size.toLong() - matches.sumOf { (it.end - it.start).toLong() } + matches.size.toLong() * text.length

internal fun matchesAlreadyReplaced(document: Document, matches: List<FindMatch>, text: String): Boolean {
    val raw = document.text
    return matches.all { m ->
        raw.substring(m.start, m.end) == text &&
            document.units.subList(m.start, m.end).all { it.style == document.units[m.start].style }
    }
}

/** Replaces every validated match in one transaction with one history entry. */
internal fun EditorEngine.applyFoundReplacement(
    basis: FindBasis,
    matches: List<FindMatch>,
    text: String,
    all: Boolean,
) {
    val before = saved()
    val replaced = replaceMatches(dc, matches, text) { seed -> units(text, breaks = false, style = seed) }
    dc = dc.copy(units = replaced.units, startStyle = replaced.startStyle)
    selection = Selection(replaced.caret, replaced.caret)
    intent = if (text.isEmpty()) null else basis.document.units[matches.first().start].style
    side = TypingSide.LEFT
    push(before, if (all) "ReplaceAll" else "ReplaceMatch", false)
    typingBarrier = true
    notice = if (all) "Replaced ${matches.size} matches" else "Match replaced"
}

private class ReplacedUnits(val units: List<TextUnit>, val startStyle: TextStyleFlags, val caret: Int)

private fun replaceMatches(
    document: Document,
    matches: List<FindMatch>,
    text: String,
    replacement: (TextStyleFlags) -> List<TextUnit>,
): ReplacedUnits {
    val result = ArrayList<TextUnit>()
    val firstDeletedStyle = HashMap<Int, TextStyleFlags>()
    val paragraphs = IntArray(document.units.size)
    var paragraph = 0
    document.units.forEachIndexed { i, unit ->
        paragraphs[i] = paragraph
        if (unit.kind == UnitKind.PARAGRAPH_BREAK) paragraph++
    }
    var cursor = 0
    var caret = 0
    for ((index, match) in matches.withIndex()) {
        result.addAll(document.units.subList(cursor, match.start))
        val seed = document.units[match.start].style // Same first-selected-text rule as ordinary noncollapsed Replace.
        firstDeletedStyle.getOrPut(paragraphs[match.start]) { seed }
        result.addAll(replacement(seed)) // New LF is always a soft break.
        if (index == 0) caret = result.size
        cursor = match.end
    }
    result.addAll(document.units.subList(cursor, document.units.size))
    val startStyle = if (text.isEmpty()) {
        // Preserve ordinary deletion's empty-paragraph typing style, without per-match history or quadratic splices.
        restoreEmptyParagraphStyles(result, firstDeletedStyle, document.startStyle)
    } else {
        document.startStyle
    }
    return ReplacedUnits(result, startStyle, caret)
}

/** Paragraph ordinals are those of the document before replacement; deletion never removes separators. */
private fun restoreEmptyParagraphStyles(
    result: ArrayList<TextUnit>,
    firstDeletedStyle: Map<Int, TextStyleFlags>,
    documentStartStyle: TextStyleFlags,
): TextStyleFlags {
    var startStyle = documentStartStyle
    var paragraph = 0
    var blockStart = 0
    fun finishBlock(end: Int) {
        val style = firstDeletedStyle[paragraph]
        if (end == blockStart && style != null) {
            if (blockStart == 0) {
                startStyle = style
            } else {
                result[blockStart - 1] = result[blockStart - 1].copy(paragraphStyle = style)
            }
        }
    }
    for (i in result.indices) {
        if (result[i].kind == UnitKind.PARAGRAPH_BREAK) {
            finishBlock(i)
            paragraph++
            blockStart = i + 1
        }
    }
    finishBlock(result.size)
    return startStyle
}
