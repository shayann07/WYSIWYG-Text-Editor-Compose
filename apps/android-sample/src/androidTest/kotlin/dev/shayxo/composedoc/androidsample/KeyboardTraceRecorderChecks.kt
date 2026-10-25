package dev.shayxo.composedoc.androidsample

/** Host-executable regression for the exact recorder used by the mounted Android witness. */
internal object KeyboardTraceRecorderChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        successfulStages()
        primaryFailureSurvivesDiagnostics()
        diagnosticFailureCannotPass()
        boundsRemainFailures()
        referenceIdentityAndImmutableSnapshots()
        println("KeyboardTraceRecorder: 5 host groups PASS (not Android qualification)")
    }

    private fun successfulStages() {
        val events = mutableListOf<String>()
        var written = ""
        val trace = KeyboardTraceRecorder({ stage ->
            events.add(stage)
            "\"$stage\""
        }, { written = it })
        val result = trace.around {
            trace.record("before-hide")
            trace.record("baseline")
            trace.record("after-show")
            42
        }
        check(result == 42 && events == listOf("before-hide", "baseline", "after-show", "finally"))
        check(written == "[\"before-hide\",\"baseline\",\"after-show\",\"finally\"]")
    }

    private fun primaryFailureSurvivesDiagnostics() {
        val primary = IllegalStateException("original visibility assertion")
        val capture = IllegalArgumentException("capture failed")
        val disk = java.io.IOException("disk failed")
        var writes = 0
        val trace = KeyboardTraceRecorder({ fail(capture) }, {
            writes++
            fail(disk)
        })
        val actual = runCatching { trace.around { fail(primary) } }.exceptionOrNull()
        check(actual === primary && writes == 1)
        check(primary.suppressed.single() === capture && capture.suppressed.single() === disk)
    }

    private fun diagnosticFailureCannotPass() {
        val disk = java.io.IOException("not silently PASS")
        val trace = KeyboardTraceRecorder({ "\"$it\"" }, { throw disk })
        check(runCatching { trace.around { true } }.exceptionOrNull() === disk)
        val capture = IllegalArgumentException("capture missing")
        var written = ""
        val bad = KeyboardTraceRecorder({ throw capture }, { written = it })
        check(runCatching { bad.around { true } }.exceptionOrNull() === capture)
        check(written == "[]")
    }

    private fun boundsRemainFailures() {
        var output = ""
        val recordBound = KeyboardTraceRecorder({ "\"$it\"" }, { output = it }, maxRecords = 2)
        check(
            runCatching {
                recordBound.around { repeat(10) { recordBound.record("$it") } }
            }.exceptionOrNull()?.message == "Keyboard trace record limit",
        )
        check(output == "[\"0\",\"1\"]")
        val byteBound = KeyboardTraceRecorder({ "\"اُردو\"" }, { output = it }, maxBytes = 4)
        check(runCatching { byteBound.around { true } }.exceptionOrNull()?.message == "Keyboard trace byte limit")
        check(output == "[]")
    }

    private fun referenceIdentityAndImmutableSnapshots() {
        data class EqualOwner(val value: Int)
        val identities = KeyboardTraceIdentities()
        val first = EqualOwner(1)
        val second = EqualOwner(1)
        check(first == second && first !== second)
        check(identities.of(first) == 1 && identities.of(first) == 1 && identities.of(second) == 2)
        check(identities.of(null) == null)
        var owner = first
        var written = ""
        val trace = KeyboardTraceRecorder({ "${identities.of(owner)}" }, { written = it })
        trace.around {
            trace.record("before")
            owner = second
            trace.record("after")
        }
        check(written == "[1,2,2]")
        repeat(62) { identities.of(Any()) }
        check(runCatching { identities.of(Any()) }.exceptionOrNull()?.message == "Keyboard trace identity limit")
    }

    private fun fail(error: Throwable): Nothing = throw error
}
