package dev.shayxo.composedoc.model

actual fun isAbsoluteDocumentUri(value: String): Boolean {
    if (value.any { it.isWhitespace() || it.code < 32 } || !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value))return false
    return try {
        js("new URL(value)")
        true
    } catch (e: Throwable) {
        false
    }
}
