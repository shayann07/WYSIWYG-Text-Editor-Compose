package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.freshStructureId
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel

private const val SAMPLE_PNG_WIDTH_PX = 240
private const val SAMPLE_PNG_HEIGHT_PX = 100
private const val SAMPLE_PNG_BACKGROUND_RGB = 0xe7f0f8
private const val SAMPLE_PNG_INK_RGB = 0x174ea6
private const val CIRCLE_ORIGIN_PX = 24
private const val CIRCLE_DIAMETER_PX = 52
private const val LINES_X_PX = 96
private const val LONG_LINE_Y_PX = 36
private const val LONG_LINE_WIDTH_PX = 112
private const val LONG_LINE_HEIGHT_PX = 12
private const val SHORT_LINE_Y_PX = 58
private const val SHORT_LINE_WIDTH_PX = 80
private const val SHORT_LINE_HEIGHT_PX = 8
private const val SAMPLE_SECTION_LEVELS = 3
private const val SECTION_HEADING_ROLE = 2
private const val SUBSECTION_HEADING_ROLE = 3
private const val SAMPLE_IMAGE_WIDTH_DP = 240

/** Local generated pixels, self-authored text; no remote asset or typography review. */
fun samplePng(): ByteArray {
    val image = java.awt.image.BufferedImage(
        SAMPLE_PNG_WIDTH_PX,
        SAMPLE_PNG_HEIGHT_PX,
        java.awt.image.BufferedImage.TYPE_INT_RGB,
    )
    val g = image.createGraphics()
    try {
        g.color = java.awt.Color(SAMPLE_PNG_BACKGROUND_RGB)
        g.fillRect(0, 0, SAMPLE_PNG_WIDTH_PX, SAMPLE_PNG_HEIGHT_PX)
        g.color = java.awt.Color(SAMPLE_PNG_INK_RGB)
        g.fillOval(CIRCLE_ORIGIN_PX, CIRCLE_ORIGIN_PX, CIRCLE_DIAMETER_PX, CIRCLE_DIAMETER_PX)
        g.fillRect(LINES_X_PX, LONG_LINE_Y_PX, LONG_LINE_WIDTH_PX, LONG_LINE_HEIGHT_PX)
        g.fillRect(LINES_X_PX, SHORT_LINE_Y_PX, SHORT_LINE_WIDTH_PX, SHORT_LINE_HEIGHT_PX)
    } finally {
        g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", out)
    return out.toByteArray()
}
fun structuredWritingSample(): Document {
    val e = EditorViewModel(urduWritingSample())
    typeStructuredSampleText(e)
    return withStableStructuredIds(withSampleImage(structuredListSample(e)))
}

private fun typeStructuredSampleText(e: EditorViewModel) {
    val text = e.snapshot().committed.text
    e.dispatch(EditorIntent.Select(Selection(0, 0)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
    for (level in 1..SAMPLE_SECTION_LEVELS) {
        val at = text.indexOf("حصہ $level")
        e.dispatch(EditorIntent.Select(Selection(at, at)))
        val role = if (level == 1)SECTION_HEADING_ROLE else SUBSECTION_HEADING_ROLE
        e.dispatch(EditorIntent.Structure(StructureCommand.Role(role)))
    }
    val at = text.indexOf("Disposable")
    e.dispatch(EditorIntent.Select(Selection(at, at + "Disposable writing sample".length)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Link("https://example.org/urdu")))
    e.dispatch(EditorIntent.Select(Selection(text.length, text.length)))
    e.dispatch(
        EditorIntent.Type(
            "\nساختی نمونہ — Structured sample\nپہلی فہرست کی شق\n" +
                "اس شق کا دوسرا پیراگراف\nNested English item\nدوسری شق\n\n",
            "sample",
            true,
        ),
    )
}

private fun structuredListSample(e: EditorViewModel): Document {
    val d = e.snapshot().committed
    val title = d.text.indexOf("ساختی نمونہ")
    e.dispatch(EditorIntent.Select(Selection(title, title)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Role(2)))
    val first = d.text.indexOf("پہلی فہرست")
    e.dispatch(EditorIntent.Select(Selection(first, first)))
    e.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.NUMBER)))
    val item = e.snapshot().committed.paragraphAt(first).properties
    val continuation = e.snapshot().committed.text.indexOf("اس شق کا")
    val block = e.snapshot().committed.paragraphAt(continuation)
    // Shared sample fixture consumes semantic properties, not inserted marker characters.
    var sample = e.snapshot().committed.patchParagraphs(
        mapOf(
            block.anchor to block.properties.copy(item = item.item, list = item.list),
        ),
    )
    val nested = sample.text.indexOf("Nested English")
    val second = sample.text.indexOf("دوسری شق")
    val n = sample.paragraphAt(nested)
    val s = sample.paragraphAt(second)
    sample =
        sample.patchParagraphs(
            mapOf(
                n.anchor to
                    n.properties.copy(
                        item = freshStructureId(),
                        parent = item.item,
                        list = ListKind.BULLET,
                        direction = ParagraphDirection.LTR,
                    ),
                s.anchor to s.properties.copy(item = freshStructureId(), list = ListKind.NUMBER),
            ),
        )
    return sample
}

private fun withSampleImage(sample: Document): Document {
    val image = ImageDecoder.validate(samplePng())
    val owner = EditorViewModel(sample)
    val end = owner.snapshot().committed.units.size
    owner.dispatch(EditorIntent.Select(Selection(end, end)))
    owner.dispatch(
        EditorIntent.Structure(
            StructureCommand.InsertImage(
                ImageBlock(
                    image.first,
                    SAMPLE_IMAGE_WIDTH_DP,
                    "مقامی نمونہ: نیلا دائرہ اور دو لکیریں — blue circle and lines",
                ),
                image.second,
            ),
        ),
    )
    return owner.snapshot().committed
}

private fun withStableStructuredIds(result: Document): Document {
    val ids = result.paragraphs().map {
        it.properties.item
    }.filter { it.isNotEmpty() }.distinct().withIndex().associate {
        it.value to
            "sample-item-${it.index + 1}"
    }
    val remapped = result.patchParagraphs(
        result.paragraphs().associate { p ->
            p.anchor to
                p.properties.copy(item = ids[p.properties.item] ?: "", parent = ids[p.properties.parent] ?: "")
        },
    )
    return remapped.copy(
        units = remapped.units.map { u ->
            u.copy(style = u.style.copy(link = u.style.link?.copy(id = "sample-link-1")))
        },
    )
}
