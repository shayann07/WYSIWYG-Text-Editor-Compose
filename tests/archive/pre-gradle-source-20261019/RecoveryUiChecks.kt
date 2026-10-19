package editor

import java.io.File
import java.awt.Component
import java.awt.Container
import java.awt.event.InputMethodEvent
import java.awt.font.TextHitInfo
import java.text.AttributedString
import javax.swing.SwingUtilities

/** Own offscreen shell and synthetic input; no desktop control or native IME qualification. */
fun runRecoveryUiChecks(result: File) {
    check(!SwingUtilities.isEventDispatchThread())
    lateinit var window: javax.swing.JFrame
    var created = false
    val checks = ArrayList<String>()
    fun <T> onOwner(block: () -> T): T {
        var value: Result<T>? = null
        SwingUtilities.invokeAndWait { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
    result.absoluteFile.parentFile.mkdirs()
    val work = java.nio.file.Files.createTempDirectory(result.absoluteFile.parentFile.toPath(), "recovery-ui-files-")
    val originalPath = work.resolve("original.astra")
    DocumentFiles.write(originalPath, Document(emptyList()))
    lateinit var files: WindowFiles
    fun awaitFiles() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (onOwner { files.busy }) {
            check(System.nanoTime() < deadline) { "The specific recovery file worker did not finish" }
            Thread.sleep(10)
        }
    }
    fun controls(root: Component): List<Component> = listOf(root) + if (root is Container) root.components.flatMap(::controls) else emptyList()
    fun button(text: String) = controls(window).filterIsInstance<javax.swing.JButton>().single { it.text == text }
    fun input(surface: EditorSurface, text: String) = surface.dispatchEvent(InputMethodEvent(surface,
        InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, AttributedString(text).iterator, 0, TextHitInfo.leading(text.length), null))
    try {
        onOwner {
            runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
            window = showEditor(offscreenTest = true, initialPath = originalPath)
            created = true
            val first = checkNotNull(findSurface(window)); input(first, "پہلا")
            val second = checkNotNull(first.abortAndRetireComposition(true)); check(second.admissionAllowed)
            val old = checkNotNull(second.retainedPreimage)
            input(second, "دوسرا")
            val third = checkNotNull(second.abortAndRetireComposition(true))
            val pending = third.recoverySession.snapshotRetainedDrafts()
            check(pending.size == 2 && pending.any { it === old }) { "A second admitted recovery replaced pending work: retained=${pending.size}" }
            checks += "Both admitted recovery capsules remain accessible"
            files = window.rootPane.getClientProperty("editor.files") as WindowFiles
            var prompts = 0; files.promptCloseChoice = { prompts++; 2 }
            check(!files.dirty && third.engine.snapshot().marked == null)
            files.close()
            check(prompts == 1 && window.isDisplayable && third.recoverySession.snapshotRetainedDrafts().size == 2) { "Clean-document close discarded pending recovery without consulting Cancel" }
            checks += "Clean-document close consults Cancel and retains both capsules"
            files.close(0); check(window.isDisplayable && !files.busy)
            files.save(closeAfter = true)
        }
        awaitFiles()
        onOwner {
            val surface = checkNotNull(findSurface(window))
            check(window.isDisplayable && surface.recoverySession.snapshotRetainedDrafts().size == 2)
            check(DocumentFiles.read(originalPath).text.isEmpty())
            checks += "Saving accepted content cannot close over pending recovery"
            val first = surface.recoverySession.snapshotRetainedDrafts().first()
            val session = RecoverySession(first)
            val metadataCopy = first.copy(selectionBefore = first.selectionBefore.copy(primary = !first.selectionBefore.primary))
            session.retainedPreimage = metadataCopy; check(session.snapshotRetainedDrafts().size == 1)
            val sameTextDifferentRecord = RetainedCompositionPreimage(first.draftText, first.selectionBefore, first.markedRangeBefore, first.committedTextBefore, first.epoch, first.recoveryDraft)
            val incoming = RecoverySession(sameTextDifferentRecord)
            session.mergeRetainedDrafts(incoming)
            check(session.snapshotRetainedDrafts().size == 2 && session.retainedPreimage === metadataCopy && incoming.retainedPreimage === sameTextDifferentRecord)
            checks += "Copies update one logical record; equal text in distinct records and session adoption do not deduplicate or consume work"
            val e = surface.engine
            e.replace("X", "AcceptedBasisChange"); e.perform(EditAction.Undo)
            check(e.snapshot().committed.text.isEmpty()) // Actual intervening accepted work makes the older records stale.
        }
        lateinit var richRecord: RetainedCompositionPreimage
        lateinit var desired: Document
        lateinit var snapshotBeforeExport: EditorFrame
    val exportFailures = ArrayList<Throwable>()
        val exported = work.resolve("retained-rich.astra")
        onOwner {
            val surface = checkNotNull(findSurface(window)); val e = surface.engine
            val text = "ک\u0650\nتاب\n\uD800\uFFFF"
            desired = Document(text.mapIndexed { index, char ->
                val hard = index == 2
                TextUnit(2001L + index, char, if (hard) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                    if (hard) TextStyleFlags() else TextStyleFlags(index % 2 == 0, index % 2 == 1),
                    if (hard) TextStyleFlags(true, true) else TextStyleFlags())
            }, TextStyleFlags(italic = true))
            val draft = e.captureRecoveryReplacement(desired, Selection(0, 0))
            richRecord = RetainedCompositionPreimage(text, e.snapshot().selection, null, e.snapshot().committed.text, surface.activeImeEpoch, draft)
            surface.retainedPreimage = richRecord; check(surface.selectRetainedDraft(richRecord))
            snapshotBeforeExport = e.snapshot()
            check(surface.exportRetainedDocument(work.resolve("direct-rich.astra").toFile()))
            check(DocumentFiles.read(work.resolve("direct-rich.astra")).same(desired))
            files.draftDestinationPicker = { exported }
            button("Export Draft").doClick()
        }
        awaitFiles()
        onOwner {
            val surface = checkNotNull(findSurface(window)); val reopened = DocumentFiles.read(exported)
            check(reopened.same(desired) && surface.engine.snapshot() == snapshotBeforeExport)
            check(surface.retainedPreimage === richRecord && surface.recoverySession.snapshotRetainedDrafts().size == 3)
            checks += "Actual Export Draft action preserves full raw UTF-16, attributes, start style and hard/soft breaks without applying or clearing a capsule"
            val originalBytes = java.nio.file.Files.readAllBytes(originalPath)
            files.reportFailureForChecks = { exportFailures += it }
            files.draftDestinationPicker = { originalPath }; button("Export Draft").doClick()
            check(exportFailures.size == 1 && java.nio.file.Files.readAllBytes(originalPath).contentEquals(originalBytes))
            files.draftDestinationPicker = { work }; button("Export Draft").doClick()
        }
        awaitFiles()
        onOwner {
            val surface = checkNotNull(findSurface(window))
            check(surface.retainedPreimage === richRecord && surface.recoverySession.snapshotRetainedDrafts().size == 3 && surface.engine.snapshot() == snapshotBeforeExport)
            check(exportFailures.size == 2 && !files.status.contains("Recovery draft exported"))
            check(java.nio.file.Files.isDirectory(work) && DocumentFiles.read(exported).same(desired))
            checks += "Invalid export/original-path refusal preserves files, records and document state"
            for ((width, height) in listOf(940 to 400, 940 to 700, 1020 to 700)) {
                window.setSize(width, height); window.validate()
                val bar = controls(window).filterIsInstance<javax.swing.JPanel>().single { it.name == "recovery-controls" }
                val bounds = bar.components.filter { it.isVisible }.map { it.bounds }
                check(bar.isShowing && bounds.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= bar.width && it.y + it.height <= bar.height }) { "Recovery controls clipped at width $width" }
                bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b -> check(!a.intersects(b)) { "Recovery controls overlap at width $width" } } }
                val bitmap = java.awt.image.BufferedImage(window.width, window.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                bitmap.createGraphics().let { g -> try { window.paint(g) } finally { g.dispose() } }
                javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "recovery-ui-${width}x$height.png"))
            }
            checks += "Recovery controls are showing, contained and nonoverlapping at minimum/default widths"
            val picker = controls(window).filterIsInstance<javax.swing.JComboBox<*>>().single { it.name == "recovery-picker" }
            picker.selectedIndex = 0
            check(surface.retainedPreimage === surface.recoverySession.snapshotRetainedDrafts()[0])
            val old = surface.retainedPreimage
            button("Apply Draft").doClick()
            check(surface.retainedPreimage === old && surface.engine.snapshot().notice.startsWith("RecoveryBaseChanged"))
            check(surface.selectRetainedDraft(richRecord))
            val priorObserver = surface.engine.onChange
            var newer: RetainedCompositionPreimage? = null
            surface.engine.onChange = { frame ->
                priorObserver(frame)
                if (frame.notice == "Recovered draft applied" && newer == null) {
                    val nextDocument = desired.copy(units = desired.units + TextUnit(9999, 'X'))
                    newer = RetainedCompositionPreimage("newer", frame.selection, null, frame.committed.text, surface.activeImeEpoch,
                        surface.engine.captureRecoveryReplacement(nextDocument, Selection(0, 0)))
                    surface.retainedPreimage = newer
                }
            }
            try { button("Apply Draft").doClick() } finally { surface.engine.onChange = priorObserver }
            val pending = surface.recoverySession.snapshotRetainedDrafts()
            check(pending.size == 3 && pending.none { it === richRecord } && surface.retainedPreimage === newer && surface.engine.snapshot().committed == desired)
            button("Discard Draft").doClick()
            check(surface.recoverySession.snapshotRetainedDrafts().size == 2 && surface.retainedPreimage !== newer)
            checks += "Picker targets the chosen record; stale apply retains it and confirmed apply resolves only that version despite newer reentrant work"
            files.close(1)
            check(!window.isDisplayable && surface.recoverySession.snapshotRetainedDrafts().isEmpty())
            check(DocumentFiles.read(originalPath).text.isEmpty() && DocumentFiles.read(exported).same(desired))
            checks += "Explicit discard-all close resolves every pending record without changing saved files"
        }
        onOwner {
            window = showEditor(offscreenTest = true, initialPath = originalPath)
            files = checkNotNull(windowFiles(window))
            val old = checkNotNull(findSurface(window))
            input(old, "مسودہ")
            old.recoveryHooks = EditorSurface.RecoveryHooks(disposeContext = { throw IllegalStateException("Quarantine regression: disposal refused") })
            val paused = checkNotNull(old.abortAndRetireComposition(true))
            check(!paused.admissionAllowed)
            input(paused, "محفوظ\uD800\u0000\uFFFF")
            check(paused.quarantinedCallbacks.size == 1 && paused.engine.snapshot().committed.text.isEmpty())
            old.recoveryHooks = EditorSurface.RecoveryHooks(); paused.recoveryHooks = EditorSurface.RecoveryHooks()
            check(paused.recoverExplicitly(false))
            paused.discardRecoveredDraft()
            check(paused.recoverySession.snapshotRetainedDrafts().isEmpty() && paused.quarantinedCallbacks.size == 1 && !files.dirty)
            var prompts = 0; files.promptCloseChoice = { prompts++; 2 }
            files.close()
            check(prompts == 1 && window.isDisplayable) { "Quarantine-only close bypassed Cancel after actual degraded callback and cleanup" }
            checks += "Actual degraded surface callback survives cleanup and capsule discard; quarantine-only close consults Cancel"
            files.close(0); check(window.isDisplayable && !files.busy)
            files.save()
        }
        awaitFiles()
        onOwner { check(window.isDisplayable); files.save(closeAfter = true) }
        awaitFiles()
        val inputExport = work.resolve("unverified-input.json")
        lateinit var beforeInputExport: EditorFrame
        lateinit var callback: QuarantinedCallback
        onOwner {
            val surface = checkNotNull(findSurface(window))
            check(window.isDisplayable && !files.dirty && surface.quarantinedCallbacks.size == 1)
            callback = surface.quarantinedCallbacks.single()
            check(callback.text == "محفوظ\uD800\u0000\uFFFF" && callback.committed == 0 && callback.caret == callback.text.length)
            beforeInputExport = surface.engine.snapshot()
            files.inputDestinationPicker = { inputExport }; button("Export Input").doClick()
            checks += "Quarantine-only Keep Editing, ordinary save and save-close retain callbacks without replay"
        }
        awaitFiles()
        onOwner {
            val surface = checkNotNull(findSurface(window))
            val payload = SimpleJson(java.nio.file.Files.readString(inputExport)).parse() as Map<*, *>
            val entry = (payload["entries"] as List<*>).single() as Map<*, *>
            check(entry["text"] == callback.text && (entry["utf16"] as List<*>).map { (it as Number).toInt() } == callback.text.map { it.code })
            check((entry["epoch"] as Number).toInt() == callback.epoch && (entry["timestampMillis"] as Number).toLong() == callback.timestamp)
            check((entry["committedCharacterCount"] as Number).toInt() == callback.committed && (entry["caret"] as Number).toInt() == callback.caret)
            check(surface.engine.snapshot() == beforeInputExport && surface.quarantinedCallbacks.single() === callback && files.path == originalPath && !files.dirty)
            checks += "Actual diagnostic export preserves lone surrogate, NUL, U+FFFF, raw UTF-16 and callback metadata without changing main document or save point"
            exportFailures.clear(); files.reportFailureForChecks = { exportFailures += it }
            files.inputDestinationPicker = { originalPath }; button("Export Input").doClick()
            check(exportFailures.size == 1 && DocumentFiles.read(originalPath).text.isEmpty())
            files.inputDestinationPicker = { work }; button("Export Input").doClick()
        }
        awaitFiles()
        onOwner {
            val surface = checkNotNull(findSurface(window))
            check(exportFailures.size == 2 && surface.quarantinedCallbacks.single() === callback && surface.engine.snapshot() == beforeInputExport)
            check(!files.status.contains("Unverified input exported"))
            checks += "Main-path refusal and failed diagnostic IO preserve the queue, files and accepted state"
            // Include both recovery rows when checking the minimum window, with a new owned capsule.
            val record = RetainedCompositionPreimage("layout", beforeInputExport.selection, null, "", surface.activeImeEpoch,
                surface.engine.captureRecoveryReplacement(Document(listOf(TextUnit(999, 'A'))), Selection(0, 0)))
            surface.retainedPreimage = record; surface.statusChanged(surface.engine.snapshot())
            for ((width, height) in listOf(940 to 400, 1020 to 700)) {
                window.setSize(width, height); window.validate()
                for (name in listOf("quarantine-controls", "recovery-controls")) {
                    val bar = controls(window).single { it.name == name } as Container
                    check(bar.isShowing)
                    val bounds = bar.components.filter { it.isVisible }.map { it.bounds }
                    check(bounds.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= bar.width && it.y + it.height <= bar.height }) { "$name clipped at $width x $height" }
                    bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b -> check(!a.intersects(b)) } }
                }
                val bitmap = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                bitmap.createGraphics().let { g -> try { window.paint(g) } finally { g.dispose() } }
                javax.imageio.ImageIO.write(bitmap, "png", File(result.parentFile, "quarantine-ui-${width}x$height.png"))
            }
            surface.discardRecoveredDraft()
            files.confirmInputDiscardForChecks = { false }; button("Discard Input…").doClick()
            check(surface.quarantinedCallbacks.single() === callback)
            val sameValueNewArrival = callback.copy()
            files.confirmInputDiscardForChecks = { count ->
                check(count == 1); surface.quarantinedCallbacks.add(sameValueNewArrival); true
            }
            button("Discard Input…").doClick()
            check(surface.quarantinedCallbacks.size == 1 && surface.quarantinedCallbacks.single() === sameValueNewArrival)
            checks += "Actual discard action requires confirmation and removes only its snapshot, keeping a value-equal reentrant arrival"
            val closeArrival = callback.copy()
            files.promptCloseChoice = { surface.quarantinedCallbacks.add(closeArrival); 1 }
            files.close()
            check(window.isDisplayable && surface.quarantinedCallbacks.single() === closeArrival)
            checks += "Discard-all close confirms only its captured recovery work; reentrant arrival keeps the window open"
            files.close(1)
            check(!window.isDisplayable && surface.quarantinedCallbacks.isEmpty() && DocumentFiles.read(originalPath).text.isEmpty())
            checks += "Explicit discard-all close clears remaining unverified input; both recovery rows fit minimum/default windows"
        }
        result.parentFile?.mkdirs(); result.writeText(evidenceJson(mapOf("status" to "PASS", "checks" to checks,
            "scope" to "Actual offscreen shell, synthetic input; no OS IME or desktop control qualification")), Charsets.UTF_8)
        println("PASS recovery UI retention checks")
    } finally { if (created) onOwner { window.dispose() } }
}
