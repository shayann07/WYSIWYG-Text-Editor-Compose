package dev.shayxo.composedoc.androidsample

/** Test-only bounded recorder. Capture failures never replace the operation's original failure. */
internal class KeyboardTraceRecorder(
    private val capture: (String) -> String,
    private val write: (String) -> Unit,
    private val maxRecords: Int = 12,
    private val maxBytes: Int = 65536,
) {
    private val records = mutableListOf<String>()
    private var bytes = 2
    private var diagnosticFailure: Throwable? = null

    fun record(stage: String) {
        attempt {
            check(records.size < maxRecords) { "Keyboard trace record limit" }
            val value = capture(stage)
            val next = bytes + value.toByteArray(Charsets.UTF_8).size + 1
            check(next <= maxBytes) { "Keyboard trace byte limit" }
            records.add(value)
            bytes = next
        }
    }

    fun <T> around(block: () -> T): T {
        val outcome = runCatching(block).onFailure { record("failure") }
        record("finally")
        attempt { write(records.joinToString(",", "[", "]")) }
        diagnosticFailure?.let { error ->
            val original = outcome.exceptionOrNull()
            if (original == null) {
                throw error
            } else if (original !== error) {
                original.addSuppressed(error)
            }
        }
        return outcome.getOrThrow()
    }

    private fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            val first = diagnosticFailure
            if (first == null) {
                diagnosticFailure = failure
            } else if (first !== failure && first.suppressed.size < 3) {
                first.addSuppressed(failure)
            }
        }
    }
}

/** Distinguish references even if equals/hashCode agree; do not retain an unbounded object graph. */
internal class KeyboardTraceIdentities {
    private val values = java.util.IdentityHashMap<Any, Int>()
    fun of(value: Any?): Int? {
        if (value == null) return null
        return values[value] ?: run {
            check(values.size < 64) { "Keyboard trace identity limit" }
            (values.size + 1).also { values[value] = it }
        }
    }
}
