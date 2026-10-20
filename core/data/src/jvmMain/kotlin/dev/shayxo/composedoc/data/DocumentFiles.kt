package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.DocumentCodec
import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Document
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** JVM filesystem adapter and compatibility facade for the shared snapshot codec. */
object DocumentFiles : DocumentCodec, DocumentRepository {
    const val MAX_UNITS = SnapshotFormat.MAX_UNITS
    const val MAX_ENCODED_BYTES = SnapshotFormat.MAX_ENCODED_BYTES
    override fun encode(document: Document) = SnapshotFormat.encode(document)
    override fun decode(bytes: ByteArray) = SnapshotFormat.decode(bytes)
    override fun load(location: String) = read(Path.of(location))
    override fun save(location: String, document: Document) = write(Path.of(location), document)
    fun read(path: Path): Document = Files.newInputStream(path).use { input ->
        // Bounded even when a file grows between stat and read, or is not a regular file.
        input.readNBytes(MAX_ENCODED_BYTES + 1).let { bytes ->
            decode(bytes).also { SafeDocumentIO.remember(it, path, bytes) }
        }
    }

    fun write(path: Path, document: Document, expected: Document? = null) = writeImpl(path, document, expected) {}

    /** Fault seam only used by checks, after force and before the atomic replacement. */
    fun writeImpl(path: Path, document: Document, expected: Document? = null, beforeReplace: () -> Unit) {
        val bytes = encode(document)
        val destination = path.toAbsolutePath().normalize()
        fun checkOriginal() {
            if (expected !=
                null
            ) {
                check(read(destination).same(expected)) {
                    "This file changed outside this window. Use Save As to keep your edits in a separate file."
                }
            }
        }
        checkOriginal()
        val temp = Files.createTempFile(destination.parent, ".astra-save-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeReplace()
            checkOriginal()
            // No unsafe truncate-in-place fallback on filesystems lacking atomic replacement.
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

/** UI save-point logic; selection and Undo depth are deliberately not dirty flags. */
class SavePoint(initial: Document) {
    private var saved = initial
    fun dirty(frame: EditorFrame) = frame.marked != null || frame.waiting || !saved.same(frame.visible)
    fun acknowledge(written: Document) {
        saved = written
    }
    fun capture(frame: EditorFrame): Document {
        check(frame.marked == null && !frame.waiting) { "Finish composing before saving." }
        return frame.committed
    }
}
