package dev.shayxo.composedoc.androidsample

import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.AndroidRecoveryStore
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.validateStructure
import dev.shayxo.composedoc.ui.EditorIntent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal fun AdapterInstrumentation.richStructureChecks(root: File) {
    semanticStructure()
    ownedImages(root)
    structuredFind(root)
    backwardCompositionFormat()
}

private fun AdapterInstrumentation.semanticStructure() {
    group("heading Enter, nested opposite-direction item paragraphs and structural UndoRedo") {
        val (model, host) = fixture("دستاویز")
        main {
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
            val heading = model.outline().single().anchor
            val connection = connection(host)
            check(connection.performEditorAction(EditorInfo.IME_ACTION_NONE))
            model.dispatch(EditorIntent.Type("پہلی شق"))
            model.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.NUMBER)))
            model.dispatch(EditorIntent.SetParagraphProperties(direction = ParagraphDirection.RTL))
            model.dispatch(EditorIntent.Structure(StructureCommand.Enter(false)))
            model.dispatch(EditorIntent.Type("English child"))
            model.dispatch(EditorIntent.SetParagraphProperties(direction = ParagraphDirection.LTR))
            model.dispatch(EditorIntent.Structure(StructureCommand.Indent))
            model.dispatch(EditorIntent.Structure(StructureCommand.Enter(true)))
            model.dispatch(EditorIntent.Type("Child continuation"))
            val before = model.snapshot().committed
            check(before.text == "دستاویز\nپہلی شق\nEnglish child\nChild continuation")
            val blocks = before.paragraphs()
            check(blocks.map { it.properties.heading } == listOf(1, 0, 0, 0))
            check(blocks[2].properties.parent == blocks[1].properties.item)
            check(blocks[2].properties.item == blocks[3].properties.item)
            check(blocks[1].properties.direction == ParagraphDirection.RTL)
            check(blocks[2].properties.direction == ParagraphDirection.LTR)
            validateStructure(before)
            model.dispatch(EditorIntent.Structure(StructureCommand.Outdent))
            val after = model.snapshot().committed
            check(after.paragraphs()[2].properties.parent.isEmpty())
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.same(before))
            model.dispatch(EditorIntent.Command(EditAction.Redo))
            check(model.snapshot().committed.same(after))
            check(model.dispatch(EditorIntent.NavigateOutline(heading)))
            check(model.snapshot().selection == Selection(0, 0))
        }
    }
}

private fun AdapterInstrumentation.ownedImages(root: File) {
    group("image resize replace exact UndoRedo, owned resources, save and recovery round trips") {
        val first = testPng(3, 2, android.graphics.Color.RED)
        val second = testPng(2, 3, android.graphics.Color.BLUE)
        val a = ImageDecoder.validate(first)
        val b = ImageDecoder.validate(second)
        val (model, _) = fixture("before\n\nafter")
        val expected = main {
            model.dispatch(EditorIntent.Select(Selection(7, 7)))
            model.dispatch(
                EditorIntent.Structure(StructureCommand.InsertImage(ImageBlock(a.first, 240, "red"), a.second)),
            )
            val inserted = model.snapshot().committed
            check(inserted.text == "before\n\uFFFC\n\nafter")
            model.dispatch(EditorIntent.Structure(StructureCommand.ImageProperties(120, "سرخ red")))
            val resized = model.snapshot().committed
            model.dispatch(EditorIntent.Structure(StructureCommand.ReplaceImage(b.first, b.second)))
            val replaced = model.snapshot().committed
            val image = replaced.units.single { it.kind == UnitKind.IMAGE }.image!!
            check(image.width == 120 && image.alt == "سرخ red" && image.resource == b.first)
            check(replaced.resources.getValue(b.first).bytes().contentEquals(second))
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.same(resized))
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.same(inserted))
            repeat(2) { model.dispatch(EditorIntent.Command(EditAction.Redo)) }
            check(model.snapshot().committed.same(replaced))
            replaced
        }
        val read = AndroidDocumentCodec.decode(AndroidDocumentCodec.encode(expected))
        check(read.same(expected) && read.resources.getValue(b.first).bytes().contentEquals(second))
        val store = AndroidRecoveryStore(File(root, "image-recovery"))
        val owner = UUID.randomUUID().toString()
        store.checkpoint(owner, 1, "Owned images", expected)
        check(store.read(owner, 1).document.same(expected))
        check(aspectWidth(ImageBlock(a.first, 240), a.second, "240", "80") == 120)
        check(aspectWidth(ImageBlock(a.first, 240), a.second, "120", "80") == 120)
        check(runCatching { aspectWidth(ImageBlock(a.first, 240), a.second, "120", "100") }.isFailure)
        check(runCatching { aspectWidth(ImageBlock(a.first, 240), a.second, "2409", "160") }.isFailure)
        check(runCatching { aspectWidth(ImageBlock(a.first, 240), a.second, "abc", "160") }.isFailure)
    }
}

