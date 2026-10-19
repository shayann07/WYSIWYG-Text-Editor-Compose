package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.layout.DocumentPagePlan
import dev.shayxo.composedoc.layout.PrintSnapshot
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.ImageResources
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.showEditor
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
/** Numbered human-review artifact from actual editor layouts; no expert verdict. */
fun typographySheet(output: File) {
    check(output.mkdirs()) { "Use a new typography-sheet directory" }
    val cases = linkedMapOf(
        "01 Common Urdu" to "یہ ایک اردو دستاویز ہے۔ پاکستان میں تعلیم اور تحقیق۔",
        "02 Letter distinctions" to "ک ك، ی ي، ے، ں، ھ، ہ — کتاب اور قلم۔",
        "03 Stacked and leading marks" to "َّ مُحَمَّد، قُرآن، نَستَعلیق، تحقیق۔",
        "04 Joining controls" to "ک‍تاب ک‌تاب، می‌روم، لا لام الف۔",
        "05 Mixed text" to "قیمت ۱۲۳ روپے — English 12.5, (۲۰۲۶), mail@example.org / https://example.org",
        "06 Other scripts" to "العربية لَا، فارسی می‌روم، שָׁלוֹם، é café, क्षि ক্ষি ก้",
        "07 Isolates as authored content" to "اردو ⁦English (123)⁩ کتاب، ⁧عربی⁩ end",
        "08 Supplementary and emoji" to "Urdu اردو — 👩🏽‍💻 🇵🇰 漢字 が 한글",
    )
    val document = buildSpecimenDocument(cases)
    File(output, "specimen.astra").writeBytes(DocumentFiles.encode(document))
    val profiles = ArrayList<Map<String, Any>>()
    for (profileId in listOf("noto-nastaliq", "gulzar", "naskh")) {
        profiles += renderTypographyProfile(output, profileId, document, cases)
    }
    File(output, "index.html").writeText(typographyIndexHtml(cases, profiles), Charsets.UTF_8)
    val results = mapOf(
        "status" to "ENGINEERING_SPECIMEN_GENERATED_HUMAN_REVIEW_OPEN",
        "cases" to cases.map { mapOf("id" to it.key, "text" to it.value, "utf16" to it.value.map { c -> c.code }) },
        "profiles" to profiles,
        "native" to "NOT RUN; mounted actual editor page plans",
    )
    File(output, "results.json").writeText(evidenceJson(results), Charsets.UTF_8)
    println("PASS numbered typography sheet generated; human review OPEN")
}

private fun buildSpecimenDocument(cases: Map<String, String>): Document {
    val owner = EditorEngine()
    owner.replace(cases.entries.joinToString("\n\n") { it.key + "\n" + it.value }, "Specimen", paragraphBreaks = true)
    for (id in cases.keys) {
        val offset = owner.snapshot().committed.text.indexOf(id)
        owner.move(Selection(offset, offset))
        owner.structure(StructureCommand.Pagination(PaginationRules(keepWithNext = true)))
    }
    return owner.snapshot().committed
}

private fun renderTypographyProfile(
    output: File,
    profileId: String,
    document: Document,
    cases: Map<String, String>,
): Map<String, Any> {
    val profile = TypographyProfile.load(profileId)
    lateinit var window: JFrame
    lateinit var s: EditorSurface
    SwingUtilities.invokeAndWait {
        window = showEditor(initial = document, offscreenTest = true, fontProfile = profileId, paged = true)
        s = findSurface(window)!!
    }
    val plan = awaitDocumentPagePlan(s)
    lateinit var captured: PrintSnapshot
    SwingUtilities.invokeAndWait {
        val b = PrintSnapshot.Builder(plan, ImageResources::cached)
        repeat(plan.pageCount) { b.next() }
        captured = b.finish()
        check(
            s.viewModel.verificationEngine.snapshot().committed.same(document) &&
                s.viewModel.verificationEngine.snapshot().undoCount == 0,
        )
    }
    val files = ArrayList<String>()
    captured.use {
        for (page in 0 until it.plan.pageCount) {
            val name = "$profileId-${page + 1}.png"
            val image = it.render(page, 96)
            javax.imageio.ImageIO.write(image, "png", File(output, name))
            image.flush()
            files += name
        }
    }
    val fallback = fontMgrFallbackDiagnostic(profileId, profile, cases)
    val result = mapOf(
        "id" to profileId,
        "fontKey" to profile.key,
        "requestedFamily" to profile.requestedFamilyName,
        "pages" to files,
        "fontMgrFallbackDiagnostic" to fallback,
        "actualComposeFallbackRunIdentity" to
            "NOT EXPOSED by pinned Paragraph API; diagnostic matching is not a verified glyph-run map",
    )
    SwingUtilities.invokeAndWait { window.dispose() }
    return result
}

