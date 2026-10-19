package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.model.*
import java.io.*
/** Bounded, versioned fragment; never native serialization of Kotlin/Java objects. */
object FragmentCodec : RichFragmentCodec {
    override val maximumEncodedBytes get() = MAX_BYTES
    const val MAX_BYTES = DocumentFiles.MAX_ENCODED_BYTES + DocumentFiles.MAX_UNITS + 32
    override fun encode(f: RichFragment): ByteArray {
        val bytes = DocumentFiles.encode(f.document)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(0x41534652)
            it.writeInt(5)
            it.writeInt(f.complete.size)
            f.complete.forEach { flag -> it.writeByte(if (flag)1 else 0) }
            it.writeInt(bytes.size)
            it.write(bytes)
        }
        return out.toByteArray()
    }
    override fun decode(bytes: ByteArray): RichFragment {
        require(bytes.size in 17..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == 0x41534652 && i.readInt() in 1..5) { "Unsupported rich fragment" }
            val n = i.readInt()
            require(n in 1..DocumentFiles.MAX_UNITS + 1 && n <= i.available() - 4)
            val flags = List(n) {
                val v = i.readUnsignedByte()
                require(v in 0..1)
                v == 1
            }
            val size = i.readInt()
            require(size == i.available() && size <= DocumentFiles.MAX_ENCODED_BYTES)
            RichFragment(DocumentFiles.decode(i.readNBytes(size)), flags)
        }
    }
}
