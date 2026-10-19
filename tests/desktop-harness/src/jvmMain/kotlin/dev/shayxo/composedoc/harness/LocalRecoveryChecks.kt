package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.LocalCopy
import dev.shayxo.composedoc.data.LocalRecoveryEntry
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.workspace.LocalRecoveryController
import dev.shayxo.composedoc.workspace.WindowFiles
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.showLocalRecovery
import dev.shayxo.composedoc.workspace.structuredWritingSample
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import dev.shayxo.composedoc.workspace.windowFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.SwingUtilities
fun crashSpecimen(tag: String): Document {
    val text = "کتاب $tag\nکِتاب\nEnglish 123 \uD800\uFFFF"
    val hard = text.indexOf('\n')
    return Document(
        text.mapIndexed { i, c ->
            TextUnit(
                i + 1L,
                c,
                if (i == hard) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                if (i ==
                    hard
                ) {
                    TextStyleFlags()
                } else {
                    TextStyleFlags(i % 2 == 0, i % 2 == 1, true, true, StoredFont.GULZAR, 32, 0x174ea6)
                },
                if (i ==
                    hard
                ) {
                    TextStyleFlags(true, true, strike = true, font = StoredFont.SYSTEM, size = 18, color = 0xb3261e)
                } else {
                    TextStyleFlags()
                },
                if (i ==
                    hard
                ) {
                    ParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.END, 24, 12, 1500)
                } else {
                    ParagraphProperties()
                },
            )
        },
        TextStyleFlags(italic = true, underline = true, font = StoredFont.NOTO_NASTALIQ, size = 22, color = 0x146c43),
        ParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER, 8, 16, 1250),
    )
}

/** Child fault barriers are on the actual store write/publish path, never the oracle. */
fun runLocalRecoveryChild(args: Array<String>) {
    val root = Path.of(checkNotNull(args.firstOrNull { it.startsWith("--recovery-dir=") }).substringAfter('='))
    if ("--multi-window-copy" in args) {
        runMultiWindowCopyChild(root)
    }
    val session = checkNotNull(args.firstOrNull { it.startsWith("--copy-session=") }).substringAfter('=')
    val original = args.firstOrNull { it.startsWith("--original=") }?.substringAfter('=')
    if ("--scan-copy" in args) {
        runScanCopyChild(args, root, session)
        return
    }
    val phase = args.firstOrNull { it.startsWith("--interrupt-at=") }?.substringAfter('=')
    var armed = false
    val store = LocalRecoveryStore(root) { point ->
        if (armed && point == phase) {
            println("BARRIER:$point")
            System.out.flush()
            checkNotNull(readlnOrNull()) { "Parent ended barrier without a kill" }
        }
    }
    store.acquire(session).use { lease ->
        store.publish(
            lease,
            LocalCopy(session, 1, System.currentTimeMillis(), original, localRecoveryChildSpecimen(args, "old")),
        )
        armed = true
        store.publish(
            lease,
            LocalCopy(session, 2, System.currentTimeMillis(), original, localRecoveryChildSpecimen(args, "new")),
        )
    }
}

private fun localRecoveryChildSpecimen(args: Array<String>, tag: String): Document {
    if ("--structured-specimen" !in args)return crashSpecimen(tag)
    val e = EditorEngine(structuredWritingSample())
    val end = e.snapshot().committed.units.size
    e.move(Selection(end, end))
    e.replace(tag, "fixture")
    return e.snapshot().committed
}

private fun runMultiWindowCopyChild(root: Path): Nothing {
    Files.createDirectories(root)
    val original = root.resolve("named-original.astra")
    DocumentFiles.write(original, crashSpecimen("window-B"))
    val controllers = ArrayList<LocalRecoveryController>()
    val windows = ArrayList<JFrame>()
    SwingUtilities.invokeAndWait {
        for (tag in listOf("window-A", "window-B")) {
            val window = showEditor(
                initial = crashSpecimen(tag),
                initialPath = if (tag == "window-B") original else null,
                offscreenTest = true,
                recoveryDirectory = root,
            )
            windows += window
            controllers += window.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
            if (tag ==
                "window-A"
            ) {
                checkNotNull(windowCurrentSurface(window)).viewModel.verificationEngine.inputMethod("未確定", 0, 3)
            }
        }
    }
    val deadline = System.nanoTime() + 15_000_000_000L
    while (controllers.any { it.state()["completed"] != 1L }) {
        check(System.nanoTime() < deadline)
        Thread.sleep(10)
    }
    println(
        "MULTI_READY:" +
            evidenceJson(
                controllers.mapIndexed { i, c ->
                    mapOf(
                        "session" to c.session,
                        "tag" to "window-${if (i == 0) "A" else "B"}",
                    )
                },
            ),
    )
    System.out.flush()
    checkNotNull(readlnOrNull())
    error("Expected owned parent kill")
}

