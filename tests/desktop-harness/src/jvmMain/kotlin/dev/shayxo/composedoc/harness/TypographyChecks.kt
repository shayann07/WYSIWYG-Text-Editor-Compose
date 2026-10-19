package dev.shayxo.composedoc.harness
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.io.File
fun runTypographyProfileChecks(fontsDir: File): Map<String, Any> {
    val profiles = TypographyProfile.labels.keys.map { TypographyProfile.load(it, fontsDir) }
    check(profiles.map { it.key }.distinct().size == profiles.size)
    // Switching presentation cannot mutate text, selection, history or native composition.
    val e = EditorEngine()
    e.replace("اردو English", "Fixture")
    e.move(Selection(0, 4))
    e.inputMethod("کتاب", 0, 4)
    val before = e.snapshot()
    val state = ViewState(before)
    profiles.forEach {
        state.changeTypography(it)
        check(e.snapshot() == before)
        check(state.geometry == null && state.measure == null)
    }
    check(e.snapshot().marked != null)
    e.inputMethod("کتاب", 4, null)
    check(e.snapshot().committed.text == "کتاب English")
    e.perform(EditAction.Undo)
    check(e.snapshot().committed.text == "اردو English")
    val isolated = java.nio.file.Files.createTempDirectory("typography-identity-check").toFile()
    try {
        File(isolated, "fonts.json").writeBytes(File(fontsDir.parentFile, "fonts.json").readBytes())
        val testFonts = File(isolated, "fonts").apply { mkdir() }
        File(testFonts, "NotoNastaliqUrdu[wght].ttf").writeBytes(byteArrayOf(0, 1, 2))
        val rejected = runCatching { TypographyProfile.load("noto-nastaliq", testFonts) }.exceptionOrNull()
        check(rejected is IllegalArgumentException && rejected.message?.startsWith("Pinned font identity mismatch") == true)
    } finally {
        isolated.deleteRecursively()
    }
    return mapOf(
        "status" to "PASS",
        "profiles" to profiles.map { mapOf("id" to it.id, "key" to it.key) },
        "modifiedFontRejected" to true,
        "scope" to "Pinned candidate loading and presentation invalidation; native typography/human review NOT RUN",
    )
}
