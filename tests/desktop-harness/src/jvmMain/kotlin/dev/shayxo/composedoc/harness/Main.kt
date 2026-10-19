package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    args.firstOrNull { it.startsWith("--typography-sheet=") }?.let { value ->
        try {
            typographySheet(File(value.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--pages-output-check" in args) {
        try {
            runPagesOutputChecks(File(args.single { it.startsWith("--host-result=") }.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    args.firstOrNull { it.startsWith("--create-pages-sample=") }?.let { value ->
        val path = java.nio.file.Path.of(value.substringAfter('=')).toAbsolutePath().normalize()
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.write(path, DocumentFiles.encode(pagesWritingSample()), java.nio.file.StandardOpenOption.CREATE_NEW)
        println("Created disposable pages sample: $path")
        return
    }
    if ("--workspace-host-check" in args) {
        try {
            runWorkspaceHostChecks(File(args.single { it.startsWith("--host-result=") }.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--workspace-native-fixture" in args) {
        workspaceNativeProbe(args)
        return
    }
    if ("--workspace-child" in args) {
        workspaceChild(args)
        return
    }
    if ("--workspace-check" in args) {
        try {
            runWorkspaceChecks(File(args.single { it.startsWith("--host-result=") }.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--v6-qualification-check" in args) {
        try {
            runV6Qualification(File(args.single { it.startsWith("--host-result=") }.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--visual-repair-check" in args) {
        try {
            runVisualRepairChecks(File(args.single { it.startsWith("--host-result=") }.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--structure-check" in args) {
        try {
            runStructureChecks(File(args.single { it.startsWith("--host-result=") }.substringAfter('=')))
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if (args.any { it.startsWith("--clipboard-child=") }) {
        clipboardChild(args)
        return
    }
    if ("--connected-writing-check" in args) {
        val result = File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))
        try {
            runConnectedWritingChecks(result)
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--paragraph-check" in args) {
        val result = File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))
        try {
            runParagraphChecks(result)
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--find-replace-check" in args) {
        val result = File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))
        try {
            runFindReplaceChecks(result)
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--local-recovery-child" in args) {
        runLocalRecoveryChild(args)
        return
    }
    if ("--local-recovery-check" in args) {
        val result = File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))
        try {
            runLocalRecoveryChecks(result)
            kotlin.system.exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--writing-check" in args) {
        val result = File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))
        try {
            runWritingChecks(result)
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    args.firstOrNull { it.startsWith("--create-workspace-sample=") }?.let {
        val target = java.nio.file.Path.of(it.substringAfter('='))
        java.nio.file.Files.write(target, DocumentFiles.encode(localWorkspaceSample()), java.nio.file.StandardOpenOption.CREATE_NEW)
        println("Created independent workspace sample: $target")
        return
    }
    args.firstOrNull { it.startsWith("--create-urdu-sample=") }?.let {
        val target = java.nio.file.Path.of(it.substringAfter('=')).toAbsolutePath().normalize()
        java.nio.file.Files.write(target, DocumentFiles.encode(structuredWritingSample()), java.nio.file.StandardOpenOption.CREATE_NEW)
        println("Created disposable Urdu sample: $target")
        return
    }
    if ("--recovery-ui-check" in args) {
        val result = File(checkNotNull(args.firstOrNull { it.startsWith("--host-result=") }).substringAfter('='))
        try {
            runRecoveryUiChecks(result)
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--web-owner-probe" in args) {
        runWebOwnerProbe()
        return
    }
    if ("--native-at-probe" in args) {
        try {
            runNativeAccessibilityProbe()
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--native-keyboard-probe" in args) {
        try {
            runNativeKeyboardProbe(args)
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--accessibility-thread-check" in args) {
        val file = File(args.firstOrNull { it.startsWith("--host-result=") }?.substringAfter('=') ?: "tests/local-evidence/desktop/accessibility-thread.json")
        try {
            runAccessibilityThreadChecks(file)
            kotlin.system.exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
    if ("--schema-probe" in args || "--schema-probe-verify" in args) {
        runSchemaProbe(args)
        return
    }
    if ("--page-probe" in args) {
        runPageProbe(args)
        return
    }
    if ("--glyph-cluster-probe" in args) {
        runGlyphClusterProbe(args)
        return
    }
    if ("--urdu-u0-check" in args) {
        runUrduChecks(args)
        return
    }
    if ("--check" in args) {
        runChecks()
        runFileChecks()
        runFormatChecks()
        runRecoveryChecks()
        runCanonicalProbeChecks()
        return
    }
    if ("--stress" in args) {
        runStressChecks()
        return
    }
    val diagnostics = args.indexOf("--diagnostics").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val trace = if ("--ime-check" in args) ImeTrace(File("tests/local-evidence/desktop/ime-${System.currentTimeMillis()}")) else null
    val language = args.firstOrNull { it.startsWith("--ime-language=") }?.substringAfter('=')
    val writing = "--urdu-writing" in args || "--pages-sample" in args
    val recoveryDirectory = args.firstOrNull { it.startsWith("--recovery-dir=") }?.substringAfter('=')?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() } ?: LocalRecoveryStore.defaultRoot()
    val fontProfile = args.firstOrNull { it.startsWith("--font-profile=") }?.substringAfter('=') ?: if (writing) "noto-nastaliq" else "system"
    val sourcePath = args.firstOrNull { it.startsWith("--open=") }?.substringAfter('=')?.let { java.nio.file.Path.of(it).toAbsolutePath().normalize() }
    require("--sample" !in args || (writing && sourcePath == null)) { "Use --urdu-writing --sample without --open" }
    val initialDocument = try {
        when {
            sourcePath != null -> DocumentFiles.read(sourcePath)
            "--pages-sample" in args -> cloneDocument(pagesWritingSample())
            "--workspace-sample" in args -> cloneDocument(DocumentFiles.read(java.nio.file.Path.of("apps/desktop-workspace/samples/disposable-workspace-v8.astra")))
            "--sample" in args -> DocumentFiles.read(java.nio.file.Path.of("apps/desktop-workspace/samples/disposable-urdu-sample-v6.astra"))
            else -> Document(emptyList())
        }
    } catch (failure: Exception) {
        SwingUtilities.invokeAndWait { JOptionPane.showMessageDialog(null, failure.message, "Document could not be opened", JOptionPane.ERROR_MESSAGE) }
        return
    }
    val hostResult = args.firstOrNull { it.startsWith("--host-result=") }?.substringAfter('=')?.let(::File) ?: File("apps/desktop-workspace/target/host-checks.json")
    require(language == null || (trace != null && language in listOf("en-US", "ja-JP", "ur-PK", "ar-SA"))) { "--ime-language requires --ime-check and a supported test language" }
    if (trace != null) println("Live IME test only. Test text is logged locally at ${trace.directory.absolutePath}. No events are injected. No automatic qualification verdict.")
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val pagedCheck = "--page-host-check" in args
        val checkMode = "--host-check" in args || pagedCheck
        val window = try {
            showEditor(diagnostics, initial = initialDocument, initialPath = sourcePath, trace = trace, traceLanguage = language, offscreenTest = checkMode, fontProfile = fontProfile, paged = pagedCheck || writing || "--paged" in args, recoveryDirectory = if (checkMode) null else recoveryDirectory)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            kotlin.system.exitProcess(1)
        }
        if ("--workspace-sample" in args)windowFiles(window)!!.markUnsavedCopy("Workspace sample")
        if ("--pages-sample" in args)windowFiles(window)!!.markUnsavedCopy("Urdu pages specimen - human typography review pending")
        if (!checkMode) {
            showLocalRecovery(recoveryDirectory, fontProfile, paged = writing || "--paged" in args, onlyIfNeeded = true)
            showUnfinishedRecovery(recoveryDirectory.resolve("unfinished-records"), fontProfile, writing || "--paged" in args, onlyIfNeeded = true)
            (window.rootPane.getClientProperty("editor.workspace") as? WorkspaceUi)?.showRecent(onlyRestore = true)
        }
        if ("--host-check" in args || pagedCheck) {
            var attempts = 0
            var pageChecksFinished = false
            val timer = javax.swing.Timer(150, null)
            timer.addActionListener {
                val surface = findSurface(window)
                if (surface?.view?.measure != null) {
                    try {
                        if (pagedCheck && !pageChecksFinished) {
                            runPagedHostChecks(surface, hostResult)
                            pageChecksFinished = true
                            attempts = 0
                            return@addActionListener
                        }
                        if (pagedCheck && surface.view.geometry?.revision != surface.engine.snapshot().documentRevision) {
                            if (++attempts <= 50) return@addActionListener
                            error("Paged surface did not render current document")
                        }
                        timer.stop()
                        if (pagedCheck) {
                            val bitmap = java.awt.image.BufferedImage(window.width, window.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                            bitmap.createGraphics().let { g ->
                                try {
                                    window.paint(g)
                                } finally {
                                    g.dispose()
                                }
                            }
                            javax.imageio.ImageIO.write(bitmap, "png", File(hostResult.parentFile, "paged-host.png"))
                        } else {
                            runHostChecks(surface, hostResult)
                        }
                        window.dispose()
                        kotlin.system.exitProcess(0)
                    } catch (failure: Throwable) {
                        failure.printStackTrace()
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
            timer.start()
        }
    }
}
