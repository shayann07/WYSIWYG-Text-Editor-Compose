package dev.shayxo.composedoc.model

private const val FIRST_NON_C0_CONTROL = 32
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4

fun evidenceJson(value: Any?): String = when (value) {
    null -> "null"
    is Number, is Boolean -> value.toString()
    is String ->
        buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else ->
                        if (c.code < FIRST_NON_C0_CONTROL ||
                            c.isSurrogate()
                        ) {
                            append("\\u" + c.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                        } else {
                            append(c)
                        }
                }
            }
            append('"')
        }
    is Map<*, *> ->
        value.entries.joinToString(
            prefix = "{",
            postfix = "}",
        ) { "${evidenceJson(it.key.toString())}:${evidenceJson(it.value)}" }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { evidenceJson(it) }
    else -> error("Unsupported evidence value")
}
