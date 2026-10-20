package dev.shayxo.composedoc.harness

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComboBox
import kotlin.math.abs

/** Same installed UI at actual width versus ample width: expected glyphs are pixels, not a size formula. */
internal fun checkClosedPreviewZoomInk(zoom: JComboBox<*>, output: File) {
    val original = zoom.size
    try {
        val actual = paintZoom(zoom, original.width)
        val reference = paintZoom(zoom, original.width + zoom.getFontMetrics(zoom.font).height * 4)
        check(ImageIO.write(actual, "png", File(output, "zoom-label-actual.png")))
        check(ImageIO.write(reference, "png", File(output, "zoom-label-wide-reference.png")))
        checkLabelCores(zoom, actual, reference)
    } finally {
        zoom.size = original
        zoom.doLayout()
    }
}

private fun paintZoom(zoom: JComboBox<*>, width: Int): BufferedImage {
    zoom.setSize(width, zoom.height)
    zoom.doLayout()
    val image = BufferedImage(width, zoom.height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        zoom.printAll(graphics)
    } finally {
        graphics.dispose()
    }
    return image
}

private fun checkLabelCores(zoom: JComboBox<*>, actual: BufferedImage, reference: BufferedImage) {
    val foreground = zoom.foreground.rgb and 0xffffff
    var ink = 0
    for (y in zoom.insets.top until actual.height - zoom.insets.bottom) {
        for (x in zoom.insets.left until minOf(actual.width, reference.width / 2)) {
            // Native gradients alter AA edges. Compare renderer foreground cores: Metal gray, Windows black.
            if (reference.getRGB(x, y) and 0xffffff != foreground) continue
            ink++
            check(nearForeground(actual.getRGB(x, y), foreground)) {
                "Closed preview zoom truncates label ink at $x,$y under ${zoom.ui.javaClass.name}"
            }
        }
    }
    check(ink > 0) { "Zoom label raster oracle found no reference glyphs" }
}

private fun nearForeground(observed: Int, foreground: Int) =
    abs((observed shr 16 and 255) - (foreground shr 16 and 255)) < 64 &&
        abs((observed shr 8 and 255) - (foreground shr 8 and 255)) < 64 &&
        abs((observed and 255) - (foreground and 255)) < 64
