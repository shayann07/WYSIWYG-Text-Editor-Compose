package dev.shayxo.composedoc.model

import platform.Foundation.NSURL

actual fun isAbsoluteDocumentUri(value: String): Boolean {
    if (value.any { it.isWhitespace() || it.code < 32 }) return false
    return NSURL.URLWithString(value)?.scheme != null
}
