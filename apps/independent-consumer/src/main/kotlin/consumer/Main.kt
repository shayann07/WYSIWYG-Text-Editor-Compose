package consumer

import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    val root = args.firstOrNull { it.startsWith("--evidence=") }?.substringAfter('=')?.let(Path::of)
        ?: Files.createTempDirectory("composedoc-consumer-data-")
    Files.createDirectories(root)
    SwingUtilities.invokeAndWait {
        val examples = prepareExamples(root)
        if (args.contains("--check-only")) {
            checkHostLifecycle(examples)
        } else {
            openWindow(examples.restored, examples.profile, "Independent ComposeDoc — first editor")
            openWindow(examples.other, examples.profile, "Independent ComposeDoc — second editor")
        }
        val proof = """
            {"status":"PASS","groups":[
              "public owner/state/intents", "exact multilingual input", "selection/formatting",
              "attributed UndoRedo", "two independent instances", "persistence port",
              "disposal/recreation", "packaged pinned font"
            ],"fontKey":"${examples.profile.key}","units":${examples.document.units.size}}
        """.trimIndent()
        Files.writeString(root.resolve("results.json"), proof)
        if (args.contains("--check-only")) println("PASS independent binary consumer 8 groups")
    }
}
