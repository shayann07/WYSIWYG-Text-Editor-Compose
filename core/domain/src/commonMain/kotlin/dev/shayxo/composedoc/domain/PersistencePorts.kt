package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.Document

/** Pure contracts; concrete formats/filesystems are selected by the application. */
interface DocumentCodec {
    fun encode(document: Document): ByteArray
    fun decode(bytes: ByteArray): Document
}

interface DocumentRepository {
    fun load(location: String): Document
    fun save(location: String, document: Document)
}

interface RichFragmentCodec {
    val maximumEncodedBytes: Int
    fun encode(fragment: RichFragment): ByteArray
    fun decode(bytes: ByteArray): RichFragment
}

internal expect fun ownerThreadToken(): Any
internal expect fun monotonicMillis(): Long
