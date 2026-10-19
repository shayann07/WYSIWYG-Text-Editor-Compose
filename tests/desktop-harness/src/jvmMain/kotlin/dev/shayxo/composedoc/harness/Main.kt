package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.cloneDocument
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.ImeTrace
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.WorkspaceUi
import dev.shayxo.composedoc.workspace.localWorkspaceSample
import dev.shayxo.composedoc.workspace.pagesWritingSample
import dev.shayxo.composedoc.workspace.showEditor
import dev.shayxo.composedoc.workspace.showLocalRecovery
import dev.shayxo.composedoc.workspace.showUnfinishedRecovery
import dev.shayxo.composedoc.workspace.structuredWritingSample
import dev.shayxo.composedoc.workspace.windowFiles
import java.io.File
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

private const val WORKSPACE_SAMPLE_PATH = "apps/desktop-workspace/samples/disposable-workspace-v8.astra"
private const val URDU_SAMPLE_PATH = "apps/desktop-workspace/samples/disposable-urdu-sample-v6.astra"

fun main(args: Array<String>) {
    // The first matching route wins, in the same order as the original flag checks.
    val route = mainHarnessRoutes.firstOrNull { it.matches(args) }
    if (route != null) {
        route.run(args)
        return
    }
    launchEditor(args)
}

/** One CLI mode of the harness: [run] either exits the process or returns to end `main`. */
private class MainHarnessRoute(val matches: (Array<String>) -> Boolean, val run: (Array<String>) -> Unit)

private fun flagRoute(flag: String, run: (Array<String>) -> Unit) = MainHarnessRoute({ flag in it }, run)

private fun prefixRoute(prefix: String, run: (String) -> Unit) = MainHarnessRoute(
    { args -> args.any { it.startsWith(prefix) } },
    { args -> run(args.first { it.startsWith(prefix) }) },
)

/** Runs a check that exits 0 on success; on failure it prints the stack trace and exits 1. */
private fun exitAfterCheck(check: () -> Unit) {
    try {
        check()
        kotlin.system.exitProcess(0)
    } catch (failure: Throwable) {
        printFailure(failure)
        kotlin.system.exitProcess(1)
    }
}

/** Writes exactly the text of `Throwable.printStackTrace()` to stderr. */
private fun printFailure(failure: Throwable) {
    System.err.print(failure.stackTraceToString())
}

/** `--host-result=` resolved inside the guarded check (a missing or repeated flag fails the check). */
private fun singleHostResult(args: Array<String>): File =
    File(args.single { it.startsWith("--host-result=") }.substringAfter('='))

/** `--host-result=` resolved before the guarded check (a missing flag escapes `main`). */
private fun requiredHostResult(args: Array<String>): File =
    File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))

private fun guardedHostResultRoute(flag: String, check: (File) -> Unit) =
    flagRoute(flag) { args -> exitAfterCheck { check(singleHostResult(args)) } }

private fun requiredHostResultRoute(flag: String, check: (File) -> Unit) = flagRoute(flag) { args ->
    val result = requiredHostResult(args)
    exitAfterCheck { check(result) }
}

private fun writeSample(target: java.nio.file.Path, document: Document) {
    java.nio.file.Files.write(target, DocumentFiles.encode(document), java.nio.file.StandardOpenOption.CREATE_NEW)
}

