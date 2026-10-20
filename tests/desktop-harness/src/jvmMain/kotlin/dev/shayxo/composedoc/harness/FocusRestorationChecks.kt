package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.ViewState
import dev.shayxo.composedoc.workspace.WindowFiles
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities

private const val FOCUS_WAIT_SECONDS = 10L

/** Simulated native focus state, with real WindowFiles worker paths and observable focus requests. */
fun runFocusRestorationChecks(result: File) {
    val originalManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val originalLockRoot = System.getProperty("astra.lockRoot")
    val root = Files.createTempDirectory("composedoc-file-focus-")
    val manager = VacantFocusManager()
    val groups = ArrayList<String>()
    System.setProperty("astra.lockRoot", root.resolve("locks").toString())
    KeyboardFocusManager.setCurrentKeyboardFocusManager(manager)
    try {
        val harness = FileFocusHarness(root, manager)
        try {
            fun test(name: String, action: () -> Unit) {
                action()
                groups += name
                println("PASS file focus $name")
            }
            test("canceled Save As restores a vacant active document asynchronously") { harness.cancel() }
            test("successful two-stage save restores only after publication and preserves history") { harness.save() }
            test("failed save reports failure and restores the unsaved document") { harness.failure() }
            test("an already focused control retains focus") { harness.occupiedControl() }
            test("a newly active window is not displaced") { harness.inactiveWindow() }
            test("disposed owner receives no focus request") { harness.disposedWindow() }
            test("a visible owned modal prevents restoration") { harness.modalWindow() }
            test("deferred restoration resolves the current surface, not a stale captured one") {
                harness.replacedSurface()
            }
        } finally {
            harness.dispose()
        }
    } finally {
        KeyboardFocusManager.setCurrentKeyboardFocusManager(originalManager)
        if (originalLockRoot == null) {
            System.clearProperty("astra.lockRoot")
        } else {
            System.setProperty("astra.lockRoot", originalLockRoot)
        }
    }
    result.parentFile.mkdirs()
    result.writeText(
        evidenceJson(
            mapOf(
                "status" to "PASS",
                "groups" to groups,
                "evidenceLevel" to "Synthetic AWT active/focus/displayable states; actual Save workers and bytes; " +
                    "not native key-event or OS focus qualification",
            ),
        ),
        Charsets.UTF_8,
    )
}

private class VacantFocusManager : DefaultKeyboardFocusManager() {
    var simulatedOwner: Component? = null
    override fun getFocusOwner(): Component? = simulatedOwner
}

private class FocusWindow : JFrame() {
    var simulatedActive = true
    var simulatedDisplayable = true
    override fun isActive() = simulatedActive
    override fun isDisplayable() = simulatedDisplayable
}

private class FocusSurface(viewModel: EditorViewModel) : EditorSurface(viewModel, ViewState(viewModel.snapshot())) {
    var requests = 0
    override fun requestFocusInWindow(): Boolean {
        requests++
        return true
    }
}

private class FocusDialog(window: JFrame) : JDialog(window) {
    var simulatedVisible = false
    override fun isVisible() = simulatedVisible
}

private class FileFocusHarness(private val root: Path, private val manager: VacantFocusManager) {
    private lateinit var window: FocusWindow
    private lateinit var viewModel: EditorViewModel
    private lateinit var surface: FocusSurface
    private lateinit var currentSurface: FocusSurface
    private lateinit var files: WindowFiles
    private val failures = ArrayList<Throwable>()

    init {
        edt {
            window = FocusWindow()
            viewModel = EditorViewModel()
            viewModel.dispatch(EditorIntent.Type("اردو English کِ"))
            surface = FocusSurface(viewModel)
            currentSurface = surface
            window.rootPane.putClientProperty("editor.surfaceGetter", { currentSurface })
            files = WindowFiles(window, viewModel, null, refresh = {})
            files.markUnsavedCopy("Focus fixture")
            files.reportFailureForChecks = { failures += it }
        }
    }

    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FOCUS_WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            var completed = false
            edt { completed = predicate() }
            if (completed) return
            Thread.sleep(10)
        }
        error("Timed out waiting for actual file worker and deferred focus completion")
    }

    fun cancel() {
        edt {
            files.saveDestinationPicker = { null }
            val before = viewModel.snapshot()
            files.save(asNew = true)
            check(surface.requests == 0)
            check(viewModel.snapshot() == before && files.dirty && !files.busy)
        }
        edt { check(surface.requests == 1) }
    }

    fun save() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = root.resolve("accepted.astra")
        lateinit var captured: dev.shayxo.composedoc.domain.EditorFrame
        edt {
            surface.requests = 0
            files.saveDestinationPicker = { target }
            files.beforeSaveWriteForChecks = {
                entered.countDown()
                check(release.await(FOCUS_WAIT_SECONDS, TimeUnit.SECONDS))
            }
            captured = viewModel.snapshot()
            files.save(asNew = true)
        }
        try {
            check(entered.await(FOCUS_WAIT_SECONDS, TimeUnit.SECONDS))
            edt { check(files.busy && surface.requests == 0) }
        } finally {
            release.countDown()
        }
        await { !files.busy && !files.dirty && surface.requests == 1 }
        edt { check(viewModel.snapshot() == captured && failures.isEmpty()) }
        check(DocumentFiles.read(target).same(captured.committed))
    }

    fun failure() {
        Files.write(root.resolve("blocked-parent"), byteArrayOf(1))
        edt {
            surface.requests = 0
            files.beforeSaveWriteForChecks = {}
            viewModel.dispatch(EditorIntent.Type(" مزید"))
            files.saveDestinationPicker = { root.resolve("blocked-parent/failure.astra") }
            files.save(asNew = true)
        }
        await { !files.busy && failures.size == 1 && surface.requests == 1 }
        edt { check(files.dirty && files.path == root.resolve("accepted.astra")) }
        check(!Files.exists(root.resolve("blocked-parent/failure.astra")))
    }

    private fun cancelWithState(change: () -> Unit) {
        edt {
            surface.requests = 0
            files.saveDestinationPicker = { null }
            files.save(asNew = true)
            change()
        }
        edt { check(surface.requests == 0) }
    }

    fun occupiedControl() {
        cancelWithState { manager.simulatedOwner = JTextField("Find") }
        edt { manager.simulatedOwner = null }
    }

    fun inactiveWindow() {
        cancelWithState { window.simulatedActive = false }
        edt { window.simulatedActive = true }
    }

    fun disposedWindow() {
        cancelWithState { window.simulatedDisplayable = false }
        edt { window.simulatedDisplayable = true }
    }

    fun modalWindow() {
        lateinit var dialog: FocusDialog
        edt {
            dialog = FocusDialog(window)
            dialog.isModal = true
            dialog.simulatedVisible = true
        }
        try {
            cancelWithState {}
        } finally {
            edt {
                dialog.simulatedVisible = false
                dialog.dispose()
            }
        }
    }

    fun replacedSurface() {
        lateinit var replacement: FocusSurface
        edt {
            surface.requests = 0
            files.saveDestinationPicker = { null }
            files.save(asNew = true)
            replacement = FocusSurface(viewModel)
            currentSurface = replacement
        }
        edt { check(surface.requests == 0 && replacement.requests == 1) }
    }

    fun dispose() {
        edt {
            window.simulatedDisplayable = false
            window.dispose()
        }
    }
}