private fun awaitDocumentPagePlan(s: EditorSurface): DocumentPagePlan {
    val deadline = System.nanoTime() + 15_000_000_000L
    var plan: DocumentPagePlan? = null
    while (plan == null && System.nanoTime() < deadline) {
        SwingUtilities.invokeAndWait { plan = s.geometry()?.documentPagePlan }
        if (plan == null)Thread.sleep(30)
    }
    return checkNotNull(plan)
}

private fun fontMgrFallbackDiagnostic(
    profileId: String,
    profile: TypographyProfile,
    cases: Map<String, String>,
): Map<String, String> {
    val fontFile = File(
        "tests/suites/urdu/u0/fonts/" + when (profileId) {
            "gulzar" -> "Gulzar-Regular.ttf"
            "naskh" -> "NotoSansArabic[wdth,wght].ttf"
            else -> "NotoNastaliqUrdu[wght].ttf"
        },
    )
    val fallback = linkedMapOf<String, String>()
    org.jetbrains.skia.FontMgr.default.makeFromFile(fontFile.path)!!.use { face ->
        for (cp in cases.values.flatMap { it.codePoints().toArray().toList() }.distinct()) {
            val needsFallback = face.getUTF32Glyph(cp).toInt() == 0 &&
                !Character.isWhitespace(cp) &&
                Character.getType(cp) != Character.FORMAT.toInt()
            if (needsFallback) fallback["U+${cp.toString(16).uppercase()}"] = fallbackFamilyName(profile, cp)
        }
    }
    return fallback
}

private fun fallbackFamilyName(profile: TypographyProfile, cp: Int): String {
    val candidate = org.jetbrains.skia.FontMgr.default.matchFamilyStyleCharacter(
        profile.requestedFamilyName,
        org.jetbrains.skia.FontStyle.NORMAL,
        arrayOf("ur"),
        cp,
    )
    return candidate?.use { it.familyName } ?: "MISSING"
}

private fun typographyHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun typographyIndexHtml(cases: Map<String, String>, profiles: List<Map<String, Any>>): String = buildString {
    append(
        "<!doctype html><meta charset=utf-8><title>Urdu typography review specimen</title>" +
            "<style>body{font:18px system-ui;max-width:1000px;margin:32px auto;line-height:1.6}" +
            "img{width:100%;border:1px solid #aaa}pre{white-space:pre-wrap}td,th{padding:10px;border:1px solid #bbb}" +
            "table{border-collapse:collapse;width:100%}</style><h1>Numbered Urdu typography specimen</h1>" +
            "<p>Engineering renders only. Proficient Urdu/calligraphic review: NOT RUN. Check joining, marks, " +
            "ink clipping, line rhythm, fallbacks and mixed direction. Record case/profile/page and a correction; " +
            "automated success is not approval.</p><table><tr><th>Case / logical source</th>" +
            "<th>Review notes</th></tr>",
    )
    for ((id, raw) in cases) {
        val codePoints = raw.codePoints().toArray().joinToString(" ") { "U+${it.toString(16).uppercase()}" }
        append(
            "<tr><td>${typographyHtml(id)}<pre dir=auto>${typographyHtml(raw)}</pre>" +
                "<small>$codePoints</small></td><td>NOT REVIEWED</td></tr>",
        )
    }
    append("</table>")
    for (p in profiles) {
        append(
            "<h2>${typographyHtml(p["id"].toString())}</h2><p>${typographyHtml(p["fontKey"].toString())}</p>" +
                "<p>Codepoint fallback diagnostics (actual Compose run mapping unqualified):" +
                "${typographyHtml(p["fontMgrFallbackDiagnostic"].toString())}</p>",
        )
        for (file in p["pages"] as List<*>) {
            append(
                "<img src='${typographyHtml(file.toString())}' " +
                    "alt='${typographyHtml(p["id"].toString())} editor page'>",
            )
        }
    }
}
