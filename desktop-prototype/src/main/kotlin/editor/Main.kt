package editor

import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    if ("--check" in args) { runChecks(); return }
    val diagnostics = args.indexOf("--diagnostics").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val window = showEditor(diagnostics)
        if ("--host-check" in args) {
            var attempts = 0
            val timer = javax.swing.Timer(150, null)
            timer.addActionListener {
                val surface = findSurface(window)
                if (surface?.view?.measure != null) {
                    timer.stop()
                    runCatching { runHostChecks(surface) }.onFailure { it.printStackTrace() }
                } else if (++attempts > 50) { timer.stop(); error("Editor did not produce layout for host checks") }
            }
            timer.start()
        }
    }
}
