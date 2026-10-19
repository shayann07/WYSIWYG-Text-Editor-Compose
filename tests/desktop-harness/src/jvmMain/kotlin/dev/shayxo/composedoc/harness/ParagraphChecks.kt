package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.resolveParagraphDirection
import dev.shayxo.composedoc.model.targetParagraphs
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.RetainedCompositionPreimage
import dev.shayxo.composedoc.workspace.LocalRecoveryController
import dev.shayxo.composedoc.workspace.ParagraphControls
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import java.io.File
import java.nio.file.Files
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

fun runParagraphChecks(result: File) {
    result.absoluteFile.parentFile.mkdirs()
    val checks = ArrayList<String>()
    fun fixture(text: String) = Document(
        text.mapIndexed { i, c ->
            TextUnit(
                i + 1L,
                c,
                if (c == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                if (c == '\n') TextStyleFlags() else TextStyleFlags(i % 2 == 0, i % 2 == 1),
            )
        },
    )
    fun props(e: EditorEngine) = e.snapshot().committed.paragraphs().map { it.properties }
    val text = "اردو\nEnglish\n\n123"
    val e = EditorEngine(fixture(text))
    val original = e.snapshot()
    val blocks = original.committed.paragraphs()
    val b = blocks[1]
    val empty = blocks[2]
    check(original.committed.targetParagraphs(Selection(b.start, empty.start)).map { it.index } == listOf(1))
    check(original.committed.targetParagraphs(Selection(empty.start, b.start)).map { it.index } == listOf(1))
    check(original.committed.targetParagraphs(Selection(b.end, b.end + 1)).map { it.index } == listOf(1))
    e.move(Selection(empty.start, b.start))
    val selected = e.snapshot().selection
    e.setParagraphProperties(direction = ParagraphDirection.LTR)
    check(e.snapshot().selection == selected && e.snapshot().undoCount == 1 && e.snapshot().committed.text == text)
    check(props(e).map { it.direction } == listOf(ParagraphDirection.AUTO, ParagraphDirection.LTR, ParagraphDirection.AUTO, ParagraphDirection.AUTO))
    e.setParagraphProperties(alignment = ParagraphAlignment.END)
    check(e.snapshot().undoCount == 2 && e.snapshot().selection == selected)
    val changed = e.snapshot().committed
    check(changed.units.zip(original.committed.units).all { (a, b) -> a.id == b.id && a.char == b.char && a.style == b.style && a.paragraphStyle == b.paragraphStyle })
    e.perform(EditAction.Undo)
    check(props(e)[1].alignment == ParagraphAlignment.START)
    e.perform(EditAction.Redo)
    check(e.snapshot().committed == changed && e.snapshot().selection == selected)
    e.move(Selection(0, empty.start + 1))
    check(e.paragraphDirectionState() == null && e.paragraphAlignmentState() == null)
    e.move(Selection(empty.start, empty.start))
    e.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER)
    check(props(e)[2] == ParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER))
    val blank = EditorEngine()
    blank.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.END)
    check(blank.snapshot().committed.units.isEmpty() && props(blank).single().bits == 9 && blank.snapshot().undoCount == 1)
    blank.perform(EditAction.Undo)
    check(props(blank).single() == ParagraphProperties())
    check(fixture("x\n").targetParagraphs(Selection(0, 2)).map { it.index } == listOf(0))
    checks += "Exact half-open/backward/separator/empty targeting, mixed states, text/inline/selection preservation and one-step property Undo/Redo"
    val split = EditorEngine(fixture("ab"))
    split.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER)
    split.move(Selection(1, 1))
    split.replace("\n", "Enter", paragraphBreaks = true)
    check(split.snapshot().committed.text == "a\nb" && props(split).distinct().single() == ParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER))
    split.setParagraphProperties(direction = ParagraphDirection.LTR)
    val beforeJoin = split.snapshot()
    split.delete(true)
    check(split.snapshot().committed.text == "ab" && props(split).single() == beforeJoin.committed.paragraphs().first().properties)
    split.perform(EditAction.Undo)
    check(split.snapshot().committed == beforeJoin.committed)
    split.perform(EditAction.Redo)
    check(props(split).single().direction == ParagraphDirection.RTL)
    val deletion = EditorEngine(fixture("left\nright\nlast"))
    deletion.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.END)
    val lastStart = deletion.snapshot().committed.paragraphs().last().start
    deletion.move(Selection(lastStart, lastStart))
    deletion.setParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.CENTER)
    val prior = deletion.snapshot().committed
    val rightStart = prior.paragraphs()[1].start
    deletion.move(Selection(0, rightStart))
    deletion.replace("", "Delete first paragraph")
    check(deletion.snapshot().committed.text == "right\nlast" && props(deletion).first().direction == ParagraphDirection.RTL && props(deletion).last().alignment == ParagraphAlignment.CENTER)
    checks += "Enter/split inherits source properties; join/deletion keeps left properties and surviving separator metadata; Undo restores distinct paragraphs"
    fun resolve(s: String, p: ParagraphProperties = ParagraphProperties(), fallback: ParagraphDirection = ParagraphDirection.LTR) = resolveParagraphDirection(fixture(s).units, p, fallback)
    check(resolve("اردو") == ParagraphDirection.RTL && resolve("English") == ParagraphDirection.LTR)
    check(resolve("123", fallback = ParagraphDirection.RTL) == ParagraphDirection.RTL && resolve("", fallback = ParagraphDirection.RTL) == ParagraphDirection.RTL)
    check(resolve("\u2067اردو\u2069 English") == ParagraphDirection.LTR && resolve("\u2066English\u2069 اردو") == ParagraphDirection.RTL)
    check(resolve("English", ParagraphProperties(ParagraphDirection.RTL)) == ParagraphDirection.RTL && resolve("اردو", ParagraphProperties(ParagraphDirection.LTR)) == ParagraphDirection.LTR)
    checks += "Auto preserves isolate-aware first-strong/neutral/empty fallback; explicit direction overrides base without modifying characters"
    for (version in 1..3) {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(0x41535450)
            out.writeInt(version)
            out.writeInt(3)
            if (version > 1) out.writeByte(2)
            for ((char, kind) in listOf('ک' to 0, '\n' to 1, '\uD800' to 0)) {
                out.writeChar(char.code)
                out.writeByte(kind)
                if (version > 1) out.writeByte(1)
            }
        }
        val payload = bytes.toByteArray()
        val decoded = DocumentFiles.decode(payload + java.security.MessageDigest.getInstance("SHA-256").digest(payload))
        check(decoded.text == "ک\n\uD800" && decoded.paragraphs().all { it.properties == ParagraphProperties() })
        check(DocumentFiles.decode(DocumentFiles.encode(decoded)).same(decoded))
    }
    val encoded = DocumentFiles.encode(changed)
    check(java.nio.ByteBuffer.wrap(encoded).getInt(4) == 9)
    check(DocumentFiles.decode(encoded).same(changed))
    checks += "Explicit v5 properties roundtrip; independent v1/v2/v3 bytes retain text/styles with documented Auto/Start defaults"
    val find = EditorEngine(fixture("a a\na a"))
    find.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER)
    find.move(Selection(find.snapshot().committed.paragraphs()[1].start, find.snapshot().committed.units.size))
    find.setParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.END)
    val paragraphBefore = props(find)
    val basis = find.captureFindBasis()
    check(find.replaceFound(basis, findLiteral(basis.document, "a"), "x", true) == FindReplaceResult.Applied && props(find) == paragraphBefore)
    val afterFind = find.snapshot().committed
    find.perform(EditAction.Undo)
    check(props(find) == paragraphBefore)
    find.perform(EditAction.Redo)
    check(find.snapshot().committed == afterFind)
    val composed = EditorEngine(fixture("abc"))
    composed.inputMethod("ک候補", 1, 2)
    val candidate = composed.snapshot()
    composed.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.END)
    check(composed.snapshot().visible == candidate.visible && composed.snapshot().committed == candidate.committed && composed.snapshot().waiting)
    composed.inputMethod("تاب", 3, null)
    check(composed.snapshot().committed.text == "کتابabc" && props(composed).single().bits == 9 && !composed.snapshot().waiting)
    val settled = composed.snapshot()
    composed.perform(EditAction.Undo)
    check(composed.snapshot().committed.text == settled.committed.text && props(composed).single() == ParagraphProperties())
    composed.perform(EditAction.Redo)
    check(composed.snapshot().committed == settled.committed)
    val queuedUndo = EditorEngine(fixture("abc"))
    queuedUndo.inputMethod("候補", 0, 2)
    queuedUndo.setParagraphProperties(direction = ParagraphDirection.RTL)
    queuedUndo.perform(EditAction.Undo)
    queuedUndo.inputMethod("X", 1, null)
    check(queuedUndo.snapshot().committed.text == "Xabc" && props(queuedUndo).single() == ParagraphProperties())
    val retained = EditorEngine(fixture("اردو"))
    retained.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER)
    retained.inputMethod("候補", 0, 2)
    val draft = checkNotNull(retained.captureRecoveryDraft())
    retained.abortComposition()
    check(retained.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied && retained.snapshot().committed.same(draft.document))
    val stale = retained.captureRecoveryReplacement(retained.snapshot().committed, retained.snapshot().selection)
    retained.setParagraphProperties(alignment = ParagraphAlignment.END)
    check(retained.applyRecoveredDraft(stale) == RecoveryApplyResult.RecoveryBaseChanged)
    checks += "Find/Replace preserves paragraph metadata; active partial composition queues properties and applies once after settlement; retained drafts preserve/refuse changed bases"

    fun <T> owner(action: () -> T): T {
        var v: Result<T>? = null
        SwingUtilities.invokeAndWait { v = runCatching(action) }
        return checkNotNull(v).getOrThrow()
    }
    fun await(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!owner(condition)) {
            check(System.nanoTime() < end) { "$label timed out" }
            Thread.sleep(10)
        }
    }
    val work = Files.createTempDirectory(result.parentFile.toPath(), "paragraph-files-")
    lateinit var window: JFrame
    lateinit var surface: EditorSurface
    lateinit var controls: ParagraphControls
    lateinit var controller: LocalRecoveryController
    var created = false
    val observed = ArrayList<Map<String, Any>>()
    try {
        owner {
            window = showEditor(initial = fixture("ABC\nاردو\nEnglish اردو 123\n123\n"), fontProfile = "noto-nastaliq", offscreenTest = true, recoveryDirectory = work.resolve("recovery"))
            created = true
            surface = checkNotNull(windowCurrentSurface(window))
            controls = window.rootPane.getClientProperty("editor.paragraphControls") as ParagraphControls
            controller = window.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
        }
        await("Initial layout") { surface.view.measure != null }
        owner {
            val g = checkNotNull(surface.geometry())
            check(
                g.paragraphs.map { it.baseDirection } == listOf(
                    androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,
                    androidx.compose.ui.text.style.ResolvedTextDirection.Rtl,
                    androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,
                    androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,
                    androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,
                ),
            )
        }
        for (paged in listOf(false, true)) {
            for ((width, height) in listOf(940 to 400, 1020 to 700)) {
                owner {
                    window.setSize(width, height)
                    window.validate()
                    surface.view.setPagedMode(paged)
                }
                await("Resized layout") { surface.view.measure != null }
                for (direction in listOf(ParagraphDirection.LTR, ParagraphDirection.RTL)) {
                    for (alignment in ParagraphAlignment.entries) {
                        owner {
                            surface.engine.move(Selection(0, 0))
                            val before = surface.engine.snapshot()
                            surface.engine.setParagraphProperties(direction, alignment)
                            surface.view.ensureCaret = false
                            surface.view.scroll = 0f
                            val g = checkNotNull(surface.geometry())
                            val p = g.paragraphs[0]
                            val left = p.layout.getLineLeft(0)
                            val right = p.layout.getLineRight(0)
                            val physicalLeft = (alignment == ParagraphAlignment.START && direction == ParagraphDirection.LTR) || (alignment == ParagraphAlignment.END && direction == ParagraphDirection.RTL)
                            when {
                                alignment == ParagraphAlignment.CENTER -> check(abs((left + right) / 2 - g.textWidth / 2f) < 1.5) { "Center is not centered" }
                                physicalLeft -> check(abs(left) < 1.5) { "Left edge $left is not zero" }
                                else -> check(abs(right - g.textWidth) < 1.5) { "Right edge $right != ${g.textWidth}" }
                            }
                            check(surface.engine.snapshot().committed.text == before.committed.text && surface.engine.snapshot().selection == before.selection)
                            val box = checkNotNull(surface.accessibleContext.accessibleText.getCharacterBounds(0))
                            val hit = surface.accessibleContext.accessibleText.getIndexAtPoint(java.awt.Point(box.x + maxOf(1, box.width / 4), box.y + box.height / 2))
                            check(hit == 0) { "Aligned A hit=$hit paged=$paged size=$width/$height $direction/$alignment box=$box geo=${g.x}/${g.y}/${g.scale} bounds=${p.layout.getBoundingBox(0)} line=$left/$right caret0=${g.caret(0)} caret1=${g.caret(1)}" }
                            val caret = g.caret(0)
                            val native = checkNotNull(surface.getTextLocation(null))
                            val screen = surface.locationOnScreen
                            check(abs(native.x - (screen.x + caret.left / g.scale).roundToInt()) <= 1 && abs(native.y - (screen.y + caret.top / g.scale).roundToInt()) <= 1)
                            val attrs = checkNotNull(surface.accessibleContext.accessibleText.getCharacterAttribute(0))
                            check(attrs.getAttribute("ParagraphDirection") == direction.name && attrs.getAttribute("ParagraphAlignment") == alignment.name)
                            surface.engine.move(Selection(0, 2))
                            check(checkNotNull(surface.geometry()).hasRenderedSelection(surface.engine.snapshot().selection))
                            observed += mapOf("paged" to paged, "width" to width, "direction" to direction.name, "alignment" to alignment.name, "left" to left, "right" to right, "textWidth" to g.textWidth, "hit" to hit)
                        }
                    }
                }
                for (index in 1..3) {
                    for (direction in listOf(ParagraphDirection.LTR, ParagraphDirection.RTL)) {
                        for (alignment in ParagraphAlignment.entries) {
                            owner {
                                val block = surface.engine.snapshot().committed.paragraphs()[index]
                                surface.engine.move(Selection(block.start, block.start))
                                surface.engine.setParagraphProperties(direction, alignment)
                                surface.view.ensureCaret = false
                                surface.view.scroll = 0f
                                val g = checkNotNull(surface.geometry())
                                val p = g.paragraphs[index]
                                check(p.baseDirection == if (direction == ParagraphDirection.RTL) androidx.compose.ui.text.style.ResolvedTextDirection.Rtl else androidx.compose.ui.text.style.ResolvedTextDirection.Ltr)
                                val left = p.layout.getLineLeft(0)
                                val right = p.layout.getLineRight(0)
                                when {
                                    alignment == ParagraphAlignment.CENTER -> check(abs((left + right) / 2 - g.textWidth / 2f) < 1.5)
                                    (alignment == ParagraphAlignment.START) == (direction == ParagraphDirection.LTR) -> check(abs(left) < 1.5)
                                    else -> check(abs(right - g.textWidth) < 1.5)
                                }
                                observed += mapOf("paragraph" to index, "paged" to paged, "width" to width, "direction" to direction.name, "alignment" to alignment.name, "left" to left, "right" to right, "textWidth" to g.textWidth)
                            }
                        }
                    }
                }
            }
        }
        owner {
            surface.engine.move(Selection(0, 0))
            surface.engine.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START)
            surface.engine.inputMethod("XYZ", 0, 1)
            val candidateGeometry = checkNotNull(surface.geometry())
            val native = checkNotNull(surface.getTextLocation(java.awt.font.TextHitInfo.leading(1)))
            val hit = surface.getLocationOffset(native.x, native.y + native.height / 2)
            check(hit?.insertionIndex == 1) { "Candidate-position query did not roundtrip exact offset 1: $hit" }
            check(candidateGeometry.revision == surface.engine.snapshot().documentRevision)
            surface.engine.abortComposition()
            val empty = surface.engine.snapshot().committed.paragraphs().last()
            surface.engine.move(Selection(empty.start, empty.start))
            surface.engine.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER)
            val g = checkNotNull(surface.geometry())
            val p = g.paragraphs.last()
            check(p.baseDirection == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl)
            check(abs(g.caret(empty.start).left - g.x - g.textWidth / 2f) < 2) { "Empty Center caret not centered" }
            val different = surface.engine.snapshot().committed.paragraphs()[1].start
            surface.engine.move(Selection(different, different))
            surface.engine.setParagraphProperties(direction = ParagraphDirection.LTR)
            surface.engine.move(Selection(0, surface.engine.snapshot().committed.units.size))
            check(controls.direction.selectedIndex == 0 && controls.alignment.selectedIndex == 0)
            val allBefore = surface.engine.snapshot()
            controls.direction.selectedItem = "Auto"
            check(surface.engine.snapshot().undoCount == allBefore.undoCount + 1 && surface.engine.snapshot().selection == allBefore.selection)
            check(surface.engine.snapshot().committed.paragraphs().dropLast(1).all { it.properties.direction == ParagraphDirection.AUTO })
            check(surface.engine.snapshot().committed.paragraphs().last().properties.direction == ParagraphDirection.RTL) // Excluded trailing empty paragraph.
            controls.alignment.selectedItem = "End"
            window.setSize(1020, 700)
            window.validate()
            val row = controls.panel.parent
            check(row.components.filter { it.isVisible }.all { it.x >= 0 && it.x + it.width <= row.width }) { "Paragraph/font controls clipped" }
            val image = java.awt.image.BufferedImage(window.width, window.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { g2 ->
                try {
                    window.paint(g2)
                } finally {
                    g2.dispose()
                }
            }
            javax.imageio.ImageIO.write(image, "png", File(result.parentFile, "paragraph-controls.png"))
        }
        await("Automatic property checkpoint") { controller.state()["completed"] == controller.state()["observed"] && controller.state()["running"] == false }
        lateinit var expected: Document
        owner {
            expected = surface.engine.snapshot().committed
            val payload = surface.engine.captureRecoveryReplacement(expected, surface.engine.snapshot().selection)
            surface.retainedPreimage = RetainedCompositionPreimage("", surface.engine.snapshot().selection, null, expected.text, surface.activeImeEpoch, payload)
            check(surface.exportRetainedDocument(work.resolve("retained.astra").toFile()))
            controller.finish(false)
            window.dispose()
        }
        val store = LocalRecoveryStore(work.resolve("recovery"))
        val deadline = System.nanoTime() + 10_000_000_000L
        var entries = store.scan()
        while (entries.isEmpty()) {
            check(System.nanoTime() < deadline)
            Thread.sleep(10)
            entries = store.scan()
        }
        check(store.restore(entries.single()).document.same(expected) && DocumentFiles.read(work.resolve("retained.astra")).same(expected))
        checks += "96 expected edge/center geometry trials across Latin/Urdu/mixed/numbers, directions/views/sizes: alignment, selection, A hit, AT bounds and fresh native query coordinates"
        checks += "Empty centered RTL caret, mixed controls, actual toolbar consumer and automatic/retained export roundtrip preserve properties"
    } finally {
        if (created) owner { if (window.isDisplayable) window.dispose() }
    }
    result.writeText(
        evidenceJson(
            mapOf(
                "status" to "PASS",
                "checks" to checks,
                "geometry" to observed,
                "scope" to "Reusable commands and actual offscreen layout/IO/API checks. Composition and candidate-position queries are synthetic; native IME popup and screen-reader qualification remain open.",
            ),
        ),
        Charsets.UTF_8,
    )
    println("PASS persistent paragraph properties checks")
}
