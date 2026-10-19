package dev.shayxo.composedoc.layout

import androidx.compose.ui.text.TextLayoutResult
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.paragraphs
/** Presentation only: marker ownership and root-side nesting never alter raw text. */
data class ListMarkerLayout(
    val text: String,
    val layout: TextLayoutResult?,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val baseline: Float,
    val bulletRadius: Float,
    val opticalCenter: Float,
    val nominalSize: Int,
    val fontKey: String,
    val rtl: Boolean,
)
data class ListColumn(val left: Float, val width: Float, val rootRtl: Boolean, val gutter: Float)
data class ListNominal(val font: StoredFont, val size: Int)

// Reason: public API (see apiCheck); the parameter is part of the published signature.
@Suppress("UnusedParameter")
fun Document.listNominal(item: String, defaultSize: Int): ListNominal {
    val seed = paragraphs().first { it.properties.item == item }.properties.markerTypography
    return ListNominal(seed.font, seed.size)
}
fun Document.listRoot(item: String): String {
    val props = paragraphs().filter {
        it.properties.item.isNotEmpty()
    }.associate { it.properties.item to it.properties }
    var id = item
    val seen = HashSet<String>()
    while (props.getValue(id).parent.isNotEmpty()) {
        require(seen.add(id))
        id = props.getValue(id).parent
    }
    return id
}
