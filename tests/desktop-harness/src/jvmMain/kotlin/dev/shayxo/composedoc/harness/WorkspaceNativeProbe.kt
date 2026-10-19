package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FileFingerprint
import dev.shayxo.composedoc.data.SafeDocumentIO
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.QuarantinedCallback
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.ui.imageHandles
import dev.shayxo.composedoc.workspace.LocalRecoveryController
import dev.shayxo.composedoc.workspace.UnfinishedController
import dev.shayxo.composedoc.workspace.WorkspaceUi
import dev.shayxo.composedoc.workspace.pagesWritingSample
import dev.shayxo.composedoc.workspace.samplePng
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.showLocalRecovery
import dev.shayxo.composedoc.workspace.showUnfinishedRecovery
import dev.shayxo.composedoc.workspace.windowFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager
/** Owned disposable fixture. Commands only inspect, set up external faults/synthetic input, or close owned windows. */
fun workspaceNativeProbe(args: Array<String>) {
    val root = Path.of(args.single { it.startsWith("--root=") }.substringAfter('=')).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val recovery = root.resolve("recovery")
    System.setProperty("astra.lockRoot", root.resolve("locks").toString())
    Files.write(root.resolve("illustration.png"), samplePng(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    val created = java.util.concurrent.CopyOnWriteArrayList<JFrame>()
    val initial = if ("--pages-fixture" in args)pagesWritingSample() else Document(emptyList())
    fun editors() = java.awt.Frame.getFrames().filterIsInstance<JFrame>().filter { it.isDisplayable && windowFiles(it) != null }
    SwingUtilities.invokeAndWait {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        created += showEditor(initial = initial, fontProfile = "noto-nastaliq", paged = true, recoveryDirectory = recovery)
        if ("--pages-fixture" in args)windowFiles(created[0])!!.markUnsavedCopy("Urdu pages disposable specimen")
        if ("--resume" in args) {
            showLocalRecovery(recovery, "noto-nastaliq", true, onlyIfNeeded = true)
            showUnfinishedRecovery(recovery.resolve("unfinished-records"), "noto-nastaliq", true, onlyIfNeeded = true)
            (created[0].rootPane.getClientProperty("editor.workspace") as WorkspaceUi).showRecent(true)
        }
    }
    Thread {
        Thread.sleep(15 * 60 * 1000L)
        System.err.println("Owned native fixture watchdog timeout")
        kotlin.system.exitProcess(124)
    }.apply {
        isDaemon = true
        start()
    }
    var selected = 0
    fun snapshot(): String {
        var value = ""
        SwingUtilities.invokeAndWait {
            val windows = editors()
            if (selected >= windows.size)selected = (windows.size - 1).coerceAtLeast(0)
            value = evidenceJson(
                mapOf(
                    "pid" to ProcessHandle.current().pid(),
                    "selected" to selected,
                    "windows" to windows.mapIndexed { index, w ->
                        val s = findSurface(w)!!
                        val f = s.viewModel.verificationEngine.snapshot()
                        val files = windowFiles(w)!!
                        val g = s.geometry()
                        mapOf(
                            "index" to index, "title" to w.title, "path" to files.path?.toString(), "dirty" to files.dirty, "busy" to files.busy, "fileStatus" to files.status,
                            "units" to f.committed.text.map { it.code }, "payload" to Base64.getEncoder().encodeToString(DocumentFiles.encode(f.committed)), "visiblePayload" to Base64.getEncoder().encodeToString(DocumentFiles.encode(f.visible)),
                            "selection" to listOf(f.selection.anchor, f.selection.focus), "marked" to f.marked?.let { listOf(it.first, it.last + 1) }, "undo" to f.undoCount, "redo" to f.redoCount,
                            "zoom" to s.view.zoom, "paged" to s.view.paged, "scroll" to s.view.scroll, "admission" to s.admissionAllowed,
                            "committedRecovery" to (w.rootPane.getClientProperty("editor.localRecovery") as? LocalRecoveryController)?.state(), "unfinished" to (w.rootPane.getClientProperty("editor.unfinishedRecovery") as? UnfinishedController)?.state(),
                            "imageHandles" to imageHandles(s)?.let { h -> mapOf("left" to listOf(h.left.x, h.left.y, h.left.width, h.left.height), "right" to listOf(h.right.x, h.right.y, h.right.width, h.right.height), "bounds" to listOf(h.bounds.x, h.bounds.y, h.bounds.width, h.bounds.height)) },
                            "pages" to g?.pageCount, "pagePlan" to g?.documentPagePlan?.let { p -> mapOf("pages" to p.pageCount, "width" to p.width, "height" to p.height, "fontKey" to p.typographyKey, "fragments" to p.fragments.map { listOf(it.paragraphIndex, it.firstLine, it.lastLine, it.page, it.top, it.height) }, "running" to p.running.map { mapOf("page" to it.page, "flow" to it.flowId, "text" to it.text, "top" to it.top) }, "notices" to p.relaxations) }, "editorBounds" to listOf(s.x, s.y, s.width, s.height), "surfaceScreen" to runCatching { s.locationOnScreen.let { listOf(it.x, it.y) } }.getOrNull(),
                        )
                    },
                ),
            )
        }
        return value
    }
    println("READY " + snapshot())
    System.out.flush()
    for (line in generateSequence(::readlnOrNull)) {
        try {
            when {
                line == "snapshot" -> println(snapshot())
                line.startsWith("select ") -> {
                    selected = line.substringAfter(' ').toInt()
                    println(snapshot())
                }
                line == "external-change" -> {
                    var p: Path? = null
                    SwingUtilities.invokeAndWait { p = editors().mapNotNull { windowFiles(it)?.path }.firstOrNull() }
                    val path = checkNotNull(p)
                    val e = EditorEngine(DocumentFiles.read(path))
                    e.move(Selection(0, 0))
                    e.replace("EXTERNAL VERSION — ", "ExternalFixture")
                    SafeDocumentIO.write(path, e.snapshot().committed, FileFingerprint.capture(path))
                    Files.copy(path, root.resolve("external-expected.astra"), StandardCopyOption.REPLACE_EXISTING)
                    println("EXTERNAL_CHANGED " + snapshot())
                }
                line == "seed-synthetic-unfinished" -> {
                    SwingUtilities.invokeAndWait {
                        val w = editors()[selected]
                        val s = findSurface(w)!!
                        val e = s.viewModel.verificationEngine
                        val n = e.snapshot().committed.units.size
                        e.move(Selection(n, n))
                        e.inputMethod("نامکمل کِ 👩🏽‍💻\ud800", 0, 3)
                        s.quarantinedCallbacks += QuarantinedCallback("unverified \ud800 callback", 0, 1, s.activeImeEpoch)
                        s.statusChanged(e.snapshot())
                        (w.rootPane.getClientProperty("editor.unfinishedRecovery") as UnfinishedController).flush()
                        (w.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController).checkpointNow()
                    }
                    println("SYNTHETIC_ONLY " + snapshot())
                }
                line == "seed-new-arrival" -> {
                    SwingUtilities.invokeAndWait {
                        val w = editors()[selected]
                        val s = findSurface(w)!!
                        s.quarantinedCallbacks += QuarantinedCallback("new arrival محفوظ", 0, null, s.activeImeEpoch)
                        s.statusChanged(s.viewModel.verificationEngine.snapshot())
                        (w.rootPane.getClientProperty("editor.unfinishedRecovery") as UnfinishedController).flush()
                    }
                    println("SYNTHETIC_NEW_ARRIVAL " + snapshot())
                }
                line == "shutdown" -> {
                    SwingUtilities.invokeAndWait { java.awt.Window.getWindows().filter { it.isDisplayable }.forEach { it.dispose() } }
                    println("BYE")
                    System.out.flush()
                    kotlin.system.exitProcess(0)
                }
                else -> error("Unsupported fixture command")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("FIXTURE_ERROR ${e.message}")
        }
        System.out.flush()
    }
    kotlin.system.exitProcess(1)
}
