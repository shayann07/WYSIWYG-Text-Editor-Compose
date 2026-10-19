package editor

import javax.swing.SwingUtilities
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Offscreen owned fixture for a separate native JAB client. No persistent activation. */
fun runNativeAccessibilityProbe() {
    lateinit var window: javax.swing.JFrame
    lateinit var surface: EditorSurface
    var created = false
    val fixture = "یہ اردو کتاب ہے۔ English 123 — مُحَمَّد۔ ".repeat(25) + "\nآخری سطر"
    val initial = Document(fixture.mapIndexed { index, char ->
        TextUnit(index.toLong() + 1, char, if (char == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
            if (char == '\n') TextStyleFlags() else TextStyleFlags(bold = index < 4, italic = index in 5..8),
            if (char == '\n') TextStyleFlags(italic = true) else TextStyleFlags())
    })
    val watchdog = Executors.newSingleThreadScheduledExecutor()
    watchdog.schedule({ System.err.println("Native AT fixture deadline expired"); kotlin.system.exitProcess(124) }, 90, TimeUnit.SECONDS)
    val output = System.out.writer(Charsets.UTF_8).buffered()
    fun emit(value: Any?) { output.write(evidenceJson(value)); output.write("\n"); output.flush() }
    fun onOwner(block: () -> Map<String, Any?>): Map<String, Any?> {
        var result: Map<String, Any?>? = null
        SwingUtilities.invokeAndWait { result = block() }; return checkNotNull(result)
    }
    fun frame(): Map<String, Any?> {
        val f = surface.engine.snapshot(); val geometry = surface.geometry()
        val boundary = geometry?.fragments?.firstOrNull { it.page == 1 }?.let {
            val p = geometry.paragraphs[it.paragraphIndex]; p.startOffset + p.layout.getLineStart(it.firstLine)
        }
        val index = boundary ?: 0
        val bounds = surface.accessibleContext.accessibleText.getCharacterBounds(index)
        val location = surface.locationOnScreen
        fun device(rect: java.awt.Rectangle): java.awt.Rectangle {
            val environment = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            val fallback = environment.defaultScreenDevice.defaultConfiguration
            val config = if (fallback.bounds.contains(rect.x, rect.y)) fallback else
                environment.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(rect.x, rect.y) } ?: fallback
            val screen = config.bounds; val transform = config.defaultTransform
            fun round(value: Double) = kotlin.math.ceil(value - .5).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
            return java.awt.Rectangle(screen.x + round((rect.x - screen.x) * transform.scaleX),
                screen.y + round((rect.y - screen.y) * transform.scaleY), round(rect.width * transform.scaleX), round(rect.height * transform.scaleY))
        }
        // Installed JDK 23 JAB converts the local text rectangle and the component
        // screen rectangle separately to device space, then translates the former.
        val deviceBounds = bounds?.let { local -> device(local).apply {
            val origin = device(java.awt.Rectangle(location.x, location.y, surface.width, surface.height))
            translate(origin.x, origin.y)
        } }
        return mapOf("text" to f.visible.text, "units" to f.visible.units.map { listOf(it.id, it.char.code, it.kind.ordinal, it.style.bits, it.paragraphStyle.bits) },
            "selection" to listOf(f.selection.anchor, f.selection.focus), "undo" to f.undoCount, "redo" to f.redoCount,
            "revision" to f.documentRevision, "pageCount" to geometry?.pageCount, "geometryIndex" to index,
            "screenCharacterBounds" to bounds?.let { listOf(it.x + location.x, it.y + location.y, it.width, it.height) },
            "nativeBridgeDeviceCharacterBounds" to deviceBounds?.let { listOf(it.x, it.y, it.width, it.height) },
            "deviceScale" to listOf(surface.graphicsConfiguration.defaultTransform.scaleX, surface.graphicsConfiguration.defaultTransform.scaleY))
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
            check(System.nanoTime() < deadline) { "Owned AT fixture did not produce layout" }; Thread.sleep(20)
        }
        emit(onOwner { mapOf("status" to "READY", "pid" to ProcessHandle.current().pid(), "title" to window.title,
            "assistiveProvider" to System.getProperty("javax.accessibility.assistive_technologies"), "frame" to frame()) })
        for (line in System.`in`.bufferedReader(Charsets.UTF_8).lineSequence()) {
            val request = SimpleJson(line).parse() as Map<*, *>
            val command = request["command"] as String
            if (command == "shutdown") { emit(mapOf("status" to "BYE")); return }
            emit(onOwner {
                when (command) {
                    "snapshot" -> {}
                    "undo" -> surface.engine.perform(EditAction.Undo)
                    "redo" -> surface.engine.perform(EditAction.Redo)
                    "typography" -> surface.setTypography(TypographyProfile.load(request["profile"] as String))
                    else -> error("Unsupported AT fixture control")
                }
                mapOf("status" to "OK", "frame" to frame())
            })
        }
    } finally {
        watchdog.shutdownNow()
        if (created) SwingUtilities.invokeAndWait { window.dispose() }
    }
}
