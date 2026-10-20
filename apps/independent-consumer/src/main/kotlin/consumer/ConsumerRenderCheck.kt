package consumer

import dev.shayxo.composedoc.ui.DesktopEditor
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.Timer

private const val RENDER_TIMEOUT_MS = 8000
private const val RENDER_POLL_MS = 40
private const val MIN_INK_PIXELS = 20
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 255
private const val INK_CHANNEL_THRESHOLD = 100

/** Mounted rendering evidence, separate from actual native user interaction. */
internal fun checkMountedCanvas(examples: EditorExamples) {
    val host = DesktopEditor(examples.restored, examples.profile)
    val frame = JFrame("Disposable binary rendering regression")
    frame.contentPane.add(host)
    frame.pack()
    frame.isVisible = true
    val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
    val deadline = System.nanoTime() + RENDER_TIMEOUT_MS * NANOS_PER_MILLISECOND
    val poll = Timer(RENDER_POLL_MS) {
        if (host.viewState.geometry != null || System.nanoTime() >= deadline) loop.exit()
    }
    try {
        poll.start()
        loop.enter()
        check(host.viewState.geometry != null) { "Mounted binary editor never produced document geometry" }
        val image = BufferedImage(host.width, host.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { graphics ->
            try {
                graphics.color = java.awt.Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                host.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }
        val ink = (0 until image.height).sumOf { y ->
            (0 until image.width).count { x ->
                val rgb = image.getRGB(x, y)
                ((rgb shr RED_SHIFT) and CHANNEL_MASK) < INK_CHANNEL_THRESHOLD &&
                    ((rgb shr GREEN_SHIFT) and CHANNEL_MASK) < INK_CHANNEL_THRESHOLD &&
                    (rgb and CHANNEL_MASK) < INK_CHANNEL_THRESHOLD
            }
        }
        check(ink > MIN_INK_PIXELS) { "Mounted document canvas has no visible text ink" }
    } finally {
        poll.stop()
        host.close()
        frame.dispose()
    }
}
