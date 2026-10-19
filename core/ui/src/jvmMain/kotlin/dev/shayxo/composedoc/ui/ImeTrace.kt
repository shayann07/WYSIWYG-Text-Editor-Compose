package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import java.io.File

/** Opt-in local test evidence; never enabled for ordinary documents. */
class ImeTrace(val directory: File) {
    private val file = File(directory, "events.jsonl")
    private var sequence = 0L
    init {
        check(directory.mkdirs()) { "Use a new trace directory" }
    }
    fun record(kind: String, detail: String, frame: EditorFrame) {
        if (sequence >= 20_000 || file.length() > 16_000_000) return
        val mark = frame.marked?.let { "[${it.first},${it.last + 1}]" } ?: "null"
        val styles = frame.visible.units.joinToString(",") { it.style.bits.toString() }
        val line = "{\"sequence\":${++sequence},\"kind\":${quote(kind)},\"detail\":${quote(detail)},\"visible\":${quote(frame.visible.text)},\"committed\":${quote(frame.committed.text)},\"selection\":[${frame.selection.anchor},${frame.selection.focus}],\"marked\":$mark,\"styles\":[$styles],\"undo\":${frame.undoCount},\"redo\":${frame.redoCount},\"waiting\":${frame.waiting},\"documentRevision\":${frame.documentRevision},\"notice\":${quote(frame.notice)}}\n"
        runCatching { file.appendText(line, Charsets.UTF_8) }.onFailure { System.err.println("IME trace write failed: ${it.message}") }
    }
    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c.code < 32 || Character.isSurrogate(c)) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
