package editor

/** Local JSON-line owner for Web/C0 feasibility spikes. No network or second history. */
fun runWebOwnerProbe() {
    val engine = EditorEngine()
    val ownerIncarnation = java.util.UUID.randomUUID().toString() // This private process endpoint, not a durable document/actor ID.
    val output = System.out.writer(Charsets.UTF_8).buffered()
    val historyRequests = ArrayList<Boolean>()
    var externalHistory = false
    fun frame(): Map<String, Any?> {
        val f = engine.snapshot()
        return mapOf("text" to f.visible.text, "committed" to f.committed.text, "ownerIncarnation" to ownerIncarnation,
            "committedUnitIds" to f.committed.units.map { it.id.toString() },
            "identityGeneration" to f.identityGeneration.toString(),
            "selection" to listOf(f.selection.anchor, f.selection.focus),
            "marked" to f.marked?.let { listOf(it.first, it.last + 1) },
            "revision" to f.documentRevision, "sessionRevision" to f.sessionRevision, "epoch" to engine.imeEpoch,
            "undo" to f.undoCount, "redo" to f.redoCount, "waiting" to f.waiting, "notice" to f.notice,
            "historyMode" to if (externalHistory) "external-probe" else "local",
            "units" to f.visible.units.map { listOf(it.id, it.char.code, it.kind.ordinal, it.style.bits, it.paragraphStyle.bits) },
            "committedUnits" to f.committed.units.map { listOf(it.id, it.char.code, it.kind.ordinal, it.style.bits, it.paragraphStyle.bits) },
            "styles" to displayedStyles(f.visible).map { listOf(it.start, it.end, it.style.bits) })
    }
    fun emit(value: Any?) { output.write(evidenceJson(value)); output.write("\n"); output.flush() }
    emit(mapOf("status" to "READY", "frame" to frame()))
    val input = System.`in`.bufferedReader(Charsets.UTF_8)
    for (line in input.lineSequence()) {
        var requestId: Any? = null
        try {
            val request = SimpleJson(line).parse() as Map<*, *>
            requestId = request["id"]
            fun number(name: String) = (request[name] as Number).toInt()
            val command = request["command"] as String
            val before = engine.snapshot()
            var outcome: String? = null
            val epoch = (request["epoch"] as? Number)?.toLong()
            if (command == "shutdown") { emit(mapOf("id" to requestId, "status" to "BYE")); return }
            if (command == "canonical-batch" && request["ownerIncarnation"] != ownerIncarnation) {
                emit(mapOf("id" to requestId, "status" to "RejectedOwnerIncarnation", "frame" to frame())); continue
            }
            if (epoch != null && epoch != engine.imeEpoch) {
                emit(mapOf("id" to requestId, "status" to "RejectedStaleEpoch", "frame" to frame())); continue
            }
            val basis = request["beforeText"] as? String
            if (basis != null && basis != before.visible.text) {
                emit(mapOf("id" to requestId, "status" to "RejectedBasis", "frame" to frame())); continue
            }
            when (command) {
                "snapshot" -> {}
                "reset" -> engine.replaceExact(request["text"] as String, "WebFixture", Selection(0, before.visible.units.size))
                "replace" -> engine.replaceExact(request["text"] as String, "WebInput", Selection(number("start"), number("end")), request["paragraphBreaks"] == true)
                "select" -> engine.move(Selection(number("anchor"), number("focus")))
                "format" -> engine.perform(EditAction.FormatAttributes(engine.capture(Selection(number("start"), number("end"))), request["bold"] as? Boolean, request["italic"] as? Boolean))
                "undo" -> engine.perform(EditAction.Undo)
                "redo" -> engine.perform(EditAction.Redo)
                "ime" -> engine.inputMethod(request["text"] as String, number("committed"), (request["caret"] as? Number)?.toInt())
                "ime-caret" -> engine.inputMethodCaret(number("caret"))
                "abort-composition" -> engine.abortComposition()
                "external-history" -> {
                    engine.installCanonicalProbeHistory { historyRequests += it }
                    externalHistory = true
                    outcome = "Configured"
                }
                "canonical-batch" -> {
                    val ids = (request["basisUnitIds"] as? List<*>)?.map { raw ->
                        require(raw is String) { "Canonical identity basis requires decimal strings" }
                        raw.toLong().also { require(it.toString() == raw) { "Noncanonical identity string" } }
                    } ?: error("Canonical identity basis required")
                    val edits = (request["edits"] as List<*>).map { raw ->
                        val edit = raw as Map<*, *>
                        val start = (edit["start"] as Number).toInt(); val end = (edit["end"] as Number).toInt()
                        when (edit["kind"]) {
                            "replace" -> CanonicalProbeEdit.Replace(start, end, edit["text"] as String, TextStyleFlags.fromBits((edit["style"] as Number).toInt()), edit["paragraphBreaks"] == true)
                            "attributes" -> CanonicalProbeEdit.Attributes(start, end, TextStyleFlags.fromBits((edit["style"] as Number).toInt()))
                            "move" -> CanonicalProbeEdit.Move(start, end, (edit["destination"] as Number).toInt())
                            else -> error("Invalid canonical probe edit")
                        }
                    }
                    val selection = request["selection"] as List<*>
                    val generation = (request["basisIdentityGeneration"] as? String)?.let { raw ->
                        raw.toLong().also { require(it >= 0 && it.toString() == raw) { "Invalid canonical identity generation" } }
                    } ?: error("Canonical identity generation required")
                    outcome = engine.applyCanonicalProbe(CanonicalProbeBasis((request["basisRevision"] as Number).toLong(), ids, generation), edits,
                        Selection((selection[0] as Number).toInt(), (selection[1] as Number).toInt())).name
                }
                else -> error("Unsupported probe command")
            }
            emit(mapOf("id" to requestId, "status" to (outcome ?: if (engine.snapshot().waiting) "Deferred" else "Applied"), "frame" to frame(), "historyRequests" to historyRequests.toList()))
            historyRequests.clear()
        } catch (error: Throwable) {
            emit(mapOf("id" to requestId, "status" to "Rejected", "error" to (error.message ?: error.javaClass.simpleName), "frame" to frame()))
        }
    }
}
