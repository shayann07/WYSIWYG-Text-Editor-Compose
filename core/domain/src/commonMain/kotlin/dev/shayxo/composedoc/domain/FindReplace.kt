package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.*
data class FindMatch(val start: Int, val end: Int, val editable: Boolean)
data class FindBasis(val document: Document, val revision: Long, val owner: Any)
enum class FindReplaceResult { Applied, NoChange, Busy, Stale, Invalid, TooLarge }

/** Literal UTF-16 equality. Hard paragraph separators are scope boundaries, never flattened. */
fun findLiteral(document: Document, query: String): List<FindMatch> {
    if (query.isEmpty()) return emptyList()
    val text = document.text
    val stops = Graphemes.stops(text).toHashSet()
    val matches = ArrayList<FindMatch>()
    var start = 0
    fun paragraph(end: Int) {
        val segment = text.substring(start, end)
        var at = segment.indexOf(query)
        while (at >= 0) {
            val a = start + at
            val b = a + query.length
            matches += FindMatch(a, b, a in stops && b in stops)
            at = segment.indexOf(query, at + query.length)
        }
    }
    document.units.forEachIndexed { index, unit ->
        if (unit.kind != UnitKind.TEXT) {
            paragraph(index)
            start = index + 1
        }
    }
    paragraph(text.length)
    return matches
}
