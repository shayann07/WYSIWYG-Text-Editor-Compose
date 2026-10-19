package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
import java.io.File
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.*

fun crashSpecimen(tag: String): Document {
    val text = "کتاب $tag\nکِتاب\nEnglish 123 \uD800\uFFFF"
    val hard = text.indexOf('\n')
    return Document(
        text.mapIndexed { i, c ->
            TextUnit(
                i + 1L,
                c,
                if (i == hard) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                if (i == hard) TextStyleFlags() else TextStyleFlags(i % 2 == 0, i % 2 == 1, true, true, StoredFont.GULZAR, 32, 0x174ea6),
                if (i == hard) TextStyleFlags(true, true, strike = true, font = StoredFont.SYSTEM, size = 18, color = 0xb3261e) else TextStyleFlags(),
                if (i == hard) ParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.END, 24, 12, 1500) else ParagraphProperties(),
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
                if (tag == "window-A") checkNotNull(windowCurrentSurface(window)).engine.inputMethod("未確定", 0, 3)
            }
        }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (controllers.any { it.state()["completed"] != 1L }) {
            check(System.nanoTime() < deadline)
            Thread.sleep(10)
        }
        println("MULTI_READY:" + evidenceJson(controllers.mapIndexed { i, c -> mapOf("session" to c.session, "tag" to "window-${if (i == 0) "A" else "B"}") }))
        System.out.flush()
        checkNotNull(readlnOrNull())
        error("Expected owned parent kill")
    }
    val session = checkNotNull(args.firstOrNull { it.startsWith("--copy-session=") }).substringAfter('=')
    fun specimen(tag: String): Document {
        if ("--structured-specimen" !in args)return crashSpecimen(tag)
        val e = EditorEngine(structuredWritingSample())
        val end = e.snapshot().committed.units.size
        e.move(Selection(end, end))
        e.replace(tag, "fixture")
        return e.snapshot().committed
    }
    val original = args.firstOrNull { it.startsWith("--original=") }?.substringAfter('=')
    if ("--scan-copy" in args) {
        val store = LocalRecoveryStore(root)
        val entry = store.scan().single { it.session == session }
        val copy = store.restore(entry)
        val out = Path.of(checkNotNull(args.firstOrNull { it.startsWith("--copy-result=") }).substringAfter('='))
        Files.write(out, DocumentFiles.encode(copy.document))
        println(evidenceJson(mapOf("sequence" to copy.sequence, "original" to copy.originalPath, "warning" to entry.warning, "session" to session)))
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
        store.publish(lease, LocalCopy(session, 1, System.currentTimeMillis(), original, specimen("old")))
        armed = true
        store.publish(lease, LocalCopy(session, 2, System.currentTimeMillis(), original, specimen("new")))
    }
}

