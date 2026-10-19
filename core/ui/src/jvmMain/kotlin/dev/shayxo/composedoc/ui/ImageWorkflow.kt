package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.paragraphs
import java.awt.Rectangle

/** Bounds of an image width produced by a resize drag, in document dp. */
private const val MIN_IMAGE_WIDTH = 10
private const val MAX_IMAGE_WIDTH = 600

/** Square resize-handle size and its half, in AWT pixels. */
private const val HANDLE_SIZE = 12
private const val HANDLE_HALF_SIZE = 6

/** Pure preview math in document dp. The adapter supplies AWT pixels and exactly one zoom transform. */
class ImageResizeGesture(
    val capture: ClipboardCapture,
    val image: ImageBlock,
    val startX: Int,
    val zoom: Float,
    val rightHandle: Boolean,
) {
    init {
        require(zoom.isFinite() && zoom > 0f && zoom <= 1f)
    }
    fun width(x: Int) = (image.width + (x - startX) * (if (rightHandle)1 else -1) / zoom).toInt().coerceIn(
        MIN_IMAGE_WIDTH,
        MAX_IMAGE_WIDTH,
    )
    fun finish(viewModel: EditorViewModel, x: Int): Boolean {
        if (viewModel.clipboardCapture() != capture)return false
        val n = width(x)
        return n == image.width ||
            viewModel.dispatch(EditorIntent.ApplyStructure(capture, StructureCommand.ImageProperties(n, image.alt)))
    }
}

data class ImageHandleGeometry(val bounds: Rectangle, val left: Rectangle, val right: Rectangle)
fun imageHandles(surface: EditorSurface): ImageHandleGeometry? {
    val f = surface.viewModel.snapshot()
    val singleUnit = f.selection.let { it.end - it.start == 1 } && f.marked == null
    val p = if (singleUnit) {
        surface.geometry()?.paragraphs?.firstOrNull { it.startOffset == f.selection.start && it.image != null }
    } else {
        null
    }
    val g = if (p != null) surface.geometry() else null
    return if (p == null || g == null) null else imageHandleGeometry(g, p)
}

private fun imageHandleGeometry(
    g: dev.shayxo.composedoc.layout.Geometry,
    p: dev.shayxo.composedoc.layout.ParagraphLayout,
): ImageHandleGeometry {
    val fragment = g.fragments.first { it.paragraphIndex == p.index }
    val bounds =
        Rectangle(
            ((g.x + p.imageLeft) / g.scale).toInt(),
            ((g.y + fragment.top) / g.scale).toInt(),
            (
                p.imageWidth /
                    g.scale
                ).toInt().coerceAtLeast(1),
            (p.height / g.scale).toInt().coerceAtLeast(1),
        )
    val cy = bounds.y + bounds.height / 2
    return ImageHandleGeometry(
        bounds,
        Rectangle(bounds.x - HANDLE_HALF_SIZE, cy - HANDLE_HALF_SIZE, HANDLE_SIZE, HANDLE_SIZE),
        Rectangle(
            bounds.x + bounds.width - HANDLE_HALF_SIZE,
            cy - HANDLE_HALF_SIZE,
            HANDLE_SIZE,
            HANDLE_SIZE,
        ),
    )
}
