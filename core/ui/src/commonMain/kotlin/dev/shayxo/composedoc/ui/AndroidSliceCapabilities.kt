package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.RunningContent
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.validateStructure

/** Continuous rich-body support; unsupported page semantics never disappear into a flattened draft. */
fun androidTextSliceLimitation(document: Document): String? = when {
    document.running != RunningContent() ->
        "Running headers/footers are not supported by the Android continuous editor"
    document.pageSettings != PageSettings() -> "Custom page layout is not supported by the Android continuous editor"
    else -> androidBodyLimitation(document)
}

internal fun androidBodyLimitation(document: Document): String? = when {
    document.units.any {
        it.kind == UnitKind.PARAGRAPH_BREAK && it.structuralBreak == StructuralBreak.PAGE
    } -> "Explicit page breaks are not supported by the Android continuous editor"
    document.paragraphs().any {
        it.properties.pagination != PaginationRules()
    } -> "Pagination rules are not supported by the Android continuous editor"
    else -> runCatching {
        validateStructure(document)
        null
    }.getOrElse { "Invalid document structure: ${it.message}" }
}
