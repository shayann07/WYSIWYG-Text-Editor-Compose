package dev.shayxo.composedoc.domain

import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommonEngineTest {
    @Test
    fun attributedHistorySurvivesMixedScriptsAndMalformedUtf16() {
        val engine = EditorEngine()
        val text = "کتاب العربية English 日本語 👩‍💻\uD83D"
        engine.replace(text)
        engine.move(Selection(0, text.length))
        engine.toggleFormat(FormatKey.BOLD)
        assertTrue(engine.snapshot().committed.units.all { it.style.bold })
        engine.perform(EditAction.Undo)
        assertEquals(text, engine.snapshot().committed.text)
        assertTrue(engine.snapshot().committed.units.none { it.style.bold })
        engine.perform(EditAction.Redo)
        assertEquals(text, engine.snapshot().committed.text)
        assertTrue(engine.snapshot().committed.units.all { it.style.bold })
    }

    @Test
    fun partialCommitRemainsCommittedWhenRemainingCandidateIsAborted() {
        val engine = EditorEngine()
        engine.inputMethod("漢字", 1, 1)
        assertEquals("漢", engine.snapshot().committed.text)
        assertEquals("漢字", engine.snapshot().visible.text)
        engine.abortComposition()
        assertEquals("漢", engine.snapshot().committed.text)
        assertEquals("漢", engine.snapshot().visible.text)
        assertFalse(engine.snapshot().waiting)
        engine.perform(EditAction.Undo)
        assertEquals("", engine.snapshot().committed.text)
    }
}
