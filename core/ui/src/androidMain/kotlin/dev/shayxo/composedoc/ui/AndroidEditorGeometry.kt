package dev.shayxo.composedoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import dev.shayxo.composedoc.model.ParagraphBlock

internal data class AndroidParagraph(
    val block: ParagraphBlock,
    val top: Float,
    val layout: TextLayoutResult,
    val left: Float,
)
internal class AndroidEditorGeometry(
    val paragraphs: List<AndroidParagraph>,
    val height: Float,
    val basisRevision: Long,
) {
    fun paragraph(at: Int) = paragraphs.lastOrNull { it.block.start <= at } ?: paragraphs.first()
    fun caret(at: Int): Rect {
        val p = paragraph(at)
        return p.layout.getCursorRect((at - p.block.start).coerceIn(0, p.layout.layoutInput.text.length))
            .translate(Offset(p.left, p.top))
    }
    fun baseline(at: Int): Float {
        val p = paragraph(at)
        val local = (at - p.block.start).coerceIn(0, p.layout.layoutInput.text.length)
        return p.top + p.layout.getLineBaseline(p.layout.getLineForOffset(local))
    }
    fun character(at: Int): Rect {
        val p = paragraph(at)
        val local = (at - p.block.start).coerceIn(0, p.layout.layoutInput.text.length - 1)
        return p.layout.getBoundingBox(local).translate(Offset(p.left, p.top))
    }
    fun hit(x: Float, y: Float): Int {
        val p = paragraphs.lastOrNull { it.top <= y } ?: paragraphs.first()
        return p.block.start + p.layout.getOffsetForPosition(Offset(x - p.left, y - p.top))
            .coerceIn(0, p.block.end - p.block.start)
    }
}
