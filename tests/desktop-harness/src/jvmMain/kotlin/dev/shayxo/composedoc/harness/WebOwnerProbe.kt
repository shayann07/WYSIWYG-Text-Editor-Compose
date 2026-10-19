package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.domain.CanonicalProbeBasis
import dev.shayxo.composedoc.domain.CanonicalProbeEdit
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.SimpleJson
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.displayedStyles
import dev.shayxo.composedoc.model.evidenceJson
import java.io.BufferedWriter

/** Local JSON-line owner for Web/C0 feasibility spikes. No network or second history. */
fun runWebOwnerProbe() {
    val engine = EditorEngine()
    // This private process endpoint, not a durable document/actor ID.
    val ownerIncarnation = java.util.UUID.randomUUID().toString()
    val output = System.out.writer(Charsets.UTF_8).buffered()
    val session = WebOwnerSession(engine, ownerIncarnation, output)
    session.emit(mapOf("status" to "READY", "frame" to session.frame()))
    val input = System.`in`.bufferedReader(Charsets.UTF_8)
    for (line in input.lineSequence()) {
        if (!session.handleLine(line)) return
    }
}

private fun webUnitRow(unit: TextUnit) =
    listOf(unit.id, unit.char.code, unit.kind.ordinal, unit.style.bits, unit.paragraphStyle.bits)

private fun webNumber(request: Map<*, *>, name: String) = (request[name] as Number).toInt()