private val mainHarnessRoutes: List<MainHarnessRoute> =
    listOf(
        prefixRoute("--typography-sheet=") { value ->
            exitAfterCheck { typographySheet(File(value.substringAfter('='))) }
        },
        guardedHostResultRoute("--pages-output-check", ::runPagesOutputChecks),
        prefixRoute("--create-pages-sample=") { value ->
            val path = java.nio.file.Path.of(value.substringAfter('=')).toAbsolutePath().normalize()
            java.nio.file.Files.createDirectories(path.parent)
            writeSample(path, pagesWritingSample())
            println("Created disposable pages sample: $path")
        },
        guardedHostResultRoute("--workspace-host-check", ::runWorkspaceHostChecks),
        flagRoute("--workspace-native-fixture", ::workspaceNativeProbe),
        flagRoute("--workspace-child", ::workspaceChild),
        guardedHostResultRoute("--workspace-check", ::runWorkspaceChecks),
        guardedHostResultRoute("--v6-qualification-check", ::runV6Qualification),
        guardedHostResultRoute("--visual-repair-check", ::runVisualRepairChecks),
        guardedHostResultRoute("--structure-check", ::runStructureChecks),
        MainHarnessRoute({ args -> args.any { it.startsWith("--clipboard-child=") } }, ::clipboardChild),
        requiredHostResultRoute("--connected-writing-check", ::runConnectedWritingChecks),
        requiredHostResultRoute("--paragraph-check", ::runParagraphChecks),
        requiredHostResultRoute("--find-replace-check", ::runFindReplaceChecks),
        flagRoute("--local-recovery-child", ::runLocalRecoveryChild),
        requiredHostResultRoute("--local-recovery-check", ::runLocalRecoveryChecks),
        requiredHostResultRoute("--writing-check", ::runWritingChecks),
        prefixRoute("--create-workspace-sample=") {
            val target = java.nio.file.Path.of(it.substringAfter('='))
            writeSample(target, localWorkspaceSample())
            println("Created independent workspace sample: $target")
        },
        prefixRoute("--create-urdu-sample=") {
            val target = java.nio.file.Path.of(it.substringAfter('=')).toAbsolutePath().normalize()
            writeSample(target, structuredWritingSample())
            println("Created disposable Urdu sample: $target")
        },
        requiredHostResultRoute("--recovery-ui-check", ::runRecoveryUiChecks),
        flagRoute("--web-owner-probe") { runWebOwnerProbe() },
        flagRoute("--native-at-probe") { exitAfterCheck { runNativeAccessibilityProbe() } },
        flagRoute("--native-keyboard-probe") { args -> exitAfterCheck { runNativeKeyboardProbe(args) } },
        flagRoute("--accessibility-thread-check") { args ->
            val file =
                File(
                    args.firstOrNull { it.startsWith("--host-result=") }?.substringAfter('=')
                        ?: "tests/local-evidence/desktop/accessibility-thread.json",
                )
            exitAfterCheck { runAccessibilityThreadChecks(file) }
        },
        MainHarnessRoute({ "--schema-probe" in it || "--schema-probe-verify" in it }, ::runSchemaProbe),
        flagRoute("--page-probe", ::runPageProbe),
        flagRoute("--glyph-cluster-probe", ::runGlyphClusterProbe),
        flagRoute("--urdu-u0-check", ::runUrduChecks),
        flagRoute("--check") {
            runChecks()
            runFileChecks()
            runFormatChecks()
            runRecoveryChecks()
            runCanonicalProbeChecks()
        },
        flagRoute("--stress") { runStressChecks() },
    )

/** Editor launch options, resolved from [args] on the main thread in declaration order. */
private class MainEditorOptions(val args: Array<String>) {
    val diagnostics = args.indexOf("--diagnostics").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val trace =
        if ("--ime-check" in args) {
            ImeTrace(File("tests/local-evidence/desktop/ime-${System.currentTimeMillis()}"))
        } else {
            null
        }
    val language = args.firstOrNull { it.startsWith("--ime-language=") }?.substringAfter('=')
    val writing = "--urdu-writing" in args || "--pages-sample" in args
    val recoveryDirectory: java.nio.file.Path =
        args
            .firstOrNull { it.startsWith("--recovery-dir=") }
            ?.substringAfter('=')
            ?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }
            ?: LocalRecoveryStore.defaultRoot()
    val fontProfile =
        args.firstOrNull { it.startsWith("--font-profile=") }?.substringAfter('=')
            ?: if (writing) "noto-nastaliq" else "system"
    val sourcePath =
        args
            .firstOrNull { it.startsWith("--open=") }
            ?.substringAfter('=')
            ?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }

    init {
        require("--sample" !in args || (writing && sourcePath == null)) { "Use --urdu-writing --sample without --open" }
    }
}

private fun launchEditor(args: Array<String>) {
    val options = MainEditorOptions(args)
    val initialDocument = loadInitialDocument(args, options.sourcePath) ?: return
    val hostResult =
        args.firstOrNull { it.startsWith("--host-result=") }?.substringAfter('=')?.let(::File)
            ?: File("apps/desktop-workspace/target/host-checks.json")
    val trace = options.trace
    val language = options.language
    require(language == null || (trace != null && language in listOf("en-US", "ja-JP", "ur-PK", "ar-SA"))) {
        "--ime-language requires --ime-check and a supported test language"
    }
    if (trace != null) {
        println(
            "Live IME test only. Test text is logged locally at ${trace.directory.absolutePath}. " +
                "No events are injected. No automatic qualification verdict.",
        )
    }
    SwingUtilities.invokeLater { openEditorWindow(options, initialDocument, hostResult) }
}

