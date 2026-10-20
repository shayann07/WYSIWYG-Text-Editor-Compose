package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs

/** Explicit first Android slice boundary; unsupported structure remains intact and is never flattened. */
fun androidTextSliceLimitation(document: Document): String? = when {
    document.units.any {
        it.kind == UnitKind.IMAGE
    } -> "Embedded image editing is not supported by this Android text slice"
    document.paragraphs().any { it.properties.heading != 0 || it.properties.list != ListKind.NONE } ->
        "Heading/list rendering is not yet qualified in the Android text slice"
    document.paragraphs().any {
        it.properties.linePermille != 0
    } -> "Explicit line spacing is not yet qualified on Android"
    document.running.header != null || document.running.footer != null ->
        "Running-content rendering is not supported by the Android continuous text slice"
    else -> null
}
