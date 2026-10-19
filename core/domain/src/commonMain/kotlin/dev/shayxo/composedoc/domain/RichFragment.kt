package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.*
data class ClipboardCapture(val basis: FindBasis, val selection: Selection, val sessionRevision: Long)
data class RichFragment(val document: Document, val complete: List<Boolean>) {
    init {
        require(complete.size == document.paragraphs().size)
    }
    companion object {
        fun capture(c: ClipboardCapture): RichFragment {
            val d = c.basis.document
            val a = c.selection.start
            val b = c.selection.end
            require(a < b)
            val blocks = d.paragraphs()
            val first = d.paragraphAt(a)
            val selected = d.units.subList(a, b).map { it.copy(followingBlockId = Long.MIN_VALUE) }
            val paragraphSeed = if (first.start == 0) d.startStyle else d.units[first.start - 1].paragraphStyle
            val seed = if (a == first.start) paragraphSeed else selected.firstOrNull { it.kind == UnitKind.TEXT }?.style ?: paragraphSeed
            val resourceIds = selected.mapNotNull { it.image?.resource }.toSet()
            var doc = Document(selected, seed, first.properties, d.resources.filterKeys { it in resourceIds }, pageSettings = d.pageSettings)
            // A fragment can omit an ancestor item. Close those roots explicitly.
            val items = doc.paragraphs().map { it.properties.item }.toSet()
            doc = doc.patchParagraphs(doc.paragraphs().filter { it.properties.parent.isNotEmpty() && it.properties.parent !in items }.associate { it.anchor to it.properties.copy(parent = "") })
            val flags = doc.paragraphs().map { p ->
                val source = blocks.last { it.start <= a + p.start }
                a + p.start == source.start && (if (source.hasBreak) b > source.end else b == d.units.size)
            }
            return RichFragment(doc, flags)
        }
    }
}