/** Returns null after telling the user the document could not be opened. */
private fun loadInitialDocument(args: Array<String>, sourcePath: java.nio.file.Path?): Document? = try {
    when {
        sourcePath != null -> DocumentFiles.read(sourcePath)
        "--pages-sample" in args -> cloneDocument(pagesWritingSample())
        "--workspace-sample" in args -> cloneDocument(
            DocumentFiles.read(java.nio.file.Path.of(WORKSPACE_SAMPLE_PATH)),
        )
        "--sample" in args -> DocumentFiles.read(java.nio.file.Path.of(URDU_SAMPLE_PATH))
        else -> Document(emptyList())
    }
} catch (failure: Exception) {
    SwingUtilities.invokeAndWait {
        JOptionPane.showMessageDialog(
            null,
            failure.message,
            "Document could not be opened",
            JOptionPane.ERROR_MESSAGE,
        )
    }
    null
}

private fun openEditorWindow(launch: MainEditorOptions, initialDocument: Document, hostResult: File) {
    val args = launch.args
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val pagedCheck = "--page-host-check" in args
    val checkMode = "--host-check" in args || pagedCheck
    val window =
        try {
            showEditor(
                launch.diagnostics,
                initial = initialDocument,
                initialPath = launch.sourcePath,
                trace = launch.trace,
                traceLanguage = launch.language,
                offscreenTest = checkMode,
                fontProfile = launch.fontProfile,
                paged = pagedCheck || launch.writing || "--paged" in args,
                recoveryDirectory = if (checkMode) null else launch.recoveryDirectory,
            )
        } catch (failure: Throwable) {
            printFailure(failure)
            kotlin.system.exitProcess(1)
        }
    if ("--workspace-sample" in args) windowFiles(window)!!.markUnsavedCopy("Workspace sample")
    if ("--pages-sample" in args) {
        windowFiles(window)!!.markUnsavedCopy("Urdu pages specimen - human typography review pending")
    }
    if (!checkMode) showStartupRecovery(launch, window)
    if ("--host-check" in args || pagedCheck) {
        MainHostCheckPoller(window, pagedCheck, hostResult).timer.start()
    }
}

private fun showStartupRecovery(launch: MainEditorOptions, window: JFrame) {
    val paged = launch.writing || "--paged" in launch.args
    showLocalRecovery(launch.recoveryDirectory, launch.fontProfile, paged = paged, onlyIfNeeded = true)
    showUnfinishedRecovery(
        launch.recoveryDirectory.resolve("unfinished-records"),
        launch.fontProfile,
        paged,
        onlyIfNeeded = true,
    )
    (window.rootPane.getClientProperty("editor.workspace") as? WorkspaceUi)?.showRecent(onlyRestore = true)
}

/** Polls the editor window on the EDT until its layout is ready, then runs the host checks and exits. */
private class MainHostCheckPoller(
    private val window: JFrame,
    private val pagedCheck: Boolean,
    private val hostResult: File,
) {
    private var attempts = 0
    private var pageChecksFinished = false
    val timer = javax.swing.Timer(150, null)

    init {
        timer.addActionListener { onTick() }
    }

    private fun onTick() {
        val surface = findSurface(window)
        if (surface?.view?.measure != null) {
            try {
                checkReadySurface(surface)
            } catch (failure: Throwable) {
                printFailure(failure)
                window.dispose()
                kotlin.system.exitProcess(1)
            }
        } else if (++attempts > 50) {
            timer.stop()
            System.err.println("Editor did not produce layout for host checks")
            window.dispose()
            kotlin.system.exitProcess(1)
        }
    }

    private fun checkReadySurface(surface: EditorSurface) {
        if (pagedCheck && !pageChecksFinished) {
            runPagedHostChecks(surface, hostResult)
            pageChecksFinished = true
            attempts = 0
            return
        }
        if (pagedCheck &&
            surface.view.geometry?.revision != surface.viewModel.verificationEngine.snapshot().documentRevision
        ) {
            if (++attempts <= 50) return
            error("Paged surface did not render current document")
        }
        timer.stop()
        if (pagedCheck) {
            writePagedScreenshot()
        } else {
            runHostChecks(surface, hostResult)
        }
        window.dispose()
        kotlin.system.exitProcess(0)
    }

    private fun writePagedScreenshot() {
        val bitmap =
            java.awt.image.BufferedImage(window.width, window.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        bitmap.createGraphics().let { g ->
            try {
                window.paint(g)
            } finally {
                g.dispose()
            }
        }
        javax.imageio.ImageIO.write(bitmap, "png", File(hostResult.parentFile, "paged-host.png"))
    }
}
