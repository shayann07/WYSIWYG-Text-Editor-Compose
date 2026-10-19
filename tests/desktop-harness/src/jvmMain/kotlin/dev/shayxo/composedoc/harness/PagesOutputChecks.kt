package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.ExactDocument
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.data.LocalCopy
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.cloneDocument
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.layout.DocumentPagePlan
import dev.shayxo.composedoc.layout.PrintSnapshot
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.RunningContent
import dev.shayxo.composedoc.model.RunningField
import dev.shayxo.composedoc.model.RunningFlow
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.documentToPoints
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.itemDepth
import dev.shayxo.composedoc.model.mmToDocument
import dev.shayxo.composedoc.model.outputUnicodeIssues
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.plainText
import dev.shayxo.composedoc.model.validateStructure
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.ImageResources
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.PageOutputUi
import dev.shayxo.composedoc.workspace.pagesWritingSample
import dev.shayxo.composedoc.workspace.showEditor
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.Timer
fun runPagesOutputChecks(result: File) {
    val root = Files.createTempDirectory("astra-pages-output-")
    val groups = ArrayList<String>()
    val metrics = linkedMapOf<String, Any>()
    val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        errors += e
        e.printStackTrace()
    }
    fun test(name: String, run: () -> Unit) {
        run()
        check(errors.isEmpty())
        groups += name
        println("PASS output $name")
    }
    fun edt(run: () -> Unit) = SwingUtilities.invokeAndWait(run)
    fun waitFor(name: String, run: () -> Boolean) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (System.nanoTime() < deadline) {
            var ok = false
            edt { ok = run() }
            if (ok)return
            Thread.sleep(30)
        }
        error("Timed out: $name")
    }
    test("document/point units are independent of125percent display and zoom") {
        check(mmToDocument(25.4f) == 96f && documentToPoints(96f) == 72f)
        check(kotlin.math.abs(documentToPoints(mmToDocument(210f)) - 595.2756f) < .001f)
    }
    test("page-break semantic separator, exact Undo/Redo and plain formfeed") {
        val e = EditorEngine()
        e.replace("کتاب English", "Fixture")
        e.move(Selection(4, 4))
        val before = e.snapshot()
        e.structure(StructureCommand.PageBreak)
        val after = e.snapshot()
        check(
            after.committed.text == "کتاب\u000c English" &&
                after.committed.units[4].structuralBreak == StructuralBreak.PAGE &&
                after.selection == Selection(5, 5),
        )
        check(after.undoCount == before.undoCount + 1 && after.committed.plainText() == "کتاب\u000c English")
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == after.committed)
        val plain = EditorEngine()
        plain.replace("a\u000cb\nc\n", "Plain", paragraphBreaks = true)
        check(
            plain.snapshot().committed.units[1].structuralBreak == StructuralBreak.PAGE &&
                plain.snapshot().committed.units[3].structuralBreak == StructuralBreak.PARAGRAPH,
        )
    }
    test("half-open/backward paragraph targeting and split inheritance") {
        val e = EditorEngine()
        e.replace("اول\nدوم\nسوم", "Fixture", true)
        val raw = e.snapshot().committed
        val rules = PaginationRules(true, true, true, 3, 2)
        e.move(Selection(4, 0))
        e.structure(StructureCommand.Pagination(rules))
        check(
            e.snapshot().committed.paragraphs().map { it.properties.pagination } ==
                listOf(rules, PaginationRules(), PaginationRules()),
        )
        check(e.snapshot().committed.units.map { it.id } == raw.units.map { it.id })
        e.move(Selection(1, 1))
        e.structure(StructureCommand.Enter(false))
        check(
            e.snapshot().committed.paragraphs()[1].properties.pagination ==
                rules.copy(breakBefore = false, keepWithNext = false),
        )
    }
    val specimen = pagesWritingSample()
    val encoded = DocumentFiles.encode(specimen)
    test("privatev9 raw attributes/breaks/flow IDs/resources Save and exact recovery") {
        check(DocumentFiles.decode(encoded).same(specimen))
        val p = root.resolve("pages.astra")
        DocumentFiles.write(p, specimen)
        check(DocumentFiles.read(p).same(specimen))
        check(ExactDocument.decode(ExactDocument.encode(specimen)) == specimen)
        val store = LocalRecoveryStore(root.resolve("committed"))
        val id = java.util.UUID.randomUUID().toString()
        store.acquire(id).use { store.publish(it, LocalCopy(id, 1, 1, null, specimen)) }
        check(store.restore(store.scan().single()).document.same(specimen))
        val raw = specimen.copy(
            running = specimen.running.copy(header = specimen.running.header!!.copy(text = "کِ \ud800")),
        )
        check(DocumentFiles.decode(DocumentFiles.encode(raw)).running.header!!.text == "کِ \ud800")
    }
    test("rich clipboard retains page-breaks/rules but destination running content owns its flows") {
        val e = EditorEngine(specimen)
        e.selectAll()
        val fragment = FragmentCodec.decode(FragmentCodec.encode(RichFragment.capture(e.clipboardCapture()!!)))
        check(fragment.document.units.count { it.structuralBreak == StructuralBreak.PAGE } == 9)
        val running = RunningContent(footer = RunningFlow(text = "destination", field = RunningField.PAGE))
        val dest = EditorEngine(Document(emptyList(), running = running))
        val before = dest.snapshot().committed.running
        check(dest.clipboardApply(dest.clipboardCapture()!!, fragment))
        check(dest.snapshot().committed.running == before)
        dest.perform(EditAction.Undo)
        check(dest.snapshot().committed.units.isEmpty())
        dest.perform(EditAction.Redo)
        check(dest.snapshot().committed.running == before)
    }
    test("running changes history/empty removal and clone identities") {
        val e = EditorEngine(specimen)
        val old = e.snapshot()
        e.structure(StructureCommand.Running(RunningContent()))
        check(!e.snapshot().committed.same(old.committed))
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == old.committed)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed.running == RunningContent())
        val copy = cloneDocument(specimen)
        check(
            copy.text == specimen.text &&
                copy.resources == specimen.resources &&
                copy.running.same(specimen.running) &&
                copy.running.header!!.id != specimen.running.header!!.id &&
                copy.running.footer!!.id != specimen.running.footer!!.id,
        )
        check(copy.units.map { it.id }.toSet().intersect(specimen.units.map { it.id }.toSet()).isEmpty())
        val originalItems = specimen.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.toSet()
        check(copy.paragraphs().none { it.properties.item in originalItems })
        check(
            copy.paragraphs().map {
                listOf(
                    it.properties.heading,
                    it.properties.direction,
                    it.properties.alignment,
                    it.properties.pagination,
                    copy.itemDepth(it.properties.item),
                )
            } ==
                specimen.paragraphs().map {
                    listOf(
                        it.properties.heading,
                        it.properties.direction,
                        it.properties.alignment,
                        it.properties.pagination,
                        specimen.itemDepth(it.properties.item),
                    )
                },
        )
    }
    test("pagination commands settle synthetic composition without accepting candidates") {
        val e = EditorEngine()
        e.replace("اردو")
        e.move(Selection(2, 2))
        e.inputMethod("کِ", 0, 2)
        val before = e.snapshot().committed
        e.structure(StructureCommand.Pagination(PaginationRules(keepLines = true)))
        check(e.snapshot().waiting && e.snapshot().committed == before)
        e.inputMethod("", 0, null)
        check(e.snapshot().marked == null && e.snapshot().committed.paragraphs()[0].properties.pagination.keepLines)
    }
    test("malformed output refused with exact offsets; accepted source remains raw") {
        val e = EditorEngine()
        e.replace("کِ \ud800 x\udfff")
        val before = e.snapshot()
        val issues = outputUnicodeIssues(before.committed)
        check(issues.size == 2 && issues[0].contains("offset3") && issues[1].contains("offset6"))
        check(e.snapshot() == before)
        check(outputUnicodeIssues(specimen).isEmpty())
    }
    lateinit var window: JFrame
    lateinit var surface: EditorSurface
    edt {
        window =
            showEditor(
                initial = specimen,
                offscreenTest = true,
                fontProfile = "noto-nastaliq",
                paged = true,
                recoveryDirectory = root.resolve("live"),
            )
        surface = findSurface(window)!!
    }
    waitFor("page plan/resources") {
        surface.geometry()?.documentPagePlan != null &&
            specimen.units.mapNotNull { it.image?.resource }.all { ImageResources.cached(it) != null }
    }
    lateinit var plan: DocumentPagePlan
    test("actual captured plan coverage and8-12page specimen") {
        edt {
            plan = surface.geometry()!!.documentPagePlan!!
            plan.assertCoverage()
            check(plan.pageCount in 8..12)
            check(plan.document.same(specimen))
            check(plan.running.none { it.page == 0 })
            check(
                plan.running.filter { !it.header }.all {
                    it.text.endsWith(
                        " / " + plan.pageCount.toString().map { c -> '\u06f0' + (c - '0') }.joinToString(""),
                    )
                },
            )
        }
        metrics["specimenPages"] = plan.pageCount
        metrics["specimenUnits"] = specimen.units.size
    }
    test("independent line-placement expectations for keep/widow/oversize/explicit breaks") {
        val policyChecks = runPagePolicyChecks()
        check(policyChecks.size == 7)
        metrics["independentPagePolicyCases"] = policyChecks
    }
    test("canonical line plan stable across continuous/paged and all zooms/viewport widths") {
        val expected = plan.paragraphs.map { p ->
            (0 until p.lineCount).map {
                p.layout.getLineStart(it) to
                    p.layout.getLineEnd(it)
            }
        }
        val before = surface.viewModel.verificationEngine.snapshot()
        for (paged in listOf(false, true)) {
            for (zoom in listOf(.5f, .75f, 1f)) {
                edt {
                    surface.view.setPagedMode(paged)
                    surface.view.changeZoom(zoom)
                    window.setSize(if (zoom == .5f)940 else 1100, 740)
                    window.validate()
                }
                waitFor("view") { surface.geometry()?.documentPagePlan != null }
                edt {
                    val p = surface.geometry()!!.documentPagePlan!!
                    check(
                        p.paragraphs.map { q ->
                            (0 until q.lineCount).map {
                                q.layout.getLineStart(it) to
                                    q.layout.getLineEnd(it)
                            }
                        } ==
                            expected,
                    )
                    check(p.pageCount == plan.pageCount)
                    check(
                        surface.viewModel.verificationEngine.snapshot().committed == before.committed &&
                            surface.viewModel.verificationEngine.snapshot().undoCount == before.undoCount,
                    )
                }
            }
        }
    }
    test("immutable picture capture, raster preview, requested paper and callback reorder/repeat/cancel") {
        lateinit var snapshot: PrintSnapshot
        val start = System.nanoTime()
        edt {
            val builder = PrintSnapshot.Builder(plan, ImageResources::cached)
            repeat(plan.pageCount) { builder.next() }
            snapshot = builder.finish()
        }
        metrics["captureMs"] = (System.nanoTime() - start) / 1e6
        snapshot.use { captured ->
            result.parentFile.mkdirs()
            for (n in 0 until plan.pageCount) {
                val bitmap = captured.render(n, 96)
                javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "output-page-${n + 1}.png"))
                bitmap.flush()
            }
            val pg = captured.pageable(1, 3)
            check(pg.numberOfPages == 3)
            val canvas = java.awt.image.BufferedImage(800, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (n in listOf(2, 0, 2)) {
                val g = canvas.createGraphics()
                try {
                    check(pg.getPrintable(n).print(g, pg.getPageFormat(n), n) == java.awt.print.Printable.PAGE_EXISTS)
                } finally {
                    g.dispose()
                }
            }
            val g = canvas.createGraphics()
            try {
                check(pg.getPrintable(0).print(g, pg.getPageFormat(0), 3) == java.awt.print.Printable.NO_SUCH_PAGE)
                check(
                    runCatching {
                        captured.pageable(cancelled = { true }).getPrintable(0).print(g, captured.paperFormat(), 0)
                    }.exceptionOrNull() is java.awt.print.PrinterAbortException,
                )
            } finally {
                g.dispose()
                canvas.flush()
            }
            check(kotlin.math.abs(captured.paperFormat().width - 595.2756) < .001)
            check(runCatching { captured.pageable(0, 2) }.isFailure)
        }
        check(surface.viewModel.verificationEngine.snapshot().committed.same(specimen))
        metrics["printServices"] = java.awt.print.PrinterJob.lookupPrintServices().map { it.name }
    }
    test("preview capture yields owner thread and Cancel preserves canonical state/history") {
        val prior = surface.viewModel.verificationEngine.snapshot()
        var ticks = 0
        var longest = 0L
        var last = System.nanoTime()
        val heartbeat = Timer(5) {
            val now = System.nanoTime()
            longest = maxOf(longest, now - last)
            last = now
            ticks++
        }
        fun children(c: java.awt.Component): List<java.awt.Component> =
            listOf(c) + (if (c is java.awt.Container)c.components.flatMap(::children) else emptyList())
        val ui = window.rootPane.getClientProperty("editor.pageOutput") as PageOutputUi
        lateinit var dialog: JDialog
        edt {
            heartbeat.start()
            children(ui.panel).filterIsInstance<JButton>().single { it.name == "print-preview" }.doClick()
        }
        waitFor("preview progress") {
            val candidate = java.awt.Window.getWindows().filterIsInstance<JDialog>().firstOrNull {
                it.isVisible &&
                    it.title.startsWith("Captured print preview")
            }
            if (candidate == null) {
                false
            } else {
                dialog = candidate
                children(dialog).filterIsInstance<JLabel>().any {
                    Regex("Captured[1-9][0-9]*/[0-9]+").matches(
                        it.text ?: "",
                    )
                }
            }
        }
        edt {
            children(dialog).filterIsInstance<JButton>().single { it.text == "Cancel / Close" }.doClick()
            heartbeat.stop()
            check(!dialog.isDisplayable)
            check(
                surface.viewModel.verificationEngine.snapshot().committed == prior.committed &&
                    surface.viewModel.verificationEngine.snapshot().undoCount == prior.undoCount,
            )
        }
        metrics["previewCaptureHeartbeat"] =
            mapOf("ticks" to ticks, "maxGapMs" to longest / 1e6, "declaredLimitMs" to 250)
        check(ticks > 0 && longest < 250_000_000L)
    }
    fun fixedPages(count: Int): Document {
        val units = ArrayList<TextUnit>()
        var id = 0L
        repeat(count) { page ->
            if (page >
                0
            ) {
                units += TextUnit(++id, '\u000c', UnitKind.PARAGRAPH_BREAK, structuralBreak = StructuralBreak.PAGE)
            }
            "صفحہ${page + 1} — Urdu English".forEach { units += TextUnit(++id, it) }
        }
        return Document(
            units,
            running = RunningContent(footer = RunningFlow(text = "صفحہ", field = RunningField.PAGE_TOTAL)),
        )
    }
    test("page fields stabilize at9-10 and99-100 without a content/history entry") {
        for (count in listOf(10, 100)) {
            lateinit var owner: JFrame
            lateinit var s: EditorSurface
            val d = fixedPages(count)
            edt {
                owner = showEditor(initial = d, offscreenTest = true, fontProfile = "noto-nastaliq", paged = true)
                s = findSurface(owner)!!
            }
            waitFor("field$count") { s.geometry()?.documentPagePlan?.pageCount == count }
            edt {
                val p = s.geometry()!!.documentPagePlan!!
                check(p.running.last().text == "صفحہ $count / $count")
                check(p.running[count - 2].text == "صفحہ ${count - 1} / $count")
                check(
                    s.viewModel.verificationEngine.snapshot().committed.same(d) &&
                        s.viewModel.verificationEngine.snapshot().undoCount == 0,
                )
                owner.dispose()
            }
        }
    }
    test("bounded50page workload thresholds declared before measurement") {
        val limits = mapOf("layoutMs" to 30000, "captureMs" to 15000, "endpointRasterMs" to 10000)
        metrics["fiftyPageLimits"] = limits
        val units = ArrayList<TextUnit>()
        var serial = 0L
        repeat(5) { group ->
            fun props(p: ParagraphProperties) = p.copy(
                item = if (p.item.isEmpty())"" else "$group-${p.item}",
                parent = if (p.parent.isEmpty())"" else "$group-${p.parent}",
            )
            if (group >
                0
            ) {
                units +=
                    TextUnit(
                        ++serial,
                        '\u000c',
                        UnitKind.PARAGRAPH_BREAK,
                        followingParagraph = props(specimen.startParagraph),
                        structuralBreak = StructuralBreak.PAGE,
                    )
            }
            specimen.units.forEach {
                units +=
                    it.copy(
                        id = ++serial,
                        followingBlockId = Long.MIN_VALUE,
                        followingParagraph = props(it.followingParagraph),
                    )
            }
        }
        val large = specimen.copy(
            units = units,
            startParagraph = specimen.startParagraph.copy(item = "", parent = "", list = ListKind.NONE),
        )
        validateStructure(large)
        lateinit var owner: JFrame
        lateinit var s: EditorSurface
        val start = System.nanoTime()
        edt {
            owner = showEditor(initial = large, offscreenTest = true, fontProfile = "noto-nastaliq", paged = true)
            s = findSurface(owner)!!
        }
        waitFor("50page layout") { s.geometry()?.documentPagePlan?.pageCount == 50 }
        val layout = (System.nanoTime() - start) / 1e6
        check(layout < limits.getValue("layoutMs"))
        lateinit var captured: PrintSnapshot
        val captureStart = System.nanoTime()
        edt {
            val b = PrintSnapshot.Builder(s.geometry()!!.documentPagePlan!!, ImageResources::cached)
            repeat(50) { b.next() }
            captured = b.finish()
        }
        val capture = (System.nanoTime() - captureStart) / 1e6
        check(capture < limits.getValue("captureMs"))
        val renderStart = System.nanoTime()
        captured.use {
            it.render(0, 96).flush()
            it.render(49, 96).flush()
        }
        val raster = (System.nanoTime() - renderStart) / 1e6
        check(raster < limits.getValue("endpointRasterMs"))
        metrics["fiftyPageWorkload"] =
            mapOf(
                "units" to large.units.size,
                "layoutMs" to layout,
                "captureMs" to capture,
                "endpointRasterMs" to raster,
                "heapUsedBytes" to java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            )
        edt {
            check(
                s.viewModel.verificationEngine.snapshot().committed.same(large) &&
                    s.viewModel.verificationEngine.snapshot().undoCount == 0,
            )
            owner.dispose()
        }
    }
    edt { window.dispose() }
    check(errors.isEmpty())
    result.writeText(
        evidenceJson(
            mapOf(
                "status" to "PASS",
                "groups" to groups,
                "metrics" to metrics,
                "evidenceLevel" to
                    "Synthetic commands/mounted rendering/print callbacks; not native interaction or semantic PDF/physical printing",
            ),
        ),
        Charsets.UTF_8,
    )
    println("PASS pages/output checks ${groups.size}")
}
