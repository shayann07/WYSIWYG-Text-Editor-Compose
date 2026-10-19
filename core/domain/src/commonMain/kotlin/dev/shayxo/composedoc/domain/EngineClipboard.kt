package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.DocumentLimits
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.freshStructureId
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.model.repairListParents
import dev.shayxo.composedoc.model.repairRemovedImageHeads
import dev.shayxo.composedoc.model.validateStructure

/** Imports a rich fragment with fresh list/link identities and pastes it over [a, b) in one transaction. */
internal fun EditorEngine.pasteRich(original: RichFragment, a: Int, b: Int): Boolean {
    val f = importFragment(original)
    validateStructure(f.document)
    if (dc.units.size.toLong() - (b - a) + f.document.units.size > DocumentLimits.MAX_UNITS) return false
    var done = false
    dispatch("Rich paste", onCommitted = { done = true }) { applyRichPaste(f, a, b) }
    return done
}

private fun importFragment(original: RichFragment): RichFragment {
    val itemIds = original.document
        .paragraphs()
        .map { it.properties.item }
        .filter { it.isNotEmpty() }
        .distinct()
        .associateWith { freshStructureId() }
    val linkIds = HashMap<String, String>()
    fun remap(style: TextStyleFlags) =
        style.copy(link = style.link?.let { it.copy(id = linkIds.getOrPut(it.id) { freshStructureId() }) })
    val imported = original.document.patchParagraphs(
        original.document.paragraphs().associate { p ->
            p.anchor to
                p.properties.copy(item = itemIds[p.properties.item] ?: "", parent = itemIds[p.properties.parent] ?: "")
        },
    )
    return original.copy(
        document = imported.copy(
            startStyle = remap(imported.startStyle),
            units = imported.units.map { it.copy(style = remap(it.style), paragraphStyle = remap(it.paragraphStyle)) },
        ),
    )
}

/** A complete source block with heading or list semantics stays a closed block at a paste boundary. */
private fun closesBlock(complete: Boolean, properties: ParagraphProperties) =
    complete && (properties.heading > 0 || properties.item.isNotEmpty())

private fun EditorEngine.applyRichPaste(f: RichFragment, a: Int, b: Int) {
    val before = saved()
    val destination = dc.paragraphAt(a)
    val sourceBlocks = f.document.paragraphs()
    val inserted = pastedUnits(f, sourceBlocks, destination)
    val isImageFirst = inserted.firstOrNull()?.kind == UnitKind.IMAGE
    val isImageLast = inserted.lastOrNull()?.kind == UnitKind.IMAGE
    val closedHead = isImageFirst || closesBlock(f.complete.first(), sourceBlocks.first().properties)
    val closedTail = isImageLast || closesBlock(f.complete.last(), sourceBlocks.last().properties)
    val prefixBreak = (a > destination.start) && closedHead
    val suffixBlock = dc.paragraphAt(b)
    val suffixBreak = (b < suffixBlock.end) && closedTail
    // Closed source blocks at a paste edge are separated from destination text by fresh hard breaks.
    if (prefixBreak) inserted.add(0, pasteBreak(f.document.startStyle, f.document.startParagraph))
    if (suffixBreak) inserted.add(pasteBreak(dc.units.getOrNull(b)?.style ?: dc.startStyle, suffixBlock.properties))
    val preserveTail = a == destination.start && b < suffixBlock.end && closedHead
    val newHeadId = if (preserveTail)++serial else null
    if (preserveTail) retainEditableTail(inserted, suffixBlock)
    val firstProps = firstPastedProperties(f, isImageFirst, a == destination.start, destination)
    val head = PastedHead(destination, firstProps, preserveTail, newHeadId)
    dc = pastedDocument(f, a, b, inserted, head)
        .repairRemovedImageHeads(before.document, b)
        .repairListParents(before.document)
    validateStructure(dc)
    selection = Selection(a + inserted.size, a + inserted.size)
    intent = inserted.lastOrNull { it.kind == UnitKind.TEXT }?.style ?: f.document.startStyle
    if (inserted.isEmpty()) settleEmptyPaste(f, a)
    push(before, "Paste", false)
    typingBarrier = true
    notice = "Rich paste"
}

