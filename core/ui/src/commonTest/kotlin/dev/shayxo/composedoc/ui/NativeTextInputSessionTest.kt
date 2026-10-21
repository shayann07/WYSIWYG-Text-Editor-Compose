package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.NativeEdit
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeTextInputSessionTest {
    private fun owner(text: String = ""): EditorViewModel = EditorViewModel().also {
        if (text.isNotEmpty()) it.dispatch(EditorIntent.Type(text, paragraphBreaks = true))
    }

    @Test fun cursorRulesAndRawReversedSelection() {
        val v = owner("abcd")
        val s = NativeTextInputSession(v)
        assertTrue(s.setSelection(3, 1))
        assertTrue(s.commitText("XY", 0))
        assertEquals("aXYd", v.snapshot().committed.text)
        assertEquals(Selection(1, 1), v.snapshot().selection)
        assertTrue(s.setSelection(1, 3))
        assertTrue(s.setComposingText("pq", 2))
        assertEquals(Selection(4, 4), v.snapshot().selection)
        assertEquals("aXYd", v.snapshot().committed.text)
        assertTrue(s.finishComposingText())
        assertEquals("apqd", v.snapshot().committed.text)
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals("aXYd", v.snapshot().committed.text)
        v.dispatch(EditorIntent.Command(EditAction.Redo))
        assertEquals(Selection(4, 4), v.snapshot().selection)
    }

    @Test fun regionNormalizationPreservesSelectionAndEmptyFinishes() {
        val v = owner("abcdef")
        val s = NativeTextInputSession(v)
        s.setSelection(5, 2)
        val before = v.snapshot()
        assertTrue(s.setComposingRegion(99, -4))
        assertEquals(before.selection, v.snapshot().selection)
        assertEquals(0..5, v.snapshot().marked)
        assertTrue(s.setComposingText("日本", 1))
        assertEquals("abcdef", v.snapshot().committed.text)
        assertTrue(s.setComposingRegion(1, 1))
        assertEquals("日本", v.snapshot().committed.text)
    }

    @Test fun zeroPartialWitnessAndEmptyCandidateAreNotAcceptance() {
        val v = owner("abcd")
        val s = NativeTextInputSession(v)
        s.setSelection(1, 3)
        s.setComposingText("XYZ", 1)
        s.setComposingRegion(2, 4)
        val before = v.snapshot()
        assertTrue(v.dispatch(EditorIntent.Native(NativeEdit.Partial(0))))
        val after = v.snapshot()
        assertEquals(before.committed, after.committed)
        assertEquals(before.visible, after.visible)
        assertEquals(before.marked, after.marked)
        assertEquals(before.selection, after.selection)
        assertEquals(before.undoCount, after.undoCount)
        s.setComposingText("", 1)
        assertEquals("abcd", v.snapshot().committed.text)
        s.close()
        assertEquals("abcd", v.snapshot().committed.text)
    }

    @Test fun acceptedPrefixSurvivesAbortAndQueuedFormattingIsCancelled() {
        val v = owner("Hello ")
        val s = NativeTextInputSession(v)
        s.setComposingText("ab", 1)
        assertTrue(v.dispatch(EditorIntent.Native(NativeEdit.Partial(1))))
        v.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
        s.close()
        assertEquals("Hello a", v.snapshot().committed.text)
        assertFalse(v.snapshot().committed.units.last().style.bold)
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals("Hello ", v.snapshot().committed.text)
    }

    @Test fun rawUtf16AndCodePointDeletionHaveDifferentBoundaries() {
        val v = owner("A😀B")
        val s = NativeTextInputSession(v)
        s.setSelection(3, 3)
        assertTrue(s.deleteSurrounding(1, 0, true))
        assertEquals("AB", v.snapshot().committed.text)
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        s.setSelection(3, 3)
        assertTrue(s.deleteSurrounding(1, 0))
        // The JS compiler emits a lone-surrogate string literal as '?'. Assert
        // numeric UTF-16 units so this oracle stays exact on every runtime.
        val rawUnits = listOf(0x41, 0xd83d, 0x42)
        assertEquals(rawUnits, v.snapshot().committed.text.map { it.code })
        assertEquals(rawUnits, v.snapshot().committed.units.map { it.char.code })
        assertFalse(s.deleteSurrounding(1, 0, true))
        assertEquals(rawUnits, v.snapshot().committed.text.map { it.code })
    }

    @Test fun surroundingDeletionPreservesSelectedAttributesAndIsOneUndo() {
        val v = owner("abcde")
        v.dispatch(EditorIntent.Select(Selection(1, 4)))
        v.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
        val base = v.snapshot()
        val s = NativeTextInputSession(v)
        s.setSelection(4, 1)
        assertTrue(s.deleteSurrounding(999, 999))
        assertEquals("bcd", v.snapshot().committed.text)
        assertTrue(v.snapshot().committed.units.all { it.style.bold })
        assertEquals(base.undoCount + 1, v.snapshot().undoCount)
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertTrue(v.snapshot().committed.same(base.committed))
        v.dispatch(EditorIntent.Command(EditAction.Redo))
        assertEquals("bcd", v.snapshot().committed.text)
    }

    @Test fun deletionOutsideCandidateSurvivesCancelWithoutAcceptingCandidate() {
        val v = owner("abcd")
        val s = NativeTextInputSession(v)
        s.setSelection(1, 3)
        s.setComposingText("Q", 1)
        assertTrue(s.deleteSurrounding(1, 1))
        assertEquals("bc", v.snapshot().committed.text)
        assertEquals("Q", v.snapshot().visible.text)
        s.close()
        assertEquals("bc", v.snapshot().committed.text)
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals("abcd", v.snapshot().committed.text)
    }

    @Test fun retargetedIslandsTerminalCommitAndCrossParagraphReplacement() {
        val v = owner("abcd\nef")
        val s = NativeTextInputSession(v)
        s.setSelection(1, 3)
        s.setComposingText("XYZ", 1)
        s.setComposingRegion(2, 4)
        assertTrue(s.commitText("P", 1))
        assertEquals("aXPd\nef", v.snapshot().committed.text)
        s.setSelection(2, 7)
        assertTrue(s.commitText("اردو", 1))
        assertEquals("aXاردو", v.snapshot().committed.text)
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals("aXPd\nef", v.snapshot().committed.text)
    }

    @Test fun nestedBatchesNotifyOnceButQueriesSeeEveryPublication() {
        val v = owner()
        val s = NativeTextInputSession(v)
        var n = 0
        s.onNotify = { n++ }
        v.onStatePublished = { s.published() }
        s.beginBatch()
        s.beginBatch()
        s.setComposingText("に", 1)
        assertEquals("に", s.allText())
        assertEquals(0, n)
        s.commitText("日本", 1)
        s.endBatch()
        assertEquals(0, n)
        s.endBatch()
        assertEquals(1, n)
        assertFalse(s.endBatch())
    }

    @Test fun staleConnectionCannotMutateAnotherOwnerOrReplacementSession() {
        val first = owner()
        val second = owner("other")
        var generation = 1
        val old = NativeTextInputSession(first, { generation == 1 })
        old.setComposingText("draft", 1)
        old.close()
        generation++
        val fresh = NativeTextInputSession(first, { generation == 2 })
        assertFalse(old.commitText("LATE", 1))
        assertNull(old.allText())
        assertTrue(fresh.commitText("new", 1))
        assertEquals("new", first.snapshot().committed.text)
        assertEquals("other", second.snapshot().committed.text)
    }

    @Test fun multilingualExactAttributesHistoryAndQueries() {
        val fixtures =
            listOf(
                "اُردو، ک\u200dتاب می\u200cروم",
                "العَرَبِيَّة لَا ١٢",
                "עברית English https://example.org 12.5",
                "👩🏽‍💻🇵🇰 e\u0301\u2066abc\u2069",
                "日本語 한국어 हिन्दी বাংলা ไทย",
            )
        fixtures.forEach { text ->
            val v = owner()
            val s = NativeTextInputSession(v)
            v.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
            s.setComposingText(text, 1)
            assertEquals("", v.snapshot().committed.text)
            s.finishComposingText()
            val accepted = v.snapshot().committed
            assertEquals(text, accepted.text)
            assertTrue(accepted.units.all { it.style.bold })
            assertEquals(text, s.textBefore(Int.MAX_VALUE))
            v.dispatch(EditorIntent.Command(EditAction.Undo))
            assertEquals("", v.snapshot().committed.text)
            v.dispatch(EditorIntent.Command(EditAction.Redo))
            assertTrue(accepted.same(v.snapshot().committed))
        }
    }

    @Test fun rejectedReentrantNativeCallbackIsNotAppliedLater() {
        val v = owner()
        val session = NativeTextInputSession(v)
        var accepted: Boolean? = null
        v.onStatePublished = {
            if (accepted == null) {
                accepted = false
                accepted = session.commitText("late", 1)
            }
        }
        assertTrue(session.commitText("a", 1))
        assertEquals(false, accepted)
        assertEquals("a", v.snapshot().committed.text)
        v.onStatePublished = {}
        assertTrue(session.commitText("b", 1))
        assertEquals("ab", v.snapshot().committed.text)
    }

    @Test fun terminalCompositionFormattingAndHistoryPreserveClusters() {
        val v = owner()
        val s = NativeTextInputSession(v)
        val text = "👩🏽‍💻 ک\u200dتاب e\u0301"
        s.setComposingText(text, 1)
        v.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
        assertTrue(v.snapshot().waiting)
        s.finishComposingText()
        assertEquals(text, v.snapshot().committed.text)
        assertTrue(v.snapshot().committed.units.all { it.style.bold })
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals(text, v.snapshot().committed.text)
        assertTrue(v.snapshot().committed.units.none { it.style.bold })
        v.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals("", v.snapshot().committed.text)
    }

    @Test fun diagnosticReentryCannotApplyCapturedRangesToAnotherBasis() {
        val v = owner()
        var once = true
        val s = NativeTextInputSession(v, trace = {
            if (once) {
                once = false
                v.dispatch(EditorIntent.Type("other"))
            }
        })
        assertFalse(s.commitText("stale", 1))
        assertEquals("other", v.snapshot().committed.text)
    }

    @Test fun retirementFencesReentrantDiagnosticBeforeAbortingOverlay() {
        val v = owner("base")
        lateinit var session: NativeTextInputSession
        var accepted = false
        session = NativeTextInputSession(v, trace = {
            if (it.startsWith("closeConnection")) accepted = session.commitText("LATE", 1)
        })
        assertTrue(session.setComposingText("draft", 1))
        session.close()
        assertFalse(accepted)
        assertEquals("base", v.snapshot().committed.text)
        assertNull(v.snapshot().marked)
    }
}
