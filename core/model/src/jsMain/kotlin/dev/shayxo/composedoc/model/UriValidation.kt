package dev.shayxo.composedoc.model

private const val FIRST_NON_C0_CONTROL = 32

actual fun isAbsoluteDocumentUri(value: String): Boolean {
    if (value.any { it.isWhitespace() || it.code < FIRST_NON_C0_CONTROL } ||
        !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)
    ) {
        return false
    }
    return runCatching {
        js("new URL(value)")
        true
    }.getOrDefault(false)
}
