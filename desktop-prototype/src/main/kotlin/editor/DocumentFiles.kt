package editor

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest

/** Prototype-only snapshot format. Not the v5 production journal/container. */
object DocumentFiles {
    const val MAX_UNITS = 100_000
    private const val MAGIC = 0x41535450 // ASTP
    private const val VERSION = 2
    private const val HEADER = 13
    private const val DIGEST = 32
    private const val MAX_BYTES = HEADER + MAX_UNITS * 4 + DIGEST

    fun encode(document: Document): ByteArray {
        require(document.units.size <= MAX_UNITS) { "Prototype files are limited to $MAX_UNITS UTF-16 units." }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(document.units.size)
            out.writeByte(document.startStyle.bits)
            document.units.forEach { unit ->
                require(unit.kind != UnitKind.PARAGRAPH_BREAK || unit.char == '\n') { "Invalid paragraph break" }
                out.writeChar(unit.char.code); out.writeByte(unit.kind.ordinal); out.writeByte(unit.style.bits)
            }
        }
        val payload = bytes.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): Document {
        require(bytes.size in 12 + DIGEST..MAX_BYTES) { "Invalid or oversized prototype file" }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size))) { "File checksum failed; document was not opened." }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Not an Astra prototype file" }
            val version = input.readInt(); require(version in 1..VERSION) { "Unsupported prototype file version" }
            val count = input.readInt()
            require(count in 0..MAX_UNITS && payload.size == (if (version == 1) 12 + count * 3 else HEADER + count * 4)) { "Invalid file length" }
            val startStyle = if (version == 1) TextStyleFlags() else TextStyleFlags.fromBits(input.readUnsignedByte())
            Document(List(count) { index ->
                val char = input.readChar(); val tag = input.readUnsignedByte()
                require(tag in UnitKind.entries.indices) { "Unknown unit kind" }
                val kind = UnitKind.entries[tag]
                require(kind != UnitKind.PARAGRAPH_BREAK || char == '\n') { "Invalid paragraph break" }
                val style = if (version == 1) TextStyleFlags() else TextStyleFlags.fromBits(input.readUnsignedByte())
                TextUnit(index.toLong() + 1, char, kind, style)
            }, startStyle)
        }
    }

    fun read(path: Path): Document = Files.newInputStream(path).use { input ->
        // Bounded even when a file grows between stat and read, or is not a regular file.
        decode(input.readNBytes(MAX_BYTES + 1))
    }

    fun write(path: Path, document: Document, expected: Document? = null) = writeImpl(path, document, expected) {}

    /** Fault seam only used by checks, after force and before the atomic replacement. */
    internal fun writeImpl(path: Path, document: Document, expected: Document? = null, beforeReplace: () -> Unit) {
        val bytes = encode(document)
        val destination = path.toAbsolutePath().normalize()
        fun checkOriginal() {
            if (expected != null) check(read(destination).same(expected)) { "This file changed outside this window. Use Save As to keep your edits in a separate file." }
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
        } finally { Files.deleteIfExists(temp) }
    }
}

/** UI save-point logic; selection and Undo depth are deliberately not dirty flags. */
class SavePoint(initial: Document) {
    private var saved = initial
    fun dirty(frame: EditorFrame) = frame.marked != null || frame.waiting || !saved.same(frame.visible)
    fun acknowledge(written: Document) { saved = written }
    fun capture(frame: EditorFrame): Document {
        check(frame.marked == null && !frame.waiting) { "Finish composing before saving." }
        return frame.committed
    }
}
