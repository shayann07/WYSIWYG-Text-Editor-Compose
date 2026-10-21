package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.shayxo.composedoc.layout.AndroidTypography
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Executes real production Android InputConnections. These callbacks are instrumented, not real IME input. */
class AdapterInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private val outcomes = JSONArray()
    internal lateinit var activity: MainActivity
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        val id = arguments.getString("run-id", "adapter-${System.currentTimeMillis()}")
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val root = File(targetContext.filesDir, "qualification/$id")
        check(root.mkdirs())
        var fatal: Throwable? = null
        try {
            runChecks(root)
        } catch (error: Throwable) {
            fatal = error
        }
        val result = resultFor(fatal)
        File(root, "results.json").writeText(result.toString(2), Charsets.UTF_8)
        val status = result.getString("status")
        val data = Bundle().apply {
            putString(
                "stream",
                "$status ${outcomes.length()} groups; " +
                    "evidence files/qualification/$id/results.json\n",
            )
            putInt("failures", result.getInt("failures"))
        }
        finish(if (status == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
    }
    private fun runChecks(root: File) {
        val intent = Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("blank", true)
            .putExtra("qualification-root", root.name)
        activity = startActivitySync(intent) as MainActivity
        waitUntil { main { activity.inputView != null } }
        if (arguments.getString("focus") == "rich-layout") {
            richLayoutChecks(root)
        } else if (arguments.getString("focus") == "rich-lifecycle") {
            richLifecycleChecks()
        } else if (arguments.getString("focus") == "shell") {
            richShellChecks()
        } else if (arguments.getString("focus") == "touch") {
            richTouchChecks(root)
        } else if (arguments.getString("focus") == "clipboard") {
            richClipboardChecks()
        } else if (arguments.getString("focus") == "uri") {
            richUriChecks(root)
        } else if (arguments.getString("focus") == "rich-structure") {
            richStructureChecks(root)
        } else if (arguments.getString("focus") == "recovery") {
            richRecoveryChecks(root)
        } else if (arguments.getString("focus") == "caret") {
            caretChecks()
        } else if (arguments.getString("focus") == "connection-probe") {
            speculativeConnectionCheck()
        } else if (arguments.getString("focus") == "connection") {
            closedConnectionCheck()
        } else if (arguments.getString("focus") == "shaping") {
            shapingChecks()
        } else {
            protocolChecks()
            persistenceChecks(root)
            fontChecks()
            shapingChecks()
            mountedChecks()
            caretChecks()
            richRecoveryChecks(root)
            richStructureChecks(root)
            richUriChecks(root)
            richClipboardChecks()
            richTouchChecks(root)
            richLifecycleChecks()
            richLayoutChecks(root)
            richShellChecks()
        }
    }
    private fun resultFor(fatal: Throwable?): JSONObject {
        val failed = (0 until outcomes.length()).count { outcomes.getJSONObject(it).getString("status") != "PASS" } +
            (if (fatal == null) 0 else 1)
        return JSONObject().put("status", if (failed == 0 && outcomes.length() > 0) "PASS" else "FAIL")
            .put("groups", outcomes).put("failures", failed).put("fatal", fatal?.stackTraceToString())
            .put("nativeEvents", JSONArray(main { activity.owners.nativeEvents.toList() }))
            .put("api", android.os.Build.VERSION.SDK_INT).put("fingerprint", android.os.Build.FINGERPRINT)
            .put("fontScale", targetContext.resources.configuration.fontScale)
            .put("densityDpi", targetContext.resources.displayMetrics.densityDpi)
            .put("apkSha256", sha(File(targetContext.applicationInfo.sourceDir).readBytes()))
            .put(
                "scope",
                "Instrumented production adapter, mounted Compose geometry, lifecycle and persistence; " +
                    "not genuine keyboard/IME or TalkBack qualification",
            )
    }
    internal fun group(name: String, block: () -> Unit) {
        val result = JSONObject().put(
            "name",
            name,
        )
        try {
            block()
            result.put(
                "status",
                "PASS",
            )
        } catch (error: Throwable) {
            result.put(
                "status",
                "FAIL",
            ).put(
                "error",
                error.stackTraceToString(),
            )
        }
        outcomes.put(result)
    }
    internal fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())return block()
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    internal fun waitUntil(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) {
            if (condition())return
            SystemClock.sleep(40)
        }
        error("Timed out waiting for actual Android state")
    }
    internal fun fixture(text: String = ""): Pair<
        EditorViewModel,
        AndroidEditorView,
        > =
        main {
            val v = EditorViewModel()
            if (text.isNotEmpty()) {
                v.dispatch(
                    EditorIntent.Type(
                        text,
                        "Fixture",
                        paragraphBreaks = true,
                    ),
                )
            }
            val h = AndroidEditorView(
                targetContext,
                v,
            )
            v.onStatePublished = { h.ownerPublished() }
            v to h
        }
    internal fun connection(view: AndroidEditorView): InputConnection =
        view.onCreateInputConnection(EditorInfo()).also {
            // Factory calls can be discarded by Android; a real callback adopts the replacement.
            check(it.getTextBeforeCursor(0, 0) != null)
        }

    private fun fontChecks() {
        group(
            "packaged asset fonts load, missing/corrupt resources disclose fallback without rewriting stored identity",
        ) {
            val face = AndroidTypography.load(targetContext)
            check(face.unavailable.isEmpty())
            val prefix = "dev/shayxo/composedoc/layout/fonts/"
            val corrupt = AndroidTypography.load(targetContext) { name ->
                if (name == prefix + "Gulzar-Regular.ttf") {
                    ByteArrayInputStream(
                        byteArrayOf(
                            0,
                            1,
                            2,
                        ),
                    )
                } else {
                    targetContext.assets.open(name)
                }
            }
            check(
                StoredFont.GULZAR in corrupt.unavailable &&
                    corrupt.status.contains("stored choice retained"),
            )
            val missing = AndroidTypography.load(targetContext) { name ->
                if (name ==
                    prefix + "NotoNastaliqUrdu[wght].ttf"
                ) {
                    throw java.io.FileNotFoundException("owned missing font")
                } else {
                    targetContext.assets.open(name)
                }
            }
            check(StoredFont.NOTO_NASTALIQ in missing.unavailable)
            check(
                runCatching {
                    AndroidTypography.load(targetContext) { ByteArrayInputStream(byteArrayOf(0)) }
                }.isFailure,
            )
        }
    }
    internal fun replaceMounted(text: String) {
        main {
            val v = activity.owners.current
            activity.inputView?.retireNativeSession()
            v.dispatch(EditorIntent.SelectAll)
            v.dispatch(
                EditorIntent.Type(
                    text,
                    "Fixture",
                    paragraphBreaks = true,
                ),
            )
            v.dispatch(EditorIntent.SelectAll)
            v.dispatch(
                EditorIntent.SetTextStyle(
                    TextStylePatch(
                        bold = false,
                        italic = false,
                        font = StoredFont.NOTO_NASTALIQ,
                        size = 26,
                    ),
                ),
            )
            v.dispatch(
                EditorIntent.SetParagraphProperties(
                    ParagraphDirection.LTR,
                    ParagraphAlignment.START,
                ),
            )
            v.dispatch(
                EditorIntent.Select(
                    Selection(
                        0,
                        0,
                    ),
                ),
            )
        }
        waitForIdleSync()
        waitUntil { main { activity.inputView?.caretRectangle() != null } }
    }
    internal fun locations(text: String): List<RectF> = main {
        val view = activity.inputView!!
        val info = view.createAccessibilityNodeInfo()
        val args = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
                0,
            )
            putInt(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                text.length,
            )
        }
        view.addExtraDataToAccessibilityNodeInfo(
            info,
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
            args,
        )
        val values =
            info.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                ?: error("No current character geometry")
        values.filterIsInstance<RectF>()
    }
    internal fun referencePaint() = Paint().apply {
        typeface = Typeface.createFromAsset(
            targetContext.assets,
            "dev/shayxo/composedoc/layout/fonts/NotoNastaliqUrdu[wght].ttf",
        )
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            26f,
            targetContext.resources.displayMetrics,
        )
        isAntiAlias = true
    }
    private fun shapingChecks() {
        for ((
            label,
            text,
            rtl,
        ) in listOf(
            Triple(
                "Urdu joining",
                "کتاب",
                true,
            ),
            Triple(
                "emoji cluster",
                "👩🏽‍💻",
                false,
            ),
            Triple(
                "combining Latin",
                "e\u0301",
                false,
            ),
        )) {
            group("mounted $label advance agrees with independent native Paint run, not per-unit spans") {
                replaceMounted(text)
                val boxes = locations(text)
                check(boxes.size == text.length)
                val observed = boxes.maxOf { it.right } - boxes.minOf { it.left }
                val expected = referencePaint().getTextRunAdvances(
                    text.toCharArray(),
                    0,
                    text.length,
                    0,
                    text.length,
                    rtl,
                    null,
                    0,
                )
                check(kotlin.math.abs(observed - expected) <= 2f) {
                    "$label observed advance=$observed expected native run=$expected"
                }
            }
        }
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