private fun runScanCopyChild(args: Array<String>, root: Path, session: String) {
    val store = LocalRecoveryStore(root)
    val entry = store.scan().single { it.session == session }
    val copy = store.restore(entry)
    val out = Path.of(checkNotNull(args.firstOrNull { it.startsWith("--copy-result=") }).substringAfter('='))
    Files.write(out, DocumentFiles.encode(copy.document))
    println(
        evidenceJson(
            mapOf(
                "sequence" to copy.sequence,
                "original" to copy.originalPath,
                "warning" to entry.warning,
                "session" to session,
            ),
        ),
    )
}

private fun <T> recoveryOwner(action: () -> T): T {
    var v: Result<T>? = null
    SwingUtilities.invokeAndWait { v = runCatching(action) }
    return checkNotNull(v).getOrThrow()
}

private fun recoveryAwait(label: String, condition: () -> Boolean) {
    val end = System.nanoTime() + 15_000_000_000L
    while (!condition()) {
        check(System.nanoTime() < end) { "$label timed out" }
        Thread.sleep(10)
    }
}

private fun localRecoveryComponents(c: java.awt.Component): List<java.awt.Component> =
    listOf(c) + if (c is java.awt.Container) c.components.flatMap(::localRecoveryComponents) else emptyList()

private class LocalRecoveryRun(
    val result: File,
    val root: Path,
    val store: LocalRecoveryStore,
    val old: Document,
    val newer: Document,
) {
    val checks = ArrayList<String>()
    val windows = ArrayList<JFrame>()
}

private class CompositionWindow(
    val window: JFrame,
    val surface: EditorSurface,
    val controller: LocalRecoveryController,
)

private class SaveAsWindow(
    val frame: JFrame,
    val controller: LocalRecoveryController,
    val surface: EditorSurface,
    val files: WindowFiles,
)

private class SavedAsCopy(val controller: LocalRecoveryController, val separate: Path, val afterSave: Document)

fun runLocalRecoveryChecks(result: File) {
    val root = Files.createTempDirectory(result.absoluteFile.parentFile.toPath(), "local-recovery-")
    val store = LocalRecoveryStore(root)
    val id = UUID.randomUUID().toString()
    val old = crashSpecimen("old")
    val newer = crashSpecimen("new")
    val run = LocalRecoveryRun(result, root, store, old, newer)
    checkLocalStoreRoundtrip(run, id)
    checkSlowWriteCoalesces(run)
    checkWriteFailureRetry(run)
    try {
        val original = root.resolve("user-original.astra")
        DocumentFiles.write(original, old)
        checkCompositionCheckpoints(run, original)
        val saved = checkSlowSaveAs(run, original)
        checkRecoveryChooser(run, saved)
        // Empty recovered deletion is still unsaved work and must prompt.
        recoveryOwner {
            val empty = showEditor(offscreenTest = true, recoveryDirectory = root)
            run.windows += empty
            val f = checkNotNull(windowFiles(empty))
            f.markRestored("Empty deletion")
            check(f.dirty)
            f.close(2)
            check(empty.isDisplayable)
            f.close(1)
        }
        run.checks += "Empty restored documents remain unsaved and normal explicit close cleans their own recovery only"
    } finally {
        recoveryOwner { run.windows.forEach { if (it.isDisplayable) it.dispose() } }
    }
    result.writeText(
        evidenceJson(
            mapOf(
                "status" to "PASS",
                "checks" to run.checks,
                "scope" to
                    "Actual store/controller/offscreen shell; synthetic IME only. " +
                    "Process kills and OS interaction separate.",
            ),
        ),
        Charsets.UTF_8,
    )
    println("PASS local committed recovery checks")
}

