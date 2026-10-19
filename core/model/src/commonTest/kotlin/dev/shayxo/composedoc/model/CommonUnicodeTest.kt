package dev.shayxo.composedoc.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CommonUnicodeTest {
    @Test
    fun graphemesKeepEmojiAndCombiningMarksTogether() {
        assertContentEquals(listOf(0, 2, 7, 11), Graphemes.stops("a\u0301👩‍💻🇵🇰").toList())
    }

    @Test
    fun malformedUtf16RemainsAnAddressableRawUnit() {
        val raw = "\uD83Dک\uDC00"
        assertContentEquals(listOf(0, 1, 2, 3), Graphemes.stops(raw).toList())
        val document = Document(raw.mapIndexed { i, char -> TextUnit(i.toLong(), char) })
        assertEquals(raw, document.text)
    }
}
