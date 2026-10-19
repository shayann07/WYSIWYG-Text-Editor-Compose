package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.data.LocalCopy
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.swing.JFrame
import javax.swing.SwingUtilities
/** Reviewed UTF-16 expectations, Unicode 16.0.0. Not computed by Graphemes or JDK BreakIterator. */
data class ClusterTarget(val name: String, val text: String, val boundaries: List<Int>)
val v6Targets = listOf(
    ClusterTarget("Urdu kasra", "کِ", listOf(0, 2)), ClusterTarget("Arabic lam mark alef", "لَا", listOf(0, 2, 3)),
    ClusterTarget("Persian ZWNJ", "می\u200c", listOf(0, 1, 3)), ClusterTarget("Hebrew niqqud", "שָׁ", listOf(0, 3)),
    ClusterTarget("Latin composed", "é", listOf(0, 1)), ClusterTarget("Latin decomposed", "e\u0301", listOf(0, 2)),
    ClusterTarget("Devanagari conjunct vowel", "क्षि", listOf(0, 4)), ClusterTarget("Bengali conjunct vowel", "ক্ষি", listOf(0, 4)),
    ClusterTarget("Thai tone", "ก้", listOf(0, 2)), ClusterTarget("Han", "漢字", listOf(0, 1, 2)),
    ClusterTarget("kana combining", "か\u3099", listOf(0, 2)), ClusterTarget("Hangul jamo", "한", listOf(0, 3)),
    ClusterTarget("supplementary scalar", "𐐀", listOf(0, 2)), ClusterTarget("emoji modifier ZWJ", "👩🏽‍💻", listOf(0, 7)),
    ClusterTarget("regional flag", "🇵🇰", listOf(0, 4)), ClusterTarget("variation selector", "✈️", listOf(0, 2)),
    ClusterTarget("bidi isolate", "\u2067اردو\u2069", listOf(0, 1, 2, 3, 4, 5, 6)),
)

