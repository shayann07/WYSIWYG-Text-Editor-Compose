package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
private fun EditorFrame.canonicalBasis() = CanonicalProbeBasis(documentRevision, committed.units.map { it.id }, identityGeneration)

/** Internal experiment boundary; not a production replication API or collaborative algorithm. */
fun runCanonicalProbeChecks() {
    val engine = EditorEngine()
    engine.replace("AB", "Fixture")
    val localHistory = engine.snapshot().undoCount
    val requested = ArrayList<Boolean>()
    check(engine.applyCanonicalProbe(engine.snapshot().canonicalBasis(), emptyList(), Selection(0, 0)) == CanonicalProbeOutcome.RequiresExternalHistory)
    engine.installCanonicalProbeHistory { requested += it }
    var publications = 0
    engine.onChange = { publications++ }
    val basis = engine.snapshot().canonicalBasis()
    check(
        engine.applyCanonicalProbe(
            basis,
            listOf(
                CanonicalProbeEdit.Replace(1, 1, "X", TextStyleFlags(bold = true)),
                CanonicalProbeEdit.Attributes(0, 1, TextStyleFlags(italic = true)),
            ),
            Selection(2, 2),
        ) == CanonicalProbeOutcome.Applied,
    )
    check(publications == 1 && engine.snapshot().committed.text == "AXB")
    check(engine.snapshot().undoCount == localHistory) { "Remote acceptance must not clear or add local snapshot history" }
    val accepted = engine.snapshot()
    check(engine.applyCanonicalProbe(basis, emptyList(), Selection(0, 0)) == CanonicalProbeOutcome.RejectedBasis)
    check(engine.snapshot() == accepted)
    engine.perform(EditAction.Undo)
    check(requested == listOf(false) && engine.snapshot().committed == accepted.committed)
    engine.perform(EditAction.Redo)
    check(requested == listOf(false, true) && engine.snapshot().committed == accepted.committed)
    val beforeFailure = engine.snapshot()
    val frames = ArrayList<EditorFrame>()
    engine.onChange = { frames += it }
    check(
        engine.applyCanonicalProbe(
            beforeFailure.canonicalBasis(),
            listOf(
                CanonicalProbeEdit.Replace(0, 1, "must roll back", TextStyleFlags()),
                CanonicalProbeEdit.Attributes(999, 1000, TextStyleFlags(bold = true)),
            ),
            Selection(0, 0),
        ) == CanonicalProbeOutcome.Rejected,
    )
    check(engine.snapshot().committed == beforeFailure.committed && engine.snapshot().selection == beforeFailure.selection)
    check(frames.all { it.committed == beforeFailure.committed }) { "A partial canonical transaction was published" }
    engine.move(Selection(1, 1))
    engine.inputMethod("候補", 0, 2)
    val composing = engine.snapshot()
    check(engine.applyCanonicalProbe(composing.canonicalBasis(), emptyList(), Selection(0, 0)) == CanonicalProbeOutcome.DeferredComposition)
    check(engine.snapshot() == composing)
    val historyRequests = requested.size
    engine.perform(EditAction.Undo)
    check(requested.size == historyRequests)
    engine.inputMethod("日本", 2, null)
    check(requested.size == historyRequests + 1 && engine.snapshot().committed.text == "A日本XB")
    val raw = EditorEngine()
    raw.replace("ک\u0650\n", "Fixture", paragraphBreaks = true)
    raw.installCanonicalProbeHistory {}
    check(
        raw.applyCanonicalProbe(
            raw.snapshot().canonicalBasis(),
            listOf(
                CanonicalProbeEdit.Attributes(1, 2, TextStyleFlags(bold = true)),
                CanonicalProbeEdit.Attributes(2, 3, TextStyleFlags(italic = true)),
            ),
            Selection(0, 0),
        ) == CanonicalProbeOutcome.Applied,
    )
    check(raw.snapshot().committed.units[0].style == TextStyleFlags())
    check(raw.snapshot().committed.units[1].style.bold)
    check(raw.snapshot().committed.units[2].style == TextStyleFlags() && raw.snapshot().committed.units[2].paragraphStyle.italic)
    check(DocumentFiles.decode(DocumentFiles.encode(raw.snapshot().committed)).same(raw.snapshot().committed))
    val observed = EditorEngine()
    observed.replace("A", "Fixture")
    observed.installCanonicalProbeHistory { throw IllegalStateException("Synthetic history transport failure") }
    observed.onChange = { if (it.notice == "Canonical probe transaction applied") observed.perform(EditAction.Undo) }
    check(
        observed.applyCanonicalProbe(
            observed.snapshot().canonicalBasis(),
            listOf(CanonicalProbeEdit.Replace(1, 1, "B", TextStyleFlags())),
            Selection(2, 2),
        ) == CanonicalProbeOutcome.Applied,
    )
    check(observed.snapshot().committed.text == "AB" && observed.snapshot().notice.startsWith("Edit rejected"))
    val identities = EditorEngine()
    identities.replace("کک", "IdentityFixture")
    identities.installCanonicalProbeHistory {}
    val identityBasis = identities.snapshot()
    check(
        identities.applyCanonicalProbe(
            identityBasis.canonicalBasis(),
            listOf(CanonicalProbeEdit.Replace(0, 1, "ک", TextStyleFlags())),
            Selection(0, 0),
        ) == CanonicalProbeOutcome.Applied,
    )
    check(identities.snapshot().documentRevision == identityBasis.documentRevision)
    check(identities.snapshot().committed.units.map { it.id } != identityBasis.committed.units.map { it.id })
    val identityReplaced = identities.snapshot()
    check(
        identities.applyCanonicalProbe(
            identityBasis.canonicalBasis(),
            listOf(CanonicalProbeEdit.Replace(0, 1, "X", TextStyleFlags())),
            Selection(0, 0),
        ) == CanonicalProbeOutcome.RejectedBasis,
    ) {
        "Same-text identity replacement admitted stale canonical work against a different unit"
    }
    check(identities.snapshot() == identityReplaced)
    identities.move(Selection(2, 2)) // Caret-only changes must not invalidate explicit identity targets.
    check(
        identities.applyCanonicalProbe(
            identityReplaced.canonicalBasis(),
            listOf(CanonicalProbeEdit.Replace(0, 1, "X", TextStyleFlags())),
            Selection(1, 1),
        ) == CanonicalProbeOutcome.Applied,
    )
    check(identities.snapshot().committed.text == "Xک" && identities.snapshot().committed.units[1] == identityReplaced.committed.units[1])
    val moved = EditorEngine()
    moved.replace("کک", "MoveFixture")
    moved.installCanonicalProbeHistory {}
    val moveBasis = moved.snapshot()
    check(moved.applyCanonicalProbe(moveBasis.canonicalBasis(), listOf(CanonicalProbeEdit.Move(0, 1, 1)), Selection(0, 0)) == CanonicalProbeOutcome.Applied)
    check(moved.applyCanonicalProbe(moved.snapshot().canonicalBasis(), listOf(CanonicalProbeEdit.Move(1, 2, 0)), Selection(0, 0)) == CanonicalProbeOutcome.Applied)
    check(moved.snapshot().committed == moveBasis.committed && moved.snapshot().documentRevision == moveBasis.documentRevision)
    check(moved.applyCanonicalProbe(moveBasis.canonicalBasis(), listOf(CanonicalProbeEdit.Replace(0, 1, "X", TextStyleFlags())), Selection(0, 0)) == CanonicalProbeOutcome.RejectedBasis) {
        "Identity-only move and inverse admitted an ABA stale canonical basis"
    }
    val rawBeforeMove = raw.snapshot()
    var movePublications = 0
    raw.onChange = { movePublications++ }
    check(raw.applyCanonicalProbe(rawBeforeMove.canonicalBasis(), listOf(CanonicalProbeEdit.Move(0, 2, 1)), Selection(2, 2)) == CanonicalProbeOutcome.Applied)
    check(raw.snapshot().committed.units == listOf(rawBeforeMove.committed.units[2], rawBeforeMove.committed.units[0], rawBeforeMove.committed.units[1]))
    check(movePublications == 1 && raw.snapshot().undoCount == rawBeforeMove.undoCount)
    val beforeMoveFailure = raw.snapshot()
    val rejectedMoves = ArrayList<EditorFrame>()
    raw.onChange = { rejectedMoves += it }
    check(raw.applyCanonicalProbe(beforeMoveFailure.canonicalBasis(), listOf(CanonicalProbeEdit.Move(1, 3, 0), CanonicalProbeEdit.Move(0, 1, 999)), Selection(0, 0)) == CanonicalProbeOutcome.Rejected)
    check(raw.snapshot().committed == beforeMoveFailure.committed && raw.snapshot().selection == beforeMoveFailure.selection && raw.snapshot().identityGeneration == beforeMoveFailure.identityGeneration)
    check(rejectedMoves.all { it.committed == beforeMoveFailure.committed })
    println("PASS canonical probe boundary: atomic raw edits, native deferral and external history routing; production C0 OPEN")
}
