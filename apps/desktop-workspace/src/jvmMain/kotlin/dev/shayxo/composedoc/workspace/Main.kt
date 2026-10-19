package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.cloneDocument
import dev.shayxo.composedoc.model.Document
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Example consumer entry point. Qualification commands live in the test harness. */
fun main(args: Array<String>) {
    fun value(key: String) = args.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
    val source = value("--open")?.let(Path::of)
    val sample = args.any { it in setOf("--sample", "--workspace-sample", "--pages-sample") }
    require(source == null || !sample) { "Choose a sample or a document, not both" }
    val initial = try {
        when {
            source != null -> DocumentFiles.read(source)
            "--pages-sample" in args -> cloneDocument(pagesWritingSample())
            "--workspace-sample" in args -> cloneDocument(DocumentFiles.read(Path.of("apps/desktop-workspace/samples/disposable-workspace-v8.astra")))
            "--sample" in args -> cloneDocument(DocumentFiles.read(Path.of("apps/desktop-workspace/samples/disposable-urdu-sample-v6.astra")))
            else -> Document(emptyList())
        }
    } catch (failure: Exception) {
        SwingUtilities.invokeAndWait { JOptionPane.showMessageDialog(null, failure.message, "Document could not be opened", JOptionPane.ERROR_MESSAGE) }
        return
    }
    val recovery = value("--recovery-dir")?.let(Path::of) ?: LocalRecoveryStore.defaultRoot()
    val font = value("--font-profile") ?: "noto-nastaliq"
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val window = showEditor(initial = initial, initialPath = source, fontProfile = font, paged = true, recoveryDirectory = recovery)
        if (sample) windowFiles(window)!!.markUnsavedCopy("ComposeDoc sample")
        showLocalRecovery(recovery, font, true, onlyIfNeeded = true)
        showUnfinishedRecovery(recovery.resolve("unfinished-records"), font, true, onlyIfNeeded = true)
        (window.rootPane.getClientProperty("editor.workspace") as? WorkspaceUi)?.showRecent(onlyRestore = true)
    }
}
