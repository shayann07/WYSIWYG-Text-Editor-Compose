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

/** Self-authored disposable specimen, not proficient-reader typography approval. */
fun pagesWritingSample(): Document {
    val e = EditorViewModel()
    val paragraphs = ArrayList<String>()
    for (n in 1..10) {
        paragraphs += "باب $n — تحریر، صفحات اور مطالعہ"
        paragraphs += "یہ خود تحریر کردہ آزمائشی عبارت ہے۔ کتاب، قلم اور تحقیق ہماری گفتگو کا موضوع ہیں۔ مُحَمَّد، قُرآن اور نَستَعلیق کے نشانات اصل صورت میں محفوظ ہیں۔"
        paragraphs += "حصہ $n: English café e\u0301, 12.5, (۲۰۲۶), example.org — اردو اور العربية لَا، می\u200cروم، ک\u200dتاب۔"
        paragraphs += "فہرست $n: مطالعہ اور سوال"
        paragraphs += "English child $n — email@example.org, [42]"
        if (n == 2 || n == 7)paragraphs += "تصویر $n"
        paragraphs += "اختتام $n: یہ نمونہ انسانی لسانی و خطاطی جائزے کا متبادل نہیں۔"
    }
    e.dispatch(EditorIntent.Type(paragraphs.joinToString("\n"), "Pages sample", paragraphBreaks = true))
    for (n in 1..10) {
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
        pos = at("example.org")
        if (n == 1) {
            e.dispatch(EditorIntent.Select(Selection(pos, pos + 11)))
            e.dispatch(EditorIntent.Structure(StructureCommand.Link("https://example.org/urdu")))
        }
    }
    val image = ImageDecoder.validate(samplePng())
    for (n in listOf(2, 7)) {
        val label = "تصویر $n"
        val pos = e.snapshot().committed.text.indexOf(label)
        e.dispatch(EditorIntent.Select(Selection(pos, pos + label.length)))
        e.dispatch(EditorIntent.Structure(StructureCommand.InsertImage(ImageBlock(image.first, 240, "مقامی نیلا دائرہ اور دو لکیریں — generated illustration"), image.second)))
    }
    var d = e.snapshot().committed
    val chapterStarts = d.paragraphs().filter { d.text.substring(it.start, it.end).startsWith("باب ") }.map { it.start }.toSet()
    d = d.copy(units = d.units.mapIndexed { index, u -> if (index + 1 in chapterStarts && index > 0)u.copy(char = '\u000c', structuralBreak = StructuralBreak.PAGE) else u })
    val middle = d.text.indexOf("باب 6 —")
    d = d.copy(
        units = d.units.mapIndexed { index, u -> if (u.kind == UnitKind.TEXT)u.copy(style = u.style.copy(font = if (index < middle)StoredFont.NOTO_NASTALIQ else StoredFont.GULZAR)) else u },
        pageSettings = PageSettings(PaperSize.A4, false, 14),
        running = RunningContent(
            header = RunningFlow(java.util.UUID.nameUUIDFromBytes("sample-header".toByteArray()).toString(), "اردو تحریر — Disposable engineering specimen", style = TextStyleFlags(font = StoredFont.NOTO_NASTALIQ, size = 16)),
            footer = RunningFlow(java.util.UUID.nameUUIDFromBytes("sample-footer".toByteArray()).toString(), "صفحہ", style = TextStyleFlags(font = StoredFont.NOTO_NASTALIQ, size = 16), alignment = ParagraphAlignment.CENTER, field = RunningField.PAGE_TOTAL),
            differentFirst = true,
            digits = PageDigits.EASTERN_ARABIC,
        ),
    )
    val items = d.paragraphs().map { it.properties.item }.filter { it.isNotEmpty() }.distinct().mapIndexed { i, s -> s to "pages-item-$i" }.toMap()
    val links = HashMap<String, String>()
    fun style(s: TextStyleFlags) = s.copy(link = s.link?.let { it.copy(id = links.getOrPut(it.id) { "pages-link-${links.size}" }) })
    d = d.patchParagraphs(d.paragraphs().associate { p -> p.anchor to p.properties.copy(item = items[p.properties.item] ?: "", parent = items[p.properties.parent] ?: "") })
    return d.copy(startStyle = style(d.startStyle), units = d.units.map { it.copy(style = style(it.style), paragraphStyle = style(it.paragraphStyle)) }).also(::validateStructure)
}