fun runLocalRecoveryChecks(result: File) {
    fun <T> owner(action: () -> T): T {
        var v: Result<T>? = null
        SwingUtilities.invokeAndWait { v = runCatching(action) }
        return checkNotNull(v).getOrThrow()
    }
    fun await(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "$label timed out" }
            Thread.sleep(10)
        }
    }
    val root = Files.createTempDirectory(result.absoluteFile.parentFile.toPath(), "local-recovery-")
    val store = LocalRecoveryStore(root)
    val checks = ArrayList<String>()
    val id = UUID.randomUUID().toString()
    val old = crashSpecimen("old")
    val newer = crashSpecimen("new")
    store.acquire(id).use { lease ->
        check(store.publish(lease, LocalCopy(id, 1, 1000, null, old)))
        check(store.scan().isEmpty())
        check(store.publish(lease, LocalCopy(id, 2, 2000, "/missing/original.astra", newer)))
        check(!store.publish(lease, LocalCopy(id, 1, 1000, null, old)))
    }
    var entry = store.scan().single()
    check(store.restore(entry).document.same(newer))
    checks += "Committed UTF-16/styles/breaks/start style roundtrip, active leases hidden, stale writes refuse"
    Files.write(root.resolve(id).resolve("copy-0.bin"), byteArrayOf(1, 2))
    entry = store.scan().single()
    check(entry.warning != null && store.restore(entry).document.same(old))
    val good = Files.readAllBytes(root.resolve(id).resolve("copy-1.bin"))
    Files.write(root.resolve(id).resolve("copy-1.bin"), byteArrayOf(4))
    check(runCatching { store.restore(entry) }.isFailure && Files.exists(root.resolve(id).resolve("copy-1.bin")))
    Files.write(root.resolve(id).resolve("copy-1.bin"), good)
    checks += "Truncated latest falls back; failed restore retains corrupt and valid record files"
    store.discard(store.scan().single())
    check(store.scan().isEmpty())
    checks += "Explicit discard tombstones the selected session and its fallback"

    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slowStore = LocalRecoveryStore(root) { point ->
        if (point == "before-publish" && started.count > 0) {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
    }
    val slow = owner {
        LocalRecoveryController(slowStore, {}, interval = 60000).also {
            it.observe(old, null)
            it.checkpointNow()
        }
    }
    check(started.await(10, TimeUnit.SECONDS))
    owner {
        repeat(200) {
            slow.observe(crashSpecimen("rapid-$it"), null)
            slow.checkpointNow()
        }
    }
    check(slow.state()["pending"] == true && slow.maximumPending == 1)
    release.countDown()
    await("Coalesced latest copy") { slow.state()["completed"] == 201L && slow.state()["running"] == false }
    owner { slow.finish(false) }
    await("Lease release") { store.scan().any { it.session == slow.session } }
    check(store.restore(store.scan().single { it.session == slow.session }).document.same(crashSpecimen("rapid-199")))
    checks += "Slow write plus 200 edits coalesces one pending snapshot; old completion cannot replace newest success"
    var fail = true
    val retry = owner {
        LocalRecoveryController(LocalRecoveryStore(root) { if (it == "before-publish" && fail) throw java.io.IOException("Injected write failure") }, {}, 60000)
            .also {
                it.observe(newer, null)
                it.checkpointNow()
            }
    }
    await("Write failure") { retry.state()["failure"] != null }
    check(retry.state()["completed"] == 0L)
    fail = false
    owner { retry.checkpointNow() }
    await("Retry success") { retry.state()["completed"] == 1L }
    owner { retry.finish(true) }
    await("Retry cleanup") { retry.state()["running"] == false }
    check(store.scan().none { it.session == retry.session })
    checks += "Write failure reports no success; retry publishes exactly and normal cleanup discards only own copy"

    val windows = ArrayList<JFrame>()
    try {
        val original = root.resolve("user-original.astra")
        DocumentFiles.write(original, old)
        lateinit var window: JFrame
        lateinit var surface: EditorSurface
        lateinit var controller: LocalRecoveryController
        lateinit var files: WindowFiles
        owner {
            window = showEditor(initial = old, initialPath = original, offscreenTest = true, fontProfile = "noto-nastaliq", paged = true, recoveryDirectory = root)
            windows += window
            surface = checkNotNull(windowCurrentSurface(window))
            files = checkNotNull(windowFiles(window))
            controller = window.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
            for ((width, height) in listOf(940 to 400, 1020 to 700)) {
                window.setSize(width, height)
                window.validate()
                fun all(c: java.awt.Component): List<java.awt.Component> = listOf(c) + if (c is java.awt.Container) c.components.flatMap(::all) else emptyList()
                val recoveryButton = all(window).filterIsInstance<JButton>().single { it.text == "Recovery Copies…" }
                val row = recoveryButton.parent
                check(row.components.filter { it.isVisible }.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= row.width && it.y + it.height <= row.height }) { "Writing/recovery row clipped at $width x $height" }
                val bitmap = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                bitmap.createGraphics().let { g ->
                    try {
                        window.paint(g)
                    } finally {
                        g.dispose()
                    }
                }
                javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "local-recovery-ui-${width}x$height.png"))
            }
            surface.engine.move(Selection(0, 4))
            surface.engine.inputMethod("پیش منظر", 0, 8)
        }
        // Real repeating schedule, no explicit checkpoint request here.
        await("Automatic committed checkpoint during composition") { controller.state()["completed"] == 1L }
        owner { check(surface.engine.snapshot().marked != null && surface.engine.snapshot().visible != surface.engine.snapshot().committed) }
        check(DocumentFiles.read(original).same(old))
        lateinit var acceptedPartial: Document
        owner {
            surface.engine.inputMethod("ن候補", 1, 2)
            acceptedPartial = surface.engine.snapshot().committed
            check(surface.engine.snapshot().marked != null && !acceptedPartial.same(old))
        }
        await("Accepted partial commit while candidate remains") { controller.state()["completed"] == 2L }
        owner {
            surface.engine.abortComposition()
            controller.finish(false)
            window.dispose()
        }
        await("Composition copy unlocked") { store.scan().any { it.session == controller.session } }
        check(store.restore(store.scan().single { it.session == controller.session }).document.same(acceptedPartial))
        checks += "Actual shell timer protects initial and accepted partial committed roots while IME preedit remains visible; named original stays unchanged"

        lateinit var second: JFrame
        lateinit var c2: LocalRecoveryController
        lateinit var s2: EditorSurface
        lateinit var f2: WindowFiles
        val saveStarted = CountDownLatch(1)
        val saveRelease = CountDownLatch(1)
        val separate = root.resolve("save-as.astra")
        owner {
            second = showEditor(initial = old, initialPath = original, offscreenTest = true, recoveryDirectory = root)
            windows += second
            s2 = checkNotNull(windowCurrentSurface(second))
            f2 = checkNotNull(windowFiles(second))
            c2 = second.rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController
            f2.saveDestinationPicker = { separate }
            f2.beforeSaveWriteForChecks = {
                saveStarted.countDown()
                check(saveRelease.await(10, TimeUnit.SECONDS))
            }
            f2.save(asNew = true)
        }
        check(saveStarted.await(10, TimeUnit.SECONDS))
        owner {
            s2.engine.replace("بعد", "AfterSaveCapture")
            c2.checkpointNow()
        }
        saveRelease.countDown()
        await("Save As complete") { owner { !f2.busy } }
        lateinit var afterSave: Document
        owner {
            afterSave = s2.engine.snapshot().committed
            check(f2.dirty && f2.path == separate)
            c2.checkpointNow()
        }
        await("Newer copy after save completion") { c2.state()["completed"] == c2.state()["observed"] && c2.state()["running"] == false }
        check(DocumentFiles.read(separate).same(old) && DocumentFiles.read(original).same(old))
        // Save now refuses an external change without touching that original.
        DocumentFiles.write(separate, newer)
        val saveFailures = ArrayList<Throwable>()
        owner {
            f2.beforeSaveWriteForChecks = {}
            f2.reportFailureForChecks = { saveFailures += it }
            f2.save()
        }
        await("External change refusal") { owner { !f2.busy } }
        owner {
            check(saveFailures.size == 1 && f2.dirty)
            c2.finish(false)
            second.dispose()
        }
        await("Saved-copy lease released") { store.scan().any { it.session == c2.session } }
        val savedEntry = store.scan().single { it.session == c2.session }
        val savedCopy = store.restore(savedEntry)
        check(savedCopy.document.same(afterSave) && savedCopy.originalPath == separate.toAbsolutePath().toString() && DocumentFiles.read(separate).same(newer))
        checks += "Slow Save As followed by new edits retains dirty/newer recovery; external file change refuses Save and never overwrites original"

        lateinit var chooser: JFrame
        owner {
            chooser = showLocalRecovery(root, "noto-nastaliq", true, offscreenTest = true, confirmDiscardForChecks = { true })
            windows += chooser
        }
        fun all(c: java.awt.Component): List<java.awt.Component> = listOf(c) + if (c is java.awt.Container) c.components.flatMap(::all) else emptyList()
        await("Recovery list scan") { owner { all(chooser).filterIsInstance<JList<*>>().single().model.size >= 3 } }
        var restored: JFrame? = null
        owner {
            val list = all(chooser).filterIsInstance<JList<LocalRecoveryEntry>>().single()
            list.selectedIndex = (0 until list.model.size).single { list.model.getElementAt(it).session == c2.session }
            val previous = JFrame.getFrames().toSet()
            all(chooser).filterIsInstance<JButton>().single { it.text == "Restore Copy" }.doClick()
            chooser.rootPane.putClientProperty("test.previousWindows", previous)
        }
        await("Actual Restore action") {
            owner {
                @Suppress("UNCHECKED_CAST")
                val previous = chooser.rootPane.getClientProperty("test.previousWindows") as Set<java.awt.Frame>
                restored = JFrame.getFrames().filterIsInstance<JFrame>().firstOrNull { it !in previous && windowFiles(it)?.dirty == true }
                restored != null
            }
        }
        await("Recovery chooser controls enabled after restore worker") {
            owner { all(chooser).filterIsInstance<JButton>().single { it.text == "Discard Copy…" }.isEnabled }
        }
        owner {
            val w = checkNotNull(restored)
            windows += w
            val restoredFiles = checkNotNull(windowFiles(w))
            check(restoredFiles.path == null && restoredFiles.dirty)
            check(checkNotNull(windowCurrentSurface(w)).engine.snapshot().committed.same(afterSave))
            restoredFiles.close(2)
            check(w.isDisplayable)
            restoredFiles.close(1)
            check(!w.isDisplayable)
            val discardButton = all(chooser).filterIsInstance<JButton>().single { it.text == "Discard Copy…" }
            check(discardButton.isEnabled)
            discardButton.doClick()
        }
        // Observe the completed UI worker before acquiring filesystem scan leases;
        // polling scan() can itself contend with the owned Discard action's tryLock.
        await("Explicit chooser discard UI completion") {
            owner {
                val list = all(chooser).filterIsInstance<JList<LocalRecoveryEntry>>().single()
                (0 until list.model.size).none { list.model.getElementAt(it).session == c2.session }
            }
        }
        check(store.scan().none { it.session == c2.session })
        check(DocumentFiles.read(separate).same(newer))
        checks += "Visible chooser names/timestamps and real Restore action open separate dirty content; Cancel preserves it; explicit discard leaves changed original untouched"
        // Empty recovered deletion is still unsaved work and must prompt.
        owner {
            val empty = showEditor(offscreenTest = true, recoveryDirectory = root)
            windows += empty
            val f = checkNotNull(windowFiles(empty))
            f.markRestored("Empty deletion")
            check(f.dirty)
            f.close(2)
            check(empty.isDisplayable)
            f.close(1)
        }
        checks += "Empty restored documents remain unsaved and normal explicit close cleans their own recovery only"
    } finally {
        owner { windows.forEach { if (it.isDisplayable) it.dispose() } }
    }
    result.writeText(evidenceJson(mapOf("status" to "PASS", "checks" to checks, "scope" to "Actual store/controller/offscreen shell; synthetic IME only. Process kills and OS interaction separate.")), Charsets.UTF_8)
    println("PASS local committed recovery checks")
}
