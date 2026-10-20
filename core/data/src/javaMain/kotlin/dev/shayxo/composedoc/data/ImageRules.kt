package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.model.ImageResource

/** Shared bounded encoded-image validation; platform decoding is supplied separately. */
internal object ImageRules {
    const val MAX_BYTES = 4_000_000
    private const val MIN_IMAGE_BYTES = 12
    private const val BYTE_MASK = 0xff
    private const val UNSIGNED_SHORT_MASK = 0xffff
    private const val UNSIGNED_INT_MASK = 0xffffffffL

    // PNG: 8-byte signature, then chunks of length, type, data and CRC.
    private const val PNG_SIGNATURE_TEXT = "\u0089PNG\r\n\u001a\n"
    private val PNG_SIGNATURE = PNG_SIGNATURE_TEXT.toByteArray(Charsets.ISO_8859_1)
    private const val PNG_FIELD_BYTES = 4
    private const val PNG_CHUNK_HEADER = 8
    private const val PNG_CHUNK_OVERHEAD = 12

    // JPEG markers and the EXIF/TIFF structures inside an APP1 segment.
    private const val JPEG_MARKER = 0xff
    private const val JPEG_SOI = 0xd8
    private const val JPEG_EOI = 0xd9
    private const val JPEG_SOS = 0xda
    private const val JPEG_APP1 = 0xe1
    private const val JPEG_SEGMENT_HEADER = 4
    private const val JPEG_LENGTH_HIGH = 2
    private const val JPEG_LENGTH_LOW = 3
    private const val EXIF_MIN_SEGMENT_LENGTH = 8
    private const val EXIF_TIFF_OFFSET = 10
    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_IFD_OFFSET_FIELD = 4
    private const val TIFF_LITTLE_ENDIAN = 0x49
    private const val TIFF_BIG_ENDIAN = 0x4d
    private const val TIFF_MAGIC = 42
    private const val MAX_IFD_ENTRIES = 256
    private const val IFD_ENTRY_BYTES = 12
    private const val IFD_COUNT_FIELD = 4
    private const val IFD_VALUE_FIELD = 8
    private const val ORIENTATION_TAG = 0x112
    private const val TIFF_SHORT_TYPE = 3
    fun validate(
        bytes: ByteArray,
        decode: (ByteArray, Boolean) -> Pair<String, ImageResource>,
    ): Pair<String, ImageResource> {
        require(bytes.size in MIN_IMAGE_BYTES..MAX_BYTES) { "Image must be <=4 MB" }
        val png = bytes.take(PNG_SIGNATURE.size).toByteArray().contentEquals(PNG_SIGNATURE)
        val jpeg = bytes[0] == JPEG_MARKER.toByte() && bytes[1] == JPEG_SOI.toByte() && bytes[2] == JPEG_MARKER.toByte()
        require(png || jpeg) { "Only PNG/JPEG signatures accepted" }
        // This decoder does not apply EXIF orientation. Refuse tagged orientation rather than silently
        // rotate incorrectly.
        if (jpeg) {
            require(bytes[bytes.size - 2] == JPEG_MARKER.toByte() && bytes.last() == JPEG_EOI.toByte()) {
                "Incomplete JPEG end marker"
            }
            rejectExifOrientation(bytes)
        }
        if (png) validatePngChunks(bytes)
        return decode(bytes, png)
    }

    private fun validatePngChunks(bytes: ByteArray) {
        var at = PNG_SIGNATURE.size
        var ended = false
        while (at + PNG_CHUNK_OVERHEAD <= bytes.size) {
            val n = java.nio.ByteBuffer.wrap(bytes, at, PNG_FIELD_BYTES).int
            require(n >= 0 && at.toLong() + PNG_CHUNK_OVERHEAD + n <= bytes.size) { "Truncated PNG chunk" }
            val type = String(bytes, at + PNG_FIELD_BYTES, PNG_FIELD_BYTES, Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(bytes, at + PNG_FIELD_BYTES, n + PNG_FIELD_BYTES) }.value
            val storedCrc = java.nio.ByteBuffer.wrap(bytes, at + PNG_CHUNK_HEADER + n, PNG_FIELD_BYTES).int.toLong()
            require(crc == (storedCrc and UNSIGNED_INT_MASK)) {
                "PNG chunk checksum failed"
            }
            require(type != "eXIf") { "PNG EXIF orientation is not supported; export a pixel-oriented PNG first" }
            at += PNG_CHUNK_OVERHEAD + n
            if (type == "IEND") {
                require(n == 0)
                ended = true
                break
            }
        }
        require(ended && at == bytes.size) { "Incomplete PNG or trailing data" }
    }

    private fun rejectExifOrientation(b: ByteArray) {
        var at = 2
        while (at + JPEG_SEGMENT_HEADER <= b.size && b[at] == JPEG_MARKER.toByte()) {
            val marker = b[at + 1].toInt() and BYTE_MASK
            if (marker == JPEG_SOS || marker == JPEG_EOI)break
            val high = b[at + JPEG_LENGTH_HIGH].toInt() and BYTE_MASK
            val n = (high shl Byte.SIZE_BITS) + (b[at + JPEG_LENGTH_LOW].toInt() and BYTE_MASK)
            require(n >= 2 && at.toLong() + 2 + n <= b.size) { "Truncated JPEG segment" }
            val exif = n >= EXIF_MIN_SEGMENT_LENGTH &&
                String(b, at + JPEG_SEGMENT_HEADER, JPEG_SEGMENT_HEADER, Charsets.US_ASCII) == "Exif"
            if (marker == JPEG_APP1 && exif) requireUprightExif(b, at, n)
            at += 2 + n
        }
    }

    /** Validates the TIFF header of an APP1 EXIF segment at [at] of length [n] and its first IFD. */
    private fun requireUprightExif(b: ByteArray, at: Int, n: Int) {
        val base = at + EXIF_TIFF_OFFSET
        require(base + TIFF_HEADER_BYTES <= at + 2 + n)
        val little = b[base] == TIFF_LITTLE_ENDIAN.toByte() && b[base + 1] == TIFF_LITTLE_ENDIAN.toByte()
        require(little || (b[base] == TIFF_BIG_ENDIAN.toByte() && b[base + 1] == TIFF_BIG_ENDIAN.toByte()))
        val buf = java.nio.ByteBuffer.wrap(
            b,
        ).order(if (little)java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
        require(buf.getShort(base + 2).toInt() == TIFF_MAGIC)
        val offset = buf.getInt(base + TIFF_IFD_OFFSET_FIELD)
        require(offset >= TIFF_HEADER_BYTES && base.toLong() + offset + 2 <= at + 2 + n)
        val table = base + offset
        val count = buf.getShort(table).toInt() and UNSIGNED_SHORT_MASK
        require(count <= MAX_IFD_ENTRIES && table.toLong() + 2 + count * IFD_ENTRY_BYTES <= at + 2 + n)
        repeat(count) { index ->
            val p = table + 2 + index * IFD_ENTRY_BYTES
            if ((buf.getShort(p).toInt() and UNSIGNED_SHORT_MASK) == ORIENTATION_TAG) {
                require(
                    buf.getShort(p + 2).toInt() == TIFF_SHORT_TYPE &&
                        buf.getInt(p + IFD_COUNT_FIELD) == 1 &&
                        buf.getShort(p + IFD_VALUE_FIELD).toInt() == 1,
                ) {
                    "EXIF orientation is not supported; save a pixel-oriented image first"
                }
            }
        }
    }
}
