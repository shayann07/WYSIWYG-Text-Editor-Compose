package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.model.ImageResource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

/** Platform IO/decoder adapter. Call only from background workers, never editing/UI thread. */
object ImageDecoder {
    const val MAX_BYTES = ImageRules.MAX_BYTES
    private const val IMPORT_QUEUE_CAPACITY = 4
    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 16_000_000
    private val imports = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(IMPORT_QUEUE_CAPACITY),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "image-import").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    // Reason: any failure while importing (IO, decoding, validation) must reach the failure callback;
    // narrowing the catch would let unexpected exceptions escape the import worker silently.
    @Suppress("TooGenericExceptionCaught")
    fun importAsync(
        path: Path,
        success: (Pair<String, ImageResource>) -> Unit,
        failure: (Exception) -> Unit,
    ): java.util.concurrent.Future<*>? {
        try {
            return imports.submit {
                try {
                    val value = import(path)
                    if (!Thread.currentThread().isInterrupted)javax.swing.SwingUtilities.invokeLater { success(value) }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted)javax.swing.SwingUtilities.invokeLater { failure(e) }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            failure(
                IllegalStateException(
                    "Image import queue is full (one decoding, four queued). Retry after it finishes.",
                    e,
                ),
            )
            return null
        }
    }
    fun import(path: Path): Pair<String, ImageResource> {
        check(!javax.swing.SwingUtilities.isEventDispatchThread())
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        return validate(bytes)
    }
    fun validate(bytes: ByteArray): Pair<String, ImageResource> {
        check(!javax.swing.SwingUtilities.isEventDispatchThread()) { "Image decoding must run off the UI thread" }
        return ImageRules.validate(bytes, ::decodeResource)
    }
    private fun decodeResource(bytes: ByteArray, png: Boolean): Pair<String, ImageResource> {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader =
                ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("Image decoder unavailable")
            try {
                reader.input = stream
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                require(w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION && w.toLong() * h <= MAX_PIXELS) {
                    "Decoded image dimensions exceed limits"
                }
                val decoded = reader.read(0)
                require(decoded.width == w && decoded.height == h)
                val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return id to
                    ImageResource(
                        Base64.getEncoder().encodeToString(bytes),
                        w,
                        h,
                        if (png)"image/png" else "image/jpeg",
                    )
            } finally {
                reader.dispose()
            }
        }
    }
}
