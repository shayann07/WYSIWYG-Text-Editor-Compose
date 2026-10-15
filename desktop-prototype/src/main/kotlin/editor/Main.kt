package editor

import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    if ("--check" in args) { runChecks(); runFileChecks(); runFormatChecks(); return }
    if ("--stress" in args) { runStressChecks(); return }
    val diagnostics = args.indexOf("--diagnostics").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val window = try { showEditor(diagnostics) } catch (failure: Throwable) { failure.printStackTrace(); kotlin.system.exitProcess(1) }
        if ("--host-check" in args) {
            var attempts = 0
            val timer = javax.swing.Timer(150, null)
            timer.addActionListener {
                val surface = findSurface(window)
                if (surface?.view?.measure != null) {
                    timer.stop()
                    try { runHostChecks(surface); window.dispose() }
                    catch (failure: Throwable) { failure.printStackTrace(); window.dispose(); kotlin.system.exitProcess(1) }
                } else if (++attempts > 50) { timer.stop(); System.err.println("Editor did not produce layout for host checks"); window.dispose(); kotlin.system.exitProcess(1) }
            }
            timer.start()
        }
    }
}
