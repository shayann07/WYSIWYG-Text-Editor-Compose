package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.data.LocalRecoveryEntry
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.RichClipboard
import dev.shayxo.composedoc.workspace.LocalRecoveryController
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import dev.shayxo.composedoc.workspace.writingCounts
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

fun clipboardChild(args: Array<String>) {
    fun arg(key: String) = args.single { it.startsWith(key + "=") }.substringAfter('=')
    fun <T> ownedClipboardRetry(action: () -> T): T {
        val end = System.nanoTime() + 5_000_000_000
        while (true) {
            try {
                return action()
            } catch (e: IllegalStateException) {
                if (System.nanoTime() >= end)throw e
                Thread.sleep(50)
            }
        }
    }
    val mode = arg("--clipboard-child")
    val path = java.nio.file.Path.of(arg("--clipboard-file"))
    if (mode == "publish") {
        val e = EditorEngine(DocumentFiles.read(path))
        e.selectAll()
        ownedClipboardRetry { RichClipboard.publish(checkNotNull(e.clipboardCapture()), Toolkit.getDefaultToolkit().systemClipboard, codec = FragmentCodec) }
        println("OWNED_CLIPBOARD_READY")
        System.out.flush()
        readlnOrNull()
    } else {
        val e = EditorEngine()
        val r = ownedClipboardRetry { RichClipboard.read(Toolkit.getDefaultToolkit().systemClipboard, mode == "plain", codec = FragmentCodec) }
        check(e.clipboardApply(checkNotNull(e.clipboardCapture()), r.rich, r.plain))
        DocumentFiles.write(path, e.snapshot().committed)
        println("OWNED_CLIPBOARD_READ")
    }
    kotlin.system.exitProcess(0)
}

