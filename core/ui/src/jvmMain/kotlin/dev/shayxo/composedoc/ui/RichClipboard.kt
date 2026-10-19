package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import java.awt.datatransfer.*
import java.io.*
/** AWT adapter: native OS transferable stream works between independent JVMs. */
object RichClipboard {
    val flavor = DataFlavor("application/x-astra-fragment;class=java.io.InputStream", "Astra rich fragment v4")
    fun publish(c: ClipboardCapture, clipboard: Clipboard, codec: RichFragmentCodec) {
        val f = RichFragment.capture(c)
        val rich = codec.encode(f)
        val plain = f.document.plainText()
        clipboard.setContents(
            object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(flavor, DataFlavor.stringFlavor)
                override fun isDataFlavorSupported(f: DataFlavor) = f == flavor || f == DataFlavor.stringFlavor
                override fun getTransferData(f: DataFlavor): Any = when (f) {
                    flavor -> ByteArrayInputStream(rich)
                    DataFlavor.stringFlavor -> plain
                    else -> throw UnsupportedFlavorException(f)
                }
            },
            null,
        )
    }
    data class Read(val rich: RichFragment?, val plain: String?, val fallback: Boolean = false)
    fun read(clipboard: Clipboard, plainOnly: Boolean = false, codec: RichFragmentCodec): Read {
        val data = clipboard.getContents(null) ?: return Read(null, null)
        var fallback = false
        if (!plainOnly && data.isDataFlavorSupported(flavor)) {
            try {
                val bytes = (data.getTransferData(flavor) as InputStream).use { it.readNBytes(codec.maximumEncodedBytes + 1) }
                return Read(codec.decode(bytes), null)
            } catch (e: Exception) {
                fallback = true
            }
        }
        if (!data.isDataFlavorSupported(DataFlavor.stringFlavor)) return Read(null, null, fallback)
        val text = (data.getTransferData(DataFlavor.stringFlavor) as? String) ?: return Read(null, null, fallback)
        require(text.length <= DocumentLimits.MAX_UNITS) { "Clipboard text is too large" }
        return Read(null, text.replace("\r\n", "\n").replace('\r', '\n'), fallback)
    }
}
