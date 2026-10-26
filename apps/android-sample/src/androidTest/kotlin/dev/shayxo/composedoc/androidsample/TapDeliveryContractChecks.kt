package dev.shayxo.composedoc.androidsample

internal object TapDeliveryContractChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        val down = TapArrival(0, 1010, 1000, 1000, 0)
        val up = TapArrival(1, 1045, 1000, 1040, 0)
        shortTap(listOf(down, up), 0, 400)
        val invalid = listOf(
            emptyList(), listOf(down), listOf(up, down), listOf(down, up, up),
            listOf(down.copy(delivered = 1520), up.copy(delivered = 1540)),
            listOf(down, up.copy(delivered = 1400)), listOf(down, up.copy(down = 1001)),
            listOf(down, up.copy(event = 999)), listOf(down.copy(delivered = 999), up),
            listOf(down, up.copy(display = 1)),
            listOf(down, up.copy(targetMatches = false)),
            listOf(down.copy(targetMatches = false), up),
        )
        for (events in invalid) check(runCatching { shortTap(events, 0, 400) }.isFailure)
        check(tapCommand(122.5f, 741.3125f, 0).startsWith("timeout 5 input touchscreen -d 0 tap 122.5 741.3125;"))
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            check(runCatching { tapCommand(x, 1f, 0) }.isFailure)
            check(runCatching { tapCommand(1f, x, 0) }.isFailure)
        }
        check(runCatching { tapCommand(1f, 1f, -1) }.isFailure)
        tapExit("\nCOMPOSEDOC_TAP_EXIT=0\n")
        for (text in listOf("", "\nCOMPOSEDOC_TAP_EXIT=124\n", "Error\nCOMPOSEDOC_TAP_EXIT=0\n")) {
            check(runCatching { tapExit(text) }.isFailure)
        }
        println(
            "TapDeliveryContract: short gesture PASS; " +
                "12 invalid traces, 7 invalid coordinates, 3 failed receipts rejected",
        )
    }
}
