package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.contiguousLinkAt
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.workspace.FindReplaceUi
import dev.shayxo.composedoc.workspace.StructureControls
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.structuredWritingSample
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Window
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs

fun runVisualRepairChecks(result: File) {
    val asyncFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, failure ->
        asyncFailures.add(failure)
        failure.printStackTrace()
    }
    val checks = ArrayList<String>()
    val languages = ArrayList<Map<String, Any>>()
    val matrix = ArrayList<Map<String, Any>>()
    val fonts = ArrayList<Map<String, Any>>()
    result.parentFile.mkdirs()
    val root = Files.createTempDirectory(result.parentFile.toPath(), "visual-repair-")
    fun test(id: String, body: () -> Unit) {
        body()
        checks += id
        println("PASS $id")
    }
    fun <T> owner(body: () -> T): T {
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(body) }
        return checkNotNull(r).getOrThrow()
    }
    fun await(label: String, body: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!owner(body)) {
            check(System.nanoTime() < end) { label }
            Thread.sleep(20)
        }
    }
    fun all(c: Container): List<Component> = c.components.flatMap { listOf(it) + (if (it is Container)all(it) else emptyList()) }
    fun currentDialog() = Window.getWindows().filterIsInstance<JDialog>().single { it.isVisible }
    fun named(name: String) = all(currentDialog()).single { it.name == name }
    fun action(text: String) = (all(currentDialog()).single { it is JButton && it.text == text } as JButton).doClick()
    fun modal(open: () -> Unit, steps: List<() -> Unit>) {
        var index = 0
        var failure: Throwable? = null
        val timer = Timer(100, null)
        timer.addActionListener {
            try {
                if (index < steps.size) {
                    steps[index++]()
                } else {
                    timer.stop()
                    check(Window.getWindows().none { it is JDialog && it.isVisible }) { "Dialog unexpectedly remained open" }
                }
            } catch (t: Throwable) {
                failure = t
                timer.stop()
                Window.getWindows().filterIsInstance<JDialog>().forEach { it.dispose() }
            }
        }
        timer.start()
        open()
        timer.stop()
        failure?.let { throw it }
        check(index == steps.size)
    }
    test("VR-LINK: interior identity target, seams, selected subrange, exact history and unrelated attributes") {
        val e = EditorEngine()
        e.replace("left link right", "fixture")
        e.move(Selection(5, 9))
        e.structure(StructureCommand.Link("https://example.org/a"))
        val original = e.snapshot().committed
        e.move(Selection(7, 7))
        e.structure(StructureCommand.Link(null))
        val removed = e.snapshot()
        check(removed.committed.text == original.text && removed.committed.units.all { it.style.link == null } && removed.selection == Selection(7, 7))
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == original)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == removed.committed)
        e.perform(EditAction.Undo)
        e.move(Selection(7, 7))
        e.structure(StructureCommand.Link("https://example.org/b"))
        check(e.snapshot().committed.units.subList(5, 9).all { it.style.link?.uri == "https://example.org/b" })
        e.move(Selection(6, 8))
        e.structure(StructureCommand.Link(null))
        check(e.snapshot().committed.units[5].style.link != null && e.snapshot().committed.units[8].style.link != null && e.snapshot().committed.units.subList(6, 8).all { it.style.link == null })
        check(original.contiguousLinkAt(Selection(5, 5)) == null && original.contiguousLinkAt(Selection(9, 9)) == null)
    }
    for (f in multilingualFixtures) {
        test("VR-${f.id}: exact raw editing/selection/format/clipboard/history/file/structures/synthetic composition") {
            val e = EditorEngine()
            e.replace(f.text, "fixture")
            val original = e.snapshot().committed
            check(original.text == f.text)
            val stops = Graphemes.stops(f.text)
            val a = stops[1]
            val b = stops[2]
            e.move(Selection(b, a))
            e.setTextStyle(TextStylePatch(underline = true, size = 32))
            val formatted = e.snapshot().committed
            check(formatted.text == f.text && formatted.units.indices.all { i -> formatted.units[i].style.underline == (i in a until b) })
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == original)
            e.perform(EditAction.Redo)
            check(e.snapshot().committed == formatted)
            e.move(Selection(a, b))
            e.replace("X", "replace")
            check(e.snapshot().committed.text == f.text.substring(0, a) + "X" + f.text.substring(b))
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == formatted)
            e.move(Selection(a, b))
            e.delete(false)
            check(e.snapshot().committed.text == f.text.removeRange(a, b))
            e.perform(EditAction.Undo)
            e.selectAll()
            val fragment = RichFragment.capture(checkNotNull(e.clipboardCapture()))
            val pasted = EditorEngine()
            check(pasted.clipboardApply(checkNotNull(pasted.clipboardCapture()), FragmentCodec.decode(FragmentCodec.encode(fragment))))
            check(pasted.snapshot().committed.same(formatted))
            val file = root.resolve(f.id + ".astra")
            DocumentFiles.write(file, formatted)
            check(DocumentFiles.read(file).same(formatted))
            e.move(Selection(a, b))
            e.structure(StructureCommand.Link("https://example.org/" + f.id))
            val linked = e.snapshot().committed
            check(linked.units.indices.all { i -> (linked.units[i].style.link != null) == (i in a until b) })
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == formatted)
            e.move(Selection(0, 0))
            e.structure(StructureCommand.Role(2))
            check(e.outline().single().label == f.text.trim())
            e.perform(EditAction.Undo)
            e.structure(StructureCommand.ListType(ListKind.NUMBER))
            val listed = e.snapshot().committed
            e.move(Selection(a, a))
            e.structure(StructureCommand.Enter(true))
            check(e.snapshot().committed.paragraphs().map { it.properties.item }.distinct().size == 1)
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == listed)
            e.move(Selection(0, 0))
            e.replace("\n", "soft", false)
            check(e.snapshot().committed.units[0].kind == UnitKind.TEXT)
            e.perform(EditAction.Undo)
            e.move(Selection(e.snapshot().committed.units.size, e.snapshot().committed.units.size))
            e.structure(StructureCommand.Enter(false))
            e.replace("child", "type")
            val sibling = e.snapshot().committed
            e.structure(StructureCommand.Indent)
            check(e.snapshot().committed.paragraphs()[1].properties.parent == listed.startParagraph.item)
            e.structure(StructureCommand.Outdent)
            check(e.snapshot().committed.same(sibling)) { "Indent/Outdent altered sibling payload for ${f.id}" }
            val beforeIme = e.snapshot().committed
            e.move(Selection(0, a))
            e.inputMethod(f.text, 0, f.text.length)
            check(e.snapshot().committed == beforeIme)
            e.inputMethod(f.text, f.text.length, null)
            val afterIme = e.snapshot().committed
            check(afterIme.text == f.text + beforeIme.text.substring(a))
            e.perform(EditAction.Undo)
            check(e.snapshot().committed == beforeIme)
            languages += mapOf("id" to f.id, "text" to f.text, "codePoints" to f.text.codePoints().toArray().map { "U+%04X".format(it) }, "utf16" to f.text.map { it.code }, "operations" to "PASS raw editing, backward format, replace/delete, rich clipboard, Undo/Redo, v6, heading/list/hard-soft/synthetic callback", "nativeIme" to "NOT RUN in this automated lane", "notes" to f.notes)
        }
    }
    val sample = structuredWritingSample()
    var w: JFrame? = null
    try {
        lateinit var surface: EditorSurface
        lateinit var ui: StructureControls
        owner {
            w = showEditor(initial = sample, fontProfile = "noto-nastaliq", paged = true, offscreenTest = true, recoveryDirectory = root.resolve("recovery"))
            surface = checkNotNull(windowCurrentSurface(w!!))
            ui = w!!.rootPane.getClientProperty("editor.structure") as StructureControls
        }
        await("Mounted measure") { surface.view.measure != null }
        test("VR-HANDLERS: actual modal validation correction/cancel/stale and link Remove event") {
            owner {
                val e = surface.viewModel.verificationEngine
                val at = e.snapshot().committed.units.indexOfFirst { it.style.link != null }
                e.move(Selection(at + 2, at + 2))
                val before = e.snapshot()
                modal(
                    { ui.link.doClick() },
                    listOf({
                        check((named("link-label") as JTextField).text == "Disposable writing sample")
                        action("Remove")
                    }),
                )
                check(e.snapshot().undoCount == before.undoCount + 1 && e.snapshot().committed.text == before.committed.text && e.snapshot().committed.units[at].style.link == null)
                e.perform(EditAction.Undo)
                check(e.snapshot().committed == before.committed)
                val im = e.snapshot().committed.units.indexOfFirst { it.image != null }
                e.move(Selection(im, im + 1))
                val beforeImage = e.snapshot()
                modal(
                    { ui.imageProperties.doClick() },
                    listOf(
                        {
                            (named("image-width") as JTextField).text = "2409"
                            action("OK")
                            check(currentDialog().isVisible)
                            check((named("validation-error") as JLabel).text.contains("10 to 600"))
                        },
                        {
                            check((named("image-width") as JTextField).text == "2409")
                            (named("image-width") as JTextField).text = "oops"
                            action("OK")
                            check((named("validation-error") as JLabel).text.contains("whole number"))
                        },
                        {
                            (named("image-width") as JTextField).text = "320"
                            (named("image-alt") as JTextField).text = "x".repeat(2049)
                            action("OK")
                            check((named("validation-error") as JLabel).text.contains("2048"))
                        },
                        {
                            (named("image-alt") as JTextField).text = "Corrected diagram"
                            action("OK")
                        },
                    ),
                )
                check(e.snapshot().committed.units[im].image == sample.units[im].image!!.copy(width = 320, alt = "Corrected diagram"))
                check(e.snapshot().undoCount == beforeImage.undoCount + 1)
                val corrected = e.snapshot()
                modal(
                    { ui.imageProperties.doClick() },
                    listOf({
                        (named("image-width") as JTextField).text = "600"
                        action("Cancel")
                    }),
                )
                check(e.snapshot().committed == corrected.committed)
                modal(
                    { ui.imageProperties.doClick() },
                    listOf({
                        e.move(Selection(0, 0))
                        action("OK")
                        check((named("validation-error") as JLabel).text.contains("Target changed"))
                        action("Cancel")
                    }),
                )
                check(e.snapshot().committed == corrected.committed)
                e.perform(EditAction.Undo)
                check(e.snapshot().committed == beforeImage.committed)
            }
        }
        test("VR-OUTLINE: cell hits only, current section follows focus/Find; keyboard activation stays separate") {
            owner {
                val e = surface.viewModel.verificationEngine
                ui.outlinePane.isVisible = true
                w!!.setSize(1020, 865)
                w!!.validate()
                ui.outline.selectedIndex = ui.outlineModel.size() - 1
                e.move(Selection(0, 0))
                check(ui.hasCurrentSection && ui.currentSection == e.outline().first().anchor)
                val last = ui.outline.getCellBounds(ui.outlineModel.size() - 1, ui.outlineModel.size() - 1)
                val before = e.snapshot()
                val event = MouseEvent(ui.outline, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 20, last.y + last.height + 25, 1, false)
                ui.outline.mouseListeners.forEach { it.mouseClicked(event) }
                check(e.snapshot().selection == before.selection && e.snapshot().committed == before.committed)
                ui.outline.actionMap.get("navigate").actionPerformed(java.awt.event.ActionEvent(ui.outline, 0, "navigate"))
                check(e.snapshot().selection.focus == e.snapshot().committed.paragraphs().last { it.properties.heading > 0 }.start)
                val find = w!!.rootPane.getClientProperty("editor.find") as FindReplaceUi
                find.session.search("Disposable")
                find.session.navigate(false)
                check(ui.currentSection == e.outline().first().anchor)
                for (i in 0 until ui.outlineModel.size()) {
                    val entry = ui.outlineModel[i]
                    val renderer = ui.outline.cellRenderer.getListCellRendererComponent(ui.outline, entry, i, false, false)
                    check(renderer.preferredSize.height >= 44 && renderer.accessibleContext.accessibleName.contains(entry.label))
                }
            }
        }
        val dimensions = listOf(Dimension(940, 400), Dimension(1020, 740), Dimension(1536, 864))
        // Representative script rows cover every script x view x zoom.
        // Targeted number/direction/size/alignment matrix is added below.
        for ((languageIndex, f) in multilingualFixtures.withIndex()) {
            for (paged in listOf(false, true)) {
                for (zoom in listOf(.5f, .75f, 1f)) {
                    val e = EditorEngine()
                    e.replace(f.text + "\n" + mixedFixture + "\n" + f.text, "fixture", true)
                    e.selectAll()
                    e.structure(StructureCommand.ListType(if (languageIndex % 2 == 0)ListKind.NUMBER else ListKind.BULLET))
                    e.move(Selection(f.text.length + 1, f.text.length + 1))
                    e.structure(StructureCommand.Indent)
                    // Retain actual engine-owned paragraph IDs. Install fixture only through Open's document API.
                    owner {
                        w!!.dispose()
                        w = showEditor(initial = e.snapshot().committed, fontProfile = "noto-nastaliq", paged = paged, offscreenTest = true)
                        surface = checkNotNull(windowCurrentSurface(w!!))
                        surface.view.changeZoom(zoom)
                        w!!.size = dimensions[languageIndex % 3]
                        w!!.validate()
                    }
                    await("Matrix renderer") { surface.view.measure != null && surface.view.geometry?.typographyKey?.contains(":zoom=$zoom") == true }
                    owner {
                        val engine = surface.viewModel.verificationEngine
                        val before = engine.snapshot().committed
                        val g = checkNotNull(surface.geometry())
                        for (p in g.paragraphs) {
                            val m = p.marker ?: error("Missing first-item marker")
                            check(m.left >= -1 && m.left + m.width <= g.textWidth + 1)
                            val left = p.contentLeft + p.layout.getLineLeft(0)
                            val right = p.contentLeft + p.layout.getLineRight(0)
                            if (p.baseDirection == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl)check(m.left >= right) else check(m.left + m.width <= left)
                            if (m.layout != null) {
                                check(abs(m.top + checkNotNull(m.layout).getLineBaseline(0) - p.layout.getLineBaseline(0)) < .1)
                            } else {
                                check(m.bulletRadius > 0 && m.opticalCenter in p.layout.getLineTop(0)..p.layout.getLineBottom(0))
                            }
                            for (at in Graphemes.stops(p.text)) {
                                val caret = g.caret(p.startOffset + at)
                                check(caret.left.isFinite() && caret.top.isFinite() && caret.bottom > caret.top)
                            }
                        }
                        check(engine.snapshot().committed == before)
                        if (!paged && zoom == 1f)fonts += observedFontEvidence(f, g.paragraphs.first().layout)
                        val out = File(result.parentFile, "layout-${f.id}-$paged-$zoom.png")
                        val bitmap = java.awt.image.BufferedImage(w!!.width, w!!.height, 1)
                        val graphics = bitmap.createGraphics()
                        w!!.paint(graphics)
                        graphics.dispose()
                        javax.imageio.ImageIO.write(bitmap, "png", out)
                        matrix += mapOf("language" to f.id, "paged" to paged, "zoom" to zoom, "window" to listOf(w!!.width, w!!.height), "status" to "PASS", "render" to out.name, "fontProfile" to surface.view.typography.key)
                    }
                }
            }
        }
        test("VR-MARKER-MATRIX: both sides, three supported sizes, six number widths, all alignments, wrapped/continuation/empty and nested items") {
            for (rtl in listOf(false, true)) {
                for (size in listOf(8, 26, 96)) {
                    for (paged in listOf(false, true)) {
                        for (zoom in listOf(.5f, .75f, 1f)) {
                            val specimen = markerMatrixDocument(rtl, size)
                            owner {
                                w!!.dispose()
                                w = showEditor(initial = specimen, fontProfile = "noto-nastaliq", paged = paged, offscreenTest = true)
                                surface = checkNotNull(windowCurrentSurface(w!!))
                                surface.view.changeZoom(zoom)
                                w!!.size = dimensions[(if (rtl)1 else 0) + if (size == 96)1 else 0]
                                w!!.validate()
                            }
                            await("Targeted marker matrix $rtl/$size/$paged/$zoom") {
                                check(surface.view.layoutError == null) { "Targeted marker matrix $rtl/$size/$paged/$zoom: ${surface.view.layoutError}" }
                                surface.view.measure != null && surface.view.geometry?.typographyKey?.contains(":zoom=$zoom") == true
                            }
                            owner {
                                val before = surface.viewModel.verificationEngine.snapshot()
                                val g = checkNotNull(surface.geometry())
                                val paragraphs = before.committed.paragraphs()
                                check(g.paragraphs.count { it.marker != null } == 24)

                                for (p in g.paragraphs) {
                                    check(p.contentLeft >= -1 && p.contentLeft + p.contentWidth <= g.textWidth + 1)
                                    val m = p.marker
                                    if (m != null) {
                                        check(m.nominalSize == size) { "First-character outlier changed nominal size" }
                                        check(m.left >= -1 && m.left + m.width <= g.textWidth + 1) { "Marker outside content area: $rtl/$size/$paged/$zoom ${m.text}" }
                                        if (m.layout != null) {
                                            check(abs(m.top + checkNotNull(m.layout).getLineBaseline(0) - p.layout.getLineBaseline(0)) < .1)
                                            check(m.top >= p.layout.getLineTop(0) - 1)
                                            check(m.top + m.height <= p.layout.getLineBottom(0) + 1) { "Numeral label clipped by first-line container" }
                                        }
                                        if (p.baseDirection == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl)check(m.left >= p.contentLeft + p.contentWidth) else check(m.left + m.width <= p.contentLeft)
                                    }
                                    val props = paragraphs[p.index].properties
                                    if (props.item.startsWith("expected-") && props.item.contains("START") && p.marker == null) {
                                        val head = paragraphs.first { it.properties.item == props.item }
                                        val first = g.paragraphs[head.index]
                                        check(first.contentLeft == p.contentLeft && first.contentWidth == p.contentWidth)
                                    }
                                }
                                val firstRoot = g.paragraphs[24]
                                val child = g.paragraphs[25]
                                check(firstRoot.listColumn!!.rootRtl == rtl && child.listColumn!!.rootRtl == rtl)
                                if (rtl)check(child.contentWidth < firstRoot.contentWidth) else check(child.contentLeft > firstRoot.contentLeft)
                                check(child.baseDirection == if (rtl)androidx.compose.ui.text.style.ResolvedTextDirection.Ltr else androidx.compose.ui.text.style.ResolvedTextDirection.Rtl)
                                val childMarker = checkNotNull(child.marker)
                                if (rtl) {
                                    check(childMarker.left + childMarker.width < child.contentLeft)
                                } else {
                                    check(childMarker.left > child.contentLeft + child.contentWidth && child.contentWidth < firstRoot.contentWidth)
                                }
                                val mixedSibling = g.paragraphs[27]
                                check(mixedSibling.contentLeft == child.contentLeft && mixedSibling.contentWidth == child.contentWidth) { "Mixed-direction/type siblings have different content lanes" }
                                val sibling = g.paragraphs[26]
                                check(sibling.contentLeft == child.contentLeft && sibling.contentWidth == child.contentWidth) { "Same-direction siblings have different shared lanes" }
                                check(abs(sibling.marker!!.left - childMarker.left) < .1f) { "Sibling marker edges differ" }
                                check(childMarker.rtl != rtl) { "Child marker direction was coupled to parent nesting side" }
                                check(surface.viewModel.verificationEngine.snapshot() == before)
                                matrix += mapOf("language" to "targeted-mixed", "rootRtl" to rtl, "size" to size, "numbers" to listOf(1, 9, 10, 99, 100, 9999), "alignments" to ParagraphAlignment.entries.map { it.name }, "paged" to paged, "zoom" to zoom, "status" to "PASS")
                                if (zoom == 1f && size == 26 && !paged) {
                                    val bitmap = java.awt.image.BufferedImage(w!!.width, w!!.height, 1)
                                    val graphics = bitmap.createGraphics()
                                    w!!.paint(graphics)
                                    graphics.dispose()
                                    javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "marker-matrix-$rtl.png"))
                                }
                            }
                        }
                    }
                }
            }
        }
        test("VR-SHELL: closed value measurements and Outline plus Find at minimum/normal/maximized sizes") {
            owner {
                w!!.dispose()
                w = showEditor(initial = sample, fontProfile = "noto-nastaliq", offscreenTest = true)
                surface = checkNotNull(windowCurrentSurface(w!!))
                ui = w!!.rootPane.getClientProperty("editor.structure") as StructureControls
            }
            await("Shell render") { surface.view.measure != null }
            owner {
                val tabs = w!!.rootPane.getClientProperty("editor.writingTabs") as JTabbedPane
                val find = w!!.rootPane.getClientProperty("editor.find") as FindReplaceUi
                ui.outlinePane.isVisible = true
                find.open(false)
                for (dimension in dimensions) {
                    for (tab in 0 until tabs.tabCount) {
                        w!!.size = dimension
                        tabs.selectedIndex = tab
                        w!!.validate()
                        check(surface.height >= 100) { "Writing viewport too short: $dimension / tab$tab height=${surface.height}" }
                        val controls = all(tabs.selectedComponent as Container).filter { it.isVisible && it is JComboBox<*> }
                        for (c in controls) {
                            val combo = c as JComboBox<*>
                            val chosen = combo.selectedItem?.toString() ?: ""
                            check(combo.width >= combo.getFontMetrics(combo.font).stringWidth(chosen) + 26) { "Closed value clipped: $chosen width=${combo.width}" }
                        }
                        if (dimension == dimensions.first() && tab == 3) {
                            val bitmap = java.awt.image.BufferedImage(w!!.width, w!!.height, 1)
                            val graphics = bitmap.createGraphics()
                            w!!.paint(graphics)
                            graphics.dispose()
                            javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "minimum-outline-find.png"))
                        }
                    }
                }
            }
        }
    } finally {
        owner { w?.dispose() }
    }
    Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    check(asyncFailures.isEmpty()) { "Uncaught UI exception: ${asyncFailures.firstOrNull()}" }
    result.writeText(evidenceJson(mapOf("status" to "PASS", "checks" to checks, "languages" to languages, "layoutMatrix" to matrix, "fontEvidence" to fonts, "scope" to "Actual mounted handlers and synthetic composition; native and optical review separate")), Charsets.UTF_8)
    println("PASS visual multilingual repair checks")
}
