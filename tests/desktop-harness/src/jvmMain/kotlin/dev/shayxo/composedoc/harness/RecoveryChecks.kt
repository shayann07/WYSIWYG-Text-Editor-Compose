package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.domain.CanonicalProbeBasis
import dev.shayxo.composedoc.domain.CanonicalProbeEdit
import dev.shayxo.composedoc.domain.CanonicalProbeOutcome
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind

private class RecoveryCheckRun {
    var count = 0

    fun test(name: String, body: () -> Unit) {
        body()
        count++
        println("PASS recovery: $name")
    }
}

private fun recoveryAbortAndCapture(e: EditorEngine): RecoveredDraft {
    val draft = checkNotNull(e.captureRecoveryDraft())
    e.abortComposition()
    check(e.snapshot().committed == draft.basis)
    check(e.snapshot().recoveryBasisRevision == draft.basisRevision)
    return draft
}

/** Deterministic recovery regressions; no OS IME qualification implied. */
fun runRecoveryChecks() {
    val bold = TextStyleFlags(bold = true)
    val italic = TextStyleFlags(italic = true)
    val initial = Document(
        listOf(
            TextUnit(1, 'ک', style = bold),
            TextUnit(2, 'ت', style = italic),
            TextUnit(3, '\n', UnitKind.PARAGRAPH_BREAK, paragraphStyle = bold),
            TextUnit(4, 'ا', style = italic),
            TextUnit(5, 'ب', style = bold),
        ),
        italic,
    )
    val run = RecoveryCheckRun()
    runRecoveryApplyChecks(run, initial, bold)
    run.test("changed basis rejects even after undo restores identical content") {
        checkChangedBasisRejects(initial)
    }
    runRecoveryDraftChecks(run, initial)
    runRecoveryOwnerChecks(run, initial)
    println("ALL ${run.count} recovery checks passed")
}

private fun runRecoveryApplyChecks(run: RecoveryCheckRun, initial: Document, bold: TextStyleFlags) {
    run.test("caret movement cannot redirect attributed structural draft; one transaction and exact history") {
        val e = EditorEngine(initial)
        e.selectAll()
        e.inputMethod("ق\nاب", 0, 4)
        val desired = e.snapshot()
        val draft = recoveryAbortAndCapture(e)
        e.move(Selection(1, 1))
        val before = e.snapshot()
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)
        check(e.snapshot().committed == desired.visible && e.snapshot().selection == desired.selection)
        check(e.snapshot().committed.units.last().style == bold)
        check(e.snapshot().undoCount == before.undoCount + 1)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed && e.snapshot().selection == before.selection)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed == desired.visible && e.snapshot().selection == desired.selection)
    }
    run.test("retained paragraph separator and paragraph attributes survive recovery") {
        val e = EditorEngine(initial)
        e.selectAll()
        e.inputMethod("ق\nاب", 0, 4)
        // The newline and suffix are retained from the original, including semantic kind and attributes.
        val expected = e.snapshot().visible
        check(expected.units[1].kind == UnitKind.PARAGRAPH_BREAK)
        check(expected.units[1].paragraphStyle == bold)
        val draft = recoveryAbortAndCapture(e)
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)
        check(e.snapshot().visible == expected)
    }
}

private fun checkChangedBasisRejects(initial: Document) {
    val e = EditorEngine(initial)
    e.selectAll()
    e.inputMethod("قلم", 0, 3)
    val draft = recoveryAbortAndCapture(e)
    e.move(Selection(0, 0))
    e.replace("X")
    val changed = e.snapshot()
    check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.RecoveryBaseChanged)
    val identityOnly = EditorEngine(Document(listOf(TextUnit(1, 'ک'), TextUnit(2, 'ک'))))
    identityOnly.move(Selection(0, 1))
    identityOnly.inputMethod("ق", 0, 1)
    val retained = recoveryAbortAndCapture(identityOnly)
    val prior = identityOnly.snapshot()
    identityOnly.installCanonicalProbeHistory {}
    fun basis() = identityOnly.snapshot().let {
        CanonicalProbeBasis(it.documentRevision, it.committed.units.map { u -> u.id }, it.identityGeneration)
    }
    check(
        identityOnly.applyCanonicalProbe(basis(), listOf(CanonicalProbeEdit.Move(0, 1, 1)), Selection(0, 0)) ==
            CanonicalProbeOutcome.Applied,
    )
    check(
        identityOnly.applyCanonicalProbe(basis(), listOf(CanonicalProbeEdit.Move(1, 2, 0)), Selection(0, 0)) ==
            CanonicalProbeOutcome.Applied,
    )
    check(
        identityOnly.snapshot().committed == retained.basis &&
            identityOnly.snapshot().documentRevision == prior.documentRevision,
    )
    check(identityOnly.applyRecoveredDraft(retained) == RecoveryApplyResult.RecoveryBaseChanged)
    check(
        identityOnly.snapshot().committed == prior.committed &&
            identityOnly.snapshot().undoCount == prior.undoCount,
    )
    val formatted = EditorEngine(initial)
    formatted.selectAll()
    formatted.inputMethod("قلم", 0, 3)
    val formatDraft = recoveryAbortAndCapture(formatted)
    formatted.move(Selection(0, 1))
    formatted.toggleFormat(FormatKey.BOLD)
    formatted.perform(EditAction.Undo)
    check(
        formatted.snapshot().committed == formatDraft.basis &&
            formatted.snapshot().identityGeneration == formatDraft.basisIdentityGeneration,
    )
    check(formatted.applyRecoveredDraft(formatDraft) == RecoveryApplyResult.RecoveryBaseChanged) {
        "Accepted attribute-only edit/Undo must invalidate recovery even with unchanged IDs"
    }
    check(e.snapshot().committed == changed.committed && e.snapshot().undoCount == changed.undoCount)
    check(e.snapshot().notice.startsWith("RecoveryBaseChanged"))
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == draft.basis)
    check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.RecoveryBaseChanged)
}

