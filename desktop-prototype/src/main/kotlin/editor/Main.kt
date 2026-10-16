package editor

import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    if ("--check" in args) { runChecks(); runFileChecks(); runFormatChecks(); return }
    if ("--stress" in args) { runStressChecks(); return }
    val diagnostics = args.indexOf("--diagnostics").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val trace = if ("--ime-check" in args) ImeTrace(File("desktop-prototype/local-evidence/ime-${System.currentTimeMillis()}")) else null
    val language = args.firstOrNull { it.startsWith("--ime-language=") }?.substringAfter('=')
    val hostResult = args.firstOrNull { it.startsWith("--host-result=") }?.substringAfter('=')?.let(::File) ?: File("desktop-prototype/target/host-checks.json")
    require(language == null || (trace != null && language in listOf("en-US","ja-JP","ur-PK","ar-SA"))) { "--ime-language requires --ime-check and a supported test language" }
    if (trace != null) println("Live IME test only. Test text is logged locally at ${trace.directory.absolutePath}. No events are injected. No automatic qualification verdict.")
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val window = try { showEditor(diagnostics, trace = trace, traceLanguage = language, offscreenTest = "--host-check" in args) } catch (failure: Throwable) { failure.printStackTrace(); kotlin.system.exitProcess(1) }
        if ("--host-check" in args) {
            var attempts = 0
            val timer = javax.swing.Timer(150, null)
            timer.addActionListener {
                val surface = findSurface(window)
                if (surface?.view?.measure != null) {
                    timer.stop()
                    try { runHostChecks(surface, hostResult); window.dispose(); kotlin.system.exitProcess(0) }
                    catch (failure: Throwable) { failure.printStackTrace(); window.dispose(); kotlin.system.exitProcess(1) }
                } else if (++attempts > 50) { timer.stop(); System.err.println("Editor did not produce layout for host checks"); window.dispose(); kotlin.system.exitProcess(1) }
            }
            timer.start()
        }
    }
}
