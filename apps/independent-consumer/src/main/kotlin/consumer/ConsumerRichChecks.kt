package consumer

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.validateStructure
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/** Consumer-generated resources and public commands only; no repository fixtures or competing owner. */
internal fun checkRichPublicIntents(root: Path) {
    val model = EditorViewModel()
    model.dispatch(EditorIntent.Type("عنوان"))
    model.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
    model.dispatch(EditorIntent.Structure(StructureCommand.Enter(false)))
    model.dispatch(EditorIntent.Type("Urdu item"))
    model.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.NUMBER)))
    model.dispatch(
        EditorIntent.SetParagraphProperties(
            direction = ParagraphDirection.RTL,
            before = PARAGRAPH_BEFORE,
            after = PARAGRAPH_AFTER,
        ),
    )
    model.dispatch(EditorIntent.Structure(StructureCommand.Enter(false)))
    model.dispatch(EditorIntent.Type("English item"))
    model.dispatch(EditorIntent.SetParagraphProperties(direction = ParagraphDirection.LTR))
    model.dispatch(EditorIntent.Structure(StructureCommand.Indent))
    val before = model.snapshot().committed
    check(before.text == "عنوان\nUrdu item\nEnglish item")
    val parent = before.paragraphs()[1].properties.item
    check(before.paragraphs()[2].properties.parent == parent)
    model.dispatch(EditorIntent.SelectAll)
    model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(underline = true, color = TEXT_COLOR)))
    val basis = model.captureFindBasis()
    val found = findLiteral(basis.document, "item")
    check(model.dispatch(EditorIntent.ReplaceFound(basis, found, "entry", true)) == FindReplaceResult.Applied)
    val replaced = model.snapshot().committed
    check(replaced.text == "عنوان\nUrdu entry\nEnglish entry")
    model.dispatch(EditorIntent.Command(EditAction.Undo))
    check(model.snapshot().committed.same(basis.document))
    model.dispatch(EditorIntent.Command(EditAction.Redo))
    check(model.snapshot().committed.same(replaced))
    checkRichResources(root, model)
    model.detach()
}

private fun checkRichResources(root: Path, model: EditorViewModel) {
    val resource = CompletableFuture.supplyAsync {
        val image = BufferedImage(PNG_WIDTH, PNG_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use {
            check(ImageIO.write(image, "png", it))
            it.toByteArray()
        }
        ImageDecoder.validate(bytes)
    }.get()
    val end = model.snapshot().committed.units.size
    model.dispatch(EditorIntent.Select(Selection(end, end)))
    model.dispatch(
        EditorIntent.Structure(
            StructureCommand.InsertImage(
                ImageBlock(resource.first, IMAGE_WIDTH, "Owned PNG"),
                resource.second,
            ),
        ),
    )
    val expected = model.snapshot().committed
    check(expected.units.single { it.kind == UnitKind.IMAGE }.image?.width == IMAGE_WIDTH)
    model.dispatch(EditorIntent.SelectAll)
    val capture = checkNotNull(model.clipboardCapture())
    val fragment = RichFragment.capture(capture)
    val loaded = CompletableFuture.supplyAsync {
        val file = root.resolve("rich-consumer.astra").toString()
        DocumentFiles.save(file, expected)
        DocumentFiles.load(file) to FragmentCodec.decode(FragmentCodec.encode(fragment))
    }.get()
    check(loaded.first.same(expected))
    check(loaded.first.resources.getValue(resource.first).bytes().contentEquals(resource.second.bytes()))
    val other = EditorViewModel()
    check(other.dispatch(EditorIntent.ApplyClipboard(checkNotNull(other.clipboardCapture()), loaded.second)))
    val pasted = other.snapshot().committed
    check(pasted.text == expected.text && pasted.resources == expected.resources)
    check(pasted.units.map { it.style } == expected.units.map { it.style })
    check(pasted.paragraphs().map { it.properties.heading } == expected.paragraphs().map { it.properties.heading })
    check(pasted.paragraphs()[2].properties.parent == pasted.paragraphs()[1].properties.item)
    check(pasted.paragraphs()[1].properties.item != expected.paragraphs()[1].properties.item)
    validateStructure(pasted)
    other.detach()
}

private const val TEXT_COLOR = 0x007744
private const val PNG_WIDTH = 3
private const val PNG_HEIGHT = 2
private const val IMAGE_WIDTH = 120
private const val PARAGRAPH_BEFORE = 12
private const val PARAGRAPH_AFTER = 18