internal fun testPng(width: Int, height: Int, color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.eraseColor(color)
        ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

private fun AdapterInstrumentation.structuredFind(root: File) {
    group("structured exact ReplaceAll captured basis, inherited formatting, one Undo and recovery") {
        val (model, _) = fixture("کتاب کتاب\nEnglish کتاب\ne\u0301 ک ك")
        val before = main {
            model.dispatch(EditorIntent.Select(Selection(4, 0)))
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(bold = true, color = 0x007744)))
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(2)))
            model.dispatch(EditorIntent.SetParagraphProperties(before = 12, after = 18, line = 1400))
            model.snapshot().committed
        }
        val expected = main {
            val captured = model.captureFindBasis()
            val matches = findLiteral(captured.document, "کتاب")
            check(matches.map { it.start } == listOf(0, 5, 18))
            check(findLiteral(captured.document, "\u0301").single().editable.not())
            val count = model.snapshot().undoCount
            check(model.dispatch(EditorIntent.ReplaceFound(captured, matches, "گل", true)) == FindReplaceResult.Applied)
            val after = model.snapshot().committed
            check(after.text == "گل گل\nEnglish گل\ne\u0301 ک ك")
            check(after.paragraphs().map { it.properties } == before.paragraphs().map { it.properties })
            check(after.units.take(2).all { it.style.bold && it.style.color == 0x007744 })
            check(model.snapshot().undoCount == count + 1)
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.same(before))
            model.dispatch(EditorIntent.Command(EditAction.Redo))
            check(model.snapshot().committed.same(after))
            check(
                model.dispatch(EditorIntent.ReplaceFound(captured, matches, "stale", true)) == FindReplaceResult.Stale,
            )
            check(model.snapshot().committed.same(after))
            after
        }
        val store = AndroidRecoveryStore(File(root, "find-recovery"))
        val owner = UUID.randomUUID().toString()
        store.checkpoint(owner, 1, "After Replace All", expected)
        check(store.read(owner, 1).document.same(expected))
    }
}

private fun AdapterInstrumentation.backwardCompositionFormat() {
    group("native active backward selection formatting settles once with exact attributed UndoRedo") {
        val (model, host) = fixture("Latin ")
        main {
            model.dispatch(EditorIntent.Select(Selection(1, 0)))
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(italic = true)))
            model.dispatch(EditorIntent.Select(Selection(6, 6)))
            val basis = model.snapshot().committed
            val c = connection(host)
            check(c.setComposingText("اُردو", 1))
            check(c.setSelection(11, 6))
            model.dispatch(EditorIntent.ToggleFormat(FormatKey.UNDERLINE))
            check(model.snapshot().waiting && model.snapshot().committed.same(basis))
            check(c.finishComposingText())
            val after = model.snapshot().committed
            check(after.text == "Latin اُردو")
            check(after.units.first().style.italic)
            check(after.units.drop(6).all { it.style.underline })
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.text == after.text)
            check(model.snapshot().committed.units.drop(6).none { it.style.underline })
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.same(basis))
            repeat(2) { model.dispatch(EditorIntent.Command(EditAction.Redo)) }
            check(model.snapshot().committed.same(after))
        }
    }
}
