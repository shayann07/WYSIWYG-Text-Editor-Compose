package dev.shayxo.composedoc.model

/** Rendering projection only: raw constituent styles are never rewritten by segmentation. */
fun displayedStyles(document: Document): List<DisplayStyle> {
    val result = ArrayList<DisplayStyle>()
    var start = 0
    fun block(end: Int) {
        val text = document.units.subList(start, end).joinToString("") { it.char.toString() }
        val stops = Graphemes.stops(text)
        for (i in 0 until stops.lastIndex) {
            val a = start + stops[i]
            val b = start + stops[i + 1]
            result += DisplayStyle(a, b, document.units[a].style)
        }
    }
    for (i in document.units.indices) {
        if (document.units[i].kind == UnitKind.PARAGRAPH_BREAK) {
            block(i)
            start = i + 1
        }
    }
    block(document.units.size)
    return result
}
