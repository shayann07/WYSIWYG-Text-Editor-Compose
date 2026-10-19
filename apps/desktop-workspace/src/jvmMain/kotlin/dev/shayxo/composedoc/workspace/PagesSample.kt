package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
/** Self-authored disposable specimen, not proficient-reader typography approval. */
fun pagesWritingSample(): Document {
    val e = EditorEngine()
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
    e.replace(paragraphs.joinToString("\n"), "Pages sample", paragraphBreaks = true)
    for (n in 1..10) {
        fun at(text: String) = e.snapshot().committed.text.indexOf(text).also { check(it >= 0) }
        var pos = at("باب $n —")
        e.move(Selection(pos, pos))
        e.structure(StructureCommand.Role(1))
        e.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START)
        e.structure(StructureCommand.Pagination(PaginationRules(keepWithNext = true)))
        pos = at("فہرست $n:")
        e.move(Selection(pos, pos))
        e.structure(StructureCommand.ListType(ListKind.NUMBER))
        e.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START)
        pos = at("English child $n —")
        e.move(Selection(pos, pos))
        e.structure(StructureCommand.ListType(ListKind.BULLET))
        e.structure(StructureCommand.Indent)
        e.setParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.START)
        pos = at("example.org")
        if (n == 1) {
            e.move(Selection(pos, pos + 11))
            e.structure(StructureCommand.Link("https://example.org/urdu"))
        }
    }
    val image = ImageDecoder.validate(samplePng())
    for (n in listOf(2, 7)) {
        val label = "تصویر $n"
        val pos = e.snapshot().committed.text.indexOf(label)
        e.move(Selection(pos, pos + label.length))
        e.structure(StructureCommand.InsertImage(ImageBlock(image.first, 240, "مقامی نیلا دائرہ اور دو لکیریں — generated illustration"), image.second))
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
