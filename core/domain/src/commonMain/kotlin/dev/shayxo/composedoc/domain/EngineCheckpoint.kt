package dev.shayxo.composedoc.domain

/** Owner state captured before a dispatched transaction so that a rejected edit restores it exactly. */
internal class EngineCheckpoint(engine: EditorEngine) {
    val dc = engine.dc
    private val selection = engine.selection
    private val intent = engine.intent
    private val side = engine.side
    private val composition = engine.composition?.let { it.copy(synthesized = HashMap(it.synthesized)) }
    private val undo = engine.undo.map { it.copy() }
    private val redo = engine.redo.map { it.copy() }
    private val pending = engine.pending.toList()
    private val typingBarrier = engine.typingBarrier
    private val settlementRequestedAt = engine.settlementRequestedAt
    private val settlementTimedOut = engine.settlementTimedOut

    fun restore(engine: EditorEngine) {
        engine.dc = dc
        engine.selection = selection
        engine.composition = composition
        engine.intent = intent
        engine.side = side
        engine.undo.clear()
        engine.undo.addAll(undo)
        engine.redo.clear()
        engine.redo.addAll(redo)
        engine.pending.clear()
        engine.pending.addAll(pending)
        engine.typingBarrier = typingBarrier
        engine.settlementRequestedAt = settlementRequestedAt
        engine.settlementTimedOut = settlementTimedOut
    }
}
