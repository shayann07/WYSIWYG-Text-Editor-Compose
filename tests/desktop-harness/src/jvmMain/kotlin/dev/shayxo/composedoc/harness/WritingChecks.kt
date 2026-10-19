package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.workspace.WindowFiles
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.urduWritingSample
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import dev.shayxo.composedoc.workspace.windowFiles
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager
/** Real offscreen shell/render/file pipeline; key callbacks here are synthetic. */
fun runWritingChecks(result: File) {
    fun <T> owner(block: () -> T): T {
        var value: Result<T>? = null
        SwingUtilities.invokeAndWait { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
    fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!owner(predicate)) {
            check(System.nanoTime() < deadline) { "$label did not settle" }
            Thread.sleep(20)
        }
    }
    fun components(c: Component): List<Component> = listOf(c) + if (c is Container) c.components.flatMap(::components) else emptyList()
    val checks = ArrayList<String>()
    val owned = ArrayList<JFrame>()
    result.absoluteFile.parentFile.mkdirs()
    val directory = java.nio.file.Files.createTempDirectory(result.parentFile.toPath(), "writing-files-")
    val source = directory.resolve("disposable-urdu-writing-with-long-document-name-for-layout.astra")
    val sample = urduWritingSample()
    DocumentFiles.write(source, sample)
    lateinit var window: JFrame
    lateinit var surface: EditorSurface
    lateinit var files: WindowFiles
    fun key(code: Int, modifiers: Int = 0) {
        val event = KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED)
        surface.keyListeners.forEach { it.keyPressed(event) }
    }
    fun ready(): Boolean = surface.view.geometry?.revision == surface.viewModel.verificationEngine.snapshot().documentRevision && !surface.view.ensureCaret
    fun caretVisible() {
        val g = checkNotNull(surface.view.geometry)
        val frame = surface.viewModel.verificationEngine.snapshot()
        val caret = g.caret(frame.selection.focus, frame.selection.primary, frame.selection.wrapAffinity)
        check(caret.top >= 0 && caret.bottom <= surface.height * g.scale) { "Active caret outside viewport: $caret / ${surface.height * g.scale}" }
    }
    fun capture(name: String) {
        val bitmap = java.awt.image.BufferedImage(window.width, window.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        bitmap.createGraphics().let { g ->
            try {
                window.paint(g)
            } finally {
                g.dispose()
            }
        }
        javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, name))
    }
    try {
        owner {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            window = showEditor(initial = sample, initialPath = source, fontProfile = "noto-nastaliq", paged = true, offscreenTest = true)
            owned += window
            surface = checkNotNull(windowCurrentSurface(window))
            files = checkNotNull(windowFiles(window))
        }
        await("Initial multipage layout", ::ready)
        owner {
            check(surface.view.typography.id == "noto-nastaliq" && checkNotNull(surface.view.geometry).pageCount >= 3)
            check(sample.units.any { it.char == '\n' && it.kind == UnitKind.TEXT } && sample.units.any { it.kind == UnitKind.PARAGRAPH_BREAK })
            checks += "Pinned Noto Nastaliq sample renders at least three pages with mixed scripts, styles and hard/soft breaks"
            for ((width, height) in listOf(940 to 400, 1020 to 700)) {
                window.setSize(width, height)
                window.validate()
                val buttons = components(window).filterIsInstance<JButton>().filter { it.isShowing }
                check(buttons.any { it.text == "Save As…" })
                buttons.forEach { b ->
                    check(b.width >= b.minimumSize.width && b.height >= b.minimumSize.height)
                    val p = checkNotNull(b.parent)
                    check(b.x >= 0 && b.x + b.width <= p.width)
                }
                capture("writing-${width}x$height.png")
            }
            key(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK)
        }
        await("End caret scrolling", ::ready)
        owner {
            check(surface.viewModel.verificationEngine.snapshot().selection.focus == sample.units.size && surface.view.scroll > 0)
            caretVisible()
            key(KeyEvent.VK_ENTER)
            key(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
            surface.viewModel.verificationEngine.replace("کتاب English 123", "WritingReplacement")
        }
        await("Typing at document end", ::ready)
        owner {
            caretVisible()
            val before = surface.viewModel.verificationEngine.snapshot()
            val start = before.visible.text.lastIndexOf("English")
            surface.viewModel.verificationEngine.move(Selection(start, start + 7))
            components(window).filterIsInstance<JButton>().single { it.text == "Bold" }.doClick()
            key(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)
            val formatted = surface.viewModel.verificationEngine.snapshot()
            check(formatted.committed.units.subList(start, start + 7).all { it.style.bold && it.style.italic })
            key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)
            key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)
            check(surface.viewModel.verificationEngine.snapshot().committed == before.committed)
            key(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)
            key(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)
            check(surface.viewModel.verificationEngine.snapshot().committed == formatted.committed)
            surface.viewModel.verificationEngine.replace("notes", "ReplaceSelected")
            val replaced = surface.viewModel.verificationEngine.snapshot()
            check(replaced.committed.text == formatted.committed.text.take(start) + "notes" + formatted.committed.text.drop(start + 7))
            key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)
            check(surface.viewModel.verificationEngine.snapshot().committed == formatted.committed)
            key(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)
            check(surface.viewModel.verificationEngine.snapshot().committed == replaced.committed)
            checks += "End navigation/typing scroll the caret into view; selection replacement and bold/italic have exact Undo/Redo"
            val toggle = components(window).filterIsInstance<JCheckBox>().single { it.name == "page-mode" }
            val beforeToggle = surface.viewModel.verificationEngine.snapshot()
            toggle.doClick()
            check(!surface.view.paged && surface.viewModel.verificationEngine.snapshot() == beforeToggle)
        }
        await("Continuous layout", ::ready)
        owner {
            caretVisible()
            val frame = surface.viewModel.verificationEngine.snapshot()
            // Wheel browsing is independent of the caret; the next edit must reveal it again.
            surface.mouseWheelListeners.forEach {
                it.mouseWheelMoved(
                    MouseWheelEvent(
                        surface, MouseWheelEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), 0, 200, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -100,
                    ),
                )
            }
            check(surface.viewModel.verificationEngine.snapshot() == frame)
        }
        await("Wheel scroll", { surface.view.geometry?.scroll == surface.view.scroll })
        owner { surface.viewModel.verificationEngine.replace("۔", "TypingAfterScroll") }
        await("Caret after browsing", ::ready)
        lateinit var saved: Document
        owner {
            caretVisible()
            saved = surface.viewModel.verificationEngine.snapshot().committed
            files.save()
        }
        await("Actual shell save", { !files.busy })
        owner {
            check(!files.dirty && DocumentFiles.read(source).same(saved))
            val prior = JFrame.getFrames().toSet()
            files.openInNewWindow(DocumentFiles.read(source), source)
            val reopened = JFrame.getFrames().filterIsInstance<JFrame>().single { it !in prior }
            owned += reopened
            val reopenedSurface = checkNotNull(windowCurrentSurface(reopened))
            check(reopenedSurface.viewModel.verificationEngine.snapshot().committed.same(saved) && reopenedSurface.view.typography.id == "noto-nastaliq" && !reopenedSurface.view.paged)
            check(checkNotNull(windowFiles(reopened)).dirty.not())
            files.close()
            check(!window.isDisplayable)
            checks += "Continuous/paged presentation leaves content/history unchanged; wheel browsing followed by typing reveals the caret"
            checks += "Actual asynchronous Save and shell reopen preserve exact supported raw text/styles/start style/hard-soft breaks and current presentation profile"
            window = reopened
            surface = reopenedSurface
            files = checkNotNull(windowFiles(window))
            surface.viewModel.verificationEngine.replace("بدلا", "UnsavedPrompt")
            var prompts = 0
            files.promptCloseChoice = {
                prompts++
                2
            }
            files.close()
            check(prompts == 1 && window.isDisplayable && files.dirty)
            checks += "Unsaved changes consult Cancel and keep the document open"
        }
        await("Reopened layout", ::ready)
        owner { capture("writing-reopened.png") }
        result.writeText(
            evidenceJson(
                mapOf(
                    "status" to "PASS",
                    "checks" to checks,
                    "sampleUnits" to sample.units.size,
                    "scope" to "Actual offscreen shell/render/IO; synthetic key callbacks and model replacement, not real keyboard qualification",
                ),
            ),
            Charsets.UTF_8,
        )
        println("PASS Urdu writing workflow checks")
    } finally {
        owner { owned.forEach { if (it.isDisplayable) it.dispose() } }
    }
}