fun runConnectedWritingChecks(result: File) {
    fun <T> owner(block: () -> T): T {
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return checkNotNull(r).getOrThrow()
    }
    fun await(label: String, p: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000
        while (!owner(p)) {
            check(System.nanoTime() < end) { label }
            Thread.sleep(20)
        }
    }
    val checks = ArrayList<String>()
    val images = ArrayList<String>()
    val root = Files.createTempDirectory(result.parentFile.toPath(), "connected-writing-")
    fun test(name: String, block: () -> Unit) {
        block()
        checks += name
        println("PASS $name")
    }
    fun doc(text: String): Document {
        val e = EditorEngine()
        e.replace(text, "fixture", true)
        return e.snapshot().committed
    }
    val style = TextStyleFlags(true, true, true, true, StoredFont.GULZAR, 32, 0x174ea6)
    val p0 = ParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER, 8, 12, 1500)
    val p1 = ParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.END, 4, 16, 2000)
    val raw = "کِتاب\u200c English 123 😀\n\nب\u200dت\nsoft\nline".replace("\\u200c", "\u200c").replace("\\u200d", "\u200d")
    val base = doc(raw).let { d ->
        d.copy(
            startStyle = style.copy(font = StoredFont.SYSTEM, size = 18, color = -1),
            startParagraph = p0,
            units = d.units.mapIndexed { i, u ->
                if (u.kind == UnitKind.PARAGRAPH_BREAK && i != raw.lastIndexOf('\n')) {
                    u.copy(paragraphStyle = style, followingParagraph = if (i % 2 == 0)p0 else p1)
                } else {
                    u.copy(kind = UnitKind.TEXT, style = if (i % 3 == 0)style else style.copy(font = StoredFont.NASKH, size = 22, color = -1, underline = false))
                }
            },
        )
    }
    test("v5 exact raw UTF16 expanded inline/empty paragraph properties and independent byte fields") {
        val bytes = DocumentFiles.encode(base)
        check(DocumentFiles.decode(bytes).same(base))
        val b = java.nio.ByteBuffer.wrap(bytes)
        check(b.getInt(4) == 9 && b.getInt(8) == raw.length)
        check(bytes[12].toInt() == 15 && bytes[13].toInt() == StoredFont.SYSTEM.ordinal && bytes[14].toInt() == 18 && b.getInt(15) == -1)
        check(b.getShort(20).toInt() == 8 && b.getShort(22).toInt() == 12 && b.getShort(24).toInt() == 1500)
        // Independent v4 bytes: RTL/Center root and LTR/End following; defaults for all new fields.
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use { i ->
            i.writeInt(0x41535450)
            i.writeInt(4)
            i.writeInt(2)
            i.writeByte(3)
            i.writeByte(5)
            i.writeChar('a'.code)
            i.writeByte(0)
            i.writeByte(1)
            i.writeByte(0)
            i.writeChar(10)
            i.writeByte(1)
            i.writeByte(2)
            i.writeByte(10)
        }
        val body = out.toByteArray()
        val old = DocumentFiles.decode(body + java.security.MessageDigest.getInstance("SHA-256").digest(body))
        check(old.startStyle == TextStyleFlags(true, true) && old.startParagraph == ParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.CENTER))
        check(old.units.last().followingParagraph == ParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.END))
        fun invalid(offset: Int, value: Int) {
            val bad = bytes.copyOf(bytes.size - 32)
            bad[offset] = value.toByte()
            check(runCatching { DocumentFiles.decode(bad + java.security.MessageDigest.getInstance("SHA-256").digest(bad)) }.isFailure)
        }
        invalid(13, 99)
        invalid(14, 7)
        invalid(12, 16)
        invalid(20, 1)
        invalid(24, 0) // unknown font,size,bits,oversize spacing,invalid line mode
        check(runCatching { DocumentFiles.decode(ByteArray(DocumentFiles.MAX_ENCODED_BYTES + 1)) }.isFailure)
    }
    test("format patches preserve other attributes, grapheme raw attribution and collapsed typing seed") {
        val e = EditorEngine(base)
        e.move(Selection(2, 0))
        val before = e.snapshot()
        e.setTextStyle(TextStylePatch(color = 0xb3261e))
        val f = e.snapshot()
        check(f.committed.units.take(2).all { it.style.color == 0xb3261e })
        check(f.committed.units.zip(before.committed.units).all { (a, b) -> a.style.copy(color = b.style.color) == b.style })
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == f.committed)
        e.move(Selection(0, 0))
        val n = e.snapshot().undoCount
        e.setTextStyle(TextStylePatch(font = StoredFont.NOTO_NASTALIQ, size = 40, underline = true, color = 0))
        check(e.snapshot().undoCount == n)
        e.inputMethod("اردو", 0, 4)
        e.inputMethod("اردو", 4, null)
        check(e.snapshot().committed.units.take(4).all { it.style.font == StoredFont.NOTO_NASTALIQ && it.style.size == 40 && it.style.underline && it.style.color == 0 })
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == f.committed)
    }
    test("spacing half-open/backward targeting, split and left join inheritance") {
        val e = EditorEngine(doc("a\n\nb"))
        e.move(Selection(2, 0))
        e.setParagraphProperties(before = 12, after = 24, line = 1500)
        check(e.snapshot().committed.paragraphs().map { it.properties.spaceBefore } == listOf(12, 0, 0))
        e.move(Selection(1, 1))
        e.replace("\n", "split", true)
        check(e.snapshot().committed.paragraphs().take(2).all { it.properties.spaceAfter == 24 })
        e.move(Selection(2, 2))
        e.delete(true)
        check(e.snapshot().committed.text == "a\n\nb" && e.snapshot().committed.startParagraph.spaceAfter == 24)
        e.move(Selection(2, 3))
        e.setParagraphProperties(before = 4)
        e.move(Selection(0, 4))
        check(e.paragraphState { it.spaceBefore } == null)
    }
    val source = EditorEngine(base)
    source.selectAll()
    val captured = checkNotNull(source.clipboardCapture())
    val fragment = RichFragment.capture(captured)
    test("rich paste independent expected units/properties, boundary-only and partial destination inheritance") {
        val clipboard = Clipboard("owned-test")
        RichClipboard.publish(captured, clipboard, codec = FragmentCodec)
        val decoded = checkNotNull(RichClipboard.read(clipboard, codec = FragmentCodec).rich)
        check(decoded.document.same(base))
        val destination = doc("LEFT\nRIGHT").copy(startParagraph = p1)
        val e = EditorEngine(destination)
        e.move(Selection(7, 6))
        val before = e.snapshot()
        check(e.clipboardApply(checkNotNull(e.clipboardCapture()), decoded))
        val after = e.snapshot()
        val expectedChars = "LEFT\nR" + raw + "GHT"
        check(after.committed.text == expectedChars)
        val expectedStyles = before.committed.units.take(6).map { it.style } + base.units.map { it.style } + before.committed.units.drop(7).map { it.style }
        check(after.committed.units.map { it.style } == expectedStyles)
        check(after.committed.units.map { it.id }.distinct().size == after.committed.units.size)
        check(after.committed.units.subList(6, 6 + base.units.size).none { u -> before.committed.units.any { it.id == u.id } })
        check(after.undoCount == before.undoCount + 1)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == after.committed)
        val partial = EditorEngine(doc("abc\nxyz").copy(startParagraph = p0))
        partial.move(Selection(2, 1))
        val part = RichFragment.capture(checkNotNull(partial.clipboardCapture()))
        val d = EditorEngine(doc("Q").copy(startParagraph = p1))
        check(d.clipboardApply(checkNotNull(d.clipboardCapture()), part))
        check(d.snapshot().committed.text == "bQ" && d.snapshot().committed.startParagraph == p1)
        partial.move(Selection(4, 3))
        val seam = RichFragment.capture(checkNotNull(partial.clipboardCapture()))
        val z = EditorEngine()
        check(z.clipboardApply(checkNotNull(z.clipboardCapture()), seam))
        check(z.snapshot().committed.units.single().kind == UnitKind.PARAGRAPH_BREAK)
    }
    test("clipboard invalid versions/truncation/fallback/bounds; stale edit-undo and composition refuse") {
        val bytes = FragmentCodec.encode(fragment)
        for (n in listOf(0, 8, bytes.size - 1)) check(runCatching { FragmentCodec.decode(bytes.copyOf(n)) }.isFailure)
        val invalid = bytes.copyOf()
        invalid[7] = 9
        check(runCatching { FragmentCodec.decode(invalid) }.isFailure)
        val clipboard = Clipboard("invalid-owned")
        clipboard.setContents(
            object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(RichClipboard.flavor, DataFlavor.stringFlavor)
                override fun isDataFlavorSupported(f: DataFlavor) = true
                override fun getTransferData(f: DataFlavor): Any = if (f == RichClipboard.flavor)java.io.ByteArrayInputStream(invalid) else "plain\r\ntext"
            },
            null,
        )
        val fallback = RichClipboard.read(clipboard, codec = FragmentCodec)
        check(fallback.fallback && fallback.rich == null && fallback.plain == "plain\ntext")
        val e = EditorEngine(base)
        e.move(Selection(0, 2))
        val c = checkNotNull(e.clipboardCapture())
        e.replace("x", "edit")
        e.perform(EditAction.Undo)
        check(!e.clipboardApply(c, null, "", true) && !e.clipboardApply(c, fragment))
        e.inputMethod("candidate", 0, 9)
        check(e.clipboardCapture() == null && !e.clipboardApply(c, fragment))
        e.setTextStyle(TextStylePatch(strike = true, size = 48))
        check(e.snapshot().waiting)
        e.inputMethod("candidate", 9, null)
        check(e.snapshot().typingStyle.strike && e.snapshot().typingStyle.size == 48)
        e.replace("NEXT", "typing")
        check(e.snapshot().committed.units.subList(9, 13).all { it.style.strike && it.style.size == 48 })
        val draft = e.captureRecoveryReplacement(base, Selection(0, 0))
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)
        check(e.snapshot().committed.same(base))
    }
    test("Replace All independent attribute oracle and exact undo-redo, save and retained exports") {
        val e = EditorEngine(base)
        val before = e.snapshot().committed
        val basis = e.captureFindBasis()
        val matches = findLiteral(before, "English")
        check(e.replaceFound(basis, matches, "Q", true) == FindReplaceResult.Applied)
        val m = matches.single()
        val after = e.snapshot().committed
        val expected = before.units.take(m.start) + listOf(before.units[m.start].copy(char = 'Q')) + before.units.drop(m.end)
        check(after.units.map { listOf(it.char, it.kind, it.style, it.paragraphStyle, it.followingParagraph) } == expected.map { listOf(it.char, it.kind, it.style, it.paragraphStyle, it.followingParagraph) })
        check(after.startParagraph == before.startParagraph)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == after)
        val path = root.resolve("save.astra")
        DocumentFiles.write(path, after)
        check(DocumentFiles.read(path).same(after))
        val draft = e.captureRecoveryReplacement(base, Selection(0, 0))
        val export = root.resolve("draft.astra")
        DocumentFiles.write(export, draft.document)
        check(DocumentFiles.read(export).same(base))
        val bytes = Files.readAllBytes(path)
        check(runCatching { DocumentFiles.writeImpl(path, base) { error("owned IO fault") } }.isFailure)
        check(Files.readAllBytes(path).contentEquals(bytes))
    }
    test("Cut publish failure/reentry keeps content; successful Cut one undoable transaction") {
        val e = EditorEngine(base)
        e.move(Selection(2, 0))
        val c = checkNotNull(e.clipboardCapture())
        val before = e.snapshot()
        val failed = object : Clipboard("owned-failure") {
            override fun setContents(t: Transferable?, o: ClipboardOwner?): Unit = throw IllegalStateException("owned publish failure")
        }
        check(runCatching { RichClipboard.publish(c, failed, codec = FragmentCodec) }.isFailure)
        check(e.snapshot() == before)
        val reentry = object : Clipboard("owned-reentry") {
            override fun setContents(t: Transferable?, o: ClipboardOwner?) {
                super.setContents(t, o)
                e.replace("peer", "edit")
            }
        }
        RichClipboard.publish(c, reentry, codec = FragmentCodec)
        val changed = e.snapshot()
        check(!e.clipboardApply(c, null, "", true) && e.snapshot() == changed)
        e.perform(EditAction.Undo)
        e.move(Selection(2, 0))
        val fresh = checkNotNull(e.clipboardCapture())
        RichClipboard.publish(fresh, Clipboard("owned-success"), codec = FragmentCodec)
        val count = e.snapshot().undoCount
        check(e.clipboardApply(fresh, null, "", true))
        check(e.snapshot().committed.text == raw.substring(2) && e.snapshot().undoCount == count + 1)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed.same(base))
    }
    test("OS rich clipboard between independent owned JVMs; explicit plain consumes only disposable content") {
        val input = root.resolve("clipboard-source.astra")
        DocumentFiles.write(input, base)
        val javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val cmd = listOf(javaExe, "-cp", System.getProperty("java.class.path"), "editor.MainKt")
        val publisher = ProcessBuilder(cmd + listOf("--clipboard-child=publish", "--clipboard-file=$input")).redirectErrorStream(true).start()
        try {
            check(publisher.inputStream.bufferedReader().readLine() == "OWNED_CLIPBOARD_READY")
            for (mode in listOf("rich", "plain")) {
                val output = root.resolve("clipboard-$mode.astra")
                val p = ProcessBuilder(cmd + listOf("--clipboard-child=$mode", "--clipboard-file=$output")).redirectErrorStream(true).start()
                check(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) { p.inputStream.bufferedReader().readText() }
                val d = DocumentFiles.read(output)
                check(d.text == raw)
                if (mode == "rich") check(d.same(base)) else check(d.units.filter { it.kind == UnitKind.TEXT }.all { it.style == TextStyleFlags() } && d.paragraphs().all { it.properties == ParagraphProperties() })
            }
        } finally {
            publisher.outputStream.write("done\n".toByteArray())
            publisher.outputStream.flush()
            if (!publisher.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))publisher.destroyForcibly()
        }
    }
    var window: JFrame? = null
    try {
        lateinit var s: EditorSurface
        owner {
            window = showEditor(initial = base, fontProfile = "noto-nastaliq", offscreenTest = true, recoveryDirectory = root.resolve("recovery"))
            s = checkNotNull(windowCurrentSurface(checkNotNull(window)))
        }
        await("mount") { s.view.measure != null }
        test("natural spacing expected gaps and >= natural advance; zoom fixed metrics and native/pointer geometry") {
            for (paged in listOf(false, true)) {
                for (zoom in listOf(1f, .75f, .5f)) {
                    owner {
                        s.view.setPagedMode(paged)
                        s.view.changeZoom(zoom)
                        checkNotNull(window).setSize(940, 400)
                        checkNotNull(window).validate()
                    }
                    await("zoom measure") { s.view.measure != null && s.view.geometry?.typographyKey?.contains(":zoom=$zoom") == true }
                    owner {
                        val before = s.viewModel.verificationEngine.snapshot()
                        val g = checkNotNull(s.geometry())
                        val p = g.paragraphs[0]
                        check(g.scale > 0 && p.layout.size.height > 0)
                        val page = base.pageSettings
                        val expectedWidth = ((page.widthMm - 2 * page.marginMm) * 96f / 25.4f).roundToInt()
                        check(g.textWidth == expectedWidth) { "Document width changed at zoom: ${g.textWidth}, expected $expectedWidth" }
                        val caret = g.caret(0)
                        val location = checkNotNull(s.getTextLocation(null))
                        val screen = s.locationOnScreen
                        check(kotlin.math.abs(location.x - screen.x - caret.left / g.scale) < 2)
                        val attrs = s.accessibleContext.accessibleText.getCharacterAttribute(0)
                        check(javax.swing.text.StyleConstants.isUnderline(attrs) && javax.swing.text.StyleConstants.isStrikeThrough(attrs) && javax.swing.text.StyleConstants.getFontSize(attrs) == 32)
                        val natural = p.layout.getLineBottom(0) - p.layout.getLineTop(0)
                        check(natural >= 32)
                        if (!paged) {
                            check(kotlin.math.abs(p.top - 8) < .1)
                            val q = g.paragraphs[1]
                            val props = base.paragraphs()[1].properties
                            check(kotlin.math.abs(q.top - (p.top + p.height + 12 + props.spaceBefore)) < .1)
                        } else {
                            check(kotlin.math.abs(g.fragments.first().top - 8) < .1)
                        }
                        check(s.viewModel.verificationEngine.snapshot() == before)
                    }
                }
            }
        }
        test("minimum shell tabs, synthetic navigation and view-only dirty/history protection") {
            owner {
                val w = checkNotNull(window)
                val tabs = w.rootPane.getClientProperty("editor.writingTabs") as JTabbedPane
                fun components(c: Component): List<Component> = listOf(c) + if (c is Container)c.components.flatMap(::components) else emptyList()
                for (i in 0..2) {
                    tabs.selectedIndex = i
                    w.validate()
                    check(s.height >= 120) { "Minimum writing area ${s.height}" }
                    components(w).filter { it.isShowing && (it is JButton || it is JComboBox<*>) }.forEach { c -> check(c.x >= 0 && c.x + c.width <= c.parent.width && c.height > 0) { "Clipped ${c.name} ${c.bounds}/${c.parent.size}" } }
                    val image = java.awt.image.BufferedImage(w.width, w.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    w.paint(g)
                    g.dispose()
                    val name = "connected-min-tab-$i.png"
                    javax.imageio.ImageIO.write(image, "png", File(result.parentFile, name))
                    images += name
                }
                fun key(code: Int, shift: Boolean = false) {
                    val k = KeyEvent(s, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), (if (shift)InputEvent.SHIFT_DOWN_MASK else 0), code, KeyEvent.CHAR_UNDEFINED)
                    s.keyListeners.forEach { it.keyPressed(k) }
                }
                s.viewModel.verificationEngine.move(Selection(0, 0))
                key(KeyEvent.VK_PAGE_DOWN, true)
                check(s.viewModel.verificationEngine.snapshot().selection.focus > 0 && s.viewModel.verificationEngine.snapshot().selection.anchor == 0)
                key(KeyEvent.VK_PAGE_UP)
                check(s.viewModel.verificationEngine.snapshot().selection.collapsed)
                val before = s.viewModel.verificationEngine.snapshot()
                s.view.changeZoom(1f)
                s.view.setPagedMode(false)
                check(s.viewModel.verificationEngine.snapshot() == before)
                val counts = writingCounts(EditorEngine(doc("کِتاب\u200c X 123 😀 \u200d")).snapshot())
                check(counts.words == 4 && counts.units == "کِتاب\u200c X 123 😀 \u200d".length && counts.selected == 0)
            }
        }
        test("automatic completed checkpoint retains expanded model separately from provisional composition") {
            owner {
                s.viewModel.verificationEngine.move(Selection(0, 0))
                s.viewModel.verificationEngine.setParagraphProperties(before = 24)
                s.viewModel.verificationEngine.setTextStyle(TextStylePatch(size = 48))
                s.viewModel.verificationEngine.replace("نیا ", "edit")
                s.viewModel.verificationEngine.inputMethod("provisional", 0, 11)
            }
            val expected = owner { s.viewModel.verificationEngine.snapshot().committed }
            val controller = owner { checkNotNull(window).rootPane.getClientProperty("editor.localRecovery") as LocalRecoveryController }
            await("completed committed copy") { controller.state().let { it["completed"] == it["observed"] && (it["completed"] as Long) > 0 } }
            owner { controller.finish(discard = false) }
            val store = LocalRecoveryStore(root.resolve("recovery"))
            var entry: LocalRecoveryEntry? = null
            val end = System.nanoTime() + 10_000_000_000
            while (entry == null) {
                entry = store.scan().firstOrNull { it.copy?.document?.same(expected) == true }
                check(System.nanoTime() < end)
                if (entry == null)Thread.sleep(20)
            }
            check(store.restore(checkNotNull(entry)).document.same(expected))
            check(!expected.text.contains("provisional"))
        }
    } finally {
        owner { window?.dispose() }
    }
    result.writeText(evidenceJson(mapOf("status" to "PASS", "checks" to checks, "screenshots" to images, "scope" to "Engine/independent byte+unit oracles, actual OS clipboard owned JVMs, offscreen Compose shell. Keyboard/IME callbacks here are synthetic; live desktop separate.")), Charsets.UTF_8)
    println("PASS connected writing deliveries checks")
}
