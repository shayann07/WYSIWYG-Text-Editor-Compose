package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.UnfinishedCapture
import dev.shayxo.composedoc.data.UnfinishedEntry
import dev.shayxo.composedoc.data.UnfinishedKind
import dev.shayxo.composedoc.data.UnfinishedStore
import dev.shayxo.composedoc.data.WorkspaceEntry
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.QuarantinedCallback
import dev.shayxo.composedoc.ui.RetainedCompositionPreimage
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.ui.imageHandles
import dev.shayxo.composedoc.workspace.FindReplaceUi
import dev.shayxo.composedoc.workspace.StructureControls
import dev.shayxo.composedoc.workspace.UnfinishedController
import dev.shayxo.composedoc.workspace.WorkspaceUi
import dev.shayxo.composedoc.workspace.WritingHeaderScroll
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.showUnfinishedRecovery
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
fun runWorkspaceHostChecks(result: File) {
    val dir = Files.createTempDirectory("workspace-mounted-")
    System.setProperty("astra.lockRoot", dir.resolve("locks").toString())
    val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        errors += e
        e.printStackTrace()
    }
    lateinit var w: JFrame
    lateinit var surface: EditorSurface
    fun edt(run: () -> Unit) = SwingUtilities.invokeAndWait(run)
    fun waitFor(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) {
            var okay = false
            edt { okay = condition() }
            if (okay) {
                check(errors.isEmpty())
                return
            }
            Thread.sleep(50)
        }
        error("Timed out: $label")
    }
    val doc = workspaceFixture()
    edt {
        w = showEditor(initial = doc, offscreenTest = true, fontProfile = "noto-nastaliq", paged = true, recoveryDirectory = dir.resolve("recovery"))
        surface = findSurface(w)!!
    }
    waitFor("initial layout") { surface.geometry() != null }
    val groups = ArrayList<String>()
    val layout = ArrayList<Map<String, Any>>()
    fun test(label: String, run: () -> Unit) {
        run()
        groups += label
        println("PASS workspace host $label")
    }
    test("actual image mouse handlers drag once / Escape cancels / stale basis refuses") {
        val index = surface.viewModel.verificationEngine.snapshot().committed.units.indexOfFirst { it.kind == UnitKind.IMAGE }
        edt { surface.viewModel.verificationEngine.move(Selection(index, index + 1)) }
        waitFor("image reveal") { imageHandles(surface) != null }
        edt {
            val c = surface.viewModel.verificationEngine.snapshot()
            val handles = imageHandles(surface)!!
            val x = handles.right.x + 6
            val y = handles.right.y + 6
            surface.mouseListeners.forEach { it.mousePressed(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, 1, 0, x, y, 1, false, MouseEvent.BUTTON1)) }
            check(surface.imageResize != null)
            surface.mouseMotionListeners.forEach { it.mouseDragged(MouseEvent(surface, MouseEvent.MOUSE_DRAGGED, 2, InputEvent.BUTTON1_DOWN_MASK, x + 40, y, 1, false)) }
            check(surface.viewModel.verificationEngine.snapshot().committed == c.committed && surface.viewModel.verificationEngine.snapshot().undoCount == c.undoCount)
            surface.mouseListeners.forEach { it.mouseReleased(MouseEvent(surface, MouseEvent.MOUSE_RELEASED, 3, 0, x + 40, y, 1, false, MouseEvent.BUTTON1)) }
            // Letter landscape is1056 document units fitted to996 AWT units here.
            // A40px gesture therefore adds42 document units (integer requested width).
            check(surface.width == 1020 && surface.viewModel.verificationEngine.snapshot().committed.units[index].image!!.width == 282 && surface.viewModel.verificationEngine.snapshot().undoCount == c.undoCount + 1)
            surface.viewModel.verificationEngine.perform(EditAction.Undo)
            check(surface.viewModel.verificationEngine.snapshot().committed == c.committed)
            val h = imageHandles(surface)!!
            val xx = h.right.x + 6
            val yy = h.right.y + 6
            surface.mouseListeners.forEach { it.mousePressed(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, 4, 0, xx, yy, 1, false, MouseEvent.BUTTON1)) }
            surface.keyListeners.forEach { it.keyPressed(KeyEvent(surface, KeyEvent.KEY_PRESSED, 5, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)) }
            surface.mouseListeners.forEach { it.mouseReleased(MouseEvent(surface, MouseEvent.MOUSE_RELEASED, 6, 0, xx + 80, yy, 1, false, MouseEvent.BUTTON1)) }
            check(surface.viewModel.verificationEngine.snapshot().committed == c.committed && surface.imageResize == null)
        }
    }
    test("all image zoom transforms and continuous/paged handler geometry") {
        for (paged in listOf(false, true)) {
            for (zoom in listOf(.5f, .75f, 1f)) {
                edt {
                    surface.view.setPagedMode(paged)
                    surface.view.changeZoom(zoom)
                }
                waitFor("zoom geometry") { surface.geometry() != null }
                edt {
                    val f = surface.viewModel.verificationEngine.snapshot()
                    val h = imageHandles(surface)!!
                    val x = h.right.x + 6
                    val y = h.right.y + 6
                    surface.mouseListeners.forEach { it.mousePressed(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, 10, 0, x, y, 1, false, MouseEvent.BUTTON1)) }
                    surface.mouseListeners.forEach { it.mouseReleased(MouseEvent(surface, MouseEvent.MOUSE_RELEASED, 11, 0, x + (40 * zoom).toInt(), y, 1, false, MouseEvent.BUTTON1)) }
                    check(surface.viewModel.verificationEngine.snapshot().committed.units[f.selection.start].image!!.width == 282)
                    surface.viewModel.verificationEngine.perform(EditAction.Undo)
                    check(surface.viewModel.verificationEngine.snapshot().committed == f.committed)
                }
            }
        }
    }
    test("bounded owner capture, publication receipt, active hidden, lifecycle resolution") {
        edt {
            surface.viewModel.verificationEngine.move(Selection(6, 6))
            surface.viewModel.verificationEngine.inputMethod("کِ 新 👩🏽‍💻", 0, 2)
            (w.rootPane.getClientProperty("editor.unfinishedRecovery") as UnfinishedController).flush()
        }
        waitFor("durable active") {
            val c = w.rootPane.getClientProperty("editor.unfinishedRecovery") as UnfinishedController
            val state = c.state()
            (state["published"] as Long) > 0 && state["pending"] == false && state["running"] == false
        }
        check(UnfinishedStore(LocalRecoveryStore(dir.resolve("recovery/unfinished-records"))).scan().isEmpty())
        edt { surface.viewModel.verificationEngine.abortComposition() }
        waitFor("lifecycle resolution") {
            val c = w.rootPane.getClientProperty("editor.unfinishedRecovery") as UnfinishedController
            return@waitFor !c.status().contains("pending") && c.status().startsWith("No unresolved")
        }
    }
    test("Find opens visibly without a resize and toolbar groups reveal their controls") {
        val find = w.rootPane.getClientProperty("editor.find") as FindReplaceUi
        edt {
            find.close()
            w.setSize(1020, 740)
            w.validate()
        }
        Thread.sleep(100)
        var before = 0
        edt {
            before = w.contentPane.components.filterIsInstance<WritingHeaderScroll>().single().height
            find.open(true)
        }
        waitFor("Find query visible after open") { find.query.height > 0 && find.query.visibleRect.height == find.query.height && w.contentPane.components.filterIsInstance<WritingHeaderScroll>().single().height > before }
        edt {
            // Text can be wider than its viewport (literal queries scroll internally).
            // The editable viewport, rather than the whole text document, must be visible.
            val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, find.query) as JViewport
            check(viewport.visibleRect.width == viewport.width && viewport.visibleRect.height == viewport.height)
        }
    }
    test("minimum / normal / square / maximized-like shell with Outline and Find") {
        edt {
            (w.rootPane.getClientProperty("editor.structure") as StructureControls).outlinePane.isVisible = true
            (w.rootPane.getClientProperty("editor.find") as FindReplaceUi).open(true)
        }
        for ((width, height) in listOf(940 to 400, 1020 to 740, 720 to 720, 1536 to 864)) {
            // Actual minimum is 940 wide; square qualification uses 940x940, not an unsupported 720-wide window.
            val actual = if (width == 720)940 to 940 else width to height
            edt {
                w.setSize(actual.first, actual.second)
                w.validate()
            }
            Thread.sleep(150)
            edt {
                val s = findSurface(w)!!
                check(s.width > 100 && s.height > 60)
                check(s.y >= 0)
                layout += mapOf("window" to listOf(w.width, w.height), "editingSurface" to listOf(s.width, s.height))
                val b = java.awt.image.BufferedImage(w.width, w.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = b.createGraphics()
                w.paint(g)
                g.dispose()
                result.parentFile.mkdirs()
                javax.imageio.ImageIO.write(b, "png", File(result.parentFile, "workspace-shell-${actual.first}x${actual.second}.png"))
            }
        }
    }
    test("legacy unqualified retained preimage preserves raw original context without inventing attributes") {
        val context = "original context کِ \ud800"
        val pre = RetainedCompositionPreimage("raw candidate \udfff", Selection(150, 154), 150 until 154, context, 7)
        lateinit var controller: UnfinishedController
        edt {
            surface.recoverySession.retainedPreimage = pre
            surface.statusChanged(surface.viewModel.verificationEngine.snapshot())
            controller = w.rootPane.getClientProperty("editor.unfinishedRecovery") as UnfinishedController
            controller.flush()
        }
        waitFor("raw retained publication") {
            val state = controller.state()
            state["pending"] == false && state["running"] == false
        }
        val record = controller.store.entries(controller.owner).single().record!!
        check(record.provenance.endsWith(context) && record.text == pre.draftText && record.selection == pre.selectionBefore)
        check(controller.store.load(record).second == null)
        controller.store.export(record, dir.resolve("raw-retained-evidence.astra-recovery"))
        lateinit var staleDecision: ((() -> Unit) -> Unit)
        val updated = pre.copy(draftText = "updated raw candidate \udfff")
        edt {
            staleDecision = controller.captureDiscardBeforeClose()
            surface.recoverySession.retainedPreimage = updated
            surface.statusChanged(surface.viewModel.verificationEngine.snapshot())
            controller.flush()
        }
        waitFor("updated retained publication") {
            val state = controller.state()
            state["pending"] == false && state["running"] == false
        }
        var wronglyClosed = false
        edt { staleDecision { wronglyClosed = true } }
        waitFor("stale close decision refused") { surface.recoveryCaptureFailure?.contains("Discard persistence failed") == true }
        check(!wronglyClosed && controller.store.entries(controller.owner).single().record!!.text == updated.draftText)
        lateinit var selectedDecision: ((() -> Unit) -> Unit)
        val arrival = QuarantinedCallback("new arrival محفوظ", 0, null, 9)
        edt {
            selectedDecision = controller.captureDiscardBeforeClose()
            surface.quarantinedCallbacks += arrival
            surface.statusChanged(surface.viewModel.verificationEngine.snapshot())
            controller.flush()
        }
        waitFor("new independent arrival published") {
            val state = controller.state()
            state["pending"] == false && state["running"] == false
        }
        edt {
            selectedDecision {
                surface.recoverySession.resolveRetainedDraft(updated)
                surface.statusChanged(surface.viewModel.verificationEngine.snapshot())
            }
        }
        waitFor("selected close snapshot resolves old record only") { surface.recoverySession.snapshotRetainedDrafts().isEmpty() && !controller.decisionBusy() }
        check(controller.store.entries(controller.owner).single().record!!.text == arrival.text)
        edt { controller.resolveLive(arrival, remove = { surface.discardQuarantinedCallbacks(listOf(arrival)) }) }
        waitFor("durable selected callback discard") { surface.quarantinedCallbacks.isEmpty() && !controller.decisionBusy() }
    }
    test("noncancelable callback capacity preserves sixteen records and visibly suspends admission") {
        edt {
            surface.pauseForRecoveryCapture("Owned synthetic capacity setup")
            val accepted = surface.viewModel.verificationEngine.snapshot().committed
            repeat(17) { n -> surface.dispatchEvent(java.awt.event.InputMethodEvent(surface, java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, java.text.AttributedString("callback-$n کِ").iterator, 0, java.awt.font.TextHitInfo.leading(1), null)) }
            check(surface.quarantinedCallbacks.size == 16 && surface.quarantinedCallbacks[0].text == "callback-0 کِ")
            check(!surface.admissionAllowed && surface.recoveryCaptureFailure!!.contains("RecoveryCaptureFailed") && surface.recoveryCaptureFailure!!.contains("could not be retained"))
            check(surface.viewModel.verificationEngine.snapshot().committed == accepted)
        }
    }
    test("discard completion removes its captured row after selection changes during IO") {
        val root = dir.resolve("discard-selection")
        val store = UnfinishedStore(LocalRecoveryStore(root))
        val owner = java.util.UUID.randomUUID().toString()
        store.recovery.acquire(owner).use { lease ->
            repeat(2) { n -> store.publish(lease, UnfinishedCapture(java.util.UUID.randomUUID().toString(), 1, UnfinishedKind.QUARANTINE, doc, null, Selection(0, 0), null, text = "owned-$n کِ"), "Owned selection regression") }
        }
        lateinit var chooser: JFrame
        lateinit var list: JList<*>
        lateinit var captured: UnfinishedEntry
        lateinit var other: UnfinishedEntry
        fun descendants(c: Component): List<Component> = listOf(c) + (if (c is Container)c.components.flatMap(::descendants) else emptyList())
        edt { chooser = showUnfinishedRecovery(root, "noto-nastaliq", true, offscreen = true) }
        waitFor("two inactive rows") {
            list = descendants(chooser).filterIsInstance<JList<*>>().single()
            list.model.size == 2
        }
        edt {
            captured = list.model.getElementAt(0) as UnfinishedEntry
            other = list.model.getElementAt(1) as UnfinishedEntry
            list.selectedIndex = 0
        }
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(10)
        try {
            // Delay the real worker dispatch so selection can change after confirmation.
            repeat(10) {
                object : SwingWorker<Unit, Unit>() {
                    override fun doInBackground() {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }.execute()
            }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            SwingUtilities.invokeLater { descendants(chooser).filterIsInstance<JButton>().single { it.name == "unfinished-discard" }.doClick() }
            waitFor("actual discard confirmation") { Window.getWindows().filterIsInstance<JDialog>().any { it.isVisible && it.title == "Discard unfinished record" } }
            edt {
                val confirm = Window.getWindows().filterIsInstance<JDialog>().single { it.isVisible && it.title == "Discard unfinished record" }
                descendants(confirm).filterIsInstance<JButton>().single { it.text == "Yes" }.doClick()
            }
            edt { list.selectedIndex = 1 }
            release.countDown()
            waitFor("discard worker completion") { list.model.size == 1 }
            edt { check((list.model.getElementAt(0) as UnfinishedEntry).id == other.id) { "Removed the current selection instead of the captured row" } }
            check(store.entries(owner).single().id == other.id && captured.id != other.id)
        } finally {
            release.countDown()
            edt { Window.getWindows().filter { it == chooser || it.owner == chooser }.forEach { it.dispose() } }
        }
    }
    test("recent remove completion keeps a different row selected during IO") {
        val ui = w.rootPane.getClientProperty("editor.workspace") as WorkspaceUi
        ui.close(false) // Freeze this owned window's metadata while the list is tested.
        val store = ui.store
        val ids = List(2) { java.util.UUID.randomUUID().toString() }
        ids.forEachIndexed { at, id -> store.update(WorkspaceEntry(id, null, "Owned recent $at", null, at + 1L, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), active = false)) }
        edt { ui.showRecent() }
        fun descendants(c: Component): List<Component> = listOf(c) + (if (c is Container)c.components.flatMap(::descendants) else emptyList())
        lateinit var dialog: JDialog
        lateinit var list: JList<*>
        var captured = ""
        var other = ""
        waitFor("recent selector") {
            val candidate = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isVisible && it.title == "Recent documents / restore last workspace" }
            if (candidate == null) {
                false
            } else {
                dialog = candidate
                list = descendants(dialog).filterIsInstance<JList<*>>().single()
                list.model.size == 2
            }
        }
        edt {
            captured = ((list.model.getElementAt(0) as Pair<*, *>).first as WorkspaceEntry).id
            other = ((list.model.getElementAt(1) as Pair<*, *>).first as WorkspaceEntry).id
            list.selectedIndex = 0
        }
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(10)
        try {
            repeat(10) {
                object : SwingWorker<Unit, Unit>() {
                    override fun doInBackground() {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }.execute()
            }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            edt {
                descendants(dialog).filterIsInstance<JButton>().single { it.name == "recent-remove" }.doClick()
                list.selectedIndex = 1
            }
            release.countDown()
            waitFor("recent removal completion") { list.model.size == 1 }
            edt { check(((list.model.getElementAt(0) as Pair<*, *>).first as WorkspaceEntry).id == other) { "Wrong recent UI row removed" } }
            check(store.entries().any { it.id == other } && store.entries().none { it.id == captured })
        } finally {
            release.countDown()
            edt { dialog.dispose() }
        }
    }
    edt { w.dispose() }
    check(errors.isEmpty())
    result.writeText(evidenceJson(mapOf("status" to "PASS", "groups" to groups, "layout" to layout, "evidenceLevel" to "Mounted offscreen actual handlers; synthetic composition; not native keyboard/desktop qualification")), Charsets.UTF_8)
    println("PASS mounted workspace checks ${groups.size}")
}