fun runV6Qualification(result: File) {
    result.parentFile.mkdirs()
    val rows = ArrayList<Map<String, Any>>()
    val groups = ArrayList<String>()
    val directory = Files.createTempDirectory(result.parentFile.toPath(), "v6-qualified-")
    fun group(name: String, body: () -> Unit) {
        body()
        groups += name
        println("PASS $name")
    }
    fun engine(raw: String) = EditorEngine(Document(raw.mapIndexed { i, c -> TextUnit(i + 1L, c, style = TextStyleFlags(italic = i % 2 == 0)) }))
    group("Unicode16 official independent boundary corpus") {
        var count = 0
        for (line in Graphemes.verified("GraphemeBreakTest-16.0.0.txt").lineSequence()) {
            val tokens = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (tokens.firstOrNull() != "÷")continue
            val text = StringBuilder()
            val expected = ArrayList<Int>()
            for (token in tokens) {
                when (token) {
                    "÷" -> expected += text.length
                    "×" -> {}
                    else -> text.appendCodePoint(token.toInt(16))
                }
            }
            check(Graphemes.stops(text.toString()) == expected) { "Official Unicode16 line: $line" }
            count++
        }
        check(count > 1000)
        rows += mapOf("oracle" to "official Unicode16 corpus", "cases" to count, "sha256" to java.security.MessageDigest.getInstance("SHA-256").digest(Graphemes.verified("GraphemeBreakTest-16.0.0.txt").toByteArray()).joinToString("") { "%02x".format(it) })
    }
    for (t in v6Targets) {
        group("TARGET ${t.name}") {
            check(Graphemes.stops(t.text) == t.boundaries) { "Explicit stops ${t.name}: ${Graphemes.stops(t.text)}" }
            val raw = "L" + t.text + "R"
            val stops = listOf(0) + t.boundaries.map { it + 1 } + listOf(raw.length)
            var deletes = 0
            for ((index, at) in stops.withIndex()) {
                for (back in listOf(false, true)) {
                    val e = engine(raw)
                    val before = e.snapshot().committed
                    e.move(Selection(at, at))
                    e.delete(back)
                    val a = if (back)stops.getOrElse(index - 1) { at } else at
                    val b = if (back)at else stops.getOrElse(index + 1) { at }
                    check(e.snapshot().committed.text == raw.removeRange(a, b)) { "${t.name} collapsed delete at $at back=$back" }
                    check(e.snapshot().committed.units.map { it.id } == before.units.filterIndexed { i, _ -> i !in a until b }.map { it.id })
                    if (a != b) {
                        val after = e.snapshot().committed
                        e.perform(EditAction.Undo)
                        check(e.snapshot().committed == before)
                        e.perform(EditAction.Redo)
                        check(e.snapshot().committed == after)
                    }
                    deletes++
                }
            }
            for ((a0, b0) in t.boundaries.zipWithNext()) {
                for (backward in listOf(false, true)) {
                    val a = a0 + 1
                    val b = b0 + 1
                    val e = engine(raw)
                    val before = e.snapshot().committed
                    e.move(if (backward)Selection(b, a) else Selection(a, b))
                    e.setTextStyle(TextStylePatch(underline = true))
                    val decorated = e.snapshot().committed
                    check(decorated.units.indices.all { i -> decorated.units[i].style.underline == (i in a until b) })
                    e.structure(StructureCommand.Link("https://example.org/cluster"))
                    check(e.snapshot().committed.units.indices.all { i -> (e.snapshot().committed.units[i].style.link != null) == (i in a until b) })
                    e.perform(EditAction.Undo)
                    check(e.snapshot().committed == decorated)
                    e.perform(EditAction.Undo)
                    check(e.snapshot().committed == before)
                    e.replace("Q", "Selected target")
                    check(e.snapshot().committed.text == raw.substring(0, a) + "Q" + raw.substring(b))
                    e.perform(EditAction.Undo)
                    check(e.snapshot().committed == before)
                    e.move(Selection(a, b))
                    val fragment = FragmentCodec.decode(FragmentCodec.encode(RichFragment.capture(checkNotNull(e.clipboardCapture()))))
                    check(fragment.document.text == raw.substring(a, b))
                    val pasted = EditorEngine()
                    check(pasted.clipboardApply(checkNotNull(pasted.clipboardCapture()), fragment))
                    check(pasted.snapshot().committed.text == raw.substring(a, b))
                }
            }
            val e = engine(raw)
            e.selectAll()
            e.setTextStyle(TextStylePatch(font = StoredFont.GULZAR, size = 32))
            val exact = e.snapshot().committed
            val file = directory.resolve(t.name + ".astra")
            DocumentFiles.write(file, exact)
            check(DocumentFiles.read(file).same(exact))
            val copy = LocalCopy(UUID.randomUUID().toString(), 1, 1, null, exact)
            check(LocalRecoveryStore.decode(LocalRecoveryStore.encode(copy)).document.same(exact))
            e.move(Selection(1, 1))
            e.inputMethod(t.text, 0, t.text.length)
            check(e.snapshot().committed == exact)
            e.abortComposition()
            check(e.snapshot().committed == exact)
            rows += mapOf("target" to t.name, "utf16" to t.text.map { it.code }, "expectedBoundaries" to t.boundaries, "collapsedDeletionCases" to deletes, "operations" to "PASS selected forward/backward replacement, format/link, clipboard, history, file, committed recovery, synthetic composition")
        }
    }
    group("joiner and mark edits preserve original constituent attribution; native raw deletion stays raw") {
        for ((raw, at, inserted, expected) in listOf(listOf("ab", 1, "\u0301", listOf(0, 2, 3)), listOf("👩💻", 2, "\u200d", listOf(0, 5)))) {
            val e = engine(raw as String)
            val original = e.snapshot().committed
            e.move(Selection(at as Int, at))
            e.replace(inserted as String)
            check(Graphemes.stops(e.snapshot().committed.text) == expected)
            check(e.snapshot().committed.units.filter { it.id in original.units.map { u -> u.id } } == original.units)
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == original)
            e.perform(EditAction.Redo)
            val range = Selection(at, at + inserted.length)
            e.perform(EditAction.Replace(e.capture(range), "", "Native UTF16", false, TextStyleFlags(), null, true))
            check(e.snapshot().committed.text == raw)
            check(e.snapshot().committed.units == original.units)
        }
        val e = engine("😀")
        e.perform(EditAction.Replace(e.capture(Selection(0, 1)), "", "Raw native", false, TextStyleFlags(), null, true))
        check(e.snapshot().committed.text == "\ude00")
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.text == "😀")
    }
    group("actual production zero acceptance multi-island retarget, positive prefix and atomic invalid selection") {
        for (k in listOf(0, 1)) {
            val e = engine("0123456789")
            val original = e.snapshot().committed
            e.move(Selection(1, 3))
            e.inputMethod("AB", 0, 2)
            e.retargetComposition(Selection(4, 5))
            e.inputMethod("XY", 0, 2)
            val before = e.snapshot()
            check(before.visible.text == "0AB3XY56789" && before.marked == 4 until 6)
            e.partialCommit(k)
            if (k == 0) {
                val z = e.snapshot()
                check(z.committed == original && z.visible == before.visible && z.marked == before.marked && z.undoCount == 0 && z.sessionRevision > before.sessionRevision)
                val failed = runCatching { e.partialCommit(0, Selection(-1, 0)) }
                check(failed.isFailure)
                check(e.snapshot() == z)
                e.abortComposition()
                check(e.snapshot().committed == original)
            } else {
                check(e.snapshot().committed.text == "0AB3X56789" && e.snapshot().marked == 5 until 6)
                e.abortComposition()
                check(e.snapshot().committed.text == "0AB3X56789")
                e.perform(EditAction.Undo)
                check(e.snapshot().committed == original)
            }
        }
        // Review marked ranges are relative to overlay origin 1: global [4,6) = local [3,5).
        // Candidate reconciliation is a real zero-acceptance update, never dropped.
        val e = engine("0123456789")
        e.move(Selection(1, 3))
        e.inputMethod("AB", 0, 2)
        e.retargetComposition(Selection(4, 5))
        e.inputMethod("XY", 0, 2)
        val before = e.snapshot()
        e.inputMethod("新", 0, 1)
        check(e.snapshot().visible.text == "0AB3新56789" && e.snapshot().committed == before.committed)
        e.abortComposition()
        check(e.snapshot().committed.text == "0123456789")
    }
    group("explicit abort cancels deferred typography after positive acceptance; FIFO attempted credit remains") {
        val e = EditorEngine()
        e.replace("Hello ")
        e.inputMethod("ab", 0, 2)
        e.inputMethod("ab", 1, 0)
        e.perform(EditAction.Undo)
        e.toggleFormat(FormatKey.BOLD)
        e.perform(EditAction.Undo)
        e.abortComposition()
        check(e.snapshot().committed.text == "Hello ")
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.text.isEmpty())
        val t = EditorEngine()
        t.inputMethod("ab", 0, 2)
        t.inputMethod("ab", 1, 0)
        t.setTextStyle(TextStylePatch(size = 48))
        t.abortComposition()
        check(t.snapshot().committed.text == "a" && t.snapshot().typingStyle.size != 48)
    }
    group("stored marker style, split/continuation/indent, full clipboard/file/recovery/history and legacy migration") {
        val e = EditorEngine()
        e.replace("parent\nshort\na longer child which must share the sibling lane", "fixture", true)
        e.selectAll()
        e.structure(StructureCommand.ListType(ListKind.NUMBER))
        e.move(Selection(7, 7))
        e.structure(StructureCommand.Indent)
        e.move(Selection(13, 13))
        e.structure(StructureCommand.Indent)
        e.selectAll()
        e.structure(StructureCommand.MarkerStyle(MarkerTypography(StoredFont.GULZAR, 32)))
        val styled = e.snapshot().committed
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.paragraphs().all { it.properties.markerTypography == MarkerTypography() })
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == styled)
        e.setTextStyle(TextStylePatch(font = StoredFont.SYSTEM, size = 8))
        check(e.snapshot().committed.paragraphs().all { it.properties.markerTypography == MarkerTypography(StoredFont.GULZAR, 32) })
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == styled)
        e.move(Selection(styled.units.size, styled.units.size))
        e.structure(StructureCommand.Enter(true))
        e.replace("continuation".repeat(8))
        check(e.snapshot().committed.paragraphs().last().properties.markerTypography == MarkerTypography(StoredFont.GULZAR, 32))
        e.structure(StructureCommand.Enter(false))
        check(e.snapshot().committed.paragraphs().last().properties.markerTypography == MarkerTypography(StoredFont.GULZAR, 32))
        val exact = e.snapshot().committed
        e.selectAll()
        val fragment = FragmentCodec.decode(FragmentCodec.encode(RichFragment.capture(checkNotNull(e.clipboardCapture()))))
        val paste = EditorEngine()
        check(paste.clipboardApply(checkNotNull(paste.clipboardCapture()), fragment))
        check(structureDescription(paste.snapshot().committed) == structureDescription(exact))
        check(paste.snapshot().committed.paragraphs().map { it.properties.markerTypography } == exact.paragraphs().map { it.properties.markerTypography })
        check(paste.snapshot().committed.paragraphs().first().properties.item != exact.startParagraph.item)
        check(runCatching { ParagraphProperties(markerTypography = MarkerTypography(StoredFont.SYSTEM, 8)) }.isFailure) { "Orphan marker metadata must reject rather than disappear on Save" }
        val encoded = DocumentFiles.encode(exact)
        val corrupted = encoded.copyOf(encoded.size - 32)
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(corrupted))
        input.skipBytes(26 + exact.units.size * 17)
        check(input.readInt() > 0)
        input.skipBytes(8)
        repeat(2) {
            val n = input.readInt()
            input.skipBytes(n * 2)
        }
        val markerOffset = corrupted.size - input.available()
        corrupted[markerOffset] = 0
        check(runCatching { DocumentFiles.decode(corrupted + java.security.MessageDigest.getInstance("SHA-256").digest(corrupted)) }.isFailure) { "DEFAULT/untrusted marker font was accepted" }
        check(java.nio.ByteBuffer.wrap(encoded).getInt(4) == 9)
        check(DocumentFiles.decode(encoded).same(exact))
        val c = LocalCopy(UUID.randomUUID().toString(), 1, 1, null, exact)
        check(LocalRecoveryStore.decode(LocalRecoveryStore.encode(c)).document.same(exact))
        val old = File("apps/desktop-workspace/samples/disposable-urdu-sample-v6.astra").readBytes()
        val migrated = DocumentFiles.decode(old)
        check(old.contentEquals(File("apps/desktop-workspace/samples/disposable-urdu-sample-v6.astra").readBytes()))
        check(migrated.paragraphs().filter { it.properties.item.isNotEmpty() }.all { it.properties.markerTypography.size in 8..96 })
        check(DocumentFiles.decode(DocumentFiles.encode(migrated)).same(migrated))
        val recovered = e.captureRecoveryReplacement(exact, Selection(0, 0))
        check(recovered.document == exact)
    }
    runV6Geometry(directory.toFile(), rows)
    result.writeText(evidenceJson(mapOf("status" to "PASS", "groups" to groups, "rows" to rows, "runtime" to System.getProperty("java.runtime.version"), "oracle" to "Unicode16 official fixtures plus independently specified UTF16 arrays; JDK BreakIterator is not a grapheme oracle", "native" to "NOT RUN by this automated lane")), Charsets.UTF_8)
    println("PASS V6 bounded production qualification")
}

