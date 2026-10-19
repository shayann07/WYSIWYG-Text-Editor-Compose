package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.validateStructure

/** Independently constructed structural expectations, not the list-command reducer. */
fun markerMatrixDocument(rtl: Boolean, size: Int): Document {
    val rows = ArrayList<MarkerMatrixBlock>()
    val marker = MarkerTypography(StoredFont.NOTO_NASTALIQ, size)
    val direction = if (rtl) ParagraphDirection.RTL else ParagraphDirection.LTR
    addNumberedMarkerRows(rows, rtl, direction, marker)
    val root = ParagraphProperties(
        direction = direction,
        item = "expected-parent",
        list = ListKind.BULLET,
        markerTypography = marker,
    )
    rows += MarkerMatrixBlock(if (rtl) "اردو فہرست" else "English parent", root)
    rows += MarkerMatrixBlock(
        if (rtl) "Nested English item" else "اردو ذیلی مشق",
        root.copy(
            direction = if (rtl) ParagraphDirection.LTR else ParagraphDirection.RTL,
            item = "expected-child",
            parent = root.item,
        ),
    )
    rows += MarkerMatrixBlock(
        if (rtl) {
            "Longer English sibling with enough content to wrap and expose shared marker alignment."
        } else {
            "یہ اسی سطح کی ایک طویل اردو شق ہے، مشترک علامت اور حاشیہ برقرار رہتے ہیں۔"
        },
        root.copy(
            direction = if (rtl) ParagraphDirection.LTR else ParagraphDirection.RTL,
            item = "expected-sibling",
            parent = root.item,
        ),
    )
    rows += MarkerMatrixBlock(
        if (rtl) "اردو ہم سطح شق" else "English sibling",
        root.copy(
            item = "expected-mixed-sibling",
            parent = root.item,
            list = ListKind.NUMBER,
            restart = 9999,
        ),
    )
    rows += MarkerMatrixBlock(
        mixedFixture + " — " + mixedFixture,
        root.copy(item = "expected-grandchild", parent = "expected-child"),
    )
    rows += MarkerMatrixBlock("", root.copy(item = "expected-empty"))
    val units = markerMatrixUnits(rows, size)
    return Document(
        units,
        startStyle = TextStyleFlags(size = size),
        startParagraph = rows.first().properties,
    ).also(::validateStructure)
}

private data class MarkerMatrixBlock(val text: String, val properties: ParagraphProperties)

private fun addNumberedMarkerRows(
    rows: MutableList<MarkerMatrixBlock>,
    rtl: Boolean,
    direction: ParagraphDirection,
    marker: MarkerTypography,
) {
    for (number in listOf(1, 9, 10, 99, 100, 9999)) {
        for (align in ParagraphAlignment.entries) {
            val id = "expected-$number-$align"
            val p = ParagraphProperties(
                direction = direction,
                alignment = align,
                item = id,
                list = ListKind.NUMBER,
                restart = number,
                markerTypography = marker,
            )
            rows += MarkerMatrixBlock(
                if (rtl) "پہلی فہرست کی مشق، مُحَمَّد اور کتاب۔" else "First item with accents: café and cafe\u0301.",
                p,
            )
            if (align == ParagraphAlignment.START) {
                rows += MarkerMatrixBlock(
                    if (rtl) "اسی مشق کا دوسرا پیراگراف" else "Another paragraph in this same item.",
                    p,
                )
            }
        }
    }
}

private fun markerMatrixUnits(rows: List<MarkerMatrixBlock>, size: Int): ArrayList<TextUnit> {
    val units = ArrayList<TextUnit>()
    var serial = 0L
    rows.forEachIndexed { i, row ->
        if (i > 0) {
            units += TextUnit(
                ++serial,
                '\n',
                UnitKind.PARAGRAPH_BREAK,
                paragraphStyle = TextStyleFlags(size = size),
                followingParagraph = row.properties,
            )
        }
        row.text.forEachIndexed { index, c ->
            val unitSize = if (index == 0) if (size == 96) 8 else 96 else size
            units += TextUnit(++serial, c, style = TextStyleFlags(size = unitSize, italic = index == 0))
        }
    }
    return units
}
