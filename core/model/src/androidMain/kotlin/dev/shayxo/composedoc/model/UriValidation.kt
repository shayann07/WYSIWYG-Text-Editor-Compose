package dev.shayxo.composedoc.model

actual fun isAbsoluteDocumentUri(value: String): Boolean = runCatching { java.net.URI(value).isAbsolute }.getOrDefault(false)
