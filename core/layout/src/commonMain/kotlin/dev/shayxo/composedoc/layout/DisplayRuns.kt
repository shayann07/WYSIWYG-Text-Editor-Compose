package dev.shayxo.composedoc.layout

import dev.shayxo.composedoc.model.DisplayStyle
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.displayedStyles

/** Rendering-only merge. Formatting commands must still use constituent grapheme ranges. */
fun renderedStyleRuns(document: Document): List<DisplayStyle> {
    val merged = ArrayList<DisplayStyle>()
    for (run in displayedStyles(document)) {
        val previous = merged.lastOrNull()
        if (previous != null && previous.end == run.start && previous.style == run.style) {
            merged[merged.lastIndex] = previous.copy(end = run.end)
        } else {
            merged += run
        }
    }
    return merged
}
