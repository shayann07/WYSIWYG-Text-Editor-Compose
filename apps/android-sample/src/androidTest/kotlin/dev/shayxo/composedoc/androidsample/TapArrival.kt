package dev.shayxo.composedoc.androidsample

internal data class TapArrival(
    val action: Int,
    val delivered: Long,
    val down: Long,
    val event: Long,
    val display: Int,
    val targetMatches: Boolean = true,
)

internal fun shortTap(arrivals: List<TapArrival>, display: Int, threshold: Int) {
    check(arrivals.size == 2) { "Expected one actual window DOWN/UP pair: $arrivals" }
    val (down, up) = arrivals
    check(down.action == 0 && up.action == 1 && down.display == display && up.display == display)
    check(down.targetMatches && up.targetMatches) { "Captured target drifted at actual delivery: $arrivals" }
    check(down.down == up.down && down.event == down.down && up.event >= down.event)
    check(down.delivered >= down.down && up.delivered >= down.delivered && up.delivered >= up.event)
    check(up.delivered - down.down < threshold) { "Stale or long delivered gesture: $arrivals" }
}

internal fun tapCommand(x: Float, y: Float, display: Int, fixture: String): String {
    require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < 1000000 && y < 1000000 && display >= 0)
    require(fixture.matches(Regex("/data/local/tmp/composedoc-tap-v1-adapter-[0-9]{1,24}/tap[.]sh")))
    return "/system/bin/sh $fixture $x $y $display"
}

internal fun tapExit(output: String) {
    check(output == "\nCOMPOSEDOC_TAP_EXIT=0\n") { "System tap failed or returned unexpected output: $output" }
}
