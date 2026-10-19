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

/** Local generated pixels, self-authored text; no remote asset or typography review. */
fun samplePng(): ByteArray {
    val image = java.awt.image.BufferedImage(240, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        g.color = java.awt.Color(0xe7f0f8)
        g.fillRect(0, 0, 240, 100)
        g.color = java.awt.Color(0x174ea6)
        g.fillOval(24, 24, 52, 52)
        g.fillRect(96, 36, 112, 12)
        g.fillRect(96, 58, 80, 8)
    } finally {
        g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", out)
    return out.toByteArray()
}
fun structuredWritingSample(): Document {
    val e = EditorViewModel(urduWritingSample())
    val text = e.snapshot().committed.text
    e.dispatch(EditorIntent.Select(Selection(0, 0)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
    for (level in 1..3) {
        val at = text.indexOf("حصہ $level")
        e.dispatch(EditorIntent.Select(Selection(at, at)))
        e.dispatch(EditorIntent.Structure(StructureCommand.Role(if (level == 1)2 else 3)))
    }
    val at = text.indexOf("Disposable")
    e.dispatch(EditorIntent.Select(Selection(at, at + "Disposable writing sample".length)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Link("https://example.org/urdu")))
    e.dispatch(EditorIntent.Select(Selection(text.length, text.length)))
    e.dispatch(
        EditorIntent.Type(
            "\nساختی نمونہ — Structured sample\nپہلی فہرست کی شق\nاس شق کا دوسرا پیراگراف\nNested English item\nدوسری شق\n\n",
            "sample",
            true,
        ),
    )
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
    val image = ImageDecoder.validate(samplePng())
    val owner = EditorViewModel(sample)
    val end = owner.snapshot().committed.units.size
    owner.dispatch(EditorIntent.Select(Selection(end, end)))
    owner.dispatch(
        EditorIntent.Structure(
            StructureCommand.InsertImage(
                ImageBlock(image.first, 240, "مقامی نمونہ: نیلا دائرہ اور دو لکیریں — blue circle and lines"),
                image.second,
            ),
        ),
    )
    val result = owner.snapshot().committed
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
