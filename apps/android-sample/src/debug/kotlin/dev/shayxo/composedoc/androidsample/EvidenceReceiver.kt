package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.domain.EditorFrame
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val DIGEST_BUFFER_BYTES = 65536
private const val EVIDENCE_QUEUE_CAPACITY = 4
private const val EVIDENCE_LOG = "ComposeDocEvidence"

/** Debug-only read-only evidence. No input, dispatch, session replay or user-file commands. */
class EvidenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("request")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,48}")) }
            ?: return
        val activity = MainActivity.currentActivity?.get() ?: return
        val frames = activity.owners.models.map { it.snapshot() }
        val result = captureState(activity, frames)
        val pending = goAsync()
        try {
            worker.execute { writeEvidence(context.applicationContext, token, frames, result, pending) }
        } catch (error: RejectedExecutionException) {
            pending.setResultCode(Activity.RESULT_CANCELED)
            pending.setResultData("Evidence queue full: ${error.message}")
            Log.e(EVIDENCE_LOG, "Evidence queue rejected request", error)
            pending.finish()
        }
    }

    companion object {
        private val worker = ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(EVIDENCE_QUEUE_CAPACITY),
        )
    }
}

private fun captureState(activity: MainActivity, frames: List<EditorFrame>): JSONObject {
    val view = activity.inputView
    val caret = view?.caretRectangle()
    val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom
    } else {
        null
    }
    return JSONObject().put("active", activity.owners.active).put("status", activity.owners.status)
        .put("nativeEvents", JSONArray(activity.owners.nativeEvents.toList()))
        .put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
        .put("viewSize", JSONArray(listOf(view?.width, view?.height)))
        .put("caret", caret?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
        .put("imeBottom", ime).put("owners", JSONArray(frames.map(::ownerRecord)))
}

private fun ownerRecord(frame: EditorFrame) = JSONObject()
    .put("committed", JSONArray(frame.committed.units.map { it.char.code }))
    .put("visible", JSONArray(frame.visible.units.map { it.char.code }))
    .put("selection", JSONArray(listOf(frame.selection.anchor, frame.selection.focus)))
    .put("marked", frame.marked?.let { JSONArray(listOf(it.first, it.last + 1)) })
    .put("undo", frame.undoCount).put("redo", frame.redoCount)
    .put("waiting", frame.waiting).put("notice", frame.notice)

private fun writeEvidence(
    context: Context,
    token: String,
    frames: List<EditorFrame>,
    result: JSONObject,
    pending: BroadcastReceiver.PendingResult,
) {
    try {
        val root = File(context.filesDir, "qualification/native-$token")
        if (!root.mkdirs()) throw IOException("Evidence path exists or cannot be created; existing records preserved")
        frames.forEachIndexed { index, frame ->
            File(root, "owner-$index.astra").writeBytes(AndroidDocumentCodec.encode(frame.committed))
        }
        result.put("apkSha256", apkHash(File(context.applicationInfo.sourceDir)))
        result.put(
            "scope",
            "Read-only debug snapshot; .astra contains committed content only; " +
                "raw visible units are diagnostic evidence",
        )
        File(root, "state.json").writeText(result.toString(2), Charsets.UTF_8)
        pending.setResultCode(Activity.RESULT_OK)
    } catch (error: IOException) {
        pending.setResultCode(Activity.RESULT_CANCELED)
        pending.setResultData("Evidence IO failed: ${error.message}")
        Log.e(EVIDENCE_LOG, "Read-only evidence failed", error)
    } finally {
        pending.finish()
    }
}

private fun apkHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