private fun runV6Geometry(root: File, rows: MutableList<Map<String, Any>>) {
    fun <T> owner(body: () -> T): T {
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(body) }
        return checkNotNull(r).getOrThrow()
    }
    val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        errors.add(e)
        e.printStackTrace()
    }
    var window: JFrame? = null
    try {
        for (paged in listOf(false, true)) {
            for (zoom in listOf(.5f, .75f, 1f)) {
                val e = EditorEngine()
                e.replace("abc אבג xyz\nאבג abc דהו\n\nabcdefghij\nab\nabcdefghij\n" + "کتاب مُحَمَّد ".repeat(30), "fixture", true)
                val w = owner {
                    showEditor(initial = e.snapshot().committed, fontProfile = "noto-nastaliq", paged = paged, offscreenTest = true).also {
                        window = it
                        it.setSize(1020, 740)
                        checkNotNull(windowCurrentSurface(it)).view.changeZoom(zoom)
                        it.validate()
                    }
                }
                val surface = owner { checkNotNull(windowCurrentSurface(w)) }
                val deadline = System.nanoTime() + 20_000_000_000L
                while (!owner { surface.view.measure != null && surface.view.geometry?.typographyKey?.contains(":zoom=$zoom") == true }) {
                    check(System.nanoTime() < deadline)
                    Thread.sleep(20)
                }
                owner {
                    val before = surface.engine.snapshot()
                    val g = surface.view.measure!!.invoke(before)
                    val p = g.paragraphs[0]
                    fun x(at: Int) = g.getHorizontalPosition(at)
                    check(x(0) < x(1) && x(1) < x(2) && x(2) < x(3))
                    check(x(5) > x(6))
                    check(x(8) < x(9) && x(9) < x(10)) { "Independent visual run order" }
                    val rtl = g.paragraphs[1]
                    check(g.getHorizontalPosition(rtl.startOffset) > g.getHorizontalPosition(rtl.startOffset + 1))
                    // Cover both explicit endpoint directions, with independently known LTR and RTL ink intervals.
                    for ((a, b) in listOf(0 to 3, 4 to 7, 8 to 11)) {
                        val rect = p.layout.getPathForRange(a, b).getBounds()
                        val expected = p.layout.getBoundingBox(a)
                        check(rect.left <= expected.left + .5f && rect.right >= expected.right - .5f && rect.height > 0)
                        surface.engine.move(Selection(a, b))
                        val forward = surface.engine.snapshot()
                        surface.engine.move(Selection(b, a))
                        check(surface.engine.snapshot().selection.start == forward.selection.start && surface.engine.snapshot().selection.end == forward.selection.end)
                    }
                    // Actual registered pointer handler; uniquely positioned interior Latin edge, bounded pixel rounding.
                    val caret = g.caret(1)
                    val point = java.awt.Point(((caret.left) / g.scale).toInt(), ((caret.top + caret.height * .5f) / g.scale).toInt())
                    val click = MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, point.x, point.y, 1, false, MouseEvent.BUTTON1)
                    surface.mouseListeners.forEach { it.mousePressed(click) }
                    check(surface.engine.snapshot().selection.focus == 1) { "Actual click did not map to known Latin boundary" }
                    val key = KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED)
                    surface.keyListeners.forEach { it.keyPressed(key) }
                    check(surface.engine.snapshot().selection == Selection(1, 2))
                    surface.engine.move(Selection(5, 5))
                    val rtlKey = KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED)
                    surface.keyListeners.forEach { it.keyPressed(rtlKey) }
                    check(surface.engine.snapshot().selection == Selection(5, 6)) { "RTL ShiftLeft must advance logical Hebrew offset" }
                    val longStart = before.committed.text.indexOf("abcdefghij")
                    surface.engine.move(Selection(longStart + 8, longStart + 8))
                    repeat(2) {
                        val down = KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED)
                        surface.keyListeners.forEach { it.keyPressed(down) }
                    }
                    check(surface.engine.snapshot().selection.focus == before.committed.text.lastIndexOf("abcdefghij") + 8) { "Vertical preferred x was lost on short intermediate row" }
                    val up = KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_END, KeyEvent.CHAR_UNDEFINED)
                    surface.keyListeners.forEach { it.keyPressed(up) }
                    check(surface.engine.snapshot().selection.focus == before.committed.units.size && surface.view.ensureCaret)
                    check(surface.engine.snapshot().committed == before.committed && surface.engine.snapshot().undoCount == 0)
                    check(g.paragraphs[2].text.isEmpty() && g.caret(g.paragraphs[2].startOffset).height > 0)
                    check(g.paragraphs.last().lineCount > 1)
                    rows += mapOf("geometry" to "PASS independent LTR/RTL run order, selected ink coverage, real handler click, ShiftRight, CtrlEnd reveal, empty/wrap, canonical nonmutation", "paged" to paged, "zoom" to zoom)
                    val image = java.awt.image.BufferedImage(w.width, w.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    val graphics = image.createGraphics()
                    w.paint(graphics)
                    graphics.dispose()
                    javax.imageio.ImageIO.write(image, "png", File(root, "geometry-$paged-$zoom.png"))
                }
                owner {
                    w.dispose()
                    window = null
                }
            }
        }
        check(errors.isEmpty()) { "Uncaught UI failures $errors" }
    } finally {
        owner { window?.dispose() }
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}
