package editor

internal data class FindMatch(val start: Int, val end: Int, val editable: Boolean)
internal data class FindBasis(val document: Document, val revision: Long, internal val owner: Any)
internal enum class FindReplaceResult { Applied, NoChange, Busy, Stale, Invalid, TooLarge }

/** Literal UTF-16 equality. Hard paragraph separators are scope boundaries, never flattened. */
internal fun findLiteral(document: Document, query: String): List<FindMatch> {
    if (query.isEmpty()) return emptyList()
    val text = document.text
    val stops = Graphemes.stops(text).toHashSet()
    val matches = ArrayList<FindMatch>()
    var start = 0
    fun paragraph(end: Int) {
        val segment = text.substring(start, end)
        var at = segment.indexOf(query)
        while (at >= 0) {
            val a = start + at; val b = a + query.length
            matches += FindMatch(a, b, a in stops && b in stops)
            at = segment.indexOf(query, at + query.length)
        }
    }
    document.units.forEachIndexed { index, unit -> if (unit.kind != UnitKind.TEXT) { paragraph(index); start = index + 1 } }
    paragraph(text.length)
    return matches
}

internal class FindSession(private val surface: () -> EditorSurface) {
    var query = ""; private set
    var basis: FindBasis? = null; private set
    var matches: List<FindMatch> = emptyList(); private set
    var active = -1; private set
    var blocked = false; private set
    val current get() = matches.getOrNull(active)
    fun admitted(): Boolean {
        val s = surface(); val f = s.engine.snapshot()
        return !s.disposed && !s.retired && s.admissionAllowed && f.marked == null && !f.waiting
    }
    fun refresh() {
        val engine = surface().engine; val f = engine.snapshot()
        val wasBlocked = blocked; blocked = !admitted()
        if (blocked) { basis = null; matches = emptyList(); active = -1; return }
        if (!wasBlocked && basis?.document === f.committed && basis?.revision == f.recoveryBasisRevision) return
        val oldStart = current?.start ?: f.selection.start
        basis = engine.captureFindBasis(); matches = findLiteral(f.committed, query)
        active = if (matches.isEmpty()) -1 else matches.indexOfFirst { it.start >= oldStart }.let { if (it < 0) 0 else it }
    }
    fun search(value: String) { query = value; basis = null; refresh(); reveal() }
    fun navigate(backwards: Boolean) {
        refresh(); if (blocked || matches.isEmpty()) return
        active = Math.floorMod(active + if (backwards) -1 else 1, matches.size); reveal()
    }
    fun reveal() {
        val s = surface(); val match = current ?: return
        if (!admitted()) return
        if (match.editable) s.engine.move(Selection(match.start, match.end))
        s.view.findReveal = Selection(match.start, match.end); s.repaint()
    }
    fun replace(value: String, all: Boolean): FindReplaceResult {
        refresh(); if (blocked) return FindReplaceResult.Busy
        val captured = basis ?: return FindReplaceResult.Invalid
        val targets = if (all) matches else listOfNotNull(current)
        if (targets.isEmpty() || targets.any { !it.editable }) return FindReplaceResult.Invalid
        val result = surface().engine.replaceFound(captured, targets, value, all)
        basis = null; refresh(); reveal(); return result
    }
}
