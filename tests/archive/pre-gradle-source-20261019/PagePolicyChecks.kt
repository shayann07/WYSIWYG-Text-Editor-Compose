package editor

/** Pure planner regressions with independently stated ten-unit line/page placements. */
internal fun runPagePolicyChecks():List<String> {
    // Exact synthetic source boxes isolate paragraph policy from platform font metrics.
    // The mounted specimen tests separately validate actual TextLayoutResult adaptation.
    fun paragraph(index:Int,n:Int)=PageParagraphMetrics(index,List(n) { it*10f },n*10f)
    fun plan(counts:List<Int>,properties:List<ParagraphProperties>,explicit:Set<Int> = emptySet(),capacity:Int=60)=
        planPageFragments(counts.mapIndexed { index,n->paragraph(index,n) },capacity+20f,10f,10f,properties,explicitBefore=explicit)
    fun placement(plan:FragmentPlan)=plan.fragments.map { Triple(it.paragraphIndex,it.firstLine..it.lastLine,it.page) }
    val ordinary=ParagraphProperties();val heading=ordinary.copy(pagination=PaginationRules(keepWithNext=true))
    val checks=ArrayList<String>()

    // Two lines remain after the first three-line paragraph. Splitting its three-line
    // successor would leave a one-line final fragment, so it moves as a whole.
    val two=plan(listOf(3,3),listOf(ordinary,ordinary),capacity=50)
    check(placement(two)==listOf(Triple(0,0..2,0),Triple(1,0..2,1)))
    check(two.pages==2 && two.relaxations.isEmpty())
    checks+="three-line successor moves rather than leaving one widow line"

    // Six lines exceed a five-line page. A five/one split is forbidden; four/two is
    // feasible, while keep-lines is explicitly relaxed rather than silently shrinking.
    val widow=plan(listOf(6),listOf(ordinary.copy(pagination=PaginationRules(keepLines=true))),capacity=50)
    check(placement(widow)==listOf(Triple(0,0..3,0),Triple(0,4..5,1)))
    check(widow.pages==2 && widow.relaxations.any { "keep-lines relaxed" in it })
    checks+="oversized six-line paragraph reserves two widow lines"

    // Thirty units already occupy page one. Heading + indivisible next paragraph need fifty:
    // both fit page two, but cannot fit in the thirty remaining units on page one.
    val kept=plan(listOf(3,1,4),listOf(ordinary,heading,ordinary.copy(pagination=PaginationRules(keepLines=true))))
    check(placement(kept)==listOf(Triple(0,0..2,0),Triple(1,0..0,1),Triple(2,0..3,1)))
    check(kept.relaxations.isEmpty())
    checks+="keep-with-next budgets the following keep-lines paragraph"

    // Four lines cannot split into at least two initial and three final lines. The full
    // paragraph therefore belongs beside its preceding heading on the next page.
    val minima=plan(listOf(3,1,4),listOf(ordinary,heading,ordinary.copy(pagination=PaginationRules(widows=3,orphans=2))))
    check(placement(minima)==listOf(Triple(0,0..2,0),Triple(1,0..0,1),Triple(2,0..3,1)))
    check(minima.relaxations.isEmpty())
    checks+="keep-with-next budgets a paragraph shorter than both split minima"

    // A six-line successor can legally put its first two lines beside the heading.
    // Keeping the whole successor would be unnecessarily conservative and waste a page.
    val split=plan(listOf(3,1,6),listOf(ordinary,heading,ordinary))
    check(placement(split)==listOf(Triple(0,0..2,0),Triple(1,0..0,0),Triple(2,0..1,0),Triple(2,2..5,1)))
    check(split.pages==2 && split.relaxations.isEmpty())
    checks+="keep-with-next retains a legal successor fragment instead of overconstraining it"

    val explicit=plan(listOf(3,1,4),listOf(ordinary,heading,ordinary),setOf(2))
    check(placement(explicit)==listOf(Triple(0,0..2,0),Triple(1,0..0,0),Triple(2,0..3,1)))
    checks+="explicit break overrides a preceding keep-with-next request"

    val oversized=plan(listOf(14),listOf(ordinary.copy(pagination=PaginationRules(keepLines=true))),capacity=50)
    check(placement(oversized)==listOf(Triple(0,0..4,0),Triple(0,5..9,1),Triple(0,10..13,2)))
    check(oversized.fragments.flatMap { (it.firstLine..it.lastLine).toList() }==(0..13).toList())
    check(oversized.pages==3 && oversized.relaxations.any { "keep-lines relaxed" in it })
    checks+="oversized keep-lines paragraph makes bounded forward progress without missing lines"
    return checks
}
