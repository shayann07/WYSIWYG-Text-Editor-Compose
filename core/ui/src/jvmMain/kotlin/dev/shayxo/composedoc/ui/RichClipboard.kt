package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.RichFragmentCodec
import dev.shayxo.composedoc.model.DocumentLimits
import dev.shayxo.composedoc.model.plainText
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.InputStream

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
        val richAvailable = !plainOnly && data.isDataFlavorSupported(flavor)
        val rich = if (richAvailable) decodeRich(data, codec) else null
        return if (rich != null) Read(rich, null) else readPlain(data, fallback = richAvailable)
    }

    /** Decodes the rich flavor, or returns null so the caller falls back to plain text. */
    private fun decodeRich(data: Transferable, codec: RichFragmentCodec): RichFragment? = try {
        val bytes = (data.getTransferData(flavor) as InputStream).use {
            it.readNBytes(
                codec.maximumEncodedBytes + 1,
            )
        }
        codec.decode(bytes)
    } catch (ignored: Exception) {
        // Any foreign, truncated or malformed rich payload degrades to the plain-text flavor.
        null
    }

    private fun readPlain(data: Transferable, fallback: Boolean): Read {
        val text = if (data.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            data.getTransferData(DataFlavor.stringFlavor) as? String
        } else {
            null
        }
        if (text == null) return Read(null, null, fallback)
        require(text.length <= DocumentLimits.MAX_UNITS) { "Clipboard text is too large" }
        return Read(null, text.replace("\r\n", "\n").replace('\r', '\n'), fallback)
    }
}
