package dev.shayxo.composedoc.androidsample

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.model.paragraphs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Instrumented cross-process transport witness, never labelled real user keyboard/clipboard interaction. */
class ClipboardTransportActivity : MainActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var attempts = 0
    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            main.post(::pasteWhenMounted)
        }
    }
    private fun pasteWhenMounted() {
        val host = inputView
        if (host == null) {
            check(++attempts < MAX_ATTEMPTS) { "Transport consumer did not mount" }
            main.postDelayed(::pasteWhenMounted, POLL_MS)
        } else {
            val token = checkNotNull(intent.getStringExtra("transport"))
            require(UUID.fromString(token).toString() == token)
            val revoked = intent.getStringExtra("revoked-uri")
            if (revoked != null) {
                worker.execute {
                    val failure = runCatching {
                        contentResolver.openInputStream(android.net.Uri.parse(revoked))!!.use { it.read() }
                    }.exceptionOrNull()
                    check(failure is SecurityException || failure is java.io.FileNotFoundException)
                    val root = File(filesDir, "qualification/transport-$token")
                    checkRecoveryWitness(AndroidDocumentCodec.decode(File(root, "document.astra").readBytes()))
                    File(root, "cleanup.json").writeText(
                        JSONObject().put("uid", Process.myUid()).put("revoked", true)
                            .put("ownedDocumentPreserved", true).put("failure", failure.javaClass.name).toString(),
                        Charsets.UTF_8,
                    )
                    main.post { finish() }
                }
                return
            }
            val previous = host.onNativeEvent
            host.onNativeEvent = { event ->
                previous(event)
                if (event == "Rich paste applied") {
                    val document = owners.current.snapshot().committed
                    worker.execute {
                        val expected = intent.getBooleanExtra("check-witness", false)
                        if (expected) checkRecoveryWitness(document)
                        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val uri = checkNotNull(manager.primaryClip).getItemAt(1).uri
                        val readOnly = runCatching { contentResolver.openOutputStream(uri, "wt") }.isFailure
                        check(readOnly)
                        val root = File(filesDir, "qualification/transport-$token")
                        check(root.mkdirs())
                        File(root, "document.astra").writeBytes(AndroidDocumentCodec.encode(document))
                        File(root, "result.json").writeText(
                            JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
                                .put("items", JSONArray(document.paragraphs().map { it.properties.item }))
                                .put("resources", JSONArray(document.resources.keys.toList()))
                                .put("readOnly", readOnly).put("independentDescriptionChecked", expected)
                                .put("event", event).toString(),
                            Charsets.UTF_8,
                        )
                        main.post { finish() }
                    }
                }
            }
            host.requestFocus()
            check(host.performAccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE, null))
        }
    }
    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
    companion object {
        private const val MAX_ATTEMPTS = 300
        private const val POLL_MS = 20L
    }
}