/** Paragraph properties of the first pasted paragraph. */
private fun firstPastedProperties(
    f: RichFragment,
    isImageFirst: Boolean,
    atParagraphStart: Boolean,
    destination: ParagraphBlock,
): ParagraphProperties = if (isImageFirst && atParagraphStart) {
    f.document.startParagraph.copy(heading = 0)
} else if (f.complete.first() && atParagraphStart) {
    f.document.startParagraph
} else {
    destination.properties
}

/** A fresh hard break inserted at a paste edge. */
private fun EditorEngine.pasteBreak(style: TextStyleFlags, following: ParagraphProperties) = TextUnit(
    ++serial,
    '\n',
    UnitKind.PARAGRAPH_BREAK,
    paragraphStyle = style,
    followingParagraph = following,
)

/** An empty paste still carries the fragment's paragraph typing style into an empty destination paragraph. */
private fun EditorEngine.settleEmptyPaste(f: RichFragment, a: Int) {
    if (a == 0) {
        dc = dc.copy(startStyle = f.document.startStyle)
    } else if (dc.units[a - 1].kind == UnitKind.PARAGRAPH_BREAK) {
        dc = dc.copy(
            units = dc.units.mapIndexed { i, u ->
                if (i == a - 1) u.copy(paragraphStyle = f.document.startStyle) else u
            },
        )
    }
}

private fun EditorEngine.pastedUnits(
    f: RichFragment,
    sourceBlocks: List<ParagraphBlock>,
    destination: ParagraphBlock,
): MutableList<TextUnit> {
    val inserted = f.document.units.map { it.copy(id = ++serial, followingBlockId = Long.MIN_VALUE) }.toMutableList()
    for (block in sourceBlocks.drop(1)) {
        val i = block.start - 1
        if (!f.complete[block.index]) inserted[i] = inserted[i].copy(followingParagraph = destination.properties)
    }
    return inserted
}

/** A closed head pasted at a paragraph start keeps the destination paragraph as its editable tail. */
private fun EditorEngine.retainEditableTail(inserted: MutableList<TextUnit>, suffixBlock: ParagraphBlock) {
    val tail = inserted.indexOfLast { it.kind == UnitKind.PARAGRAPH_BREAK }
    require(tail >= 0) { "Closed block paste must retain an editable tail" }
    val typing = if (suffixBlock.start == 0)dc.startStyle else dc.units[suffixBlock.start - 1].paragraphStyle
    inserted[tail] = inserted[tail].copy(
        followingBlockId = suffixBlock.anchor,
        followingParagraph = suffixBlock.properties,
        paragraphStyle = typing,
    )
}

private class PastedHead(
    val destination: ParagraphBlock,
    val firstProps: ParagraphProperties,
    val preserveTail: Boolean,
    val newHeadId: Long?,
)

private fun EditorEngine.pastedDocument(
    f: RichFragment,
    a: Int,
    b: Int,
    inserted: List<TextUnit>,
    head: PastedHead,
): Document {
    var root = dc.startParagraph
    var rootIdentity = dc.rootBlockId
    var result = dc.units.take(a) + inserted + dc.units.drop(b)
    if (a == head.destination.start) {
        if (a == 0) {
            root = head.firstProps
            if (head.preserveTail)rootIdentity = head.newHeadId
        } else {
            result = result.mapIndexed { i, u ->
                if (i == a - 1) {
                    u.copy(
                        followingParagraph = head.firstProps,
                        followingBlockId = if (head.preserveTail)head.newHeadId else u.followingBlockId,
                    )
                } else {
                    u
                }
            }
        }
    }
    var rootStyle = dc.startStyle
    if (f.complete.first() && a == head.destination.start) {
        if (a == 0) {
            rootStyle = f.document.startStyle
        } else {
            result = result.mapIndexed { i, u -> if (i == a - 1) u.copy(paragraphStyle = f.document.startStyle) else u }
        }
    }
    return Document(
        result,
        rootStyle,
        root,
        dc.resources + f.document.resources,
        rootIdentity,
        dc.pageSettings,
        dc.running,
    )
}
