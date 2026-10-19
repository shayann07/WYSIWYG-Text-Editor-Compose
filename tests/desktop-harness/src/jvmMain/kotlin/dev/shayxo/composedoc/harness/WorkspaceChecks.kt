package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.ExactDocument
import dev.shayxo.composedoc.data.FileConflict
import dev.shayxo.composedoc.data.FileFingerprint
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.data.LocalCopy
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.SafeDocumentIO
import dev.shayxo.composedoc.data.SavePoint
import dev.shayxo.composedoc.data.TemplateStore
import dev.shayxo.composedoc.data.UnfinishedCapture
import dev.shayxo.composedoc.data.UnfinishedKind
import dev.shayxo.composedoc.data.UnfinishedStore
import dev.shayxo.composedoc.data.WorkspaceBytes
import dev.shayxo.composedoc.data.WorkspaceEntry
import dev.shayxo.composedoc.data.WorkspaceStore
import dev.shayxo.composedoc.data.cloneDocument
import dev.shayxo.composedoc.data.workspacePositionsValid
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaperSize
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.WrapAffinity
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.ImageResizeGesture
import dev.shayxo.composedoc.workspace.samplePng
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun workspaceFixture(): Document {
    val e = EditorEngine()
    e.replace("عنوان\nاردو کِ، العربية لَا، می‌، שָׁ، é क्षि ক্ষি ก้ 漢字 が 한 👩🏽‍💻 \ud800\nFirst item\nSecond item\n", "Fixture", paragraphBreaks = true)
    e.move(Selection(0, 0))
    e.structure(StructureCommand.Role(1))
    e.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START)
    val a = e.snapshot().committed.text.indexOf("First")
    e.move(Selection(a, a))
    e.structure(StructureCommand.ListType(ListKind.NUMBER))
    e.structure(StructureCommand.MarkerStyle(MarkerTypography(StoredFont.GULZAR, 32)))
    val b = e.snapshot().committed.text.indexOf("Second")
    e.move(Selection(b, b))
    e.structure(StructureCommand.ListType(ListKind.BULLET))
    e.structure(StructureCommand.Indent)
    e.move(Selection(0, 5))
    e.setTextStyle(TextStylePatch(underline = true, font = StoredFont.NOTO_NASTALIQ, size = 32, color = 0x174ea6))
    e.move(Selection(a, a + 5))
    e.structure(StructureCommand.Link("https://example.test/اردو"))
    e.move(Selection(e.snapshot().committed.units.size, e.snapshot().committed.units.size))
    val data = ImageDecoder.validate(samplePng())
    e.structure(StructureCommand.InsertImage(ImageBlock(data.first, 240, "خاکہ — illustration"), data.second))
    e.structure(StructureCommand.Pages(PageSettings(PaperSize.LETTER, true, 20)))
    var fixture = e.snapshot().committed
    val itemIds = fixture.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct().mapIndexed { index, value -> value to "fixture-item-$index" }.toMap()
    fixture = fixture.patchParagraphs(fixture.paragraphs().associate { it.anchor to it.properties.copy(item = itemIds[it.properties.item] ?: "", parent = itemIds[it.properties.parent] ?: "") })
    fixture = fixture.copy(units = fixture.units.map { u -> u.copy(style = u.style.copy(link = u.style.link?.copy(id = "fixture-link"))) })
    return EditorEngine(fixture).snapshot().committed
}
fun runWorkspaceChecks(result: File) {
    val root = Files.createTempDirectory("astra-workspace-check-")
    System.setProperty("astra.lockRoot", root.resolve("locks").toString())
    val groups = ArrayList<String>()
    val metrics = HashMap<String, Any>()
    fun test(name: String, body: () -> Unit) {
        body()
        groups += name
        println("PASS workspace $name")
    }
    val doc = workspaceFixture()
    val canonical = DocumentFiles.encode(doc)
    test("independent fixture and v8 exact page/structure/resource roundtrip") {
        check(doc.text.startsWith("عنوان\nاردو کِ"))
        check(doc.text.contains("\ud800"))
        check(doc.resources.size == 1)
        check(doc.pageSettings == PageSettings(PaperSize.LETTER, true, 20))
        val ps = doc.paragraphs()
        check(ps[0].properties.heading == 1)
        val first = ps.first { doc.text.substring(it.start, it.end) == "First item" }
        val second = ps.first { doc.text.substring(it.start, it.end) == "Second item" }
        check(first.properties.markerTypography == MarkerTypography(StoredFont.GULZAR, 32))
        check(second.properties.parent == first.properties.item)
        check(DocumentFiles.decode(canonical).same(doc))
        check(ExactDocument.decode(ExactDocument.encode(doc)) == doc)
        val bad = canonical.copyOf()
        java.nio.ByteBuffer.wrap(bad).putInt(4, Int.MAX_VALUE)
        val raw = bad.copyOfRange(0, bad.size - 32)
        val newer = raw + java.security.MessageDigest.getInstance("SHA-256").digest(raw)
        check(runCatching { DocumentFiles.decode(newer) }.isFailure)
    }
    test("page command preserves units/selection and is one exact Undo/Redo") {
        val e = EditorEngine(doc)
        e.move(Selection(10, 3))
        val before = e.snapshot()
        e.structure(StructureCommand.Pages(PageSettings(PaperSize.A4, false, 10)))
        check(e.snapshot().committed.units == doc.units && e.snapshot().selection == before.selection && e.snapshot().undoCount == 1)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == doc)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed.pageSettings == PageSettings(PaperSize.A4, false, 10))
    }
    test("template fresh identities, immutable source, embedded resources, user removal") {
        val store = TemplateStore(root.resolve("templates"))
        check(store.list().size == 3)
        val blank = store.create(store.builtIns[0])
        check(blank.units.isEmpty() && blank.startParagraph.direction == ParagraphDirection.RTL && blank.startStyle.font == StoredFont.NOTO_NASTALIQ)
        val entry = store.save("میرا مضمون — Template", doc)
        val source = store.source(entry)
        val first = store.create(entry)
        val second = store.create(entry)
        check(first.text == source.text && first.units.map { it.id }.toSet().intersect(source.units.map { it.id }.toSet()).isEmpty())
        check(first.rootBlockId != second.rootBlockId && first.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.toSet().intersect(second.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.toSet()).isEmpty())
        val e = EditorEngine(first)
        e.move(Selection(0, 0))
        e.replace("بدل ")
        check(DocumentFiles.encode(store.source(entry)).contentEquals(DocumentFiles.encode(source)))
        store.remove(entry)
        check(store.list().size == 3 && DocumentFiles.encode(second).contentEquals(DocumentFiles.encode(first)).not()) // different semantic IDs, same logical content
        check(first.resources == doc.resources && second.resources == doc.resources && first.pageSettings == doc.pageSettings)
    }
    test("byte-based stale save, Save As race and absence protect originals") {
        val file = root.resolve("test.astra")
        val absent = FileFingerprint.capture(file)
        val written = SafeDocumentIO.write(file, doc, absent)
        check(written.exists)
        val e = EditorEngine(doc)
        e.move(Selection(0, 0))
        e.replace("local ")
        val local = e.snapshot().committed
        val changed = DocumentFiles.encode(doc.copy(startStyle = doc.startStyle.copy(bold = true)))
        Files.write(file, changed)
        check(runCatching { SafeDocumentIO.write(file, local, written) }.exceptionOrNull() is FileConflict)
        check(Files.readAllBytes(file).contentEquals(changed))
        val copy = root.resolve("copy.astra")
        SafeDocumentIO.write(copy, local, FileFingerprint(false))
        check(DocumentFiles.read(copy).same(local))
        val before = FileFingerprint.capture(copy)
        check(runCatching { SafeDocumentIO.write(copy, doc, before) { stage -> if (stage == "before-publish")Files.write(copy, changed) } }.exceptionOrNull() is FileConflict)
        check(Files.readAllBytes(copy).contentEquals(changed))
        Files.delete(copy)
        check(runCatching { SafeDocumentIO.write(copy, doc, before) }.exceptionOrNull() is FileConflict)
        SafeDocumentIO.write(copy, doc, FileFingerprint(false))
        Files.delete(copy)
        Files.write(copy, changed)
        check(runCatching { SafeDocumentIO.write(copy, doc, before) }.exceptionOrNull() is FileConflict)
    }
    test("write failure preserves target and slow write preserves newer edits") {
        val p = root.resolve("failure.astra")
        val fp = SafeDocumentIO.write(p, doc, FileFingerprint(false))
        check(runCatching { SafeDocumentIO.write(p, doc.copy(pageSettings = PageSettings()), fp) { if (it == "before-publish")throw java.io.IOException("injected disk-full") } }.isFailure)
        check(Files.readAllBytes(p).contentEquals(canonical))
        val save = SavePoint(doc)
        val e = EditorEngine(doc)
        e.move(Selection(0, 0))
        e.replace("first ")
        val captured = save.capture(e.snapshot())
        e.replace("later ")
        save.acknowledge(captured)
        check(save.dirty(e.snapshot()))
        check(runCatching { SafeDocumentIO.write(root.resolve("missing-parent/x.astra"), doc, FileFingerprint(false)) }.isSuccess) // owned directories only
    }
    val rs = LocalRecoveryStore(root.resolve("unfinished"))
    val store = UnfinishedStore(rs)
    val owner = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val e = EditorEngine(doc)
    e.move(Selection(6, 6))
    e.inputMethod("کِ 👩🏽‍💻\ud800", 0, 2)
    val active = e.snapshot()
    val capture = UnfinishedCapture(id, 1, UnfinishedKind.ACTIVE_CANDIDATE, active.committed, active.visible, active.selection, active.marked, basisRevision = active.recoveryBasisRevision, identityGeneration = active.identityGeneration)
    test("unfinished exact attributed delta and no automatic native replay") {
        rs.acquire(owner).use { lease ->
            val record = store.publish(lease, capture, "Untitled اردو")
            val (basis, draft) = store.load(record)
            check(basis == doc && draft == active.visible)
            val fresh = EditorEngine(cloneDocument(draft!!))
            check(fresh.snapshot().marked == null && fresh.snapshot().undoCount == 0 && fresh.snapshot().committed.text == active.visible.text)
            val export = root.resolve("unfinished.astra-recovery")
            store.export(record, export)
            check(Files.size(export) > 100)
            check(runCatching { store.export(record, export) }.isFailure && store.entries(owner).size == 1)
        }
    }
    test("generation order, corrupt latest fallback and missing prerequisite visible") {
        rs.acquire(owner).use { lease ->
            val r2 = store.publish(lease, capture.copy(generation = 2, text = "new"), "Named")
            check(store.publish(lease, capture, "old") == r2)
            val path = rs.root.resolve(owner).resolve("unfinished/$id.0.record")
            val valid = Files.readAllBytes(path)
            Files.write(path, valid.take(20).toByteArray())
            val entry = store.entries(owner).single()
            check(entry.warning != null && entry.record!!.generation == 1L)
            check(store.load(checkNotNull(entry.record)).second == active.visible)
            Files.write(path, valid)
            val base = rs.root.resolve(owner).resolve("unfinished/blobs/${r2.basisHash}.bin")
            val bytes = Files.readAllBytes(base)
            Files.delete(base)
            check(runCatching { store.load(r2) }.isFailure && store.entries(owner).single().record == r2)
            Files.write(base, bytes)
        }
    }
    test("selected discard stale refusal, failed discard preserves and new arrivals survive") {
        rs.acquire(owner).use { lease ->
            val r = store.entries(owner).single().record!!
            check(runCatching { store.resolve(lease, r, interrupt = { if (it == "before-publish")throw java.io.IOException("injected denial") }) }.isFailure)
            check(store.entries(owner).single().record == r)
            val next = store.publish(lease, capture.copy(id = UUID.randomUUID().toString(), generation = 3, kind = UnfinishedKind.QUARANTINE, draft = null, text = "\ud800 callback", committed = 0), "Other record")
            val changed = store.publish(lease, capture.copy(generation = 4), "Changed")
            check(runCatching { store.resolve(lease, r) }.isFailure)
            store.resolve(lease, changed)
            check(store.entries(owner).mapNotNull { it.record } == listOf(next))
            check(store.load(next).second == null)
        }
    }
    test("Apply exact same runtime, stale after edit Undo, accepted-before-resolution") {
        val retained = e.captureRecoveryDraft()!!
        e.abortComposition()
        check(e.applyRecoveredDraft(retained) == RecoveryApplyResult.Applied)
        val accepted = e.snapshot().committed
        check(accepted == active.visible)
        check(e.snapshot().undoCount == 1)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == doc)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == accepted)
        val other = EditorEngine(doc)
        other.move(Selection(0, 0))
        other.replace("x")
        other.perform(EditAction.Undo)
        check(other.applyRecoveredDraft(retained) == RecoveryApplyResult.RecoveryBaseChanged)
        rs.acquire(owner).use { lease ->
            val r = store.entries(owner).single().record!!
            store.resolve(lease, r, accepted)
            check(store.entries(owner).isEmpty())
            val protected = ExactDocument.decode(Files.readAllBytes(rs.root.resolve(owner).resolve("unfinished/accepted.bin")))
            check(protected == accepted)
        }
    }
    test("quota exact accounting no eviction, orphan publications count, retry") {
        val small = UnfinishedStore(LocalRecoveryStore(root.resolve("quota")), quota = 6000, recordLimit = 2)
        val own = UUID.randomUUID().toString()
        val d = EditorEngine().also { it.replace("اردو") }.snapshot().committed
        val c = UnfinishedCapture(UUID.randomUUID().toString(), 1, UnfinishedKind.ACTIVE_CANDIDATE, d, d, Selection(0, 0), null)
        small.recovery.acquire(own).use { l ->
            val r = small.publish(l, c, "one")
            small.publish(l, c.copy(id = UUID.randomUUID().toString()), "two")
            check(runCatching { small.publish(l, c.copy(id = UUID.randomUUID().toString()), "three") }.isFailure)
            check(small.entries(own).size == 2)
            val orphan = l.directory.resolve("unfinished/orphan.tmp")
            Files.write(orphan, ByteArray(6000))
            check(small.accounted(own) > 6000)
            check(runCatching { small.publish(l, c.copy(generation = 2, text = "retry"), "one") }.isFailure)
            check(small.entries(own).first { it.id == r.id }.record == r)
            Files.delete(orphan)
            small.publish(l, c.copy(generation = 2), "retry")
            check(small.entries(own).size == 2)
        }
    }
    test("workspace active versus closed, missing/changed basis, corrupt index fallback") {
        val ws = WorkspaceStore(root.resolve("workspace"))
        val fp = FileFingerprint.capture(root.resolve("test.astra"))
        val a = WorkspaceEntry(UUID.randomUUID().toString(), root.resolve("test.astra").toString(), "مضمون — long mixed file", fp, 1, owner, owner, Selection(12, 3, false, WrapAffinity.UPSTREAM), 42f, false, .75f, "noto-nastaliq")
        ws.update(a)
        val b = a.copy(id = UUID.randomUUID().toString(), path = null, name = "Untitled", opened = 2)
        ws.update(b)
        check(ws.entries().toSet() == setOf(a, b))
        ws.update(a.copy(active = false))
        check(ws.entries().single { it.id == a.id }.active.not())
        Files.write(root.resolve("workspace/workspace-1.bin"), byteArrayOf(1, 2, 3))
        check(ws.entries().size == 2) // generation 2 fallback
        ws.remove(a.id)
        check(ws.entries().single() == b)
        ws.clear()
        check(ws.entries().isEmpty())
        check(Files.exists(root.resolve("test.astra")))
    }
    test("image replace exact Undo metadata/resources and cancelled/stale resizing") {
        val imAt = doc.units.indexOfFirst { it.kind == UnitKind.IMAGE }
        val engine = EditorEngine(doc)
        val viewModel = EditorViewModel(engine)
        engine.move(Selection(imAt, imAt + 1))
        val c = engine.clipboardCapture()!!
        val image = doc.units[imAt].image!!
        for (zoom in listOf(.5f, .75f, 1f)) {
            val drag = ImageResizeGesture(c, image, 100, zoom, true)
            check(drag.width((100 + 40 * zoom).toInt()) == 280)
            check(engine.snapshot().committed == doc && engine.snapshot().undoCount == 0)
        }
        val drag = ImageResizeGesture(c, image, 100, 1f, true)
        check(drag.finish(viewModel, 150))
        val resized = engine.snapshot().committed
        check(resized.units[imAt].image!!.width == 290 && engine.snapshot().undoCount == 1)
        check(!drag.finish(viewModel, 200))
        engine.perform(EditAction.Undo)
        check(engine.snapshot().committed == doc)
        engine.perform(EditAction.Redo)
        check(engine.snapshot().committed == resized)
        val transparent = java.awt.image.BufferedImage(16, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(transparent, "png", it) }.toByteArray()
        val (hash, data) = ImageDecoder.validate(bytes)
        check(engine.applyStructure(engine.clipboardCapture()!!, StructureCommand.ReplaceImage(hash, data)))
        val replaced = engine.snapshot().committed
        check(replaced.units[imAt].id == doc.units[imAt].id && replaced.units[imAt].image == image.copy(width = 290, resource = hash))
        check(replaced.resources.containsKey(image.resource))
        engine.perform(EditAction.Undo)
        check(engine.snapshot().committed == resized)
        engine.perform(EditAction.Redo)
        check(engine.snapshot().committed == replaced)
        check(DocumentFiles.decode(DocumentFiles.encode(replaced)).same(replaced))
        check(RichFragment.capture(engine.clipboardCapture()!!).document.resources.getValue(hash) == data)
        check(runCatching { ImageDecoder.validate(samplePng().take(24).toByteArray()) }.isFailure)
        check(runCatching { ImageDecoder.validate(ByteArray(100)) }.isFailure)
    }
    test("composition page/image command queues and zero partial commitment remains exact") {
        val engine = EditorEngine(doc)
        engine.move(Selection(5, 5))
        engine.inputMethod("نیا", 0, 2)
        val before = engine.snapshot().committed
        engine.structure(StructureCommand.Pages(PageSettings(PaperSize.A4, true, 15)))
        check(engine.snapshot().waiting && engine.snapshot().committed == before)
        engine.partialCommit(0)
        check(engine.snapshot().committed == before)
        engine.inputMethod("نیا", 3, null)
        check(engine.snapshot().marked == null && engine.snapshot().committed.pageSettings.landscape)
        engine.perform(EditAction.Undo)
        check(engine.snapshot().committed.pageSettings == doc.pageSettings)
    }
    test("rich paste preserves destination pages; expanded history/recovery after replacement") {
        val source = EditorEngine(doc)
        source.selectAll()
        val fragment = RichFragment.capture(source.clipboardCapture()!!)
        val target = EditorEngine(Document(emptyList(), pageSettings = PageSettings(PaperSize.A4, false, 35)))
        val before = target.snapshot().committed
        check(target.clipboardApply(target.clipboardCapture()!!, fragment))
        val pasted = target.snapshot().committed
        check(pasted.pageSettings == before.pageSettings && pasted.text == doc.text && pasted.resources == doc.resources)
        target.perform(EditAction.Undo)
        check(target.snapshot().committed == before)
        target.perform(EditAction.Redo)
        check(target.snapshot().committed == pasted)
        val term = "First"
        val basis = target.captureFindBasis()
        val found = findLiteral(basis.document, term)
        check(found.size == 1)
        check(target.replaceFound(basis, found, "Changed", all = true) == FindReplaceResult.Applied)
        val replaced = target.snapshot().committed
        check(replaced.pageSettings == before.pageSettings && replaced.resources == doc.resources)
        target.perform(EditAction.Undo)
        check(target.snapshot().committed == pasted)
        target.perform(EditAction.Redo)
        check(target.snapshot().committed == replaced)
        val copy = LocalCopy(UUID.randomUUID().toString(), 1, 1, null, replaced)
        check(LocalRecoveryStore.decode(LocalRecoveryStore.encode(copy)).document.same(replaced))
    }
    test("workspace positions require editing basis, not only unchanged last-saved bytes") {
        val saved = doc
        val engine = EditorEngine(saved)
        engine.move(Selection(0, 0))
        engine.replace("new unsaved prefix ")
        val reference = WorkspaceEntry(
            UUID.randomUUID().toString(), "same-name.astra", "same-name", FileFingerprint(true, canonical.size.toLong(), WorkspaceBytes.hash(canonical)), 1, owner, owner,
            Selection(12, 3, false, WrapAffinity.UPSTREAM), contentHash = WorkspaceBytes.hash(DocumentFiles.encode(engine.snapshot().committed)),
        )
        check(!workspacePositionsValid(reference, saved))
        check(workspacePositionsValid(reference, engine.snapshot().committed))
        val ws = WorkspaceStore(root.resolve("content-basis-index"))
        ws.update(reference)
        check(ws.entries().single() == reference)
    }
    test("legacy v7 defaults without rewriting source; private v8 preserves settings") {
        val encoded = DocumentFiles.encode(doc)
        val extension = 12 + 3 * doc.paragraphs().size
        val payload = encoded.copyOfRange(0, encoded.size - 32 - 3 - extension)
        java.nio.ByteBuffer.wrap(payload).putInt(4, 7)
        val old = payload + java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        val path = root.resolve("legacy-v7.astra")
        Files.write(path, old)
        val migrated = DocumentFiles.read(path)
        check(migrated.pageSettings == PageSettings() && migrated.units.size == doc.units.size && migrated.resources == doc.resources)
        check(Files.readAllBytes(path).contentEquals(old))
        check(DocumentFiles.decode(DocumentFiles.encode(migrated)).same(migrated))
    }
    test("two cooperating writers serialize publication; second stale basis conflicts") {
        val p = root.resolve("two-writers.astra")
        val original = SafeDocumentIO.write(p, doc, FileFingerprint(false))
        val gate = java.util.concurrent.CountDownLatch(1)
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val threads = (1..2).map { n ->
            Thread {
                gate.await()
                try {
                    SafeDocumentIO.write(p, doc.copy(pageSettings = PageSettings(marginMm = 20 + n)), original)
                    outcomes += "saved"
                } catch (_: FileConflict) {
                    outcomes += "conflict"
                }
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach {
            it.join(10000)
            check(!it.isAlive)
        }
        check(outcomes.sorted() == listOf("conflict", "saved"))
        check(DocumentFiles.read(p).pageSettings.marginMm in 21..22)
    }
    test("partial acceptance and retargeted islands recover separately; malformed quarantine stays raw") {
        val engine = EditorEngine()
        engine.replace("0123456789")
        engine.move(Selection(1, 3))
        engine.inputMethod("AB", 0, 2)
        engine.retargetComposition(Selection(4, 5))
        engine.inputMethod("XY", 0, 2)
        engine.partialCommit(1)
        val f = engine.snapshot()
        check(f.committed.text == "0AB3X56789" && f.visible.text == "0AB3XY56789")
        val own = UUID.randomUUID().toString()
        rs.acquire(own).use { l ->
            val r = store.publish(l, UnfinishedCapture(UUID.randomUUID().toString(), 1, UnfinishedKind.ACTIVE_CANDIDATE, f.committed, f.visible, f.selection, f.marked, provenance = engine.recoveryCompositionEvidence()), "Named original changed later")
            val loaded = store.load(r)
            check(loaded.first.text == "0AB3X56789" && loaded.second!!.text == "0AB3XY56789")
            val q = store.publish(l, UnfinishedCapture(UUID.randomUUID().toString(), 2, UnfinishedKind.QUARANTINE, f.committed, null, Selection(0, 0), null, "\ud800\u0000", Int.MAX_VALUE, -19, -7), "Raw unverified")
            check(store.entries(own).first { it.id == q.id }.record!!.let { it.text == "\ud800\u0000" && it.committed == Int.MAX_VALUE && it.caret == -19 })
            check(store.load(q).second == null)
        }
        engine.abortComposition()
        check(engine.snapshot().committed.text == "0AB3X56789")
    }
    test("default sixteen record capacity, no FIFO eviction and unique prerequisite accounting") {
        val own = UUID.randomUUID().toString()
        val d = EditorEngine().also { it.replace("کِ") }.snapshot().committed
        val c = UnfinishedCapture(UUID.randomUUID().toString(), 1, UnfinishedKind.RETAINED_DRAFT, d, d, Selection(0, 0), null)
        rs.acquire(own).use { l ->
            repeat(16) { n -> store.publish(l, c.copy(id = UUID.nameUUIDFromBytes("quota-$n".toByteArray()).toString()), "draft $n") }
            val before = store.entries(own)
            check(before.size == 16)
            check(runCatching { store.publish(l, c.copy(id = UUID.randomUUID().toString()), "overflow") }.isFailure)
            check(store.entries(own) == before)
            val files = Files.list(l.directory.resolve("unfinished/blobs")).use { it.toList() }
            check(files.size == 2) // shared exact base + zero delta
        }
    }
    // Bounded actual production codec/decode timing, not a latency qualification.
    val start = System.nanoTime()
    repeat(5) { DocumentFiles.decode(DocumentFiles.encode(doc)) }
    metrics["fiveFixtureCodecRoundtripsMs"] = (System.nanoTime() - start) / 1e6
    test("larger bounded writing document exact save and measured edit/codec costs") {
        val paragraph = "اردو کِ، Persian می‌، English café 123 — ایک مکمل خیال۔\n"
        val text = paragraph.repeat(300)
        val larger = EditorEngine()
        larger.replace(text, "LargerFixture", paragraphBreaks = true)
        val editStart = System.nanoTime()
        repeat(20) {
            larger.move(Selection(0, 0))
            larger.replace("ن", "MeasuredEdit")
            larger.perform(EditAction.Undo)
        }
        metrics["largerUnits"] = text.length
        metrics["largerTwentyEditUndoMs"] = (System.nanoTime() - editStart) / 1e6
        val codecStart = System.nanoTime()
        val b = DocumentFiles.encode(larger.snapshot().committed)
        check(DocumentFiles.decode(b).text == text)
        metrics["largerCodecMs"] = (System.nanoTime() - codecStart) / 1e6
        val saveStart = System.nanoTime()
        val path = root.resolve("larger.astra")
        SafeDocumentIO.write(path, larger.snapshot().committed, FileFingerprint(false))
        check(DocumentFiles.read(path).text == text)
        metrics["largerSaveReopenMs"] = (System.nanoTime() - saveStart) / 1e6
    }
    result.parentFile.mkdirs()
    result.writeText(evidenceJson(mapOf("status" to "PASS", "groups" to groups, "metrics" to metrics, "native" to "NOT RUN by automated checks", "root" to root.toString())), Charsets.UTF_8)
    println("PASS local writing workspace checks ${groups.size}")
}

/** Deterministic process interruption seam around production publication. Harness kills this PID only. */
fun workspaceChild(args: Array<String>) {
    val root = Path.of(args.single { it.startsWith("--root=") }.substringAfter('='))
    val mode = args.single { it.startsWith("--mode=") }.substringAfter('=')
    val stage = args.single { it.startsWith("--stage=") }.substringAfter('=')
    val owner = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    val id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    val store = UnfinishedStore(LocalRecoveryStore(root.resolve("unfinished-records")))
    val d = workspaceFixture()
    val e = EditorEngine(d)
    e.move(Selection(6, 6))
    e.inputMethod("نیا 👩🏽‍💻\ud800", 0, 2)
    val draft = e.snapshot()
    val capture = UnfinishedCapture(id, 1, UnfinishedKind.ACTIVE_CANDIDATE, d, draft.visible, draft.selection, draft.marked)
    val replacementPixels = java.awt.image.BufferedImage(16, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val replacementBytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(replacementPixels, "png", it) }.toByteArray()
    val (replacementId, replacementResource) = ImageDecoder.validate(replacementBytes)
    val nextDoc = draft.visible.copy(pageSettings = PageSettings(PaperSize.A4, true, 10), resources = draft.visible.resources + (replacementId to replacementResource), units = draft.visible.units.map { u -> if (u.image != null)u.copy(image = checkNotNull(u.image).copy(resource = replacementId)) else u })
    fun stop(s: String) {
        if (s == stage) {
            println("INTERRUPT $mode $stage PID=${ProcessHandle.current().pid()}")
            System.out.flush()
            java.util.concurrent.CountDownLatch(1).await()
        }
    }
    if (mode == "read") {
        val entries = store.entries(owner)
        val r = entries.singleOrNull()?.record
        if (r != null) {
            val (base, target) = store.load(r)
            check(base == d)
            check(target == if (r.generation == 1L)draft.visible else nextDoc)
        }
        val accepted = store.recovery.root.resolve(owner).resolve("unfinished/accepted.bin")
        if (Files.exists(accepted))check(ExactDocument.decode(Files.readAllBytes(accepted)) == draft.visible)
        println(evidenceJson(mapOf("status" to "PASS", "records" to entries.size, "generation" to r?.generation, "accepted" to Files.exists(accepted), "activeComposition" to EditorEngine(d).snapshot().marked)))
        return
    }
    if (mode == "index") {
        val ws = WorkspaceStore(root.resolve("workspace"))
        val old = WorkspaceEntry(owner, null, "old", null, 1, owner, owner)
        ws.update(old)
        ws.update(old.copy(name = "new", opened = 2), ::stop)
        return
    }
    if (mode == "read-index") {
        val es = WorkspaceStore(root.resolve("workspace")).entries()
        check(es.size == 1 && es[0].name in setOf("old", "new"))
        println(evidenceJson(mapOf("status" to "PASS", "name" to es[0].name)))
        return
    }
    store.recovery.acquire(owner).use { lease ->
        val r = store.publish(lease, capture, "Owned test")
        if (mode == "record") {
            store.publish(lease, capture.copy(generation = 2, draft = nextDoc), "Owned test", ::stop)
        } else if (mode == "resolve") {
            val retained = e.captureRecoveryDraft()!!
            e.abortComposition()
            check(e.applyRecoveredDraft(retained) == RecoveryApplyResult.Applied)
            check(e.snapshot().committed == draft.visible && e.snapshot().marked == null)
            stop("after-apply")
            store.resolve(lease, r, e.snapshot().committed, ::stop)
        } else {
            error("Unknown child mode")
        }
    }
}
