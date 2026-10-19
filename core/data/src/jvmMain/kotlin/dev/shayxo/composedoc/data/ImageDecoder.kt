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
    const val MAX_BYTES = 4_000_000
    private val imports = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(4),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "image-import").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )
    fun importAsync(path: Path, success: (Pair<String, ImageResource>) -> Unit, failure: (Exception) -> Unit): java.util.concurrent.Future<*>? {
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
            failure(IllegalStateException("Image import queue is full (one decoding, four queued). Retry after it finishes.", e))
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
        require(bytes.size in 12..MAX_BYTES) { "Image must be <=4 MB" }
        val png = bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val jpeg = bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte()
        require(png || jpeg) { "Only PNG/JPEG signatures accepted" }
        // This decoder does not apply EXIF orientation. Refuse tagged orientation rather than silently rotate incorrectly.
        if (jpeg) {
            require(bytes[bytes.size - 2] == 255.toByte() && bytes.last() == 217.toByte()) { "Incomplete JPEG end marker" }
            rejectExifOrientation(bytes)
        }
        if (png) {
            var at = 8
            var ended = false
            while (at + 12 <= bytes.size) {
                val n = java.nio.ByteBuffer.wrap(bytes, at, 4).int
                require(n >= 0 && at.toLong() + 12 + n <= bytes.size) { "Truncated PNG chunk" }
                val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
                val crc = java.util.zip.CRC32().apply { update(bytes, at + 4, n + 4) }.value
                require(crc == (java.nio.ByteBuffer.wrap(bytes, at + 8 + n, 4).int.toLong() and 0xffffffffL)) { "PNG chunk checksum failed" }
                require(type != "eXIf") { "PNG EXIF orientation is not supported; export a pixel-oriented PNG first" }
                at += 12 + n
                if (type == "IEND") {
                    require(n == 0)
                    ended = true
                    break
                }
            }
            require(ended && at == bytes.size) { "Incomplete PNG or trailing data" }
        }
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("Image decoder unavailable")
            try {
                reader.input = stream
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16_000_000) { "Decoded image dimensions exceed limits" }
                val decoded = reader.read(0)
                require(decoded.width == w && decoded.height == h)
                val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return id to ImageResource(Base64.getEncoder().encodeToString(bytes), w, h, if (png)"image/png" else "image/jpeg")
            } finally {
                reader.dispose()
            }
        }
    }
    private fun rejectExifOrientation(b: ByteArray) {
        var at = 2
        while (at + 4 <= b.size && b[at] == 255.toByte()) {
            val marker = b[at + 1].toInt() and 255
            if (marker == 0xda || marker == 0xd9)break
            val n = ((b[at + 2].toInt() and 255) shl 8) + (b[at + 3].toInt() and 255)
            require(n >= 2 && at.toLong() + 2 + n <= b.size) { "Truncated JPEG segment" }
            if (marker == 0xe1 && n >= 8 && String(b, at + 4, 4, Charsets.US_ASCII) == "Exif") {
                val base = at + 10
                require(base + 8 <= at + 2 + n)
                val little = b[base] == 0x49.toByte() && b[base + 1] == 0x49.toByte()
                require(little || (b[base] == 0x4d.toByte() && b[base + 1] == 0x4d.toByte()))
                val buf = java.nio.ByteBuffer.wrap(b).order(if (little)java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
                require(buf.getShort(base + 2).toInt() == 42)
                val offset = buf.getInt(base + 4)
                require(offset >= 8 && base.toLong() + offset + 2 <= at + 2 + n)
                val table = base + offset
                val count = buf.getShort(table).toInt() and 65535
                require(count <= 256 && table.toLong() + 2 + count * 12 <= at + 2 + n)
                repeat(count) { index ->
                    val p = table + 2 + index * 12
                    if ((buf.getShort(p).toInt() and 65535) == 0x112) {
                        require(buf.getShort(p + 2).toInt() == 3 && buf.getInt(p + 4) == 1 && buf.getShort(p + 8).toInt() == 1) { "EXIF orientation is not supported; save a pixel-oriented image first" }
                    }
                }
            }
            at += 2 + n
        }
    }
}
