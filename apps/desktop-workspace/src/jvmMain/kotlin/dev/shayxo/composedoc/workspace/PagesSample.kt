package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.PageDigits
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.PaperSize
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.RunningContent
import dev.shayxo.composedoc.model.RunningField
import dev.shayxo.composedoc.model.RunningFlow
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.model.validateStructure
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel

private const val SAMPLE_CHAPTER_COUNT = 10
private const val SAMPLE_IMAGE_WIDTH_DP = 240
private const val PAGES_SAMPLE_MARGIN_MM = 14
private const val SAMPLE_LINK_TEXT = "example.org"
private const val FIRST_IMAGE_CHAPTER = 2
private const val SECOND_IMAGE_CHAPTER = 7
private val IMAGE_CHAPTERS = listOf(FIRST_IMAGE_CHAPTER, SECOND_IMAGE_CHAPTER)

/** Self-authored disposable specimen, not proficient-reader typography approval. */
fun pagesWritingSample(): Document {
    val e = EditorViewModel()
    e.dispatch(EditorIntent.Type(pagesSampleParagraphs().joinToString("\n"), "Pages sample", paragraphBreaks = true))
    for (n in 1..SAMPLE_CHAPTER_COUNT) applyChapterStructure(e, n)
    insertSampleImages(e)
    return withStableSampleIds(withSampleTypography(withChapterBreaks(e.snapshot().committed)))
}

private fun pagesSampleParagraphs(): List<String> {
    val paragraphs = ArrayList<String>()
    for (n in 1..SAMPLE_CHAPTER_COUNT) {
        paragraphs += "باب $n — تحریر، صفحات اور مطالعہ"
        paragraphs +=
            "یہ خود تحریر کردہ آزمائشی عبارت ہے۔ " +
            "کتاب، قلم اور تحقیق ہماری گفتگو کا موضوع ہیں۔ " +
            "مُحَمَّد، قُرآن اور نَستَعلیق کے نشانات اصل صورت میں محفوظ ہیں۔"
        paragraphs +=
            "حصہ $n: English café e\u0301, 12.5, (۲۰۲۶), example.org — اردو اور العربية لَا، می\u200cروم، ک\u200dتاب۔"
        paragraphs += "فہرست $n: مطالعہ اور سوال"
        paragraphs += "English child $n — email@example.org, [42]"
        if (n in IMAGE_CHAPTERS)paragraphs += "تصویر $n"
        paragraphs += "اختتام $n: یہ نمونہ انسانی لسانی و خطاطی جائزے کا متبادل نہیں۔"
    }
    return paragraphs
}

private fun applyChapterStructure(e: EditorViewModel, n: Int) {
    fun at(text: String) = e.snapshot().committed.text.indexOf(text).also { check(it >= 0) }
    var pos = at("باب $n —")
    e.dispatch(EditorIntent.Select(Selection(pos, pos)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
    e.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START))
    e.dispatch(EditorIntent.Structure(StructureCommand.Pagination(PaginationRules(keepWithNext = true))))
    pos = at("فہرست $n:")
    e.dispatch(EditorIntent.Select(Selection(pos, pos)))
    e.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.NUMBER)))
    e.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START))
    pos = at("English child $n —")
    e.dispatch(EditorIntent.Select(Selection(pos, pos)))
    e.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.BULLET)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Indent))
    e.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.START))
    pos = at(SAMPLE_LINK_TEXT)
    if (n == 1) {
        e.dispatch(EditorIntent.Select(Selection(pos, pos + SAMPLE_LINK_TEXT.length)))
        e.dispatch(EditorIntent.Structure(StructureCommand.Link("https://example.org/urdu")))
    }
}

private fun insertSampleImages(e: EditorViewModel) {
    val image = ImageDecoder.validate(samplePng())
    for (n in IMAGE_CHAPTERS) {
        val label = "تصویر $n"
        val pos = e.snapshot().committed.text.indexOf(label)
        e.dispatch(EditorIntent.Select(Selection(pos, pos + label.length)))
        e.dispatch(
            EditorIntent.Structure(
                StructureCommand.InsertImage(
                    ImageBlock(
                        image.first,
                        SAMPLE_IMAGE_WIDTH_DP,
                        "مقامی نیلا دائرہ اور دو لکیریں — generated illustration",
                    ),
                    image.second,
                ),
            ),
        )
    }
}

private fun withChapterBreaks(document: Document): Document {
    val d = document
    val chapterStarts = d.paragraphs().filter {
        d.text.substring(it.start, it.end).startsWith("باب ")
    }.map { it.start }.toSet()
    return d.copy(
        units = d.units.mapIndexed { index, u ->
            if (index + 1 in chapterStarts &&
                index > 0
            ) {
                u.copy(char = '\u000c', structuralBreak = StructuralBreak.PAGE)
            } else {
                u
            }
        },
    )
}

private fun withSampleTypography(document: Document): Document {
    val d = document
    val middle = d.text.indexOf("باب 6 —")
    return d.copy(
        units = d.units.mapIndexed { index, u ->
            if (u.kind ==
                UnitKind.TEXT
            ) {
                u.copy(
                    style = u.style.copy(
                        font = if (index <
                            middle
                        ) {
                            StoredFont.NOTO_NASTALIQ
                        } else {
                            StoredFont.GULZAR
                        },
                    ),
                )
            } else {
                u
            }
        },
        pageSettings = PageSettings(PaperSize.A4, false, PAGES_SAMPLE_MARGIN_MM),
        running = RunningContent(
            header = RunningFlow(
                java.util.UUID.nameUUIDFromBytes("sample-header".toByteArray()).toString(),
                "اردو تحریر — Disposable engineering specimen",
                style = TextStyleFlags(font = StoredFont.NOTO_NASTALIQ, size = 16),
            ),
            footer = RunningFlow(
                java.util.UUID.nameUUIDFromBytes("sample-footer".toByteArray()).toString(),
                "صفحہ",
                style = TextStyleFlags(font = StoredFont.NOTO_NASTALIQ, size = 16),
                alignment = ParagraphAlignment.CENTER,
                field = RunningField.PAGE_TOTAL,
            ),
            differentFirst = true,
            digits = PageDigits.EASTERN_ARABIC,
        ),
    )
}

private fun withStableSampleIds(document: Document): Document {
    var d = document
    val items = d.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct().mapIndexed { i, s ->
        s to
            "pages-item-$i"
    }.toMap()
    val links = HashMap<String, String>()
    fun style(s: TextStyleFlags) =
        s.copy(link = s.link?.let { it.copy(id = links.getOrPut(it.id) { "pages-link-${links.size}" }) })
    d =
        d.patchParagraphs(
            d.paragraphs().associate { p ->
                p.anchor to
                    p.properties.copy(item = items[p.properties.item] ?: "", parent = items[p.properties.parent] ?: "")
            },
        )
    return d.copy(
        startStyle = style(d.startStyle),
        units = d.units.map {
            it.copy(style = style(it.style), paragraphStyle = style(it.paragraphStyle))
        },
    ).also(::validateStructure)
}
