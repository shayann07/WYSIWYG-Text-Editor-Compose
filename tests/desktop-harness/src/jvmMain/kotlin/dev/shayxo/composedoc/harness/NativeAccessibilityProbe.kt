package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.SimpleJson
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.showEditor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Offscreen owned fixture for a separate native JAB client. No persistent activation. */
fun runNativeAccessibilityProbe() {
    lateinit var window: javax.swing.JFrame
    lateinit var surface: EditorSurface
    var created = false
    val initial = nativeAtFixtureDocument()
    val watchdog = Executors.newSingleThreadScheduledExecutor()
    watchdog.schedule({
        System.err.println("Native AT fixture deadline expired")
        kotlin.system.exitProcess(124)
    }, 90, TimeUnit.SECONDS)
    val output = System.out.writer(Charsets.UTF_8).buffered()
    fun emit(value: Any?) {
        output.write(evidenceJson(value))
        output.write("\n")
        output.flush()
    }
    try {
        SwingUtilities.invokeAndWait {
            window = showEditor(initial = initial, offscreenTest = true, fontProfile = "noto-nastaliq", paged = true)
            created = true
            window.title = "Astra Native AT Probe ${ProcessHandle.current().pid()}"
            surface = checkNotNull(findSurface(window))
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (onOwner { mapOf("ready" to (surface.view.measure != null)) }["ready"] != true) {
            check(System.nanoTime() < deadline) { "Owned AT fixture did not produce layout" }
            Thread.sleep(20)
        }
        emit(
            onOwner {
                mapOf(
                    "status" to "READY",
                    "pid" to ProcessHandle.current().pid(),
                    "title" to window.title,
                    "assistiveProvider" to System.getProperty("javax.accessibility.assistive_technologies"),
                    "frame" to nativeAtFrame(surface),
                )
            },
        )
        for (line in System.`in`.bufferedReader(Charsets.UTF_8).lineSequence()) {
            val request = SimpleJson(line).parse() as Map<*, *>
            val command = request["command"] as String
            if (command == "shutdown") {
                emit(mapOf("status" to "BYE"))
                return
            }
            emit(
                onOwner {
                    applyNativeAtCommand(surface, request, command)
                    mapOf("status" to "OK", "frame" to nativeAtFrame(surface))
                },
            )
        }
    } finally {
        watchdog.shutdownNow()
        if (created) SwingUtilities.invokeAndWait { window.dispose() }
    }
}

private fun nativeAtFixtureDocument(): Document {
    val fixture = "یہ اردو کتاب ہے۔ English 123 — مُحَمَّد۔ ".repeat(25) + "\nآخری سطر"
    return Document(
        fixture.mapIndexed { index, char ->
            TextUnit(
                index.toLong() + 1,
                char,
                if (char == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                if (char == '\n') TextStyleFlags() else TextStyleFlags(bold = index < 4, italic = index in 5..8),
                if (char == '\n') TextStyleFlags(italic = true) else TextStyleFlags(),
            )
        },
    )
}

/** Runs [block] on the EDT (the document owner thread) and returns its result. */
private fun onOwner(block: () -> Map<String, Any?>): Map<String, Any?> {
    var result: Map<String, Any?>? = null
    SwingUtilities.invokeAndWait { result = block() }
    return checkNotNull(result)
}

/** Must run on the EDT. */
private fun applyNativeAtCommand(surface: EditorSurface, request: Map<*, *>, command: String) {
    when (command) {
        "snapshot" -> {}
        "undo" -> surface.viewModel.verificationEngine.perform(EditAction.Undo)
        "redo" -> surface.viewModel.verificationEngine.perform(EditAction.Redo)
        "typography" -> surface.changeTypography(TypographyProfile.load(request["profile"] as String))
        else -> error("Unsupported AT fixture control")
    }
}

private fun jabRound(value: Double) =
    kotlin.math.ceil(value - .5).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()

/** Converts a screen rectangle to device space the way the installed JAB bridge does. */
private fun jabDeviceRect(rect: java.awt.Rectangle): java.awt.Rectangle {
    val environment = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val fallback = environment.defaultScreenDevice.defaultConfiguration
    val config = if (fallback.bounds.contains(rect.x, rect.y)) {
        fallback
    } else {
        environment.screenDevices.map {
            it.defaultConfiguration
        }.firstOrNull { it.bounds.contains(rect.x, rect.y) }
            ?: fallback
    }
    val screen = config.bounds
    val transform = config.defaultTransform
    return java.awt.Rectangle(
        screen.x + jabRound((rect.x - screen.x) * transform.scaleX),
        screen.y + jabRound((rect.y - screen.y) * transform.scaleY),
        jabRound(rect.width * transform.scaleX),
        jabRound(rect.height * transform.scaleY),
    )
}

/** Must run on the EDT. */
private fun nativeAtFrame(surface: EditorSurface): Map<String, Any?> {
    val f = surface.viewModel.verificationEngine.snapshot()
    val geometry = surface.geometry()
    val boundary = geometry?.fragments?.firstOrNull { it.page == 1 }?.let {
        val p = geometry.paragraphs[it.paragraphIndex]
        p.startOffset + p.layout.getLineStart(it.firstLine)
    }
    val index = boundary ?: 0
    val bounds = surface.accessibleContext.accessibleText.getCharacterBounds(index)
    val location = surface.locationOnScreen
    // Installed JDK 23 JAB converts the local text rectangle and the component
    // screen rectangle separately to device space, then translates the former.
    val deviceBounds = bounds?.let { local ->
        jabDeviceRect(local).apply {
            val origin = jabDeviceRect(java.awt.Rectangle(location.x, location.y, surface.width, surface.height))
            translate(origin.x, origin.y)
        }
    }
    return mapOf(
        "text" to f.visible.text,
        "units" to
            f.visible.units.map {
                listOf(it.id, it.char.code, it.kind.ordinal, it.style.bits, it.paragraphStyle.bits)
            },
        "selection" to listOf(f.selection.anchor, f.selection.focus), "undo" to f.undoCount, "redo" to f.redoCount,
        "revision" to f.documentRevision, "pageCount" to geometry?.pageCount, "geometryIndex" to index,
        "screenCharacterBounds" to bounds?.let {
            listOf(it.x + location.x, it.y + location.y, it.width, it.height)
        },
        "nativeBridgeDeviceCharacterBounds" to deviceBounds?.let { listOf(it.x, it.y, it.width, it.height) },
        "deviceScale" to
            listOf(
                surface.graphicsConfiguration.defaultTransform.scaleX,
                surface.graphicsConfiguration.defaultTransform.scaleY,
            ),
    )
}
