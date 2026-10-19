package dev.shayxo.composedoc.ui
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.shayxo.composedoc.model.*
object ImageResources {
    private val bitmaps = java.util.LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(16, .75f, true)
    private val pending = HashSet<String>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "image-decoder").apply { isDaemon = true } }

    @Synchronized fun cached(id: String) = bitmaps[id]
    fun prepare(d: Document, repaint: () -> Unit) = prepareOnce(d, repaint, HashSet())

    @Synchronized private fun prepareOnce(d: Document, repaint: () -> Unit, attempted: MutableSet<String>) {
        for ((id, resource) in d.resources) {
            if (id !in bitmaps && id !in pending && id !in attempted && pending.size < 4) {
                attempted += id
                pending += id
                executor.execute {
                    val loaded = runCatching { org.jetbrains.skia.Image.makeFromEncoded(resource.bytes()).toComposeImageBitmap() }.getOrNull()
                    synchronized(this) {
                        pending -= id
                        if (loaded != null) {
                            bitmaps[id] = loaded
                            while (bitmaps.size > 32 || bitmaps.values.sumOf { it.width.toLong() * it.height } > 16_000_000) {
                                val first = bitmaps.entries.iterator()
                                first.next()
                                first.remove()
                            }
                        }
                    }
                    javax.swing.SwingUtilities.invokeLater {
                        repaint()
                        prepareOnce(d, repaint, attempted)
                    }
                }
            }
        }
    }
}