private fun checkLocalStoreRoundtrip(run: LocalRecoveryRun, id: String) {
    val root = run.root
    val store = run.store
    val old = run.old
    val newer = run.newer
    store.acquire(id).use { lease ->
        check(store.publish(lease, LocalCopy(id, 1, 1000, null, old)))
        check(store.scan().isEmpty())
        check(store.publish(lease, LocalCopy(id, 2, 2000, "/missing/original.astra", newer)))
        check(!store.publish(lease, LocalCopy(id, 1, 1000, null, old)))
    }
    var entry = store.scan().single()
    check(store.restore(entry).document.same(newer))
    run.checks += "Committed UTF-16/styles/breaks/start style roundtrip, active leases hidden, stale writes refuse"
    Files.write(root.resolve(id).resolve("copy-0.bin"), byteArrayOf(1, 2))
    entry = store.scan().single()
    check(entry.warning != null && store.restore(entry).document.same(old))
    val good = Files.readAllBytes(root.resolve(id).resolve("copy-1.bin"))
    Files.write(root.resolve(id).resolve("copy-1.bin"), byteArrayOf(4))
    check(runCatching { store.restore(entry) }.isFailure && Files.exists(root.resolve(id).resolve("copy-1.bin")))
    Files.write(root.resolve(id).resolve("copy-1.bin"), good)
    run.checks += "Truncated latest falls back; failed restore retains corrupt and valid record files"
    store.discard(store.scan().single())
    check(store.scan().isEmpty())
    run.checks += "Explicit discard tombstones the selected session and its fallback"
}

