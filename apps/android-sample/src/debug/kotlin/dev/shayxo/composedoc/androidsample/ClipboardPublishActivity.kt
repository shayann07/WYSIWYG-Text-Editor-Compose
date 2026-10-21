package dev.shayxo.composedoc.androidsample

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Shell-only fixture for real system clipboard grants across independently installed application UIDs. */
class ClipboardPublishActivity : MainActivity() {
    private lateinit var token: String
    private var started = false
    private var attempts = 0
    override fun onCreate(state: Bundle?) {
        token = checkNotNull(intent.getStringExtra("transport"))
        require(UUID.fromString(token).toString() == token)
        intent.putExtra("blank", true).putExtra("qualification-root", "clipboard-owner-$token")
        super.onCreate(state)
    }
    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            owners.main.post(::publishWhenMounted)
        }
    }
    private fun publishWhenMounted() {
        val host = inputView
        if (host == null || !hasWindowFocus()) {
            check(++attempts < MAX_ATTEMPTS) { "Clipboard producer did not mount with focus" }
            owners.main.postDelayed(::publishWhenMounted, POLL_MS)
            return
        }
        if (intent.getBooleanExtra("clear-cache", false)) {
            owners.work({
                checkNotNull(owners.clipboardFiles).clear()
                val root = File(filesDir, "qualification/cleared-$token")
                check(root.mkdirs())
                File(root, "ready.json").writeText(
                    JSONObject().put("pid", Process.myPid()).put("cleared", true).toString(),
                    Charsets.UTF_8,
                )
            }) {}
            return
        }
        owners.work({ image() }) { image ->
            val expected = buildRecoveryWitness(owners.current, image)
            owners.current.dispatch(EditorIntent.SelectAll)
            val previous = host.onNativeEvent
            host.onNativeEvent = { event ->
                previous(event)
                if (event == "Rich clipboard published") {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = checkNotNull(clipboard.primaryClip)
                    check(clip.itemCount == 2)
                    val uri = checkNotNull(clip.getItemAt(1).uri).toString()
                    owners.work({
                        val root = File(filesDir, "qualification/published-$token")
                        check(root.mkdirs())
                        File(root, "expected.astra").writeBytes(AndroidDocumentCodec.encode(expected))
                        File(root, "ready.json").writeText(
                            JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
                                .put("items", JSONArray(expected.paragraphs().map { it.properties.item }))
                                .put("resources", JSONArray(expected.resources.keys.toList()))
                                .put("uri", uri).put("plain", clip.getItemAt(0).text.toString()).toString(),
                            Charsets.UTF_8,
                        )
                    }) {}
                }
            }
            host.requestFocus()
            check(host.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null))
        }
    }
    private fun image(): Pair<String, dev.shayxo.composedoc.model.ImageResource> {
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
    private companion object {
        private const val IMAGE_WIDTH = 3
        private const val IMAGE_HEIGHT = 2
        private const val IMAGE_COLOR = 0xff2559ba.toInt()
        private const val PNG_QUALITY = 100
        private const val MAX_ATTEMPTS = 300
        private const val POLL_MS = 20L
    }
}
