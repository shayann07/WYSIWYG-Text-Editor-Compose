package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.FindBasis
import dev.shayxo.composedoc.domain.FindMatch
import dev.shayxo.composedoc.domain.FindReplaceResult
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Selection

class FindSession(private val surface: () -> EditorSurface) {
    var query = ""
        private set
    var basis: FindBasis? = null
        private set
    var matches: List<FindMatch> = emptyList()
        private set
    var active = -1
        private set
    var blocked = false
        private set
    val current get() = matches.getOrNull(active)
    fun admitted(): Boolean {
        val s = surface()
        val f = s.viewModel.snapshot()
        return !s.disposed && !s.retired && s.admissionAllowed && f.marked == null && !f.waiting
    }
    fun refresh() {
        val viewModel = surface().viewModel
        val f = viewModel.snapshot()
        val wasBlocked = blocked
        blocked = !admitted()
        if (blocked) {
            basis = null
            matches = emptyList()
            active = -1
            return
        }
        if (!wasBlocked && basis?.document === f.committed && basis?.revision == f.recoveryBasisRevision) return
        val oldStart = current?.start ?: f.selection.start
        basis = viewModel.captureFindBasis()
        matches = findLiteral(f.committed, query)
        active =
            if (matches.isEmpty()) -1 else matches.indexOfFirst { it.start >= oldStart }.let { if (it < 0) 0 else it }
    }
    fun search(value: String) {
        query = value
        basis = null
        refresh()
        reveal()
    }
    fun navigate(backwards: Boolean) {
        refresh()
        if (blocked || matches.isEmpty()) return
        active = Math.floorMod(active + if (backwards) -1 else 1, matches.size)
        reveal()
    }
    fun reveal() {
        val s = surface()
        val match = current ?: return
        if (!admitted()) return
        if (match.editable) s.viewModel.dispatch(EditorIntent.Select(Selection(match.start, match.end)))
        s.view.findReveal = Selection(match.start, match.end)
        s.repaint()
    }
    fun replace(value: String, all: Boolean): FindReplaceResult {
        refresh()
        val captured = basis
        val targets = if (all) matches else listOfNotNull(current)
        return when {
            blocked -> FindReplaceResult.Busy
            captured == null || targets.isEmpty() || targets.any { !it.editable } -> FindReplaceResult.Invalid
            else -> {
                val result = surface().viewModel.dispatch(EditorIntent.ReplaceFound(captured, targets, value, all))
                basis = null
                refresh()
                reveal()
                result
            }
        }
    }
}
