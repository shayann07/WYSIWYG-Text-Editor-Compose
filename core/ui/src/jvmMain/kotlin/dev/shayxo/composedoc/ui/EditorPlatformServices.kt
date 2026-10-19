package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.domain.RichFragment
import dev.shayxo.composedoc.domain.RichFragmentCodec
import dev.shayxo.composedoc.model.Document

/** Platform IO is supplied by the consumer; the UI never imports core:data. */
data class EditorPlatformServices(
    val documents: DocumentRepository,
    val fragments: RichFragmentCodec,
) {
    companion object {
        val unavailable = EditorPlatformServices(
            object : DocumentRepository {
                override fun load(location: String): Document = error("Document repository not supplied")
                override fun save(location: String, document: Document): Unit = error("Document repository not supplied")
            },
            object : RichFragmentCodec {
                override val maximumEncodedBytes = 0
                override fun encode(fragment: RichFragment): ByteArray = error("Fragment codec not supplied")
                override fun decode(bytes: ByteArray): RichFragment = error("Fragment codec not supplied")
            },
        )
    }
}