private fun checkSlowWriteCoalesces(run: LocalRecoveryRun) {
    val store = run.store
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slowStore = LocalRecoveryStore(run.root) { point ->
        if (point == "before-publish" && started.count > 0) {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
    }
    val slow = recoveryOwner {
        LocalRecoveryController(slowStore, {}, interval = 60000).also {
            it.observe(run.old, null)
            it.checkpointNow()
        }
    }
    check(started.await(10, TimeUnit.SECONDS))
    recoveryOwner {
        repeat(200) {
            slow.observe(crashSpecimen("rapid-$it"), null)
            slow.checkpointNow()
        }
    }
    check(slow.state()["pending"] == true && slow.maximumPending == 1)
    release.countDown()
    recoveryAwait("Coalesced latest copy") {
        slow.state()["completed"] == 201L && slow.state()["running"] == false
    }
    recoveryOwner { slow.finish(false) }
    recoveryAwait("Lease release") { store.scan().any { it.session == slow.session } }
    check(store.restore(store.scan().single { it.session == slow.session }).document.same(crashSpecimen("rapid-199")))
    run.checks +=
        "Slow write plus 200 edits coalesces one pending snapshot; old completion cannot replace newest success"
}

private fun checkWriteFailureRetry(run: LocalRecoveryRun) {
    var fail = true
    val retry = recoveryOwner {
        LocalRecoveryController(
            LocalRecoveryStore(run.root) {
                if (it == "before-publish" &&
                    fail
                ) {
                    throw java.io.IOException("Injected write failure")
                }
            },
            {},
            60000,
        )
            .also {
                it.observe(run.newer, null)
                it.checkpointNow()
            }
    }
    recoveryAwait("Write failure") { retry.state()["failure"] != null }
    check(retry.state()["completed"] == 0L)
    fail = false
    recoveryOwner { retry.checkpointNow() }
    recoveryAwait("Retry success") { retry.state()["completed"] == 1L }
    recoveryOwner { retry.finish(true) }
    recoveryAwait("Retry cleanup") { retry.state()["running"] == false }
    check(run.store.scan().none { it.session == retry.session })
    run.checks +=
        "Write failure reports no success; retry publishes exactly and normal cleanup discards only own copy"
}

private fun checkCompositionCheckpoints(run: LocalRecoveryRun, original: Path) {
    val opened = recoveryOwner { openCompositionWindow(run, original) }
    val surface = opened.surface
    val controller = opened.controller
    // Real repeating schedule, no explicit checkpoint request here.
    recoveryAwait("Automatic committed checkpoint during composition") { controller.state()["completed"] == 1L }
    recoveryOwner {
        check(
            surface.viewModel.verificationEngine.snapshot().marked != null &&
                surface.viewModel.verificationEngine.snapshot().visible !=
                surface.viewModel.verificationEngine.snapshot().committed,
        )
    }
    check(DocumentFiles.read(original).same(run.old))
    val acceptedPartial = recoveryOwner {
        surface.viewModel.verificationEngine.inputMethod("ن候補", 1, 2)
        val accepted = surface.viewModel.verificationEngine.snapshot().committed
        check(surface.viewModel.verificationEngine.snapshot().marked != null && !accepted.same(run.old))
        accepted
    }
    recoveryAwait("Accepted partial commit while candidate remains") { controller.state()["completed"] == 2L }
    recoveryOwner {
        surface.viewModel.verificationEngine.abortComposition()
        controller.finish(false)
        opened.window.dispose()
    }
    recoveryAwait("Composition copy unlocked") { run.store.scan().any { it.session == controller.session } }
    check(
        run.store.restore(run.store.scan().single { it.session == controller.session }).document.same(acceptedPartial),
    )
    run.checks +=
        "Actual shell timer protects initial and accepted partial committed roots while IME preedit " +
        "remains visible; named original stays unchanged"
}

/** Runs on the owner (EDT) thread. */
private fun openCompositionWindow(run: LocalRecoveryRun, original: Path): CompositionWindow {
    val window =
        showEditor(
            initial = run.old,
            initialPath = original,
            offscreenTest = true,
            fontProfile = "noto-nastaliq",
            paged = true,
            recoveryDirectory = run.root,
        )
    run.windows += window
    val surface = checkNotNull(windowCurrentSurface(window))
    checkNotNull(windowFiles(window))
    val controller = window.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
    for ((width, height) in listOf(940 to 400, 1020 to 700)) {
        checkRecoveryRowAndPaint(run.result, window, width, height)
    }
    surface.viewModel.verificationEngine.move(Selection(0, 4))
    surface.viewModel.verificationEngine.inputMethod("پیش منظر", 0, 8)
    return CompositionWindow(window, surface, controller)
}

private fun checkRecoveryRowAndPaint(result: File, window: JFrame, width: Int, height: Int) {
    window.setSize(width, height)
    window.validate()
    val recoveryButton =
        localRecoveryComponents(window).filterIsInstance<JButton>().single { it.text == "Recovery Copies…" }
    val row = recoveryButton.parent
    check(
        row.components.filter { it.isVisible }.all {
            it.x >= 0 &&
                it.y >= 0 &&
                it.x + it.width <= row.width &&
                it.y + it.height <= row.height
        },
    ) { "Writing/recovery row clipped at $width x $height" }
    val bitmap = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    bitmap.createGraphics().let { g ->
        try {
            window.paint(g)
        } finally {
            g.dispose()
        }
    }
    javax.imageio.ImageIO.write(
        bitmap,
        "png",
        File(result.parentFile, "local-recovery-ui-${width}x$height.png"),
    )
}

private fun checkSlowSaveAs(run: LocalRecoveryRun, original: Path): SavedAsCopy {
    val saveStarted = CountDownLatch(1)
    val saveRelease = CountDownLatch(1)
    val separate = run.root.resolve("save-as.astra")
    val w = recoveryOwner { openSlowSaveAsWindow(run, original, separate, saveStarted, saveRelease) }
    check(saveStarted.await(10, TimeUnit.SECONDS))
    recoveryOwner {
        w.surface.viewModel.verificationEngine.replace("بعد", "AfterSaveCapture")
        w.controller.checkpointNow()
    }
    saveRelease.countDown()
    recoveryAwait("Save As complete") { recoveryOwner { !w.files.busy } }
    val afterSave = recoveryOwner {
        val committed = w.surface.viewModel.verificationEngine.snapshot().committed
        check(w.files.dirty && w.files.path == separate)
        w.controller.checkpointNow()
        committed
    }
    recoveryAwait("Newer copy after save completion") {
        w.controller.state()["completed"] == w.controller.state()["observed"] &&
            w.controller.state()["running"] == false
    }
    check(DocumentFiles.read(separate).same(run.old) && DocumentFiles.read(original).same(run.old))
    checkExternalChangeRefusal(run, w, separate, afterSave)
    return SavedAsCopy(w.controller, separate, afterSave)
}

/** Runs on the owner (EDT) thread. */
private fun openSlowSaveAsWindow(
    run: LocalRecoveryRun,
    original: Path,
    separate: Path,
    saveStarted: CountDownLatch,
    saveRelease: CountDownLatch,
): SaveAsWindow {
    val second =
        showEditor(initial = run.old, initialPath = original, offscreenTest = true, recoveryDirectory = run.root)
    run.windows += second
    val s2 = checkNotNull(windowCurrentSurface(second))
    val f2 = checkNotNull(windowFiles(second))
    val c2 = second.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
    f2.saveDestinationPicker = { separate }
    f2.beforeSaveWriteForChecks = {
        saveStarted.countDown()
        check(saveRelease.await(10, TimeUnit.SECONDS))
    }
    f2.save(asNew = true)
    return SaveAsWindow(second, c2, s2, f2)
}

private fun checkExternalChangeRefusal(run: LocalRecoveryRun, w: SaveAsWindow, separate: Path, afterSave: Document) {
    // Save now refuses an external change without touching that original.
    DocumentFiles.write(separate, run.newer)
    val saveFailures = ArrayList<Throwable>()
    recoveryOwner {
        w.files.beforeSaveWriteForChecks = {}
        w.files.reportFailureForChecks = { saveFailures += it }
        w.files.save()
    }
    recoveryAwait("External change refusal") { recoveryOwner { !w.files.busy } }
    recoveryOwner {
        check(saveFailures.size == 1 && w.files.dirty)
        w.controller.finish(false)
        w.frame.dispose()
    }
    recoveryAwait("Saved-copy lease released") { run.store.scan().any { it.session == w.controller.session } }
    val savedEntry = run.store.scan().single { it.session == w.controller.session }
    val savedCopy = run.store.restore(savedEntry)
    check(
        savedCopy.document.same(afterSave) &&
            savedCopy.originalPath == separate.toAbsolutePath().toString() &&
            DocumentFiles.read(separate).same(run.newer),
    )
    run.checks +=
        "Slow Save As followed by new edits retains dirty/newer recovery; " +
        "external file change refuses Save and never overwrites original"
}

private fun checkRecoveryChooser(run: LocalRecoveryRun, saved: SavedAsCopy) {
    val c2 = saved.controller
    val chooser = recoveryOwner {
        val opened =
            showLocalRecovery(run.root, "noto-nastaliq", true, offscreenTest = true, confirmDiscardForChecks = { true })
        run.windows += opened
        opened
    }
    recoveryAwait("Recovery list scan") {
        recoveryOwner { localRecoveryComponents(chooser).filterIsInstance<JList<*>>().single().model.size >= 3 }
    }
    val restored = restoreChooserCopy(chooser, c2)
    recoveryOwner {
        val w = checkNotNull(restored)
        run.windows += w
        val restoredFiles = checkNotNull(windowFiles(w))
        check(restoredFiles.path == null && restoredFiles.dirty)
        check(
            checkNotNull(windowCurrentSurface(w)).viewModel.verificationEngine.snapshot().committed.same(
                saved.afterSave,
            ),
        )
        restoredFiles.close(2)
        check(w.isDisplayable)
        restoredFiles.close(1)
        check(!w.isDisplayable)
        val discardButton =
            localRecoveryComponents(chooser).filterIsInstance<JButton>().single { it.text == "Discard Copy…" }
        check(discardButton.isEnabled)
        discardButton.doClick()
    }
    // Observe the completed UI worker before acquiring filesystem scan leases;
    // polling scan() can itself contend with the owned Discard action's tryLock.
    recoveryAwait("Explicit chooser discard UI completion") {
        recoveryOwner {
            val list = localRecoveryComponents(chooser).filterIsInstance<JList<LocalRecoveryEntry>>().single()
            (0 until list.model.size).none { list.model.getElementAt(it).session == c2.session }
        }
    }
    check(run.store.scan().none { it.session == c2.session })
    check(DocumentFiles.read(saved.separate).same(run.newer))
    run.checks +=
        "Visible chooser names/timestamps and real Restore action open separate dirty content; " +
        "Cancel preserves it; explicit discard leaves changed original untouched"
}

private fun restoreChooserCopy(chooser: JFrame, c2: LocalRecoveryController): JFrame? {
    var restored: JFrame? = null
    recoveryOwner {
        val list = localRecoveryComponents(chooser).filterIsInstance<JList<LocalRecoveryEntry>>().single()
        list.selectedIndex = (0 until list.model.size).single { list.model.getElementAt(it).session == c2.session }
        val previous = JFrame.getFrames().toSet()
        localRecoveryComponents(chooser).filterIsInstance<JButton>().single { it.text == "Restore Copy" }.doClick()
        chooser.rootPane.putClientProperty("test.previousWindows", previous)
    }
    recoveryAwait("Actual Restore action") {
        recoveryOwner {
            @Suppress("UNCHECKED_CAST")
            val previous = chooser.rootPane.getClientProperty("test.previousWindows") as Set<java.awt.Frame>
            restored =
                JFrame.getFrames().filterIsInstance<JFrame>().firstOrNull {
                    it !in previous &&
                        windowFiles(it)?.dirty == true
                }
            restored != null
        }
    }
    recoveryAwait("Recovery chooser controls enabled after restore worker") {
        recoveryOwner {
            localRecoveryComponents(chooser).filterIsInstance<JButton>().single { it.text == "Discard Copy…" }.isEnabled
        }
    }
    return restored
}