private class WebOwnerSession(
    private val engine: EditorEngine,
    private val ownerIncarnation: String,
    private val output: BufferedWriter,
) {
    private val historyRequests = ArrayList<Boolean>()
    private var externalHistory = false

    fun frame(): Map<String, Any?> {
        val f = engine.snapshot()
        return mapOf(
            "text" to f.visible.text, "committed" to f.committed.text, "ownerIncarnation" to ownerIncarnation,
            "committedUnitIds" to f.committed.units.map { it.id.toString() },
            "identityGeneration" to f.identityGeneration.toString(),
            "selection" to listOf(f.selection.anchor, f.selection.focus),
            "marked" to f.marked?.let { listOf(it.first, it.last + 1) },
            "revision" to f.documentRevision, "sessionRevision" to f.sessionRevision, "epoch" to engine.imeEpoch,
            "undo" to f.undoCount, "redo" to f.redoCount, "waiting" to f.waiting, "notice" to f.notice,
            "historyMode" to if (externalHistory) "external-probe" else "local",
            "units" to f.visible.units.map(::webUnitRow),
            "committedUnits" to f.committed.units.map(::webUnitRow),
            "styles" to displayedStyles(f.visible).map { listOf(it.start, it.end, it.style.bits) },
        )
    }

    fun emit(value: Any?) {
        output.write(evidenceJson(value))
        output.write("\n")
        output.flush()
    }

    /** Handles one request line; returns false after a shutdown request. */
    fun handleLine(line: String): Boolean {
        var requestId: Any? = null
        return try {
            val request = SimpleJson(line).parse() as Map<*, *>
            requestId = request["id"]
            handleRequest(request, requestId)
        } catch (error: Throwable) {
            emit(
                mapOf(
                    "id" to requestId,
                    "status" to "Rejected",
                    "error" to (error.message ?: error.javaClass.simpleName),
                    "frame" to frame(),
                ),
            )
            true
        }
    }

    private fun handleRequest(request: Map<*, *>, requestId: Any?): Boolean {
        val command = request["command"] as String
        val before = engine.snapshot()
        val epoch = (request["epoch"] as? Number)?.toLong()
        if (command == "shutdown") {
            emit(mapOf("id" to requestId, "status" to "BYE"))
            return false
        }
        val rejection = rejectionStatus(request, command, epoch, before)
        if (rejection != null) {
            emit(mapOf("id" to requestId, "status" to rejection, "frame" to frame()))
        } else {
            val outcome = applyCommand(request, command, before)
            emit(
                mapOf(
                    "id" to requestId,
                    "status" to (outcome ?: if (engine.snapshot().waiting) "Deferred" else "Applied"),
                    "frame" to frame(),
                    "historyRequests" to historyRequests.toList(),
                ),
            )
            historyRequests.clear()
        }
        return true
    }

    private fun rejectionStatus(request: Map<*, *>, command: String, epoch: Long?, before: EditorFrame): String? =
        when {
            command == "canonical-batch" && request["ownerIncarnation"] != ownerIncarnation -> {
                "RejectedOwnerIncarnation"
            }

            epoch != null && epoch != engine.imeEpoch -> {
                "RejectedStaleEpoch"
            }

            (request["beforeText"] as? String).let { basis -> basis != null && basis != before.visible.text } -> {
                "RejectedBasis"
            }

            else -> {
                null
            }
        }

    private fun applyCommand(request: Map<*, *>, command: String, before: EditorFrame): String? = when (command) {
        "external-history" -> {
            engine.installCanonicalProbeHistory { historyRequests += it }
            externalHistory = true
            "Configured"
        }

        "canonical-batch" -> {
            applyCanonicalBatch(request)
        }

        else -> {
            applyEditCommand(request, command, before)
            null
        }
    }

    private fun applyEditCommand(request: Map<*, *>, command: String, before: EditorFrame) {
        when (command) {
            "snapshot" -> {}
            "reset" -> engine.replaceExact(
                request["text"] as String,
                "WebFixture",
                Selection(0, before.visible.units.size),
            )
            "replace" -> {
                engine.replaceExact(
                    request["text"] as String,
                    "WebInput",
                    Selection(webNumber(request, "start"), webNumber(request, "end")),
                    request["paragraphBreaks"] == true,
                )
            }
            "select" -> engine.move(Selection(webNumber(request, "anchor"), webNumber(request, "focus")))
            "format" -> {
                engine.perform(
                    EditAction.FormatAttributes(
                        engine.capture(Selection(webNumber(request, "start"), webNumber(request, "end"))),
                        request["bold"] as? Boolean,
                        request["italic"] as? Boolean,
                    ),
                )
            }
            "undo" -> engine.perform(EditAction.Undo)
            "redo" -> engine.perform(EditAction.Redo)
            "ime" -> {
                engine.inputMethod(
                    request["text"] as String,
                    webNumber(request, "committed"),
                    (request["caret"] as? Number)?.toInt(),
                )
            }
            "ime-caret" -> engine.inputMethodCaret(webNumber(request, "caret"))
            "abort-composition" -> engine.abortComposition()
            else -> error("Unsupported probe command")
        }
    }

    private fun applyCanonicalBatch(request: Map<*, *>): String {
        val ids =
            (request["basisUnitIds"] as? List<*>)?.map { raw ->
                require(raw is String) { "Canonical identity basis requires decimal strings" }
                raw.toLong().also { require(it.toString() == raw) { "Noncanonical identity string" } }
            } ?: error("Canonical identity basis required")
        val edits = (request["edits"] as List<*>).map(::canonicalProbeEdit)
        val selection = request["selection"] as List<*>
        val generation =
            (request["basisIdentityGeneration"] as? String)?.let { raw ->
                raw.toLong().also {
                    require(it >= 0 && it.toString() == raw) { "Invalid canonical identity generation" }
                }
            } ?: error("Canonical identity generation required")
        return engine
            .applyCanonicalProbe(
                CanonicalProbeBasis((request["basisRevision"] as Number).toLong(), ids, generation),
                edits,
                Selection((selection[0] as Number).toInt(), (selection[1] as Number).toInt()),
            ).name
    }

    private fun canonicalProbeEdit(raw: Any?): CanonicalProbeEdit {
        val edit = raw as Map<*, *>
        val start = (edit["start"] as Number).toInt()
        val end = (edit["end"] as Number).toInt()
        return when (edit["kind"]) {
            "replace" -> {
                CanonicalProbeEdit.Replace(
                    start,
                    end,
                    edit["text"] as String,
                    TextStyleFlags.fromBits((edit["style"] as Number).toInt()),
                    edit["paragraphBreaks"] == true,
                )
            }

            "attributes" -> {
                CanonicalProbeEdit.Attributes(start, end, TextStyleFlags.fromBits((edit["style"] as Number).toInt()))
            }

            "move" -> {
                CanonicalProbeEdit.Move(start, end, (edit["destination"] as Number).toInt())
            }

            else -> {
                error("Invalid canonical probe edit")
            }
        }
    }
}
