package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.LinkValue
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphProperties
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

/** List ancestry is at most this deep, so a subtree closes after this many expansion passes. */
private const val MAX_LIST_DEPTH = 8
private const val MAX_HEADING_LEVEL = 6
private const val MAX_LIST_RESTART = 9999
private const val MAX_LINK_LABEL_LENGTH = 4096

/** Pure semantic reducer; desktop controls only capture commands/targets. */
fun structureEdit(
    document: Document,
    target: Selection,
    anchors: List<Long?>,
    command: StructureCommand,
    allocate: () -> Long,
): Pair<Document, Selection> {
    val editor = StructureEditor(document, target, anchors, allocate)
    return if (command is StructureCommand.Enter && editor.entersEmptyItem(command)) {
        structureEdit(document, target, anchors, StructureCommand.Outdent, allocate)
    } else {
        editor.apply(command)
    }
}

private class StructureEditor(
    document: Document,
    private val target: Selection,
    private val anchors: List<Long?>,
    private val allocate: () -> Long,
) {
    private var d = document
    private var selection = target
    private val blocks = d.paragraphs()
    private val selected = blocks.filter { it.anchor in anchors }

    init {
        require(selected.size == anchors.distinct().size) { "Paragraph target deleted" }
    }

    /** Enter in an entirely empty list item leaves the list instead of creating another item. */
    fun entersEmptyItem(command: StructureCommand.Enter): Boolean {
        val props = d.paragraphAt(target.start).properties
        val candidate = !command.sameItem && props.item.isNotEmpty() && target.collapsed
        return candidate && blocks.filter { it.properties.item == props.item }.all { it.start == it.end }
    }

    fun apply(command: StructureCommand): Pair<Document, Selection> {
        when (command) {
            is StructureCommand.Running -> d = d.copy(running = command.value)
            is StructureCommand.Pagination -> change(selected) { it.copy(pagination = command.rules) }
            StructureCommand.PageBreak -> pageBreak()
            is StructureCommand.Pages -> d = d.copy(pageSettings = command.settings)
            is StructureCommand.ReplaceImage -> replaceImage(command)
            is StructureCommand.Role -> role(command)
            is StructureCommand.ListType -> listType(command)
            StructureCommand.Indent, StructureCommand.Outdent -> indentOrOutdent(command)
            is StructureCommand.Restart -> restart(command)
            is StructureCommand.MarkerStyle -> markerStyle(command)
            is StructureCommand.Enter -> enter(command)
            is StructureCommand.Link -> link(command)
            is StructureCommand.InsertImage -> insertImage(command)
            is StructureCommand.ImageProperties -> imageProperties(command)
        }
        validateStructure(d)
        return d to selection
    }

    private fun change(ps: List<ParagraphBlock>, patch: (ParagraphProperties) -> ParagraphProperties) {
        d = d.patchParagraphs(ps.associate { it.anchor to patch(it.properties) })
    }

    private fun selectedItems(): Set<String> {
        val ids = selected.map { it.properties.item }.toSet()
        require("" !in ids) { "Select list items" }
        return ids
    }

    private fun subtree(ids: Set<String>): Set<String> {
        val set = ids.toMutableSet()
        repeat(MAX_LIST_DEPTH) { blocks.filter { it.properties.parent in set }.forEach { set += it.properties.item } }
        return set
    }

    private fun pageBreak() {
        val block = d.paragraphAt(target.start)
        require(
            d.units.subList(block.start, block.end).none {
                it.kind == UnitKind.IMAGE
            },
        ) { "Insert a page break in a text paragraph" }
        val seed = if (target.start ==
            0
        ) {
            d.startStyle
        } else {
            d.units[target.start - 1].let {
                if (it.kind ==
                    UnitKind.PARAGRAPH_BREAK
                ) {
                    it.paragraphStyle
                } else {
                    it.style
                }
            }
        }
        val following = block.properties.copy(
            heading = 0,
            pagination = block.properties.pagination.copy(breakBefore = false),
        )
        d =
            d.splice(
                target.start,
                target.end,
                listOf(
                    TextUnit(
                        allocate(),
                        '\u000c',
                        UnitKind.PARAGRAPH_BREAK,
                        paragraphStyle = seed,
                        followingParagraph = following,
                        structuralBreak = StructuralBreak.PAGE,
                    ),
                ),
            )
        selection = Selection(target.start + 1, target.start + 1)
    }

    private fun replaceImage(command: StructureCommand.ReplaceImage) {
        val u =
            d.units.subList(target.start, target.end).singleOrNull()?.takeIf { it.kind == UnitKind.IMAGE }
                ?: error("Select one image")
        d =
            d.copy(
                resources = d.resources + (command.resource to command.data),
                units = d.units.map {
                    if (it.id ==
                        u.id
                    ) {
                        it.copy(image = it.image!!.copy(resource = command.resource))
                    } else {
                        it
                    }
                },
            )
    }

    private fun role(command: StructureCommand.Role) {
        require(command.level in 0..MAX_HEADING_LEVEL)
        require(selected.none { p -> d.units.subList(p.start, p.end).any { it.kind == UnitKind.IMAGE } })
        change(selected) { it.copy(heading = command.level) }
    }

    private fun listType(command: StructureCommand.ListType) {
        val ids = selected.map { it.properties.item }.filter { it.isNotEmpty() }.toSet()
        val scope = blocks.filter { it.anchor in anchors || it.properties.item in ids }
        val seeds = scope.associate { p ->
            p.anchor to
                MarkerTypography.from(if (p.start == 0)d.startStyle else d.units[p.start - 1].paragraphStyle)
        }
        d = d.patchParagraphs(
            scope.associate { block ->
                val p = block.properties
                block.anchor to
                    if (command.kind ==
                        ListKind.NONE
                    ) {
                        p.copy(
                            item = "",
                            parent = "",
                            list = ListKind.NONE,
                            restart = 0,
                            markerTypography = MarkerTypography(),
                        )
                    } else {
                        p.copy(
                            item = p.item.ifEmpty {
                                freshStructureId()
                            },
                            list = command.kind,
                            markerTypography = if (p.item.isEmpty()) {
                                seeds.getValue(
                                    block.anchor,
                                )
                            } else {
                                p.markerTypography
                            },
                        )
                    }
            },
        )
        if (command.kind == ListKind.NONE) {
            val removed = ids
            d =
                d.patchParagraphs(
                    d.paragraphs().filter { it.properties.parent in removed }.associate {
                        it.anchor to
                            it.properties.copy(parent = "")
                    },
                )
        }
    }

    private fun indentOrOutdent(command: StructureCommand) {
        val ids = selectedItems()
        val roots = ids.filter { id -> blocks.first { it.properties.item == id }.properties.parent !in ids }.toSet()
        val first = blocks.first { it.properties.item in roots }
        val parent = first.properties.parent
        require(
            roots.all { id ->
                blocks.first { it.properties.item == id }.properties.parent == parent
            },
        ) { "Select sibling items" }
        val items = blocks.filter {
            it.properties.parent == parent && it.properties.item.isNotEmpty()
        }.distinctBy { it.properties.item }
        val rootOrder = items.filter { it.properties.item in roots }
        val positions = rootOrder.map { items.indexOf(it) }
        require(positions.zipWithNext().all { it.second == it.first + 1 })
        val newParent = newParentFor(command, items, first, parent)
        change(blocks.filter { it.properties.item in roots }) {
            if (command == StructureCommand.Outdent &&
                parent.isEmpty()
            ) {
                it.copy(
                    item = "",
                    parent = "",
                    list = ListKind.NONE,
                    restart = 0,
                    markerTypography = MarkerTypography(),
                )
            } else {
                it.copy(parent = newParent)
            }
        }
        if (command == StructureCommand.Outdent &&
            parent.isEmpty()
        ) {
            change(d.paragraphs().filter { it.properties.parent in roots }) { it.copy(parent = "") }
        }
        if (command == StructureCommand.Outdent && parent.isNotEmpty()) moveOutdentedSubtree(roots, parent)
    }

    private fun newParentFor(
        command: StructureCommand,
        items: List<ParagraphBlock>,
        first: ParagraphBlock,
        parent: String,
    ): String = if (command == StructureCommand.Indent) {
        val previous = items.getOrNull(items.indexOf(first) - 1) ?: error("No preceding sibling to indent into")
        previous.properties.item
    } else if (parent.isEmpty()) {
        ""
    } else {
        blocks.first { it.properties.item == parent }.properties.parent
    }

    /** Outdented subtrees move after their former parent's subtree, retaining unit identities. */
    private fun moveOutdentedSubtree(roots: Set<String>, parent: String) {
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
                output +=
                    (
                        sep ?: TextUnit(
                            allocate(),
                            '\n',
                            UnitKind.PARAGRAPH_BREAK,
                        )
                        ).copy(followingParagraph = block.properties, followingBlockId = block.anchor)
            }
            output.addAll(d.units.subList(block.start, block.end))
        }
        val rootStyle = if (remaining.first().start ==
            0
        ) {
            d.startStyle
        } else {
            d.units[remaining.first().start - 1].paragraphStyle
        }
        d =
            d.copy(
                units = output,
                startParagraph = rootProps,
                startStyle = rootStyle,
                rootBlockId = remaining.first().anchor,
            )
        fun map(id: Long?, fallback: Int) = id?.let { key -> output.indexOfFirst { it.id == key }.takeIf { it >= 0 } }
            ?: fallback.coerceAtMost(output.size)
        selection = Selection(map(anchorId, target.anchor), map(focusId, target.focus))
    }

    private fun restart(command: StructureCommand.Restart) {
        require(command.value in 1..MAX_LIST_RESTART)
        val ids = selectedItems()
        change(blocks.filter { it.properties.item in ids }) {
            require(it.list == ListKind.NUMBER)
            it.copy(restart = command.value)
        }
    }

    private fun markerStyle(command: StructureCommand.MarkerStyle) {
        val ids = selectedItems()
        change(blocks.filter { it.properties.item in ids }) { it.copy(markerTypography = command.typography) }
    }

    private fun enter(command: StructureCommand.Enter) {
        val a = target.start
        val b = target.end
        val p = d.paragraphAt(a)
        val props = p.properties
        var next = props.copy(pagination = props.pagination.copy(breakBefore = false, keepWithNext = false))
        if (props.heading > 0 && a == p.end)next = next.copy(heading = 0)
        if (props.item.isNotEmpty() && !command.sameItem) next = splitListItem(p, props, next)
        require(
            d.units.subList(p.start, p.end).none {
                it.kind == UnitKind.IMAGE
            },
        ) { "Use the neighboring text paragraph" }
        val seed =
            d.units.getOrNull(a - 1)?.takeIf { it.kind == UnitKind.TEXT }?.style?.copy(link = null)
                ?: d.startStyle.copy(link = null)
        d =
            d.splice(
                a,
                b,
                listOf(
                    TextUnit(
                        allocate(),
                        '\n',
                        UnitKind.PARAGRAPH_BREAK,
                        paragraphStyle = seed,
                        followingParagraph = next,
                    ),
                ),
            )
        selection = Selection(a + 1, a + 1)
    }

    /** Trailing paragraphs and children of the split item move to a fresh item identity. */
    private fun splitListItem(
        p: ParagraphBlock,
        props: ParagraphProperties,
        next: ParagraphProperties,
    ): ParagraphProperties {
        val id = freshStructureId()
        val split = next.copy(item = id, restart = 0)
        val trailing = blocks.filter { it.index > p.index && it.properties.item == props.item }
        d = d.patchParagraphs(trailing.associate { it.anchor to it.properties.copy(item = id, restart = 0) })
        d =
            d.patchParagraphs(
                d.paragraphs().filter {
                    it.index > p.index && it.properties.parent == props.item
                }.associate {
                    it.anchor to
                        it.properties.copy(parent = id)
                },
            )
        return split
    }

    private fun link(command: StructureCommand.Link) {
        val link = command.uri?.let {
            LinkValue(freshStructureId(), it).also { l ->
                require(l.activatable()) { "Allowed schemes: https, http, mailto" }
            }
        }
        val current = d.contiguousLinkAt(target)
        val range = current?.second ?: target
        if (range.collapsed) insertLinkLabel(command, link) else relink(range, link)
    }

    private fun insertLinkLabel(command: StructureCommand.Link, link: LinkValue?) {
        require(
            link != null &&
                command.label.isNotEmpty() &&
                command.label.length <= MAX_LINK_LABEL_LENGTH &&
                command.label.none { it == '\n' || it == '￼' },
        )
        val seed = d.units.getOrNull(target.start - 1)?.style ?: d.startStyle
        d =
            d.splice(
                target.start,
                target.end,
                command.label.map {
                    TextUnit(allocate(), it, style = seed.copy(link = link))
                },
            )
        selection = Selection(target.start, target.start + command.label.length)
    }

    private fun relink(range: Selection, link: LinkValue?) {
        val ranges = displayedStyles(d).filter { it.start < range.end && it.end > range.start }
        d =
            d.copy(
                units = d.units.mapIndexed { i, u ->
                    if (u.kind == UnitKind.TEXT &&
                        ranges.any { i in it.start until it.end }
                    ) {
                        u.copy(style = u.style.copy(link = link))
                    } else {
                        u
                    }
                },
            )
    }

    private fun insertImage(command: StructureCommand.InsertImage) {
        val a = target.start
        val p = d.paragraphAt(a)
        val seed = d.units.getOrNull(a - 1)?.style ?: d.startStyle
        val inserted = ArrayList<TextUnit>()
        if (a !=
            p.start
        ) {
            inserted +=
                TextUnit(
                    allocate(),
                    '\n',
                    UnitKind.PARAGRAPH_BREAK,
                    paragraphStyle = seed,
                    followingParagraph = p.properties.copy(heading = 0),
                )
        }
        inserted += TextUnit(allocate(), '￼', UnitKind.IMAGE, image = command.image)
        val suffix = d.paragraphAt(target.end)
        val retainTail = target.end < suffix.end
        val tailProperties = if (retainTail)suffix.properties else p.properties.copy(heading = 0)
        val tailIdentity = if (a == p.start && retainTail)suffix.anchor else Long.MIN_VALUE
        inserted +=
            TextUnit(
                allocate(),
                '\n',
                UnitKind.PARAGRAPH_BREAK,
                paragraphStyle = seed.copy(link = null),
                followingParagraph = tailProperties,
                followingBlockId = tailIdentity,
            )
        d =
            d.copy(
                resources = d.resources + (command.image.resource to command.data),
            ).splice(a, target.end, inserted)
        if (a == p.start) attachImageHead(a, p, retainTail)
        selection = Selection(a + if (a == p.start)0 else 1, a + if (a == p.start)1 else 2)
    }

    /** An image inserted at a paragraph start owns that paragraph head; a retained tail gets a fresh one. */
    private fun attachImageHead(a: Int, p: ParagraphBlock, retainTail: Boolean) {
        val first = p.properties.copy(heading = 0)
        val identity = if (retainTail)allocate() else p.anchor
        if (a == 0) {
            d = d.copy(startParagraph = first, rootBlockId = identity)
        } else {
            d =
                d.copy(
                    units = d.units.mapIndexed { i, u ->
                        if (i ==
                            a - 1
                        ) {
                            u.copy(followingParagraph = first, followingBlockId = identity)
                        } else {
                            u
                        }
                    },
                )
        }
    }

    private fun imageProperties(command: StructureCommand.ImageProperties) {
        val image =
            d.units.subList(target.start, target.end).singleOrNull { it.kind == UnitKind.IMAGE }
                ?: error("Select one image")
        d =
            d.copy(
                units = d.units.map {
                    if (it.id ==
                        image.id
                    ) {
                        it.copy(image = it.image!!.copy(width = command.width, alt = command.alt))
                    } else {
                        it
                    }
                },
            )
    }
}
