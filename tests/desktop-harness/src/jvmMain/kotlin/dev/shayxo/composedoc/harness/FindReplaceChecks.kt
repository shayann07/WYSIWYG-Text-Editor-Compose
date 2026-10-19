package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.FindMatch
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.workspace.FindReplaceUi
import dev.shayxo.composedoc.workspace.LocalRecoveryController
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.urduWritingSample
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import dev.shayxo.composedoc.workspace.windowFiles
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.SwingUtilities
fun runFindReplaceChecks(result: File) {
    result.absoluteFile.parentFile.mkdirs()
    val checks = ArrayList<String>()
    fun doc(text: String, hard: Set<Int> = emptySet()) = Document(
        text.mapIndexed { i, c ->
            TextUnit(
                i + 1L,
                c,
                if (i in hard) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                if (i in hard) TextStyleFlags() else TextStyleFlags(i % 2 == 0, i % 2 == 1),
                if (i in hard) TextStyleFlags(true, true) else TextStyleFlags(),
            )
        },
        TextStyleFlags(italic = true),
    )
    fun positions(text: String, query: String) = findLiteral(doc(text), query).map { it.start }
    check(positions("ک ك ک ی ي ے", "ک") == listOf(0, 4))
    check(positions("ک ك ک ی ي ے", "ي") == listOf(8))
    check(positions("A a Aa", "A") == listOf(0, 4))
    check(positions("aaaaa", "aa") == listOf(0, 2))
    check(positions("ک\u200cتاب کتاب", "کتاب") == listOf(6))
    checks += "Exact case-sensitive Urdu/Arabic distinctions; left-to-right logical non-overlapping matches"
    for ((text, query) in listOf("کِ ک" to "ک", "کِ" to "ِ", "👩‍💻 👩" to "👩", "a\u200db" to "a", "😀" to "\uD83D")) {
        val e = EditorEngine(doc(text))
        val before = e.snapshot()
        val m = findLiteral(before.committed, query)
        check(m.any { !it.editable })
        check(e.replaceFound(e.captureFindBasis(), m, "x", true) == FindReplaceResult.Invalid)
        check(e.snapshot() == before)
    }
    val emoji = EditorEngine(doc("👩‍💻 👩‍💻"))
    val em = findLiteral(emoji.snapshot().committed, "👩‍💻")
    check(em.size == 2 && em.all { it.editable })
    check(emoji.replaceFound(emoji.captureFindBasis(), em, "🙂", true) == FindReplaceResult.Applied)
    check(emoji.snapshot().committed.text == "🙂 🙂")
    checks += "Diacritics/joiners/emoji/surrogate-interior literals stay visible but replacements refuse, with no expansion or partial batch"
    val safeOne = EditorEngine(doc("کِ ک"))
    val safeMatches = findLiteral(safeOne.snapshot().committed, "ک")
    check(!safeMatches.first().editable && safeMatches.last().editable)
    check(safeOne.replaceFound(safeOne.captureFindBasis(), listOf(safeMatches.last()), "x", false) == FindReplaceResult.Applied)
    check(safeOne.snapshot().committed.text == "کِ x")
    val structured = doc("a\nb a\nb", setOf(1))
    val sm = findLiteral(structured, "a\nb")
    check(sm.map { it.start } == listOf(4))
    val se = EditorEngine(structured)
    check(se.replaceFound(se.captureFindBasis(), sm, "x\ny", true) == FindReplaceResult.Applied)
    val sd = se.snapshot().committed
    check(sd.text == "a\nb x\ny" && sd.units[1] == structured.units[1] && sd.units[1].kind == UnitKind.PARAGRAPH_BREAK)
    check(sd.units[5].kind == UnitKind.TEXT)
    checks += "Hard paragraphs bound search; literal LF matches soft breaks only and inserted LF is soft"
    val e = EditorEngine(doc("کتاب English کتاب English"))
    e.move(Selection(5, 12))
    val before = e.snapshot()
    val basis = e.captureFindBasis()
    val matches = findLiteral(before.committed, "English")
    check(e.snapshot() == before)
    check(e.replaceFound(basis, matches, "EnglishEnglish", true) == FindReplaceResult.Applied)
    val after = e.snapshot()
    check(after.committed.text == "کتاب EnglishEnglish کتاب EnglishEnglish" && after.undoCount == before.undoCount + 1)
    check(after.committed.units.take(5) == before.committed.units.take(5))
    check(after.committed.units.subList(5, 19).all { it.style == before.committed.units[5].style })
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == before.committed && e.snapshot().selection == before.selection)
    e.perform(EditAction.Redo)
    check(e.snapshot().committed == after.committed && e.snapshot().selection == after.selection)
    checks += "Replace All captures one basis, inherits first matched formatting, does not rescan inserted search text and has one exact Undo/Redo"
    // Compare empty-paragraph retention to the established ordinary replacement command.
    for (text in listOf("aa", "aa\naa", "x aa\naa\n", "aa\n\naa")) {
        val initial = doc(text, text.indices.filter { text[it] == '\n' }.toSet())
        val all = EditorEngine(initial)
        val normal = EditorEngine(initial)
        val ms = findLiteral(all.snapshot().committed, "a")
        check(all.replaceFound(all.captureFindBasis(), ms, "", true) == FindReplaceResult.Applied)
        for (m in ms.asReversed()) normal.replace("", "ControlDeletion", range = Selection(m.start, m.end))
        check(all.snapshot().committed.same(normal.snapshot().committed))
        check(all.snapshot().committed.units.filter { it.kind == UnitKind.PARAGRAPH_BREAK }.map { it.id } == initial.units.filter { it.kind == UnitKind.PARAGRAPH_BREAK }.map { it.id })
    }
    check(findLiteral(doc("abc"), "").isEmpty())
    checks += "Empty search is inert; deletion preserves hard separators and existing empty-paragraph style inheritance"
    val stale = EditorEngine(doc("abc abc"))
    val old = stale.captureFindBasis()
    val ms = findLiteral(old.document, "abc")
    stale.replace("X")
    val changed = stale.snapshot()
    check(stale.replaceFound(old, ms, "z", true) == FindReplaceResult.Stale && stale.snapshot() == changed)
    stale.perform(EditAction.Undo)
    check(stale.replaceFound(old, ms, "z", true) == FindReplaceResult.Stale)
    val foreign = EditorEngine(old.document)
    check(foreign.replaceFound(old, ms, "z", true) == FindReplaceResult.Stale)
    val atomic = EditorEngine(doc("abc\nabc", setOf(3)))
    val ab = atomic.captureFindBasis()
    val snap = atomic.snapshot()
    check(atomic.replaceFound(ab, listOf(FindMatch(0, 3, true), FindMatch(3, 5, true)), "x", true) == FindReplaceResult.Invalid)
    check(atomic.snapshot() == snap)
    checks += "Intervening edit/Undo and foreign owners refuse stale bases; invalid later structural target leaves the whole batch unchanged"
    val composing = EditorEngine(doc("abc abc"))
    val cb = composing.captureFindBasis()
    val cm = findLiteral(cb.document, "abc")
    composing.inputMethod("候補", 0, 2)
    val candidate = composing.snapshot()
    check(composing.replaceFound(cb, cm, "x", true) == FindReplaceResult.Busy && composing.snapshot() == candidate && !composing.snapshot().waiting)
    composing.inputMethod("ن候補", 1, 2)
    val partial = composing.snapshot()
    check(composing.replaceFound(cb, cm, "x", true) == FindReplaceResult.Busy && composing.snapshot() == partial)
    composing.abortComposition()
    check(composing.replaceFound(cb, cm, "x", true) == FindReplaceResult.Stale)
    checks += "Active and partially committed composition refuse replacement without queueing, settlement fiction or candidate mutation"
    val many = EditorEngine(Document(List(10000) { TextUnit(it + 1L, 'a') }))
    check(many.replaceFound(many.captureFindBasis(), findLiteral(many.snapshot().committed, "a"), "bb", true) == FindReplaceResult.Applied)
    check(many.snapshot().committed.text == "bb".repeat(10000) && many.snapshot().undoCount == 1)
    val limitBefore = many.snapshot()
    check(many.replaceFound(many.captureFindBasis(), findLiteral(many.snapshot().committed, "b"), "too much", true) == FindReplaceResult.TooLarge)
    check(many.snapshot() == limitBefore)
    checks += "Bounded 10000-match bulk replacement publishes once with one history entry"

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
    val directory = Files.createTempDirectory(result.parentFile.toPath(), "find-files-")
    val original = directory.resolve("writing.astra")
    val sample = urduWritingSample()
    DocumentFiles.write(original, sample)
    lateinit var window: JFrame
    lateinit var surface: EditorSurface
    lateinit var ui: FindReplaceUi
    lateinit var controller: LocalRecoveryController
    var created = false
    try {
        owner {
            window = showEditor(initial = sample, initialPath = original, fontProfile = "noto-nastaliq", paged = true, offscreenTest = true, recoveryDirectory = directory.resolve("recovery"))
            created = true
            surface = checkNotNull(windowCurrentSurface(window))
            ui = window.rootPane.getClientProperty("editor.find") as FindReplaceUi
            controller = window.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
            window.rootPane.actionMap.get("editor.replace").actionPerformed(null)
            ui.query.text = "English notes"
            check(ui.session.matches.size == 8 && ui.count.text == "1 / 8")
            repeat(7) { ui.query.actionMap.get("find.next").actionPerformed(null) }
            check(ui.count.text == "8 / 8")
        }
        await("Paged search reveal") { surface.view.findReveal == null && surface.view.geometry?.scroll == surface.view.scroll && surface.view.scroll > 0 }
        owner {
            val g = checkNotNull(surface.view.geometry)
            val match = checkNotNull(ui.session.current)
            val caret = g.caret(match.start)
            check(caret.top >= 0 && caret.bottom <= surface.height * g.scale)
            check(surface.engine.snapshot().committed.same(sample) && surface.engine.snapshot().undoCount == 0 && !checkNotNull(windowFiles(window)).dirty)
            check(controller.state()["observed"] == 1L)
            ui.query.actionMap.get("find.previous").actionPerformed(null)
            check(ui.count.text == "7 / 8")
            surface.view.setPagedMode(false)
            ui.session.reveal()
        }
        await("Continuous search reveal") { surface.view.findReveal == null && surface.view.geometry?.typographyKey?.endsWith(":continuous") == true }
        owner {
            val g = checkNotNull(surface.view.geometry)
            val caret = g.caret(checkNotNull(ui.session.current).start)
            check(caret.top >= 0 && caret.bottom <= surface.height * g.scale)
            fun all(c: java.awt.Component): List<java.awt.Component> = listOf(c) + if (c is java.awt.Container) c.components.flatMap(::all) else emptyList()
            for ((w, h) in listOf(940 to 400, 1020 to 700)) {
                window.setSize(w, h)
                window.validate()
                for (row in ui.panel.components.filterIsInstance<java.awt.Container>().filter { it.isVisible }) {
                    if (row.layout is java.awt.FlowLayout) check(row.components.filter { it.isVisible }.all { it.x >= 0 && it.x + it.width <= row.width && it.y + it.height <= row.height })
                }
                val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { g2 ->
                    try {
                        window.paint(g2)
                    } finally {
                        g2.dispose()
                    }
                }
                javax.imageio.ImageIO.write(image, "png", File(result.parentFile, "find-${w}x$h.png"))
            }
            ui.replacement.text = "English notes changed"
            all(ui.panel).filterIsInstance<JButton>().single { it.text == "Replace All" }.doClick()
            val target = surface.engine.snapshot().committed
            check(target.text == sample.text.replace("English notes", "English notes changed"))
            check(surface.engine.snapshot().undoCount == 1 && checkNotNull(windowFiles(window)).dirty)
            surface.engine.perform(EditAction.Undo)
            check(surface.engine.snapshot().committed.same(sample))
            surface.engine.perform(EditAction.Redo)
            check(surface.engine.snapshot().committed == target)
            surface.engine.replace("کِ", "UnsafeFindFixture")
            ui.query.text = "ِ"
            check(ui.session.matches.isNotEmpty() && ui.session.matches.any { !it.editable })
            check(!all(ui.panel).filterIsInstance<JButton>().single { it.text == "Replace All" }.isEnabled)
            surface.engine.perform(EditAction.Undo)
            check(surface.engine.snapshot().committed == target)
            ui.query.text = "English notes"
            surface.engine.inputMethod("候補", 0, 2)
            check(ui.session.blocked && surface.view.findMatches.isEmpty())
            check(!all(ui.panel).filterIsInstance<JButton>().single { it.text == "Replace" }.isEnabled)
            surface.engine.abortComposition()
            check(!ui.session.blocked)
            ui.query.actionMap.get("find.close").actionPerformed(null)
            check(!ui.panel.isVisible && surface.view.findMatches.isEmpty())
        }
        await("Replacement recovery checkpoint") { controller.state()["completed"] == controller.state()["observed"] && controller.state()["running"] == false }
        lateinit var expected: Document
        owner {
            expected = surface.engine.snapshot().committed
            controller.finish(false)
            window.dispose()
        }
        val store = LocalRecoveryStore(directory.resolve("recovery"))
        val until = System.nanoTime() + 10_000_000_000L
        var entries = store.scan()
        while (entries.isEmpty()) {
            check(System.nanoTime() < until)
            Thread.sleep(10)
            entries = store.scan()
        }
        check(store.restore(entries.single()).document.same(expected) && DocumentFiles.read(original).same(sample))
        checks += "Actual panel/count/Enter/Shift+Enter, cross-page and continuous reveal do not dirty history or recovery; field edits stay out of document Undo"
        checks += "Actual Replace All button has exact Undo/Redo, grapheme/composition guards and transient highlight clearing at minimum/default layouts"
        checks += "Completed automatic recovery checkpoint after replacement/Undo/Redo roundtrips full supported content/format/breaks; original file untouched"
    } finally {
        if (created) owner { if (window.isDisplayable) window.dispose() }
    }
    result.writeText(evidenceJson(mapOf("status" to "PASS", "checks" to checks, "scope" to "Engine and actual offscreen shell/render/IO; field actions and composition are synthetic, real desktop evidence separate")), Charsets.UTF_8)
    println("PASS exact Unicode Find/Replace checks")
}
