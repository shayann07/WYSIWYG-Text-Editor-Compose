package dev.shayxo.composedoc.androidsample

import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ImageResource
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel

internal const val WITNESS_TITLE = "اُردو workspace"
internal const val WITNESS_TEXT = "$WITNESS_TITLE\nParent 42\nEnglish child\ncontinuation\n\ufffc\n"
internal const val WITNESS_PROPOSAL = "仮候補"
internal const val WITNESS_AFTER = " AFTER LAST CHECKPOINT"
private const val TITLE_SIZE = 32
private const val TITLE_COLOR = 0x006633
private const val IMAGE_SIZE = 120
private const val SPACE_BEFORE = 12
private const val SPACE_AFTER = 18
private const val RESOURCE_WIDTH = 3
private const val RESOURCE_HEIGHT = 2
private const val CONTINUATION_INDEX = 3

internal fun buildRecoveryWitness(model: EditorViewModel, image: Pair<String, ImageResource>): Document {
    model.dispatch(EditorIntent.Type(WITNESS_TITLE))
    model.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
    model.dispatch(EditorIntent.Structure(StructureCommand.Enter(false)))
    model.dispatch(EditorIntent.Type("Parent 42"))
    model.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.NUMBER)))
    model.dispatch(
        EditorIntent.SetParagraphProperties(
            direction = ParagraphDirection.RTL,
            before = SPACE_BEFORE,
            after = SPACE_AFTER,
        ),
    )
    model.dispatch(EditorIntent.Structure(StructureCommand.Enter(false)))
    model.dispatch(EditorIntent.Type("English child"))
    model.dispatch(EditorIntent.SetParagraphProperties(direction = ParagraphDirection.LTR))
    model.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.BULLET)))
    model.dispatch(EditorIntent.Structure(StructureCommand.Indent))
    model.dispatch(EditorIntent.Structure(StructureCommand.Enter(true)))
    model.dispatch(EditorIntent.Type("continuation"))
    model.dispatch(
        EditorIntent.Structure(
            StructureCommand.InsertImage(ImageBlock(image.first, IMAGE_SIZE, "Owned blue PNG"), image.second),
        ),
    )
    model.dispatch(EditorIntent.Select(Selection(0, WITNESS_TITLE.length)))
    model.dispatch(
        EditorIntent.SetTextStyle(
            TextStylePatch(
                font = StoredFont.GULZAR,
                size = TITLE_SIZE,
                bold = true,
                underline = true,
                color = TITLE_COLOR,
            ),
        ),
    )
    return model.snapshot().committed.also(::checkRecoveryWitness)
}

/** Independent expected structure/resource description, beyond comparing two codec calls. */
internal fun checkRecoveryWitness(document: Document) {
    check(document.text == WITNESS_TEXT)
    val blocks = document.paragraphs()
    check(blocks[0].properties.heading == 1 && blocks.drop(1).all { it.properties.heading == 0 })
    val parent = blocks[1].properties
    val child = blocks[2].properties
    check(parent.list == ListKind.NUMBER && parent.direction == ParagraphDirection.RTL)
    check(parent.spaceBefore == SPACE_BEFORE && parent.spaceAfter == SPACE_AFTER)
    check(child.list == ListKind.BULLET && child.direction == ParagraphDirection.LTR && child.parent == parent.item)
    check(blocks[CONTINUATION_INDEX].properties.item == child.item)
    check(
        document.units.take(WITNESS_TITLE.length).all {
            it.style.font == StoredFont.GULZAR &&
                it.style.size == TITLE_SIZE &&
                it.style.bold &&
                it.style.underline &&
                it.style.color == TITLE_COLOR
        },
    )
    val image = document.units.single { it.kind == UnitKind.IMAGE }.image!!
    check(image.width == IMAGE_SIZE && image.alt == "Owned blue PNG")
    val resource = document.resources.getValue(image.resource)
    check(
        resource.width == RESOURCE_WIDTH &&
            resource.height == RESOURCE_HEIGHT &&
            resource.mime == "image/png" &&
            resource.bytes().isNotEmpty(),
    )
}
