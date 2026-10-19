package dev.shayxo.composedoc.model

/** Old formats migrate exactly once, from the first substantive cluster, never a vote. */
fun Document.migrateMarkerTypography(): Document {
    val seeds =
        paragraphs().filter { it.properties.item.isNotEmpty() }.groupBy { it.properties.item }.mapValues { (_, ps) ->
            val first = ps.first()
            val raw = units.subList(first.start, first.end)
            val at =
                raw.indexOfFirst {
                    !it.char.isWhitespace() &&
                        it.char.category != CharCategory.FORMAT &&
                        it.char.code !in 0..31 &&
                        it.char.code !in 127..159
                }
            MarkerTypography.from(
                if (at >= 0) {
                    raw[at].style
                } else if (first.start == 0) {
                    startStyle
                } else {
                    units[first.start - 1].paragraphStyle
                },
            )
        }
    return patchParagraphs(
        paragraphs().filter { it.properties.item.isNotEmpty() }.associate {
            it.anchor to
                it.properties.copy(markerTypography = seeds.getValue(it.properties.item))
        },
    )
}
