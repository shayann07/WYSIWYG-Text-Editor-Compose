package editor

import java.awt.datatransfer.*
import java.io.*

internal data class ClipboardCapture(val basis: FindBasis, val selection: Selection, val sessionRevision: Long)
internal data class RichFragment(val document: Document, val complete: List<Boolean>) {
    init { require(complete.size == document.paragraphs().size) }
    companion object {
        fun capture(c: ClipboardCapture): RichFragment {
            val d=c.basis.document; val a=c.selection.start; val b=c.selection.end
            require(a < b)
            val blocks=d.paragraphs(); val first=d.paragraphAt(a)
            val selected=d.units.subList(a,b).map { it.copy(followingBlockId=Long.MIN_VALUE) }
            val paragraphSeed=if(first.start==0) d.startStyle else d.units[first.start-1].paragraphStyle
            val seed=if(a==first.start) paragraphSeed else selected.firstOrNull { it.kind==UnitKind.TEXT }?.style ?: paragraphSeed
            val resourceIds=selected.mapNotNull { it.image?.resource }.toSet()
            var doc=Document(selected, seed, first.properties,d.resources.filterKeys { it in resourceIds },pageSettings=d.pageSettings)
            // A fragment can omit an ancestor item. Close those roots explicitly.
            val items=doc.paragraphs().map { it.properties.item }.toSet()
            doc=doc.patchParagraphs(doc.paragraphs().filter { it.properties.parent.isNotEmpty() && it.properties.parent !in items }.associate { it.anchor to it.properties.copy(parent="") })
            val flags=doc.paragraphs().map { p ->
                val source=blocks.last { it.start <= a+p.start }
                a+p.start==source.start && (if(source.hasBreak) b>source.end else b==d.units.size)
            }
            return RichFragment(doc, flags)
        }
    }
}
/** Bounded, versioned fragment; never native serialization of Kotlin/Java objects. */
internal object FragmentCodec {
    const val MAX_BYTES = DocumentFiles.MAX_ENCODED_BYTES + DocumentFiles.MAX_UNITS + 32
    fun encode(f: RichFragment): ByteArray {
        val bytes=DocumentFiles.encode(f.document); val out=ByteArrayOutputStream()
        DataOutputStream(out).use { it.writeInt(0x41534652); it.writeInt(5); it.writeInt(f.complete.size); f.complete.forEach { flag -> it.writeByte(if(flag)1 else 0) }; it.writeInt(bytes.size); it.write(bytes) }
        return out.toByteArray()
    }
    fun decode(bytes: ByteArray): RichFragment {
        require(bytes.size in 17..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt()==0x41534652 && i.readInt() in 1..5) { "Unsupported rich fragment" }
            val n=i.readInt(); require(n in 1..DocumentFiles.MAX_UNITS+1 && n <= i.available()-4)
            val flags=List(n) { val v=i.readUnsignedByte(); require(v in 0..1); v==1 }
            val size=i.readInt(); require(size==i.available() && size<=DocumentFiles.MAX_ENCODED_BYTES)
            RichFragment(DocumentFiles.decode(i.readNBytes(size)), flags)
        }
    }
}
/** AWT adapter: native OS transferable stream works between independent JVMs. */
internal object RichClipboard {
    val flavor=DataFlavor("application/x-astra-fragment;class=java.io.InputStream", "Astra rich fragment v4")
    fun publish(c: ClipboardCapture, clipboard: Clipboard) {
        val f=RichFragment.capture(c); val rich=FragmentCodec.encode(f); val plain=f.document.plainText()
        clipboard.setContents(object: Transferable {
            override fun getTransferDataFlavors()=arrayOf(flavor,DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(f: DataFlavor)=f==flavor || f==DataFlavor.stringFlavor
            override fun getTransferData(f: DataFlavor): Any = when(f) { flavor -> ByteArrayInputStream(rich); DataFlavor.stringFlavor -> plain; else -> throw UnsupportedFlavorException(f) }
        },null)
    }
    data class Read(val rich: RichFragment?, val plain: String?, val fallback: Boolean=false)
    fun read(clipboard: Clipboard, plainOnly: Boolean=false): Read {
        val data=clipboard.getContents(null) ?: return Read(null,null)
        var fallback=false
        if(!plainOnly && data.isDataFlavorSupported(flavor)) {
            try { val bytes=(data.getTransferData(flavor) as InputStream).use { it.readNBytes(FragmentCodec.MAX_BYTES+1) }; return Read(FragmentCodec.decode(bytes),null) }
            catch(e: Exception) { fallback=true }
        }
        if(!data.isDataFlavorSupported(DataFlavor.stringFlavor)) return Read(null,null,fallback)
        val text=(data.getTransferData(DataFlavor.stringFlavor) as? String) ?: return Read(null,null,fallback)
        require(text.length<=DocumentFiles.MAX_UNITS) { "Clipboard text is too large" }
        return Read(null,text.replace("\r\n","\n").replace('\r','\n'),fallback)
    }
}
