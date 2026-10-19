package dev.shayxo.composedoc.model

private const val C0_CONTROL_LAST = 31
private const val ASCII_DELETE = 127
private const val C1_CONTROL_LAST = 159

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
                        it.char.code !in 0..C0_CONTROL_LAST &&
                        it.char.code !in ASCII_DELETE..C1_CONTROL_LAST
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
