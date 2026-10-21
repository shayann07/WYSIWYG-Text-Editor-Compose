package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.RichFragmentCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded, versioned fragment; never native serialization of Kotlin/Java objects. */
object FragmentCodec : RichFragmentCodec {
    override val maximumEncodedBytes get() = MAX_BYTES
    const val MAX_BYTES = SnapshotFormat.MAX_ENCODED_BYTES + SnapshotFormat.MAX_UNITS + 32
    private const val MAGIC = 0x41534652 // ASFR
    private const val VERSION = 5
    private const val MIN_ENCODED_BYTES = 17
    override fun encode(f: RichFragment): ByteArray {
        val bytes = SnapshotFormat.encode(f.document)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(MAGIC)
            it.writeInt(VERSION)
            it.writeInt(f.complete.size)
            f.complete.forEach { flag -> it.writeByte(if (flag)1 else 0) }
            it.writeInt(bytes.size)
            it.write(bytes)
        }
        return out.toByteArray()
    }
    override fun decode(bytes: ByteArray): RichFragment {
        require(bytes.size in MIN_ENCODED_BYTES..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == MAGIC && i.readInt() in 1..VERSION) { "Unsupported rich fragment" }
            val n = i.readInt()
            require(n in 1..SnapshotFormat.MAX_UNITS + 1 && n <= i.available() - Int.SIZE_BYTES)
            val flags = List(n) {
                val v = i.readUnsignedByte()
                require(v in 0..1)
                v == 1
            }
            val size = i.readInt()
            require(size == i.available() && size <= SnapshotFormat.MAX_ENCODED_BYTES)
            val document = SnapshotFormat.decode(ByteArray(size).also { i.readFully(it) })
            document.resources.forEach { (id, resource) ->
                val verified = ImageDecoder.validate(resource.bytes())
                require(
                    verified.first == id &&
                        verified.second.width == resource.width &&
                        verified.second.height == resource.height &&
                        verified.second.mime == resource.mime,
                )
            }
            RichFragment(document, flags)
        }
    }
}