private fun runRecoveryDraftChecks(run: RecoveryCheckRun, initial: Document) {
    run.test("explicit empty deletion draft remains meaningful and undoable") {
        val e = EditorEngine(initial)
        e.selectAll()
        // Public AWT empty events settle immediately. This tests an explicit recovery
        // payload containing a deletion, not a claim of native zero-length preedit.
        e.inputMethod("کX", 1, 1)
        e.inputMethodCaret(0)
        val draft = recoveryAbortAndCapture(e)
        val empty = draft.copy(document = Document(emptyList(), initial.startStyle), selection = Selection(0, 0))
        val before = e.snapshot()
        check(e.applyRecoveredDraft(empty) == RecoveryApplyResult.Applied)
        check(e.snapshot().visible.units.isEmpty())
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == before.committed)
        e.perform(EditAction.Redo)
        check(e.snapshot().committed.units.isEmpty())
    }
    run.test("active composition and reentrant callbacks cannot acknowledge deferred success") {
        val e = EditorEngine(initial)
        e.selectAll()
        e.inputMethod("قلم", 0, 3)
        val draft = recoveryAbortAndCapture(e)
        e.inputMethod("ا", 0, 1)
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.RecoveryBusy)
        e.abortComposition()
        var receipt: RecoveryApplyResult? = null
        e.onChange = { receipt = e.applyRecoveredDraft(draft) }
        e.move(Selection(0, 0))
        check(receipt == RecoveryApplyResult.RecoveryBusy)
    }
    run.test("confirmed recovery remains Applied when an observer queues a later independent edit") {
        val e = EditorEngine(initial)
        e.move(Selection(e.snapshot().visible.units.size, e.snapshot().visible.units.size))
        e.inputMethod("قلم", 0, 3)
        val draft = recoveryAbortAndCapture(e)
        e.onChange = { if (it.notice == "Recovered draft applied") e.replace("x", "ObserverEdit") }
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)
        check(e.snapshot().committed.text == draft.document.text + "x")
        e.onChange = {}
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == draft.document)
        e.perform(EditAction.Undo)
        check(e.snapshot().committed == draft.basis)
    }
}

private fun runRecoveryOwnerChecks(run: RecoveryCheckRun, initial: Document) {
    run.test("independent owners with coincident local bases cannot consume each other's draft") {
        val origin = EditorEngine()
        origin.inputMethod("کتاب", 0, 4)
        val draft = recoveryAbortAndCapture(origin)
        val foreign = EditorEngine()
        foreign.inputMethod("قلم", 0, 3)
        foreign.abortComposition()
        val before = foreign.snapshot()
        check(
            before.committed == draft.basis &&
                before.recoveryBasisRevision == draft.basisRevision &&
                before.identityGeneration == draft.basisIdentityGeneration,
        )
        val result = foreign.applyRecoveredDraft(draft)
        check(result == RecoveryApplyResult.RecoveryBaseChanged) {
            "Independent owner accepted a foreign recovery capsule: $result; " +
                "text=${foreign.snapshot().committed.text}"
        }
        check(foreign.snapshot().committed == before.committed && foreign.snapshot().undoCount == before.undoCount)
        val localBefore = origin.snapshot()
        check(origin.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)
        check(
            origin.snapshot().committed == draft.document &&
                origin.snapshot().undoCount == localBefore.undoCount + 1,
        )
        origin.perform(EditAction.Undo)
        check(origin.snapshot().committed == draft.basis)
        origin.perform(EditAction.Redo)
        check(origin.snapshot().committed == draft.document)
    }
    run.test("a canceled candidate does not invalidate an unchanged committed recovery basis") {
        val e = EditorEngine(initial)
        e.selectAll()
        e.inputMethod("پہلا", 0, 4)
        val draft = recoveryAbortAndCapture(e)
        e.inputMethod("دوسرا", 0, 5)
        e.abortComposition()
        check(
            e.snapshot().committed == draft.basis &&
                e.snapshot().identityGeneration == draft.basisIdentityGeneration,
        )
        check(e.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied) {
            "A discarded candidate invalidated an unchanged committed recovery basis"
        }
        check(e.snapshot().committed == draft.document)
    }
}
