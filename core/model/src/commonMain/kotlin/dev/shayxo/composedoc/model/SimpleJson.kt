package dev.shayxo.composedoc.model
class SimpleJson(private val src: String) {
    private var pos = 0

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    fun parse(): Any? {
        skipWhitespace()
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> if (ch == '-' || ch.isDigit()) parseNumber() else error("Unexpected char '$ch' at pos $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        check(src[pos++] == '{')
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return map
        }
        while (pos < src.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            check(src[pos++] == ':')
            val value = parse()
            map[key] = value
            skipWhitespace()
            if (peek() == ',') {
                pos++
                continue
            }
            if (peek() == '}') {
                pos++
                break
            }
            error("Expected ',' or '}' at pos $pos")
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        check(src[pos++] == '[')
        val list = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return list
        }
        while (pos < src.length) {
            list.add(parse())
            skipWhitespace()
            if (peek() == ',') {
                pos++
                continue
            }
            if (peek() == ']') {
                pos++
                break
            }
            error("Expected ',' or ']' at pos $pos")
        }
        return list
    }

    private fun parseString(): String {
        check(src[pos++] == '"')
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> error("Unknown escape '\\$esc' at pos $pos")
                }
            } else {
                sb.append(c)
            }
        }
        error("Unterminated string")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length &&
            (
                src[pos].isDigit() ||
                    src[pos] == '.' ||
                    src[pos] == 'e' ||
                    src[pos] == 'E' ||
                    src[pos] == '+' ||
                    src[pos] == '-'
                )
        ) {
            pos++
        }
        val s = src.substring(start, pos)
        return if (s.contains('.') || s.contains('e') || s.contains('E')) s.toDouble() else s.toLong()
    }

    private fun parseBoolean(): Boolean {
        if (src.startsWith("true", pos)) {
            pos += 4
            return true
        }
        if (src.startsWith("false", pos)) {
            pos += 5
            return false
        }
        error("Expected boolean at pos $pos")
    }

    private fun parseNull(): Any? {
        if (src.startsWith("null", pos)) {
            pos += 4
            return null
        }
        error("Expected null at pos $pos")
    }
}
