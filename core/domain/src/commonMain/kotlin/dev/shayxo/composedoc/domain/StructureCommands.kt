package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ImageResource
import dev.shayxo.composedoc.model.LinkValue
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.RunningContent
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.contiguousLinkAt
import dev.shayxo.composedoc.model.displayedStyles
import dev.shayxo.composedoc.model.freshStructureId
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.model.validateStructure

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

/** Pure semantic reducer; desktop controls only capture commands/targets. */
fun structureEdit(document: Document, target: Selection, anchors: List<Long?>, command: StructureCommand, allocate: () -> Long): Pair<Document, Selection> {
    var d = document
    var selection = target
    val blocks = d.paragraphs()
    val selected = blocks.filter { it.anchor in anchors }
    require(selected.size == anchors.distinct().size) { "Paragraph target deleted" }
    fun change(ps: List<ParagraphBlock>, patch: (ParagraphProperties) -> ParagraphProperties) {
        d = d.patchParagraphs(ps.associate { it.anchor to patch(it.properties) })
    }
    fun selectedItems(): Set<String> {
        val ids = selected.map { it.properties.item }.toSet()
        require("" !in ids) { "Select list items" }
        return ids
    }
    fun subtree(ids: Set<String>): Set<String> {
        val set = ids.toMutableSet()
        repeat(8) { blocks.filter { it.properties.parent in set }.forEach { set += it.properties.item } }
        return set
    }
    when (command) {
        is StructureCommand.Running -> d = d.copy(running = command.value)
        is StructureCommand.Pagination -> change(selected) { it.copy(pagination = command.rules) }
        StructureCommand.PageBreak -> {
            val block = d.paragraphAt(target.start)
            require(d.units.subList(block.start, block.end).none { it.kind == UnitKind.IMAGE }) { "Insert a page break in a text paragraph" }
            val seed = if (target.start == 0)d.startStyle else d.units[target.start - 1].let { if (it.kind == UnitKind.PARAGRAPH_BREAK)it.paragraphStyle else it.style }
            val following = block.properties.copy(heading = 0, pagination = block.properties.pagination.copy(breakBefore = false))
            d = d.splice(target.start, target.end, listOf(TextUnit(allocate(), '\u000c', UnitKind.PARAGRAPH_BREAK, paragraphStyle = seed, followingParagraph = following, structuralBreak = StructuralBreak.PAGE)))
            selection = Selection(target.start + 1, target.start + 1)
        }
        is StructureCommand.Pages -> d = d.copy(pageSettings = command.settings)
        is StructureCommand.ReplaceImage -> {
            val u = d.units.subList(target.start, target.end).singleOrNull()?.takeIf { it.kind == UnitKind.IMAGE } ?: error("Select one image")
            d = d.copy(resources = d.resources + (command.resource to command.data), units = d.units.map { if (it.id == u.id)it.copy(image = it.image!!.copy(resource = command.resource)) else it })
        }
        is StructureCommand.Role -> {
            require(command.level in 0..6)
            require(selected.none { p -> d.units.subList(p.start, p.end).any { it.kind == UnitKind.IMAGE } })
            change(selected) { it.copy(heading = command.level) }
        }
        is StructureCommand.ListType -> {
            val ids = selected.map { it.properties.item }.filter { it.isNotEmpty() }.toSet()
            val scope = blocks.filter { it.anchor in anchors || it.properties.item in ids }
            val seeds = scope.associate { p -> p.anchor to MarkerTypography.from(if (p.start == 0)d.startStyle else d.units[p.start - 1].paragraphStyle) }
            d = d.patchParagraphs(
                scope.associate { block ->
                    val p = block.properties
                    block.anchor to if (command.kind == ListKind.NONE)p.copy(item = "", parent = "", list = ListKind.NONE, restart = 0, markerTypography = MarkerTypography()) else p.copy(item = p.item.ifEmpty { freshStructureId() }, list = command.kind, markerTypography = if (p.item.isEmpty())seeds.getValue(block.anchor) else p.markerTypography)
                },
            )
            if (command.kind == ListKind.NONE) {
                val removed = ids
                d = d.patchParagraphs(d.paragraphs().filter { it.properties.parent in removed }.associate { it.anchor to it.properties.copy(parent = "") })
            }
        }
        StructureCommand.Indent, StructureCommand.Outdent -> {
            val ids = selectedItems()
            val roots = ids.filter { id -> blocks.first { it.properties.item == id }.properties.parent !in ids }.toSet()
            val first = blocks.first { it.properties.item in roots }
            val parent = first.properties.parent
            require(roots.all { id -> blocks.first { it.properties.item == id }.properties.parent == parent }) { "Select sibling items" }
            val items = blocks.filter { it.properties.parent == parent && it.properties.item.isNotEmpty() }.distinctBy { it.properties.item }
            val rootOrder = items.filter { it.properties.item in roots }
            val positions = rootOrder.map { items.indexOf(it) }
            require(positions.zipWithNext().all { it.second == it.first + 1 })
            val newParent = if (command == StructureCommand.Indent) {
                val previous = items.getOrNull(items.indexOf(first) - 1) ?: error("No preceding sibling to indent into")
                previous.properties.item
            } else if (parent.isEmpty()) {
                ""
            } else {
                blocks.first { it.properties.item == parent }.properties.parent
            }
            change(blocks.filter { it.properties.item in roots }) { if (command == StructureCommand.Outdent && parent.isEmpty()) it.copy(item = "", parent = "", list = ListKind.NONE, restart = 0, markerTypography = MarkerTypography()) else it.copy(parent = newParent) }
            if (command == StructureCommand.Outdent && parent.isEmpty())change(d.paragraphs().filter { it.properties.parent in roots }) { it.copy(parent = "") }
            if (command == StructureCommand.Outdent && parent.isNotEmpty()) {
                val moving = subtree(roots)
                val parentTree = subtree(setOf(parent))
                val current = d.paragraphs()
                val moved = current.filter { it.properties.item in moving }
                val remaining = current.filter { it !in moved }.toMutableList()
                val after = remaining.indexOfLast { it.properties.item in parentTree }
                remaining.addAll(after + 1, moved)

                val anchorId = target.anchor.let { d.units.getOrNull(it)?.id }
                val focusId = target.focus.let { d.units.getOrNull(it)?.id }
                val output = ArrayList<TextUnit>()
                var rootProps = remaining.first().properties
                remaining.forEachIndexed { index, block ->
                    if (index > 0) {
                        val sep = if (block.start > 0)d.units[block.start - 1] else null
                        output += (sep ?: TextUnit(allocate(), '\n', UnitKind.PARAGRAPH_BREAK)).copy(followingParagraph = block.properties, followingBlockId = block.anchor)
                    }
                    output.addAll(d.units.subList(block.start, block.end))
                }
                val rootStyle = if (remaining.first().start == 0)d.startStyle else d.units[remaining.first().start - 1].paragraphStyle
                d = d.copy(units = output, startParagraph = rootProps, startStyle = rootStyle, rootBlockId = remaining.first().anchor)
                fun map(id: Long?, fallback: Int) = id?.let { key -> output.indexOfFirst { it.id == key }.takeIf { it >= 0 } } ?: fallback.coerceAtMost(output.size)
                selection = Selection(map(anchorId, target.anchor), map(focusId, target.focus))
            }
        }
        is StructureCommand.Restart -> {
            require(command.value in 1..9999)
            val ids = selectedItems()
            change(blocks.filter { it.properties.item in ids }) {
                require(it.list == ListKind.NUMBER)
                it.copy(restart = command.value)
            }
        }
        is StructureCommand.MarkerStyle -> {
            val ids = selectedItems()
            change(blocks.filter { it.properties.item in ids }) { it.copy(markerTypography = command.typography) }
        }
        is StructureCommand.Enter -> {
            val a = target.start
            val b = target.end
            val p = d.paragraphAt(a)
            val props = p.properties
            if (!command.sameItem && props.item.isNotEmpty() && target.collapsed && blocks.filter { it.properties.item == props.item }.all { it.start == it.end }) {
                return structureEdit(d, target, anchors, StructureCommand.Outdent, allocate)
            }
            var next = props.copy(pagination = props.pagination.copy(breakBefore = false, keepWithNext = false))
            if (props.heading > 0 && a == p.end)next = next.copy(heading = 0)
            if (props.item.isNotEmpty() && !command.sameItem) {
                val id = freshStructureId()
                next = next.copy(item = id, restart = 0)
                val trailing = blocks.filter { it.index > p.index && it.properties.item == props.item }
                d = d.patchParagraphs(trailing.associate { it.anchor to it.properties.copy(item = id, restart = 0) })
                d = d.patchParagraphs(d.paragraphs().filter { it.index > p.index && it.properties.parent == props.item }.associate { it.anchor to it.properties.copy(parent = id) })
            }
            require(d.units.subList(p.start, p.end).none { it.kind == UnitKind.IMAGE }) { "Use the neighboring text paragraph" }
            val seed = d.units.getOrNull(a - 1)?.takeIf { it.kind == UnitKind.TEXT }?.style?.copy(link = null) ?: d.startStyle.copy(link = null)
            d = d.splice(a, b, listOf(TextUnit(allocate(), '\n', UnitKind.PARAGRAPH_BREAK, paragraphStyle = seed, followingParagraph = next)))
            selection = Selection(a + 1, a + 1)
        }
        is StructureCommand.Link -> {
            val link = command.uri?.let { LinkValue(freshStructureId(), it).also { l -> require(l.activatable()) { "Allowed schemes: https, http, mailto" } } }
            val current = d.contiguousLinkAt(target)
            val range = current?.second ?: target
            if (range.collapsed) {
                require(link != null && command.label.isNotEmpty() && command.label.length <= 4096 && command.label.none { it == '\n' || it == '\ufffc' })
                val seed = d.units.getOrNull(target.start - 1)?.style ?: d.startStyle
                d = d.splice(target.start, target.end, command.label.map { TextUnit(allocate(), it, style = seed.copy(link = link)) })
                selection = Selection(target.start, target.start + command.label.length)
            } else {
                val ranges = displayedStyles(d).filter { it.start < range.end && it.end > range.start }
                d = d.copy(units = d.units.mapIndexed { i, u -> if (u.kind == UnitKind.TEXT && ranges.any { i in it.start until it.end })u.copy(style = u.style.copy(link = link)) else u })
            }
        }
        is StructureCommand.InsertImage -> {
            val a = target.start
            val p = d.paragraphAt(a)
            val seed = d.units.getOrNull(a - 1)?.style ?: d.startStyle
            val inserted = ArrayList<TextUnit>()
            if (a != p.start)inserted += TextUnit(allocate(), '\n', UnitKind.PARAGRAPH_BREAK, paragraphStyle = seed, followingParagraph = p.properties.copy(heading = 0))
            inserted += TextUnit(allocate(), '\ufffc', UnitKind.IMAGE, image = command.image)
            val suffix = d.paragraphAt(target.end)
            val retainTail = target.end < suffix.end
            val tailProperties = if (retainTail)suffix.properties else p.properties.copy(heading = 0)
            val tailIdentity = if (a == p.start && retainTail)suffix.anchor else Long.MIN_VALUE
            inserted += TextUnit(allocate(), '\n', UnitKind.PARAGRAPH_BREAK, paragraphStyle = seed.copy(link = null), followingParagraph = tailProperties, followingBlockId = tailIdentity)
            d = d.copy(resources = d.resources + (command.image.resource to command.data)).splice(a, target.end, inserted)
            if (a == p.start) {
                val first = p.properties.copy(heading = 0)
                val identity = if (retainTail)allocate() else p.anchor
                if (a == 0) {
                    d = d.copy(startParagraph = first, rootBlockId = identity)
                } else {
                    d = d.copy(units = d.units.mapIndexed { i, u -> if (i == a - 1)u.copy(followingParagraph = first, followingBlockId = identity) else u })
                }
            }
            selection = Selection(a + if (a == p.start)0 else 1, a + if (a == p.start)1 else 2)
        }
        is StructureCommand.ImageProperties -> {
            val image = d.units.subList(target.start, target.end).singleOrNull { it.kind == UnitKind.IMAGE } ?: error("Select one image")
            d = d.copy(units = d.units.map { if (it.id == image.id) it.copy(image = it.image!!.copy(width = command.width, alt = command.alt)) else it })
        }
    }
    validateStructure(d)
    return d to selection
}
