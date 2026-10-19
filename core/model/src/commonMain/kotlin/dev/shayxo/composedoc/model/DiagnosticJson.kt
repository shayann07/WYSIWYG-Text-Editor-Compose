package dev.shayxo.composedoc.model

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
                    else -> if (c.code < 32 || c.isSurrogate()) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
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
