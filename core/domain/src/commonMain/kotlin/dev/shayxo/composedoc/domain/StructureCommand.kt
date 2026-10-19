package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ImageResource
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.RunningContent

sealed interface StructureCommand {
    data object PageBreak : StructureCommand
    data class Pagination(val rules: PaginationRules) : StructureCommand
    data class Running(val value: RunningContent) : StructureCommand
    data class Pages(val settings: PageSettings) : StructureCommand
    data class ReplaceImage(val resource: String, val data: ImageResource) : StructureCommand
    data class Role(val level: Int) : StructureCommand
    data class ListType(val kind: ListKind) : StructureCommand
    data object Indent : StructureCommand
    data object Outdent : StructureCommand
    data class Restart(val value: Int) : StructureCommand
    data class MarkerStyle(val typography: MarkerTypography) : StructureCommand
    data class Enter(val sameItem: Boolean) : StructureCommand
    data class Link(val uri: String?, val label: String = "") : StructureCommand
    data class InsertImage(val image: ImageBlock, val data: ImageResource) : StructureCommand
    data class ImageProperties(val width: Int, val alt: String) : StructureCommand
}
