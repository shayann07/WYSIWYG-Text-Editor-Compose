package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
import java.io.File
import java.nio.file.Files
import javax.swing.*

fun structureDescription(d: Document): Map<String, Any> {
    val items = d.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct()
    val links = d.units.mapNotNull { it.style.link?.id }.distinct()
    return mapOf(
        "textUnits" to d.units.map { it.char.code },
        "blocks" to d.paragraphs().map { p ->
            val v = p.properties
            listOf(p.start, p.end, v.heading, v.list.name, items.indexOf(v.item), items.indexOf(v.parent), v.restart, v.markerTypography.font.name, v.markerTypography.size)
        },
        "links" to d.units.map { listOf(links.indexOf(it.style.link?.id), it.style.link?.uri ?: "") },
        "images" to d.units.mapNotNull { it.image?.let { im -> listOf(im.resource, im.width, im.alt) } },
        "resources" to d.resources.toSortedMap().map { (id, r) -> listOf(id, r.width, r.height, r.mime, r.data) },
    )
}
fun runStructureChecks(result: File) {
    result.parentFile.mkdirs()
    val root = Files.createTempDirectory(result.parentFile.toPath(), "structures-")
    val checks = ArrayList<String>()
    val costs = linkedMapOf<String, Long>()
    fun test(name: String, block: () -> Unit) {
        block()
        checks += name
        println("PASS $name")
    }
    fun e(text: String) = EditorEngine().apply { replace(text, "fixture", true) }
    fun at(e: EditorEngine, text: String) {
        val p = e.snapshot().committed.text.indexOf(text)
        check(p >= 0)
        e.move(Selection(p, p))
    }
    test("Semantic repeated/empty headings stable outline targets, half-open backward, middle/end Enter and Undo") {
        val e = e("same\nsame\n\nlast")
        e.move(Selection(10, 0))
        e.structure(StructureCommand.Role(2))
        check(e.outline().size == 2)
        val second = e.outline()[1].anchor
        e.move(Selection(0, 0))
        e.replace("prefix ", "edit")
        check(e.navigateOutline(second))
        check(e.snapshot().selection.focus == 12)
        val before = e.snapshot()
        e.replace("\n", "Enter", true)
        check(e.snapshot().committed.paragraphs()[2].properties.heading == 2)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
        at(e, "last")
        e.move(Selection(e.snapshot().committed.units.size, e.snapshot().committed.units.size))
        e.structure(StructureCommand.Role(3))
        e.replace("\n", "Enter", true)
        check(e.snapshot().committed.paragraphs().last().properties.heading == 0)
        e.structure(StructureCommand.Role(4))
        check(e.outline().last().label == "Empty heading")
        e.structure(StructureCommand.Role(0))
        check(e.outline().none { it.label == "Empty heading" })
        e.move(Selection(0, e.snapshot().committed.units.size))
        e.replace("", "delete")
        check(!e.navigateOutline(second))
        e.perform(EditAction.Undo)
        check(e.navigateOutline(second))
    }
    test("Nested multi-paragraph items, derived numbering, split/empty lift/backspace, atomic invalid indent") {
        val e = e("one\ntwo\nthree")
        e.selectAll()
        e.structure(StructureCommand.ListType(ListKind.NUMBER))
        check(e.snapshot().committed.listMarkers().values.toSet() == setOf("1.", "2.", "3."))
        at(e, "two")
        val before = e.snapshot()
        e.structure(StructureCommand.Indent)
        var d = e.snapshot().committed
        check(d.itemDepth(d.paragraphs()[1].properties.item) == 2)
        check(d.listMarkers()[d.paragraphs()[2].anchor] == "2.")
        e.structure(StructureCommand.Restart(7))
        check(e.snapshot().committed.listMarkers()[d.paragraphs()[1].anchor] == "7.")
        e.structure(StructureCommand.Outdent)
        d = e.snapshot().committed
        check(d.itemDepth(d.paragraphs()[1].properties.item) == 1)
        e.move(Selection(5, 5))
        e.structure(StructureCommand.Enter(true))
        d = e.snapshot().committed
        check(d.paragraphs()[1].properties.item == d.paragraphs()[2].properties.item && d.listMarkers().size == 3)
        val item = d.paragraphs()[1].properties.item
        e.move(Selection(d.paragraphs()[2].start, d.paragraphs()[2].start))
        e.replace("\n", "Enter", true)
        check(e.snapshot().committed.paragraphs()[3].properties.item != item)
        e.move(Selection(0, 0))
        val stable = e.snapshot()
        e.structure(StructureCommand.Indent)
        check(e.snapshot().committed == stable.committed && e.snapshot().undoCount == stable.undoCount)
        e.delete(true)
        check(e.snapshot().committed.startParagraph.list == ListKind.NONE)
        val empty = e("")
        empty.structure(StructureCommand.ListType(ListKind.BULLET))
        empty.replace("\n", "Enter", true)
        check(empty.snapshot().committed.units.isEmpty() && empty.snapshot().committed.startParagraph.list == ListKind.NONE)
        check(before.committed.text == "one\ntwo\nthree")
    }
    test("Link IDs distinct from URI; formatting/grapheme boundaries; exterior/interior and whitespace affinity") {
        val e = e("کِتاب abc xyz")
        e.move(Selection(0, 1))
        e.structure(StructureCommand.Link("https://example.org"))
        check(e.snapshot().committed.units.take(2).all { it.style.link != null })
        val first = e.snapshot().committed.units[0].style.link
        e.move(Selection(7, 10))
        e.structure(StructureCommand.Link("https://example.org"))
        check(e.snapshot().committed.units[7].style.link?.id != first?.id)
        e.move(Selection(0, 0))
        e.replace("X", "type")
        check(e.snapshot().committed.units[0].style.link == null)
        val interior = e("abcd")
        interior.selectAll()
        interior.structure(StructureCommand.Link("https://example.org"))
        val inherited = interior.snapshot().committed.units[0].style.link
        interior.move(Selection(2, 2))
        interior.replace("z ", "type")
        check(interior.snapshot().committed.units[2].style.link == inherited && interior.snapshot().committed.units[3].style.link == null)
        e.selectAll()
        e.setTextStyle(TextStylePatch(underline = true, color = 0))
        check(e.snapshot().committed.units.any { it.style.link == first })
        val before = e.snapshot()
        e.structure(StructureCommand.Link("file:///C:/x"))
        check(e.snapshot().committed == before.committed)
        e.move(Selection(0, 1))
        e.structure(StructureCommand.Link(null))
        check(e.snapshot().committed.units[0].style.link == null)
        e.move(Selection(0, 0))
        e.structure(StructureCommand.Link("mailto:test@example.org", "mail"))
        check(e.snapshot().committed.text.startsWith("mail"))
        check(e.snapshot().committed.plainText().contains("<mailto:test@example.org>"))
    }
    test("Inserted whitespace exits link for rest of text batch and accepted composition epoch") {
        fun linked(): EditorEngine {
            val e = e("abcd")
            e.selectAll()
            e.structure(StructureCommand.Link("https://example.org"))
            e.move(Selection(2, 2))
            return e
        }
        val typed = linked()
        val before = typed.snapshot()
        typed.replace("X Y", "Typing")
        var d = typed.snapshot().committed
        check(d.units[2].style.link != null && d.units[3].style.link == null && d.units[4].style.link == null)
        typed.perform(EditAction.Undo)
        check(typed.snapshot().committed == before.committed)
        val ime = linked()
        ime.inputMethod("X Y", 0, 3)
        check(ime.snapshot().visible.units[4].style.link == null)
        ime.inputMethod("X Y", 2, 1)
        ime.inputMethod("ZZ", 2, null)
        d = ime.snapshot().committed
        check(d.text == "abX ZZcd" && d.units.subList(4, 6).all { it.style.link == null })
        ime.perform(EditAction.Undo)
        check(ime.snapshot().committed.text == "abcd")
        ime.perform(EditAction.Redo)
        check(ime.snapshot().committed == d)
    }
    val png = root.resolve("input.dat")
    Files.write(png, samplePng())
    val imported = ImageDecoder.import(png)
    test("Images own validated bytes, isolated atomic blocks, aspect/alt/selection, source deletion and exact Undo") {
        val e = e("left\n\nright")
        e.move(Selection(5, 5))
        val before = e.snapshot()
        e.structure(StructureCommand.InsertImage(ImageBlock(imported.first, 300, "test illustration"), imported.second))
        val inserted = e.snapshot()
        check(inserted.committed.units.count { it.kind == UnitKind.IMAGE } == 1 && inserted.committed.resources.size == 1)
        val im = inserted.committed.units.indexOfFirst { it.image != null }
        check(inserted.selection == Selection(im, im + 1))
        e.structure(StructureCommand.ImageProperties(600, "updated alt"))
        check(e.snapshot().committed.units[im].image?.width == 600)
        e.move(Selection(im, im))
        check(!e.nativeStructureAdmission("x"))
        e.replace("x", "type")
        check(e.snapshot().committed.units[im].image != null)
        e.move(Selection(im + 1, im + 1))
        e.delete(true)
        check(e.snapshot().selection == Selection(im, im + 1))
        e.delete(false)
        check(e.snapshot().committed.units.none { it.image != null } && e.snapshot().committed.resources.size == 1)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.units[im].image != null)
        Files.delete(png)
        val file = root.resolve("owned.astra")
        DocumentFiles.write(file, e.snapshot().committed)
        check(DocumentFiles.read(file).same(e.snapshot().committed))
        check(runCatching { ImageDecoder.validate(ByteArray(20)) }.isFailure)
        check(runCatching { ImageDecoder.validate(ByteArray(ImageDecoder.MAX_BYTES + 1)) }.isFailure)
        check(before.committed.text == "left\n\nright")
    }
    test("JPEG signature import and repeated-image writer-reader agreement beyond 1024 blocks") {
        val bitmap = java.awt.image.BufferedImage(32, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        check(javax.imageio.ImageIO.write(bitmap, "jpeg", out))
        val jpeg = root.resolve("jpeg-with-bin-extension.bin")
        Files.write(jpeg, out.toByteArray())
        val decoded = ImageDecoder.import(jpeg)
        check(decoded.second.mime == "image/jpeg" && decoded.second.width == 32 && decoded.second.height == 16)
        val owner = EditorEngine()
        owner.structure(StructureCommand.InsertImage(ImageBlock(decoded.first, 160, "JPEG alternative"), decoded.second))
        Files.delete(jpeg)
        val saved = root.resolve("jpeg-owned.astra")
        DocumentFiles.write(saved, owner.snapshot().committed)
        check(DocumentFiles.read(saved).same(owner.snapshot().committed))
        val repeated = Document((0 until 1025).flatMap { n -> listOf(TextUnit(n * 2L + 1, '\ufffc', UnitKind.IMAGE, image = ImageBlock(imported.first, 240, "Repeated image")), TextUnit(n * 2L + 2, '\n', UnitKind.PARAGRAPH_BREAK)) }, resources = mapOf(imported))
        val encoded = DocumentFiles.encode(repeated)
        val restored = DocumentFiles.decode(encoded)
        check(restored.same(repeated) && restored.units.count { it.kind == UnitKind.IMAGE } == 1025 && restored.resources.size == 1)
        val dimensions = samplePng().copyOf()
        java.nio.ByteBuffer.wrap(dimensions).putInt(16, 9000)
        check(runCatching { ImageDecoder.validate(dimensions) }.isFailure)
    }
    val sample = structuredWritingSample()
    val description = structureDescription(sample)
    test("Independent mixed sample semantic/resource expectations and v6 exact old migration and rich fresh identity") {
        check(sample.outlineEntries().map { it.level } == listOf(1, 2, 3, 3, 2))
        check(sample.paragraphs().count { it.properties.item.isNotEmpty() } == 4)
        val listed = sample.paragraphs().filter { it.properties.item.isNotEmpty() }
        check(listed[0].properties.item == listed[1].properties.item && listed[2].properties.parent == listed[0].properties.item)
        check(sample.listMarkers()[listed[0].anchor] == "1." && sample.listMarkers()[listed[3].anchor] == "2.")
        check(sample.resources.size == 1 && sample.units.count { it.image != null } == 1 && sample.units.count { it.style.link != null } == 25)
        val bytes = DocumentFiles.encode(sample)
        check(java.nio.ByteBuffer.wrap(bytes).getInt(4) == 9)
        check(structureDescription(DocumentFiles.decode(bytes)) == description)
        val source = EditorEngine(sample)
        source.selectAll()
        val fragment = RichFragment.capture(checkNotNull(source.clipboardCapture()))
        val target = EditorEngine()
        check(target.clipboardApply(checkNotNull(target.clipboardCapture()), FragmentCodec.decode(FragmentCodec.encode(fragment))))
        check(structureDescription(target.snapshot().committed) == description)
        check(target.snapshot().committed.paragraphs().first { it.properties.item.isNotEmpty() }.properties.item != listed[0].properties.item)
        check(target.snapshot().committed.units.first { it.style.link != null }.style.link!!.id != sample.units.first { it.style.link != null }.style.link!!.id)
        val after = target.snapshot()
        target.perform(EditAction.Undo)
        check(target.snapshot().committed.units.isEmpty())
        target.perform(EditAction.Redo)
        check(target.snapshot().committed == after.committed)
        val plain = sample.plainText()
        check(plain.contains("1. پہلی") && plain.contains("  • Nested") && plain.contains("[Image:") && plain.contains("<https://example.org/urdu>"))
        val invalid = bytes.copyOf(bytes.size - 32)
        java.nio.ByteBuffer.wrap(invalid).putInt(4, 10)
        check(runCatching { DocumentFiles.decode(invalid + java.security.MessageDigest.getInstance("SHA-256").digest(invalid)) }.isFailure)
    }
    test("Image-only and closed heading rich fragments split destination text without flattening") {
        val source = EditorEngine(sample)
        val i = sample.units.indexOfFirst { it.image != null }
        source.move(Selection(i, i + 1))
        val f = RichFragment.capture(checkNotNull(source.clipboardCapture()))
        val destination = e("abcd")
        destination.move(Selection(2, 2))
        val before = destination.snapshot()
        check(destination.clipboardApply(checkNotNull(destination.clipboardCapture()), f))
        val after = destination.snapshot()
        check(after.committed.text == "ab\n\ufffc\ncd" && after.committed.resources == sample.resources)
        destination.perform(EditAction.Undo)
        check(destination.snapshot().committed == before.committed)
        destination.perform(EditAction.Redo)
        check(destination.snapshot().committed == after.committed)
        source.move(Selection(0, sample.paragraphs()[1].start))
        val heading = RichFragment.capture(checkNotNull(source.clipboardCapture()))
        val d = e("LEFTTAIL")
        d.move(Selection(4, 4))
        check(d.clipboardApply(checkNotNull(d.clipboardCapture()), heading))
        check(d.snapshot().committed.paragraphs()[1].properties.heading == 1 && d.snapshot().committed.text.startsWith("LEFT\n"))
    }
    test("Cross-structure replacement and list ancestor deletion preserve surviving resources and numbering") {
        val e = EditorEngine(sample)
        val before = e.snapshot()
        val query = findLiteral(before.committed, "English notes")
        check(e.replaceFound(e.captureFindBasis(), query, "Writing notes", true) == FindReplaceResult.Applied)
        val changed = e.snapshot()
        check(changed.committed.resources == before.committed.resources && changed.committed.paragraphs().map { it.properties } == before.committed.paragraphs().map { it.properties })
        check(changed.committed.units.filter { it.style.link != null } == before.committed.units.filter { it.style.link != null })
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == changed.committed)
        e.selectAll()
        e.replace("replacement", "cross-structure")
        check(e.snapshot().committed.text == "replacement" && e.snapshot().committed.resources == sample.resources)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == changed.committed)
        val nested = e("parent\ncontinuation\nchild\nlast")
        nested.selectAll()
        nested.structure(StructureCommand.ListType(ListKind.NUMBER))
        at(nested, "child")
        nested.structure(StructureCommand.Indent)
        val pre = nested.snapshot()
        nested.structure(StructureCommand.Outdent)
        check(nested.snapshot().committed.text == pre.committed.text)
        val parent = nested.snapshot().committed.paragraphs()[1].properties.item
        check(nested.snapshot().committed.paragraphs()[2].properties.parent == "")
        nested.move(Selection(0, 7))
        nested.replace("", "delete parent")
        validateStructure(nested.snapshot().committed)
    }
    test("Joining sibling item text preserves left identity and reparents right nested children") {
        val joined = e("A\nB\nchild")
        joined.selectAll()
        joined.structure(StructureCommand.ListType(ListKind.NUMBER))
        at(joined, "child")
        joined.structure(StructureCommand.Indent)
        val before = joined.snapshot()
        val left = before.committed.paragraphs()[0].properties.item
        joined.move(Selection(1, 1))
        joined.delete(false)
        val after = joined.snapshot()
        check(after.committed.text == "AB\nchild")
        check(after.committed.paragraphs()[0].properties.item == left && after.committed.paragraphs()[1].properties.parent == left) { "Sibling join lost nested item ownership" }
        joined.perform(EditAction.Undo)
        check(joined.snapshot().committed == before.committed)
        joined.perform(EditAction.Redo)
        check(joined.snapshot().committed == after.committed)
    }
    test("Sibling joins merge continuation paragraphs and native partial epochs preserve accepted ownership") {
        fun listed(multi: Boolean = false): EditorEngine {
            val owner = e(if (multi) "A\nB1\nB2\nchild" else "A\nB\nchild")
            owner.selectAll()
            owner.structure(StructureCommand.ListType(ListKind.NUMBER))
            if (multi) {
                val old = owner.snapshot().committed
                val ps = old.paragraphs()
                val b = ps[1].properties
                val replacement = old.patchParagraphs(mapOf(ps[2].anchor to b))
                val fresh = EditorEngine(replacement)
                at(fresh, "child")
                fresh.structure(StructureCommand.Indent)
                return fresh
            }
            at(owner, "child")
            owner.structure(StructureCommand.Indent)
            return owner
        }
        val joined = listed(true)
        val before = joined.snapshot()
        val left = before.committed.paragraphs()[0].properties.item
        joined.move(Selection(1, 2))
        joined.replace("", "Join")
        val after = joined.snapshot()
        val ps = after.committed.paragraphs()
        check(after.committed.text == "AB1\nB2\nchild" && ps.take(2).all { it.properties.item == left } && ps[2].properties.parent == left) { "Continuation item did not merge into left sibling" }
        joined.perform(EditAction.Undo)
        check(joined.snapshot().committed == before.committed)
        joined.perform(EditAction.Redo)
        check(joined.snapshot().committed == after.committed)
        for (partial in listOf(false, true)) {
            val owner = listed()
            val original = owner.snapshot().committed
            val leftItem = original.startParagraph.item
            owner.move(if (partial) Selection(1, 4) else Selection(1, 3))
            val candidate = if (partial) "X\n" else "X"
            check(owner.nativeStructureAdmission(candidate)) { "Safe list join refused" }
            owner.inputMethod(candidate, 0, candidate.length)
            validateStructure(owner.snapshot().visible)
            if (partial) {
                owner.inputMethod(candidate, 1, 1)
                validateStructure(owner.snapshot().visible)
                owner.inputMethod("\n", 1, null)
            } else {
                owner.inputMethod(candidate, candidate.length, null)
            }
            val accepted = owner.snapshot().committed
            check(accepted.text == "AX\nchild" && accepted.paragraphs()[1].properties.parent == leftItem)
            owner.perform(EditAction.Undo)
            check(owner.snapshot().committed == original)
            owner.perform(EditAction.Redo)
            check(owner.snapshot().committed == accepted)
        }
    }
    test("Image inserted inside an item preserves multi-paragraph item ownership") {
        val owner = e("abcd")
        owner.structure(StructureCommand.ListType(ListKind.NUMBER))
        owner.move(Selection(2, 2))
        val before = owner.snapshot()
        owner.structure(StructureCommand.InsertImage(ImageBlock(imported.first, 240, "Within item"), imported.second))
        val after = owner.snapshot()
        check(after.committed.text == "ab\n\ufffc\ncd" && after.committed.paragraphs().all { it.properties.item == before.committed.startParagraph.item })
        check(after.committed.listMarkers().size == 1 && DocumentFiles.decode(DocumentFiles.encode(after.committed)).same(after.committed))
        owner.perform(EditAction.Undo)
        check(owner.snapshot().committed == before.committed)
        owner.perform(EditAction.Redo)
        check(owner.snapshot().committed == after.committed)
    }
    test("Block insertion before headings retains original tail role/identity and targets it through edits/Find/Undo") {
        val image = ImageBlock(imported.first, 240, "Before heading")
        for (prefix in listOf("", "prefix\n")) {
            val owner = e(prefix + "Old heading\nbody")
            at(owner, "Old heading")
            owner.structure(StructureCommand.Role(1))
            val original = owner.outline().single()
            val before = owner.snapshot()
            owner.structure(StructureCommand.InsertImage(image, imported.second))
            check(owner.outline().single().anchor == original.anchor && owner.outline().single().label == "Old heading")
            check(owner.navigateOutline(original.anchor))
            check(owner.snapshot().committed.text.substring(owner.snapshot().selection.focus).startsWith("Old heading"))
            owner.setParagraphProperties(alignment = ParagraphAlignment.END)
            check(owner.snapshot().committed.paragraphAt(owner.snapshot().selection.focus).properties.alignment == ParagraphAlignment.END)
            owner.perform(EditAction.Undo)
            owner.perform(EditAction.Undo)
            check(owner.snapshot().committed == before.committed)
            owner.perform(EditAction.Redo)
            check(owner.navigateOutline(original.anchor))
            val source = e("New heading\n")
            source.move(Selection(0, 0))
            source.structure(StructureCommand.Role(2))
            source.move(Selection(0, source.snapshot().committed.units.size))
            val fragment = RichFragment.capture(checkNotNull(source.clipboardCapture()))
            check(owner.clipboardApply(checkNotNull(owner.clipboardCapture()), fragment))
            val outline = owner.outline()
            check(outline.map { it.label } == listOf("New heading", "Old heading") && outline[1].anchor == original.anchor && outline[0].anchor != original.anchor)
            check(owner.navigateOutline(original.anchor))
            owner.structure(StructureCommand.Role(3))
            check(owner.outline()[0].level == 2 && owner.outline()[1].level == 3)
            val basis = owner.captureFindBasis()
            check(owner.replaceFound(basis, findLiteral(basis.document, "body"), "tail", true) == FindReplaceResult.Applied)
            check(owner.navigateOutline(original.anchor))
            check(DocumentFiles.decode(DocumentFiles.encode(owner.snapshot().committed)).same(owner.snapshot().committed))
        }
        val split = e("Old heading")
        split.structure(StructureCommand.Role(1))
        split.move(Selection(3, 3))
        split.structure(StructureCommand.InsertImage(image, imported.second))
        check(split.outline().map { it.level } == listOf(1, 1) && split.outline().map { it.label } == listOf("Old", " heading".trim()))
    }
    test("Outline navigation settles composition by stable heading identity rather than captured offsets") {
        val owner = e("aaa\nTarget")
        at(owner, "Target")
        owner.structure(StructureCommand.Role(2))
        val heading = owner.outline().single()
        val before = owner.snapshot().committed
        owner.move(Selection(0, 0))
        owner.inputMethod("prefix", 0, 6)
        val count = owner.snapshot().undoCount
        check(owner.navigateOutline(heading.anchor) && owner.snapshot().waiting)
        owner.inputMethod("prefix", 6, null)
        check(owner.snapshot().committed.text == "prefixaaa\nTarget")
        check(owner.snapshot().selection == Selection(10, 10) && owner.snapshot().undoCount == count + 1)
        owner.perform(EditAction.Undo)
        check(owner.snapshot().committed == before)
    }
    test("Selected image plus boundary promotes surviving text head; native retained objects never mix into text") {
        fun owner(): EditorEngine {
            val e = e("Old heading\nbody")
            e.move(Selection(0, 0))
            e.structure(StructureCommand.Role(1))
            e.structure(StructureCommand.InsertImage(ImageBlock(imported.first, 240, "Object"), imported.second))
            return e
        }
        for (native in listOf(false, true)) {
            val e = owner()
            val heading = e.outline().single()
            val before = e.snapshot()
            e.move(Selection(0, 2))
            if (native) {
                e.inputMethod("new", 0, 3)
                check(e.snapshot().visible.startParagraph.heading == 1)
                e.inputMethod("new", 3, null)
            } else {
                e.replace("", "Delete")
            }
            check(e.outline().single().anchor == heading.anchor && e.snapshot().committed.startParagraph.heading == 1)
            check(e.navigateOutline(heading.anchor))
            check(e.snapshot().selection == Selection(0, 0))
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == before.committed)
        }
        val e = owner()
        val before = e.snapshot()
        e.move(Selection(0, 2))
        check(!e.nativeStructureAdmission("\ufffcX"))
        e.inputMethod("\ufffcX", 0, 2)
        check(e.snapshot().committed == before.committed && e.snapshot().marked == null)
        val end = e.snapshot().committed.paragraphs()[1].end
        e.move(Selection(0, end))
        check(e.nativeStructureAdmission("\ufffc\nChanged heading"))
        e.inputMethod("\ufffc\nChanged heading", 0, 17)
        validateStructure(e.snapshot().visible)
        check(e.snapshot().visible.units.first().kind == UnitKind.IMAGE)
        e.inputMethod("\ufffc\nChanged heading", 17, null)
        check(e.snapshot().committed.units.first().kind == UnitKind.IMAGE)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
    }
    test("Composition queued roles/list commands, selected-image replacement, retained draft and stale worker basis") {
        val e = EditorEngine(sample)
        e.move(Selection(0, 1))
        e.inputMethod("اردو", 0, 4)
        e.structure(StructureCommand.Role(3))
        check(e.snapshot().waiting && e.snapshot().committed.startParagraph.heading == 1)
        e.inputMethod("اردو", 4, null)
        check(e.snapshot().committed.startParagraph.heading == 3)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.startParagraph.heading == 1)
        val im = e.snapshot().committed.units.indexOfFirst { it.image != null }
        e.move(Selection(im, im + 1))
        e.inputMethod("text", 0, 4)
        e.inputMethod("text", 4, null)
        check(e.snapshot().committed.units.none { it.image != null })
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.units.any { it.image != null })
        val c = checkNotNull(e.clipboardCapture())
        e.replace("x", "edit")
        check(!e.applyStructure(c, StructureCommand.InsertImage(ImageBlock(imported.first), imported.second)))
        val draft = e.captureRecoveryReplacement(sample, Selection(0, 0))
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)
        check(structureDescription(e.snapshot().committed) == description)
        val store = LocalRecoveryStore(root.resolve("recovery"))
        val id = freshStructureId()
        store.acquire(id).use { store.publish(it, LocalCopy(id, 1, System.currentTimeMillis(), null, e.snapshot().committed)) }
        check(structureDescription(store.restore(store.scan().single()).document) == description)
    }
    test("Bounded edit/encode/decode cost samples") {
        for (n in listOf(1, 8)) {
            val e = EditorEngine(sample)
            repeat(n - 1) {
                e.move(Selection(e.snapshot().committed.units.size, e.snapshot().committed.units.size))
                e.replace("\n" + sample.text.replace('\ufffc', ' '), "larger", true)
            }
            val start = System.nanoTime()
            e.move(Selection(0, 0))
            e.replace("X", "edit")
            costs["edit-${e.snapshot().committed.units.size}"] = (System.nanoTime() - start) / 1_000_000
            val write = System.nanoTime()
            val bytes = DocumentFiles.encode(e.snapshot().committed)
            val decoded = DocumentFiles.decode(bytes)
            costs["codec-${decoded.units.size}"] = (System.nanoTime() - write) / 1_000_000
            check(decoded.same(e.snapshot().committed))
        }
    }
    runStructureHostChecks(result, root, sample, checks, costs)
    result.writeText(evidenceJson(mapOf("status" to "PASS", "checks" to checks, "costsMs" to costs, "scope" to "Core/independent semantic description, actual mounted shell/clipboard. IME callbacks synthetic; real native session separate.")), Charsets.UTF_8)
    println("PASS structured document milestone checks")
}
fun runStructureHostChecks(result: File, root: java.nio.file.Path, sample: Document, checks: MutableList<String>, costs: MutableMap<String, Long>) {
    fun <T> owner(f: () -> T): T {
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(f) }
        return checkNotNull(r).getOrThrow()
    }
    fun await(label: String, f: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!owner(f)) {
            check(System.nanoTime() < end) { label }
            Thread.sleep(20)
        }
    }
    var w: JFrame? = null
    try {
        lateinit var surface: EditorSurface
        lateinit var controls: StructureControls
        owner {
            w = showEditor(initial = sample, fontProfile = "noto-nastaliq", paged = true, offscreenTest = true, recoveryDirectory = root.resolve("mounted-recovery"))
            surface = checkNotNull(windowCurrentSurface(w!!))
            controls = w!!.rootPane.getClientProperty("editor.structure") as StructureControls
        }
        await("Mounted structure geometry") { surface.view.measure != null && sample.resources.keys.all { ImageResources.cached(it) != null } }
        for (paged in listOf(false, true)) {
            for (zoom in listOf(1f, .75f)) {
                owner {
                    surface.view.setPagedMode(paged)
                    surface.view.changeZoom(zoom)
                    w!!.setSize(940, 400)
                    w!!.validate()
                }
                await("Updated structure geometry") { surface.view.measure != null && surface.view.geometry?.typographyKey?.contains(":zoom=$zoom") == true && surface.view.geometry?.typographyKey?.endsWith(if (paged)":paged" else ":continuous") == true }
                owner {
                    val start = System.nanoTime()
                    var g = checkNotNull(surface.geometry())
                    costs["layout-$paged-$zoom"] = (System.nanoTime() - start) / 1_000_000
                    var p = g.paragraphs.single { it.image != null }
                    check(kotlin.math.abs(p.height / p.imageWidth - 100f / 240) < .001)
                    check(p.height <= g.pageHeight.takeIf { it > 0 }?.minus(80 * zoom) ?: Float.MAX_VALUE)
                    for (direction in listOf(ParagraphDirection.RTL, ParagraphDirection.LTR)) {
                        for (alignment in ParagraphAlignment.entries) {
                            surface.engine.move(Selection(p.startOffset, p.startOffset + 1))
                            surface.engine.setParagraphProperties(direction, alignment)
                            val trial = checkNotNull(surface.geometry())
                            val image = trial.paragraphs.single { it.image != null }
                            check(kotlin.math.abs(image.imageWidth / trial.scale - 240 * zoom) < 1) { "Zoomed image width uses stale density: $paged/$zoom key=${trial.typographyKey} width=${image.imageWidth} scale=${trial.scale} declared=${image.image?.width} textWidth=${trial.textWidth}" }
                            val expected = when (alignment) {
                                ParagraphAlignment.CENTER -> (trial.textWidth - image.imageWidth) / 2
                                ParagraphAlignment.START -> if (direction == ParagraphDirection.RTL)trial.textWidth - image.imageWidth else 0f
                                ParagraphAlignment.END -> if (direction == ParagraphDirection.RTL)0f else trial.textWidth - image.imageWidth
                            }
                            check(kotlin.math.abs(image.imageLeft - expected) < .01)
                            check(kotlin.math.abs(trial.caret(image.startOffset).left - (trial.x + expected + if (direction == ParagraphDirection.RTL)image.imageWidth else 0f)) < .01)
                            val actual = checkNotNull(surface.accessibleContext.accessibleText.getCharacterBounds(image.startOffset))
                            check(kotlin.math.abs(actual.x - (trial.x + expected) / trial.scale) < 1)
                            surface.engine.perform(EditAction.Undo)
                        }
                    }
                    g = checkNotNull(surface.geometry())
                    p = g.paragraphs.single { it.image != null }
                    val at = p.startOffset
                    surface.engine.move(Selection(at, at + 1))
                    val location = checkNotNull(surface.getTextLocation(null))
                    check(location.height > 0)
                    val attrs = surface.accessibleContext.accessibleText.getCharacterAttribute(at)
                    check(attrs.getAttribute("ImageAlternativeText") == sample.units[at].image!!.alt)
                    val box = checkNotNull(surface.accessibleContext.accessibleText.getCharacterBounds(at))
                    check(box.width == (p.imageWidth / g.scale).toInt() || kotlin.math.abs(box.width - p.imageWidth / g.scale) < 2)
                    check(g.paragraphs.first().layout.getLineBottom(0) > g.paragraphs.first().layout.getLineTop(0))
                    val listed = sample.paragraphs().filter { it.properties.item.isNotEmpty() }
                    for (block in listed) {
                        val layout = g.paragraphs[block.index]
                        val props = block.properties
                        val column = checkNotNull(layout.listColumn)
                        if (column.rootRtl)check(column.width < g.textWidth) else check(layout.contentLeft > 0)
                        check(layout.contentLeft + layout.layout.getLineRight(0) <= g.textWidth + 1)
                    }
                    val tabs = w!!.rootPane.getClientProperty("editor.writingTabs") as JTabbedPane
                    tabs.selectedIndex = 3
                    w!!.validate()
                    check(surface.height >= 110)
                    check(controls.role.width > 0 && controls.insertImage.width > 0)
                    val entry = controls.outlineModel[1]
                    val before = surface.engine.snapshot()
                    check(surface.engine.navigateOutline(entry.anchor))
                    check(surface.engine.snapshot().committed == before.committed)
                    surface.engine.move(Selection(0, 0))
                    controls.role.selectedIndex = 3
                    check(surface.engine.snapshot().committed.startParagraph.heading == 2)
                    surface.engine.perform(EditAction.Undo)
                    check(surface.engine.snapshot().committed.same(sample))
                }
            }
        }
        owner {
            surface.view.changeZoom(1f)
            surface.view.setPagedMode(true)
            surface.engine.move(Selection(0, 0))
            controls.outlinePane.isVisible = true
            w!!.setSize(1020, 740)
            w!!.validate()
        }
        await("Final mounted render") { surface.view.geometry?.revision == surface.engine.snapshot().documentRevision && !surface.view.ensureCaret && surface.view.geometry?.hostWidth == surface.width }
        owner {
            check(surface.view.hostWidth == surface.width && surface.width < 900)
            val g = checkNotNull(surface.view.geometry)
            check(g.x + g.textWidth <= surface.width * g.scale) { "Outline width not propagated to renderer" }
        }
        owner {
            val bitmap = java.awt.image.BufferedImage(w!!.width, w!!.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = bitmap.createGraphics()
            w!!.paint(graphics)
            graphics.dispose()
            javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "structured-shell.png"))
        }
        checks += "Mounted continuous/paged/zoom image aspect/AT/native bounds, list indent geometry, heading role/identity outline, minimum shell"
        owner {
            surface.engine.move(Selection(0, 0))
            surface.engine.setParagraphProperties(line = 0)
            surface.changeTypography(TypographyProfile.system)
            surface.view.changeZoom(1f)
        }
        await("System heading natural metrics") { surface.view.measure != null && surface.view.geometry?.typographyKey?.startsWith(TypographyProfile.system.key) == true }
        owner {
            val g = checkNotNull(surface.geometry())
            check(g.paragraphs.first().layout.layoutInput.style.lineHeight == androidx.compose.ui.unit.TextUnit.Unspecified)
            surface.engine.perform(EditAction.Undo)
            surface.changeTypography(TypographyProfile.load("noto-nastaliq"))
        }
        await("Restored candidate profile") { surface.view.measure != null && surface.view.geometry?.typographyKey?.startsWith(surface.view.typography.key) == true }
        // Actual OS clipboard processes own only the sample payload; no prior clipboard read.
        val input = root.resolve("clipboard-structured.astra")
        DocumentFiles.write(input, sample)
        val javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val base = listOf(javaExe, "-cp", System.getProperty("java.class.path"), "editor.MainKt")
        val publisher = ProcessBuilder(base + listOf("--clipboard-child=publish", "--clipboard-file=$input")).redirectErrorStream(true).start()
        try {
            check(publisher.inputStream.bufferedReader().readLine() == "OWNED_CLIPBOARD_READY")
            for (mode in listOf("rich", "plain")) {
                val file = root.resolve("cross-process-$mode.astra")
                val process = ProcessBuilder(base + listOf("--clipboard-child=$mode", "--clipboard-file=$file")).redirectErrorStream(true).start()
                check(process.waitFor(40, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
                val decoded = DocumentFiles.read(file)
                if (mode == "rich")check(structureDescription(decoded) == structureDescription(sample)) else check(decoded.text == sample.plainText() && decoded.resources.isEmpty())
            }
        } finally {
            publisher.outputStream.write("done\n".toByteArray())
            publisher.outputStream.flush()
            if (!publisher.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))publisher.destroyForcibly()
        }
        checks += "Actual owned cross-process mixed heading/list/link/image clipboard and explicit semantic plain representation"
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val localClipboard = java.awt.datatransfer.Clipboard("owned-image-worker")
        lateinit var captured: ClipboardCapture
        owner {
            captured = checkNotNull(
                surface.engine.clipboardCapture().let {
                    surface.engine.selectAll()
                    surface.engine.clipboardCapture()
                },
            )
            RichClipboard.publish(captured, localClipboard, codec = FragmentCodec)
            surface.engine.move(Selection(surface.engine.snapshot().committed.units.size, surface.engine.snapshot().committed.units.size))
            surface.clipboardReaderForChecks = { plain ->
                check(!SwingUtilities.isEventDispatchThread())
                reads.incrementAndGet()
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                RichClipboard.read(localClipboard, plain, codec = FragmentCodec)
            }
            surface.paste()
            surface.paste()
            check(surface.clipboardPasteBusy)
        }
        check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        lateinit var intervening: Document
        owner {
            surface.engine.replace("intervening", "edit")
            intervening = surface.engine.snapshot().committed
        }
        release.countDown()
        await("Stale image clipboard decode refused") { !surface.clipboardPasteBusy }
        owner {
            check(reads.get() == 1 && surface.engine.snapshot().committed == intervening && surface.clipboardMessage.contains("refused"))
            surface.engine.perform(EditAction.Undo)
            surface.engine.move(Selection(0, surface.engine.snapshot().committed.units.size))
            surface.clipboardReaderForChecks = { plain ->
                check(!SwingUtilities.isEventDispatchThread())
                RichClipboard.read(localClipboard, plain, codec = FragmentCodec)
            }
            surface.paste()
        }
        await("Actual mounted rich image paste") { !surface.clipboardPasteBusy }
        owner {
            check(structureDescription(surface.engine.snapshot().committed) == structureDescription(sample))
            check(surface.clipboardMessage == "Pasted rich text")
            surface.engine.perform(EditAction.Undo)
            surface.clipboardReaderForChecks = null
        }
        checks += "Mounted clipboard image decode off EDT; one bounded worker, duplicate paste suppressed, stale intervening edit refused and fresh full structure/resource paste one Undo"
        owner {
            val intent = EditorEngine()
            intent.replace("abc", "fixture")
            intent.move(Selection(0, 0))
            val old = checkNotNull(intent.clipboardCapture())
            intent.setTextStyle(TextStylePatch(size = 40))
            check(!intent.clipboardApply(old, null, "paste"))
            check(intent.snapshot().committed.text == "abc")
            val selection = checkNotNull(intent.clipboardCapture())
            intent.move(Selection(1, 1))
            intent.move(Selection(0, 0))
            check(!intent.clipboardApply(selection, null, "paste"))
        }
        checks += "Async clipboard/dialog captures reject changed typing intent and selection-away/back without requiring a text change"
        owner {
            val at = surface.engine.snapshot().committed.units.size
            surface.engine.move(Selection(at, at))
            surface.engine.replace("checkpoint", "edit")
            surface.engine.inputMethod("candidate", 0, 9)
            check(!controls.link.isEnabled && !controls.insertImage.isEnabled)
        }
        val expected = owner { surface.engine.snapshot().committed }
        val controller = owner { w!!.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController }
        await("Expanded structured committed checkpoint") { controller.state().let { it["observed"] == it["completed"] && (it["completed"] as Long) > 1L } }
        owner { controller.finish(false) }
        val store = LocalRecoveryStore(root.resolve("mounted-recovery"))
        val end = System.nanoTime() + 10_000_000_000
        var entry: LocalRecoveryEntry? = null
        while (entry == null) {
            entry = store.scan().firstOrNull { it.copy?.document?.same(expected) == true }
            check(System.nanoTime() < end)
            if (entry == null)Thread.sleep(20)
        }
        check(structureDescription(store.restore(entry).document) == structureDescription(expected))
        checks += "Actual controller checkpoint preserves all structure/resources and excludes provisional candidate"
        owner {
            surface.engine.abortComposition()
            val image = surface.engine.snapshot().committed.units.indexOfFirst { it.kind == UnitKind.IMAGE }
            surface.engine.move(Selection(image, image))
            val before = surface.engine.snapshot()
            fun native(text: String) {
                val event = java.awt.event.InputMethodEvent(surface, java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, java.text.AttributedString(text).iterator, text.length, null, null)
                surface.dispatchEvent(event)
            }
            native("seam")
            check(!surface.admissionAllowed && surface.quarantinedCallbacks.size == 1 && surface.engine.snapshot().committed == before.committed)
            surface.engine.move(Selection(0, 0))
            native("late")
            check(surface.quarantinedCallbacks.size == 2 && surface.engine.snapshot().committed == before.committed)
            val retired = surface
            check(surface.recoverExplicitly())
            surface = checkNotNull(windowCurrentSurface(w!!))
            check(retired.retired && surface !== retired && surface.admissionAllowed && surface.quarantinedCallbacks.size == 2)
            native("safe")
            check(surface.engine.snapshot().committed.text.startsWith("safe") && surface.quarantinedCallbacks.size == 2)
        }
        checks += "Synthetic noncancelable image-seam input pauses admission, retains subsequent callbacks, explicit existing host recovery never replays and admits only new input"
        owner {
            val image = surface.engine.snapshot().committed.units.indexOfFirst { it.kind == UnitKind.IMAGE }
            for (direction in listOf(ParagraphDirection.RTL, ParagraphDirection.LTR)) {
                surface.engine.move(Selection(image, image + 1))
                surface.engine.setParagraphProperties(direction = direction)
                surface.engine.move(Selection(image, image + 2))
                surface.engine.inputMethod("\ufffc\ncandidate", 0, 0)
                for (offset in 0..1) {
                    val forward = checkNotNull(surface.getTextLocation(java.awt.font.TextHitInfo.leading(offset)))
                    val physicalRight = (offset == 0) == (direction == ParagraphDirection.RTL)
                    val reverse = checkNotNull(surface.getLocationOffset(forward.x + if (physicalRight)-1 else 1, forward.y + forward.height / 2))
                    check(reverse.insertionIndex == offset) { "Atomic image native offset $offset became ${reverse.insertionIndex}" }
                }
                surface.engine.abortComposition()
                surface.engine.perform(EditAction.Undo)
            }
        }
        checks += "Synthetic retained-image composition forward/reverse native queries preserve raw before/after offsets in RTL and LTR"
    } finally {
        owner { w?.dispose() }
    }
}
