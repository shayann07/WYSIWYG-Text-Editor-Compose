package dev.shayxo.composedoc.ui
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.shayxo.composedoc.model.Document

/** Initial capacity and load factor of the access-ordered (LRU) bitmap cache. */
private const val CACHE_INITIAL_CAPACITY = 16
private const val CACHE_LOAD_FACTOR = .75f

/** At most this many image decodes are queued at once. */
private const val MAX_PENDING_DECODES = 4

/** Cache eviction bounds: entry count and total decoded pixels. */
private const val MAX_CACHED_BITMAPS = 32
private const val MAX_CACHED_PIXELS = 16_000_000

object ImageResources {
    private val bitmaps = java.util.LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(
        CACHE_INITIAL_CAPACITY,
        CACHE_LOAD_FACTOR,
        true,
    )
    private val pending = HashSet<String>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "image-decoder").apply {
            isDaemon =
                true
        }
    }

    @Synchronized fun cached(id: String) = bitmaps[id]
    fun prepare(d: Document, repaint: () -> Unit) = prepareOnce(d, repaint, HashSet())

    /** Caller holds this object's lock. */
    private fun shouldDecode(id: String, attempted: Set<String>): Boolean =
        id !in bitmaps && id !in pending && id !in attempted && pending.size < MAX_PENDING_DECODES

    @Synchronized private fun prepareOnce(d: Document, repaint: () -> Unit, attempted: MutableSet<String>) {
        for ((id, resource) in d.resources) {
            if (shouldDecode(id, attempted)) {
                attempted += id
                pending += id
                executor.execute {
                    val loaded = runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(resource.bytes()).toComposeImageBitmap()
                    }.getOrNull()
                    synchronized(this) {
                        pending -= id
                        if (loaded != null) {
                            bitmaps[id] = loaded
                            while (bitmaps.size > MAX_CACHED_BITMAPS ||
                                bitmaps.values.sumOf { it.width.toLong() * it.height } > MAX_CACHED_PIXELS
                            ) {
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
