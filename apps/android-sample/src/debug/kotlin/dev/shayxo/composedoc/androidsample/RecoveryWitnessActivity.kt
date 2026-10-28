package dev.shayxo.composedoc.androidsample

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Shell-only debug fixture: production auto-checkpointing and a separately killed process, not native IME evidence. */
class RecoveryWitnessActivity : MainActivity() {
    private lateinit var request: String
    private var began = false
    private var deadline = 0L
    private val root get() = File(filesDir, "qualification/kill-$request")
    override fun onCreate(state: Bundle?) {
        request = checkNotNull(intent.getStringExtra("request"))
        require(UUID.fromString(request).toString() == request)
        intent.putExtra("blank", true).putExtra("qualification-root", "kill-$request")
        super.onCreate(state)
    }
    override fun onResume() {
        super.onResume()
        if (!began) {
            began = true
            deadline = SystemClock.uptimeMillis() + READY_LIMIT_MS
            owners.main.post(::mounted)
        }
    }
    private fun mounted() {
        check(SystemClock.uptimeMillis() < deadline) { "Recovery witness editor did not mount" }
        val view = inputView
        if (view == null) {
            owners.main.postDelayed(::mounted, POLL_MS)
        } else if (intent.getStringExtra("phase") == "restore") {
            restoreWitness()
        } else {
            view.isFocusable = false
            view.clearFocus()
            owners.work({ witnessImage() }) { image ->
                val accepted = buildRecoveryWitness(owners.current, image)
                owners.current.dispatch(EditorIntent.Select(Selection(accepted.units.size, accepted.units.size)))
                owners.current.dispatch(EditorIntent.NativeText(WITNESS_PROPOSAL, 0, null))
                owners.main.post { checkpointReady(accepted) }
            }
        }
    }
    private fun checkpointReady(expected: Document) {
        check(SystemClock.uptimeMillis() < deadline) { "Automatic recovery checkpoint did not finish" }
        val tab = owners.tab
        val complete = tab.recovery.startsWith("Recovery #${tab.sequence} completed") &&
            !tab.recovery.contains("pending")
        if (!complete) {
            owners.main.postDelayed({ checkpointReady(expected) }, POLL_MS)
        } else if (intent.getBooleanExtra("close-after-discard", false)) {
            closeAfterDiscard(expected)
        } else {
            owners.work({
                val record = owners.recoveryStore.read(tab.id, tab.sequence)
                check(record.document.same(expected) && record.proposal?.text == expected.text + WITNESS_PROPOSAL)
                File(root, "expected.astra").writeBytes(AndroidDocumentCodec.encode(expected))
                JSONObject().put("owner", tab.id).put("sequence", tab.sequence).put("sourcePid", Process.myPid())
            }) { metadata -> lateUncheckpointedEdit(metadata) }
        }
    }
    private fun closeAfterDiscard(expected: Document) {
        val tab = owners.tab
        val discarded = tab.sequence
        owners.checkpoints.discard(tab.id, discarded) { result ->
            check(result.getOrThrow())
            owners.closeDocument(tab) { outcome ->
                check(outcome.durable && tab !in owners.tabs)
                val metadata = JSONObject().put("owner", tab.id).put("sequence", tab.sequence)
                    .put("sourcePid", Process.myPid()).put("discardedSequence", discarded)
                    .put("stage", "CLOSE_REPORTED_DURABLE").put("closeReportedSuccess", true)
                owners.work({
                    val record = owners.recoveryStore.read(tab.id, tab.sequence)
                    check(tab.sequence > discarded && record.document.same(expected))
                    check(record.proposal?.text == expected.text + WITNESS_PROPOSAL)
                    File(root, "expected.astra").writeBytes(AndroidDocumentCodec.encode(expected))
                    publishReceipt(File(root, "late-ready.json"), metadata)
                }) {}
            }
        }
    }
    private fun lateUncheckpointedEdit(metadata: JSONObject) {
        owners.current.dispatch(EditorIntent.NativeText(WITNESS_PROPOSAL, WITNESS_PROPOSAL.length, null))
        owners.current.dispatch(EditorIntent.Type(WITNESS_AFTER))
        val late = owners.current.snapshot().committed
        check(late.text == WITNESS_TEXT + WITNESS_PROPOSAL + WITNESS_AFTER)
        owners.work({
            File(root, "late.astra").writeBytes(AndroidDocumentCodec.encode(late))
            publishReceipt(
                File(root, "late-ready.json"),
                metadata.put("stage", "AFTER_COMPLETED_CHECKPOINT")
                    .put("scope", "Synthetic setup; UI timer deliberately gated before an owned SIGKILL"),
            )
        }) {}
        // Test-only deterministic loss window: no timer may publish these later edits before the driver kills this PID.
        SystemClock.sleep(KILL_GATE_MS)
        error("Owned-process kill driver did not arrive before the test gate expired")
    }
    private fun restoreWitness() {
        owners.work({
            val metadata = JSONObject(File(root, "late-ready.json").readText(Charsets.UTF_8))
            val original = AndroidDocumentCodec.decode(File(root, "expected.astra").readBytes())
            val record = owners.recoveryStore.read(metadata.getString("owner"), metadata.getLong("sequence"))
            checkRecoveryWitness(record.document)
            check(record.document.same(original))
            check(record.proposal?.text == WITNESS_TEXT + WITNESS_PROPOSAL)
            Triple(record, metadata, original)
        }) { (record, metadata, original) ->
            val restored = owners.add(record.document, "Recovered process witness", activate = true)
            val frame = restored.model.snapshot()
            check(frame.committed.same(original) && frame.visible.same(frame.committed))
            check(frame.undoCount == 0 && !frame.waiting && restored.model.compositionCaptureToken() == null)
            check(restored.dirty && restored.id != metadata.getString("owner"))
            owners.work({
                check(owners.recoveryStore.entries().any { it.owner == metadata.getString("owner") })
                File(root, "restored.astra").writeBytes(AndroidDocumentCodec.encode(frame.committed))
                publishReceipt(
                    File(root, "restored.json"),
                    metadata.put("status", "PASS")
                        .put("restoredPid", Process.myPid()).put("canonicalText", frame.committed.text)
                        .put("separateProposalText", record.proposal?.text)
                        .put("independentDescriptionChecked", true).put("newUnsavedOwner", true),
                )
            }) {}
        }
    }
    private fun witnessImage(): Pair<String, dev.shayxo.composedoc.model.ImageResource> {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(IMAGE_COLOR)
            val bytes = ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it))
                it.toByteArray()
            }
            ImageDecoder.validate(bytes)
        } finally {
            bitmap.recycle()
        }
    }
    private fun publishReceipt(file: File, value: JSONObject) {
        check(!file.exists())
        val temporary = File(file.path + ".tmp")
        temporary.writeText(value.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(file))
    }
    private companion object {
        private const val IMAGE_WIDTH = 3
        private const val PNG_QUALITY = 100
        private const val IMAGE_HEIGHT = 2
        private const val IMAGE_COLOR = 0xff2559ba.toInt()
        private const val READY_LIMIT_MS = 20000L
        private const val POLL_MS = 50L
        private const val KILL_GATE_MS = 10000L
    }
}
