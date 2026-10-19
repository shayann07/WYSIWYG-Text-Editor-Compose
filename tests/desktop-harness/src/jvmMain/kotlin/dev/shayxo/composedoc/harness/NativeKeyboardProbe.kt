package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.SimpleJson
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.outlineEntries
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.ImeTrace
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.FindReplaceUi
import dev.shayxo.composedoc.workspace.LocalRecoveryController
import dev.shayxo.composedoc.workspace.StructureControls
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.showLocalRecovery
import dev.shayxo.composedoc.workspace.structuredWritingSample
import dev.shayxo.composedoc.workspace.urduWritingSample
import dev.shayxo.composedoc.workspace.windowCurrentSurface
import dev.shayxo.composedoc.workspace.windowFiles
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Owned visible native keyboard fixture. It exposes setup/snapshot/cleanup, never injected input. */
fun runNativeKeyboardProbe(args: Array<String>) {
    val output = File(checkNotNull(args.firstOrNull { it.startsWith("--keyboard-output=") }).substringAfter('='))
    check(output.mkdirs()) { "Use a new native keyboard directory" }
    val crashWriting = "--native-crash-writing" in args
    val writing = "--native-writing" in args || crashWriting
    val recoveryDirectory = args.firstOrNull {
        it.startsWith("--recovery-dir=")
    }?.substringAfter('=')?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }
    val writingPath = File(output, "disposable-native-writing.astra").toPath()
    val initialWriting = if ("--native-structured" in args) {
        structuredWritingSample()
    } else if (writing && !crashWriting) {
        urduWritingSample()
    } else {
        Document(emptyList())
    }
    if (writing && !crashWriting) DocumentFiles.write(writingPath, initialWriting)
    val nativeType = Class.forName("OwnedKeyboardLayout")
    val native = nativeType.getConstructor().newInstance()
    fun nativeCall(name: String, value: String? = null): Any? = if (value == null) {
        nativeType.getMethod(name).invoke(native)
    } else {
        nativeType.getMethod(name, String::class.java).invoke(native, value)
    }
    lateinit var window: javax.swing.JFrame
    lateinit var surface: EditorSurface
    var created = false
    var reference: javax.swing.JTextArea? = null
    var referenceImeEvents = 0
    var referenceMarkedUnits = 0
    val originalLocale = java.util.concurrent.atomic.AtomicReference<java.util.Locale?>()
    val trace = ImeTrace(File(output, "trace"))
    val writer = System.out.writer(Charsets.UTF_8).buffered()
    fun emit(value: Any?) {
        val line = evidenceJson(value)
        File(output, "protocol.jsonl").appendText(line + "\n", Charsets.UTF_8)
        if (writing && value is Map<*, *>) {
            val frame = value["frame"] as? Map<*, *>
            writer.write(
                evidenceJson(
                    mapOf(
                        "status" to value["status"], "pid" to value["pid"], "title" to value["title"],
                        "length" to (frame?.get("text") as? String)?.length, "selection" to frame?.get("selection"),
                        "focusOwner" to frame?.get("focusOwner"), "paged" to frame?.get("paged"),
                        "displayable" to frame?.get("displayable"),
                        "dirty" to frame?.get("dirty"), "busy" to frame?.get("busy"), "native" to value["native"],
                    ),
                ),
            )
        } else {
            writer.write(line)
        }
        writer.write("\n")
        writer.flush()
    }
    fun owner(block: () -> Map<String, Any?>): Map<String, Any?> {
        var result: Map<String, Any?>? = null
        SwingUtilities.invokeAndWait {
            if (created) surface = windowCurrentSurface(window) ?: surface
            result = block()
        }
        return checkNotNull(result)
    }
    fun snapshot(): Map<String, Any?> {
        val frame = surface.viewModel.verificationEngine.snapshot()
        val find = window.rootPane.getClientProperty("editor.find") as? FindReplaceUi
        return mapOf(
            "text" to frame.visible.text, "selection" to listOf(frame.selection.anchor, frame.selection.focus),
            "startStyle" to frame.visible.startStyle.bits,
            "startParagraph" to frame.visible.startParagraph.bits,
            "paragraphs" to frame.visible.paragraphs().map { p ->
                mapOf(
                    "index" to p.index,
                    "start" to p.start,
                    "end" to p.end,
                    "direction" to p.properties.direction.name,
                    "alignment" to p.properties.alignment.name,
                    "bits" to p.properties.bits,
                )
            },
            "unitParagraphs" to frame.visible.units.map { it.followingParagraph.bits },
            "expandedDocument" to java.util.Base64.getEncoder().encodeToString(DocumentFiles.encode(frame.committed)),
            "zoom" to surface.view.zoom,
            "structure" to structureDescription(frame.committed),
            "outline" to frame.committed.outlineEntries().map { listOf(it.anchor, it.level, it.label) },
            "paragraphSpacing" to
                frame.visible.paragraphs().map {
                    listOf(it.properties.spaceBefore, it.properties.spaceAfter, it.properties.linePermille)
                },
            "inlineAttributes" to
                frame.visible.units.map {
                    listOf(
                        it.style.bits,
                        it.style.font.ordinal,
                        it.style.size,
                        it.style.color,
                        it.paragraphStyle.bits,
                        it.paragraphStyle.font.ordinal,
                        it.paragraphStyle.size,
                        it.paragraphStyle.color,
                    )
                },
            "find" to find?.let { ui ->
                mapOf(
                    "visible" to ui.panel.isVisible,
                    "query" to ui.query.text,
                    "replacement" to ui.replacement.text,
                    "count" to ui.count.text,
                    "active" to ui.session.active,
                    "matches" to ui.session.matches.map { listOf(it.start, it.end, it.editable) },
                    "queryFocused" to ui.query.isFocusOwner,
                )
            },
            "units" to
                frame.visible.units.map {
                    listOf(it.id, it.char.code, it.kind.ordinal, it.style.bits, it.paragraphStyle.bits)
                },
            "undo" to frame.undoCount, "redo" to frame.redoCount,
            "marked" to frame.marked?.let { listOf(it.first, it.last + 1) },
            "revision" to frame.documentRevision, "locale" to surface.inputContext?.locale?.toLanguageTag(),
            "focusOwner" to surface.isFocusOwner,
            "referenceText" to reference?.text, "referenceFocus" to reference?.isFocusOwner,
            "referenceLocale" to reference?.inputContext?.locale?.toLanguageTag(),
            "referenceImeEvents" to referenceImeEvents, "referenceMarkedUnits" to referenceMarkedUnits,
            "displayable" to window.isDisplayable, "paged" to surface.view.paged, "scroll" to surface.view.scroll,
            "fontProfile" to surface.view.typography.key, "dirty" to windowFiles(window)?.dirty,
            "busy" to windowFiles(window)?.busy,
            "path" to windowFiles(window)?.path?.toString(), "viewport" to listOf(surface.width, surface.height),
            "localRecovery" to
                (window.rootPane.getClientProperty("editor.localRecovery") as? LocalRecoveryController)?.state(),
            "visualControls" to
                (window.rootPane.getClientProperty("editor.structure") as? StructureControls)?.let { ui ->
                    mapOf(
                        "hasCurrentSection" to ui.hasCurrentSection,
                        "currentSection" to ui.currentSection,
                        "outlineKeyboardRow" to ui.outline.selectedIndex,
                        "outlineWidth" to ui.outlinePane.width,
                        "roleWidth" to ui.role.width,
                        "roleValue" to ui.role.selectedItem.toString(),
                    )
                },
            "geometry" to surface.view.geometry?.let { g ->
                val c = g.caret(frame.selection.focus, frame.selection.primary, frame.selection.wrapAffinity)
                mapOf(
                    "listMarkers" to g.paragraphs.filter { it.marker != null }.map { p ->
                        val m = p.marker!!
                        mapOf(
                            "paragraph" to p.index, "label" to m.text, "nominalSize" to m.nominalSize,
                            "baseline" to m.baseline, "left" to m.left, "top" to m.top, "width" to m.width,
                            "contentLeft" to p.contentLeft, "contentWidth" to p.contentWidth, "fontKey" to m.fontKey,
                        )
                    },
                    "pages" to g.pageCount,
                    "scale" to g.scale,
                    "caret" to listOf(c.left, c.top, c.right, c.bottom),
                )
            },
        )
    }
    fun restore(): Any? {
        val result = nativeCall("restore")
        File(output, "restore-state.json").writeText(evidenceJson(result), Charsets.UTF_8)
        if (created) {
            owner {
                originalLocale.get()?.let { locale ->
                    surface.selectInputLocale(locale)
                    reference?.inputContext?.selectInputMethod(locale)
                }
                mapOf("frame" to snapshot())
            }
        }
        return result
    }
    val watchdog = Executors.newSingleThreadScheduledExecutor()
    watchdog.schedule({
        runCatching {
            restore()
        }.onSuccess { File(output, "deadline-cleanup.json").writeText(evidenceJson(it), Charsets.UTF_8) }
        kotlin.system.exitProcess(124)
    }, if (writing) 12 else 8, TimeUnit.MINUTES)
    try {
        owner {
            window = showEditor(
                diagnostics = File(output, "diagnostics.json"),
                trace = trace,
                fontProfile = "noto-nastaliq",
                paged = true,
                initial = initialWriting,
                initialPath = if (writing && !crashWriting) writingPath else null,
                recoveryDirectory = recoveryDirectory,
            )
            created = true
            surface = checkNotNull(findSurface(window))
            originalLocale.set(surface.inputContext?.locale)
            if ("--native-reference" in args) {
                reference = javax.swing.JTextArea(2, 30).apply {
                    accessibleContext.accessibleName = "Reference JTextArea (comparison only)"
                    addInputMethodListener(object : java.awt.event.InputMethodListener {
                        override fun inputMethodTextChanged(event: java.awt.event.InputMethodEvent) {
                            referenceImeEvents++
                            val text =
                                (event.text?.clone() as? java.text.AttributedCharacterIterator)?.let { iterator ->
                                    buildString {
                                        var char = iterator.first()
                                        while (char != java.text.CharacterIterator.DONE) {
                                            append(char)
                                            char = iterator.next()
                                        }
                                    }
                                } ?: ""
                            referenceMarkedUnits = text.length - event.committedCharacterCount
                            File(output, "reference-events.jsonl").appendText(
                                evidenceJson(
                                    mapOf(
                                        "timestamp" to java.time.Instant.now().toString(),
                                        "committed" to event.committedCharacterCount,
                                        "utf16" to text.map { it.code },
                                        "markedUnits" to referenceMarkedUnits,
                                    ),
                                ) + "\n",
                                Charsets.UTF_8,
                            )
                        }
                        override fun caretPositionChanged(event: java.awt.event.InputMethodEvent) {}
                    })
                }
                val panel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                    add(
                        javax.swing.JLabel(
                            "Reference JTextArea — disposable comparison, separate from the editor document",
                        ),
                        java.awt.BorderLayout.NORTH,
                    )
                    add(javax.swing.JScrollPane(reference), java.awt.BorderLayout.CENTER)
                }
                (window.contentPane.layout as java.awt.BorderLayout).getLayoutComponent(
                    java.awt.BorderLayout.SOUTH,
                )?.let {
                    window.contentPane.remove(it)
                }
                window.contentPane.add(panel, java.awt.BorderLayout.SOUTH)
                window.validate()
            }
            window.title = "Astra Native Keyboard Probe ${ProcessHandle.current().pid()}"
            if ("--restore-copies" in
                args
            ) {
                showLocalRecovery(checkNotNull(recoveryDirectory), "noto-nastaliq", true, onlyIfNeeded = true)
            }
            mapOf("created" to true)
        }
        emit(
            owner {
                mapOf(
                    "status" to "READY",
                    "pid" to ProcessHandle.current().pid(),
                    "title" to window.title,
                    "frame" to snapshot(),
                    "native" to nativeCall("snapshot"),
                )
            },
        )
        for (line in System.`in`.bufferedReader(Charsets.UTF_8).lineSequence()) {
            val request = SimpleJson(line).parse() as Map<*, *>
            when (val command = request["command"] as String) {
                "snapshot" -> emit(
                    owner {
                        mapOf(
                            "status" to "OK",
                            "frame" to snapshot(),
                            "native" to nativeCall("snapshot"),
                        )
                    },
                )
                "tsf-query" -> emit(
                    mapOf(
                        "status" to "TSF_QUERY",
                        "main" to nativeCall("tsf"),
                        "owner" to owner { mapOf("query" to nativeCall("tsf")) },
                    ),
                )
                "prepare-ime" -> emit(mapOf("status" to "IME_PROFILE_RESULT", "native" to nativeCall("prepareIme")))
                "prepare" -> {
                    val language = request["language"] as String
                    val klid = when (language) {
                        "ur-PK" -> "00000420"
                        "ja-JP" -> "00000411"
                        "ar-SA" -> "00000401"
                        else -> error("Unsupported qualification locale")
                    }
                    val result = nativeCall("prepare", klid)
                    emit(
                        owner {
                            val accepted = if ((result as Map<*, *>)["status"] == "Prepared") {
                                surface.selectInputLocale(
                                    java.util.Locale.forLanguageTag(language),
                                    if (language ==
                                        "ja-JP"
                                    ) {
                                        true
                                    } else {
                                        null
                                    },
                                )
                            } else {
                                false
                            }
                            if ((result as Map<*, *>)["status"] == "Prepared") {
                                reference?.inputContext?.let { context ->
                                    context.selectInputMethod(java.util.Locale.forLanguageTag(language))
                                    if (language == "ja-JP") runCatching { context.isCompositionEnabled = true }
                                }
                            }
                            mapOf(
                                "status" to "SETUP_RESULT",
                                "native" to result,
                                "localeAccepted" to accepted,
                                "frame" to snapshot(),
                            )
                        },
                    )
                }
                "restore" -> emit(
                    mapOf(
                        "status" to "RESTORE_RESULT",
                        "native" to restore(),
                        "frame" to owner { snapshot() },
                    ),
                )
                "reopen" -> emit(
                    owner {
                        check(writing && !window.isDisplayable) {
                            "Close the disposable writing window before reopening"
                        }
                        val saved = DocumentFiles.read(writingPath)
                        val paged = surface.view.paged
                        window = showEditor(
                            diagnostics = File(output, "reopened-diagnostics.json"),
                            initial = saved,
                            initialPath = writingPath,
                            trace = trace,
                            fontProfile = "noto-nastaliq",
                            paged = paged,
                        )
                        surface = checkNotNull(windowCurrentSurface(window))
                        check(surface.viewModel.verificationEngine.snapshot().committed.same(saved))
                        window.title = "Astra Reopened Writing ${ProcessHandle.current().pid()}"
                        mapOf("status" to "REOPENED_FROM_DISK", "title" to window.title, "frame" to snapshot())
                    },
                )
                "adopt-open" -> emit(
                    owner {
                        val opened = javax.swing.JFrame.getFrames().filterIsInstance<javax.swing.JFrame>().single {
                            it.isDisplayable &&
                                it !== window &&
                                windowFiles(it)?.path == writingPath
                        }
                        window = opened
                        surface = checkNotNull(windowCurrentSurface(window))
                        mapOf("status" to "ADOPTED_NATIVE_OPEN", "frame" to snapshot())
                    },
                )
                "adopt-restored" -> emit(
                    owner {
                        val restored = javax.swing.JFrame.getFrames().filterIsInstance<javax.swing.JFrame>().single {
                            it.isDisplayable && it !== window && windowFiles(it)?.name?.contains("(recovered)") == true
                        }
                        checkNotNull(windowFiles(window)).close()
                        window = restored
                        surface = checkNotNull(windowCurrentSurface(window))
                        mapOf("status" to "RESTORED_WINDOW_SNAPSHOT", "frame" to snapshot())
                    },
                )
                "clean" -> emit(
                    owner {
                        check(surface.viewModel.verificationEngine.snapshot().marked == null) {
                            "Native composition must settle before fixture cleanup"
                        }
                        while (surface.viewModel.verificationEngine.snapshot().undoCount >
                            0
                        ) {
                            surface.viewModel.verificationEngine.perform(EditAction.Undo)
                        }
                        mapOf("status" to "CLEAN", "frame" to snapshot())
                    },
                )
                "shutdown" -> {
                    emit(mapOf("status" to "BYE", "native" to restore()))
                    return
                }
                else -> error("Unsupported keyboard fixture control: $command")
            }
        }
    } finally {
        watchdog.shutdownNow()
        try {
            restore()
            (native as AutoCloseable).close()
        } finally {
            if (created) SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
