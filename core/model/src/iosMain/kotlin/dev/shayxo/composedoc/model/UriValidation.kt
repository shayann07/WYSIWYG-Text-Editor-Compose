package dev.shayxo.composedoc.model

import platform.Foundation.NSURL

private const val FIRST_NON_C0_CONTROL = 32

actual fun isAbsoluteDocumentUri(value: String): Boolean {
    if (value.any { it.isWhitespace() || it.code < FIRST_NON_C0_CONTROL }) return false
    return NSURL.URLWithString(value)?.scheme != null
}
