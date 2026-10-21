package dev.shayxo.composedoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection

internal data class AndroidMarker(
    val layout: TextLayoutResult?,
    val left: Float,
    val top: Float,
    val radius: Float,
    val center: Float,
)
internal data class AndroidParagraph(
    val block: ParagraphBlock,
    val top: Float,
    val layout: TextLayoutResult,
    val left: Float,
    val height: Float = layout.size.height.toFloat(),
    val direction: ParagraphDirection = ParagraphDirection.LTR,
    val marker: AndroidMarker? = null,
    val image: ImageBlock? = null,
    val imageWidth: Float = 0f,
)
internal class AndroidEditorGeometry(
    val paragraphs: List<AndroidParagraph>,
    val height: Float,
    val basisRevision: Long,
) {
    fun paragraph(at: Int) = paragraphs.lastOrNull { it.block.start <= at } ?: paragraphs.first()
    fun caret(at: Int): Rect {
        val p = paragraph(at)
        if (p.image != null) {
            val before = at <= p.block.start
            val right = before == (p.direction == ParagraphDirection.RTL)
            val x = p.left + if (right) p.imageWidth else 0f
            return Rect(x, p.top, x + 1f, p.top + p.height)
        }
        return p.layout.getCursorRect((at - p.block.start).coerceIn(0, p.layout.layoutInput.text.length))
            .translate(Offset(p.left, p.top))
    }
    fun baseline(at: Int): Float {
        val p = paragraph(at)
        if (p.image != null) return p.top + p.height
        val local = (at - p.block.start).coerceIn(0, p.layout.layoutInput.text.length)
        return p.top + p.layout.getLineBaseline(p.layout.getLineForOffset(local))
    }
    fun character(at: Int): Rect {
        val p = paragraph(at)
        return when {
            p.image != null -> Rect(p.left, p.top, p.left + p.imageWidth, p.top + p.height)
            p.layout.layoutInput.text.isEmpty() -> caret(at)
            else -> {
                val local = (at - p.block.start).coerceIn(0, p.layout.layoutInput.text.length - 1)
                p.layout.getBoundingBox(local).translate(Offset(p.left, p.top))
            }
        }
    }
    fun hit(x: Float, y: Float): Int {
        val p = paragraphs.lastOrNull { it.top <= y } ?: paragraphs.first()
        if (p.image != null) {
            val right = x >= p.left + p.imageWidth / 2
            return p.block.start + if (right == (p.direction == ParagraphDirection.RTL)) 0 else 1
        }
        return p.block.start + p.layout.getOffsetForPosition(Offset(x - p.left, y - p.top))
            .coerceIn(0, p.block.end - p.block.start)
    }

    /** Word gestures hit painted character cells, not the nearest caret at an ambiguous bidi boundary. */
    fun wordHit(x: Float, y: Float): Int {
        val p = paragraphs.lastOrNull { it.top <= y } ?: paragraphs.first()
        if (p.image != null || p.block.start == p.block.end) return hit(x, y)
        val local = Offset(x - p.left, y - p.top)
        val line = p.layout.getLineForVerticalPosition(local.y)
        val start = p.layout.getLineStart(line)
        val end = p.layout.getLineEnd(line)
        val cell = (start until end).firstOrNull { at ->
            val bounds = p.layout.getBoundingBox(at)
            bounds.width > 0 && local.x >= bounds.left && local.x < bounds.right
        }
        return cell?.let { p.block.start + it } ?: hit(x, y)
    }
}
