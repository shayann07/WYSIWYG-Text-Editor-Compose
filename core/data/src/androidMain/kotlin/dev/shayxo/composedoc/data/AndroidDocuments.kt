package dev.shayxo.composedoc.data

import android.graphics.BitmapFactory
import android.os.Looper
import android.util.AtomicFile
import android.util.Base64
import dev.shayxo.composedoc.domain.DocumentCatalog
import dev.shayxo.composedoc.domain.DocumentCodec
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageResource
import java.io.File
import java.security.MessageDigest

/** Same versioned snapshot bytes as desktop; decode/save must run on an IO worker. */
object AndroidDocumentCodec : DocumentCodec {
    override fun encode(document: Document) = SnapshotFormat.encode(document)
    override fun decode(bytes: ByteArray) = SnapshotFormat.decode(bytes).also { document ->
        document.resources.forEach { (id, resource) ->
            val verified = ImageDecoder.validate(resource.bytes())
            require(
                verified.first == id &&
                    verified.second.width == resource.width &&
                    verified.second.height == resource.height &&
                    verified.second.mime == resource.mime,
            ) {
                "Embedded image identity or dimensions do not match its owned bytes"
            }
        }
    }
}

/** App-owned directory only; AtomicFile retains its last complete value on interrupted replacement. */
class AndroidDocumentRepository(private val directory: File) : DocumentCatalog {
    private fun target(location: String): AtomicFile {
        require(location.matches(Regex("[a-zA-Z0-9_-]{1,64}\\.astra"))) { "Use an app-private document name" }
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, location))
    }
    override fun locations(): List<String> {
        check(Looper.myLooper() != Looper.getMainLooper())
        return directory.listFiles().orEmpty().filter {
            it.isFile &&
                it.name.matches(Regex("[a-zA-Z0-9_-]{1,64}\\.astra"))
        }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }).map { it.name }
    }
    override fun load(location: String): Document {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Document IO must run off the UI thread" }
        val bytes = target(location).openRead().use { it.readBytesBounded(SnapshotFormat.MAX_ENCODED_BYTES) }
        return AndroidDocumentCodec.decode(bytes)
    }
    override fun save(location: String, document: Document) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Document IO must run off the UI thread" }
        val bytes = AndroidDocumentCodec.encode(document)
        val file = target(location)
        atomicBytes(file.baseFile, bytes)
    }
}

private const val RESOURCE_READ_BUFFER_BYTES = 8192

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(RESOURCE_READ_BUFFER_BYTES)
    while (true) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (n < 0) break
        output.write(buffer, 0, n)
        require(output.size() <= limit) { "Resource exceeds admitted size" }
    }
    return output.toByteArray()
}

/** Android decoder; signatures, lengths, checksums and orientation policy are shared with desktop. */
object ImageDecoder {
    const val MAX_BYTES = ImageRules.MAX_BYTES
    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 16_000_000
    fun validate(bytes: ByteArray): Pair<String, ImageResource> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Image decoding must run off the UI thread" }
        return ImageRules.validate(bytes) { data, png ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(
                bounds.outWidth in 1..MAX_DIMENSION &&
                    bounds.outHeight in 1..MAX_DIMENSION &&
                    bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS,
            )
            val image = checkNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
            try {
                require(image.width == bounds.outWidth && image.height == bounds.outHeight)
            } finally {
                image.recycle()
            }
            val id = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            id to ImageResource(
                Base64.encodeToString(data, Base64.NO_WRAP),
                bounds.outWidth,
                bounds.outHeight,
                if (png) "image/png" else "image/jpeg",
            )
        }
    }
}
