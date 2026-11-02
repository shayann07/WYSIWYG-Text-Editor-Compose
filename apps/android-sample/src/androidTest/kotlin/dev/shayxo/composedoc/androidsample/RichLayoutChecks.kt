package dev.shayxo.composedoc.androidsample

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.TtsSpan
import android.view.PixelCopy
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.EditorIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal fun AdapterInstrumentation.richLayoutChecks(root: File) {
    markerRaster(root)
    imageGeometry()
    headingAppearance()
}

private fun AdapterInstrumentation.resetRichMounted(text: String) {
    replaceMounted(text)
    main {
        val model = activity.owners.current
        model.dispatch(EditorIntent.SelectAll)
        model.dispatch(EditorIntent.Structure(StructureCommand.Role(0)))
        model.dispatch(EditorIntent.Structure(StructureCommand.ListType(ListKind.NONE)))
        model.dispatch(
            EditorIntent.SetParagraphProperties(
                ParagraphDirection.LTR,
                before = 0,
                after = 0,
                line = 0,
            ),
        )
        model.dispatch(EditorIntent.Select(Selection(0, 0)))
        val view = activity.inputView!!
        val manager = targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        manager.hideSoftInputFromWindow(view.windowToken, 0)
    }
    waitForIdleSync()
}

private fun AdapterInstrumentation.markerRaster(root: File) {
    group(
        "Android marker ink aligns with independently rendered inline digit/x ink across fonts sizes and directions",
    ) {
        val records = JSONArray()
        for (font in listOf(StoredFont.NOTO_NASTALIQ, StoredFont.GULZAR, StoredFont.NASKH, StoredFont.SYSTEM)) {
            for (size in listOf(16, 26)) {
                for (direction in listOf(ParagraphDirection.LTR, ParagraphDirection.RTL)) {
                    for (kind in listOf(ListKind.NUMBER, ListKind.BULLET)) {
                        records.put(markerCase(root, MarkerCase(font, size, direction, kind)))
                    }
                }
            }
        }
        File(root, "android-marker-matrix.json").writeText(records.toString(2), Charsets.UTF_8)
    }
}

private data class MarkerCase(
    val font: StoredFont,
    val size: Int,
    val direction: ParagraphDirection,
    val kind: ListKind,
)

private fun AdapterInstrumentation.markerCase(root: File, case: MarkerCase): JSONObject {
    val text = if (case.kind == ListKind.NUMBER) "1. اردو English" else "x اردو English"
    resetRichMounted(text)
    applyMarkerCase(case, text.length)
    waitForIdleSync()
    // Compose may publish geometry after an unrelated decor frame commits. Fence the
    // requested document revision first, then capture its subsequent committed frame.
    waitUntil { main { activity.inputView?.caretRectangle() != null } }
    awaitRenderedFrame()
    val reference = locations(text).first()
    val view = main { activity.inputView!! }
    val location = main { IntArray(2).also(view::getLocationOnScreen) }
    val canvas =
        RectF(
            location[0].toFloat(),
            reference.top,
            (location[0] + view.width).toFloat(),
            reference.bottom,
        )
    val raster = committedWindowRaster()
    val bitmap = raster.bitmap
    canvas.offset(-raster.screenX.toFloat(), -raster.screenY.toFloat())
    reference.offset(-raster.screenX.toFloat(), -raster.screenY.toFloat())
    try {
        File(root, "observed-${case.font.name}-${case.size}-${case.direction}-${case.kind}.png")
            .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val ink = inkBounds(bitmap, canvas, false)
        val inline = inkBounds(bitmap, reference, true)
        val difference = if (case.kind == ListKind.NUMBER) {
            abs(ink.top - inline.top)
        } else {
            abs(ink.centerY() - inline.centerY())
        }
        check(difference <= 2f) {
            "$case marker=$ink inline=$inline delta=$difference"
        }

        if (case.size == 26 && case.direction == ParagraphDirection.RTL) {
            File(root, "marker-${case.font.name}-${case.kind.name}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        return JSONObject().put("font", case.font.name).put("size", case.size).put("direction", case.direction.name)
            .put("kind", case.kind.name).put("markerInk", ink.toString()).put("inlineInk", inline.toString())
            .put("capture", raster.method).put("windowScreenX", raster.screenX).put("windowScreenY", raster.screenY)
    } finally {
        bitmap.recycle()
    }
}

private data class WindowRaster(val bitmap: Bitmap, val screenX: Int, val screenY: Int, val method: String)

private fun AdapterInstrumentation.committedWindowRaster(): WindowRaster {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
        return WindowRaster(checkNotNull(uiAutomation.takeScreenshot()), 0, 0, "display-screenshot")
    }
    val raster = main {
        val decor = activity.window.decorView
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        WindowRaster(
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888),
            origin[0],
            origin[1],
            "window-pixel-copy",
        )
    }
    val copied = CountDownLatch(1)
    var result = PixelCopy.ERROR_UNKNOWN
    try {
        // A committed buffer need not yet be visible on the display. Copy that window
        // buffer directly, rather than racing SurfaceFlinger's display presentation.
        PixelCopy.request(
            activity.window,
            raster.bitmap,
            { code ->
                result = code
                copied.countDown()
            },
            Handler(Looper.getMainLooper()),
        )
        check(copied.await(5, TimeUnit.SECONDS)) { "No Android window pixel-copy result" }
        check(result == PixelCopy.SUCCESS) { "Android window pixel-copy failed: $result" }
        return raster
    } catch (error: Throwable) {
        raster.bitmap.recycle()
        throw error
    }
}

private fun AdapterInstrumentation.awaitRenderedFrame() {
    val committed = CountDownLatch(1)
    main {
        val view = activity.window.decorView
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        } else {
            view.postOnAnimation { view.postOnAnimation { committed.countDown() } }
        }
    }
    check(committed.await(5, TimeUnit.SECONDS)) { "No committed Android render frame" }
}

private fun AdapterInstrumentation.applyMarkerCase(case: MarkerCase, length: Int) = main {
    val model = activity.owners.current
    model.dispatch(EditorIntent.SelectAll)
    model.dispatch(
        EditorIntent.SetTextStyle(
            TextStylePatch(
                font = case.font,
                size = case.size,
                color = 0x0055bb,
                bold = false,
                italic = false,
                underline = false,
                strike = false,
            ),
        ),
    )
    model.dispatch(EditorIntent.Structure(StructureCommand.ListType(case.kind)))
    model.dispatch(EditorIntent.Structure(StructureCommand.MarkerStyle(MarkerTypography(case.font, case.size))))
    model.dispatch(EditorIntent.SetParagraphProperties(direction = case.direction))
    model.dispatch(EditorIntent.Select(Selection(length, length)))
}

private fun inkBounds(bitmap: Bitmap, region: RectF, blue: Boolean): RectF {
    var left = bitmap.width
    var right = -1
    var top = bitmap.height
    var bottom = -1
    for (y in region.top.toInt().coerceAtLeast(0) until region.bottom.toInt().coerceAtMost(bitmap.height)) {
        for (x in region.left.toInt().coerceAtLeast(0) until region.right.toInt().coerceAtMost(bitmap.width)) {
            val pixel = bitmap.getPixel(x, y)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val ink = if (blue) r < 100 && b > r + 40 && b > g + 30 else r < 30 && g < 30 && b < 30
            if (ink) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
    }
    check(right >= left && bottom >= top) { "Missing ${if (blue) "inline reference" else "marker"} ink in $region" }
    return RectF(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
}

private fun AdapterInstrumentation.imageGeometry() {
    group("mounted image fitting and aspect geometry preserve requested size and accessible alt spans") {
        val resource = ImageDecoder.validate(testPng(3, 2, android.graphics.Color.RED))
        resetRichMounted("")
        main {
            val model = activity.owners.current
            model.dispatch(
                EditorIntent.Structure(
                    StructureCommand.InsertImage(
                        ImageBlock(resource.first, 600, "سرخ red illustration"),
                        resource.second,
                    ),
                ),
            )
        }
        waitForIdleSync()
        val view = main { activity.inputView!! }
        val box = locations("\uFFFC").single()
        val density = targetContext.resources.displayMetrics.density
        val expected = minOf(600 * density, view.width - 32 * density)
        check(abs(box.width() - expected) <= 1.5f)
        check(abs(box.height() - box.width() * 2 / 3) <= 1.5f)
        main {
            val document = activity.owners.current.snapshot().committed
            check(document.units.single { it.image != null }.image?.width == 600)
            val node = view.createAccessibilityNodeInfo()
            check(node.text.toString() == document.text)
            val spans = (node.text as Spanned).getSpans(0, 1, TtsSpan::class.java)
            check(spans.single().args.getString(TtsSpan.ARG_TEXT) == "سرخ red illustration")
            activity.owners.current.dispatch(
                EditorIntent.Structure(StructureCommand.ImageProperties(120, "changed alt")),
            )
        }
        waitForIdleSync()
        val resized = locations("\uFFFC").single()
        check(abs(resized.width() - 120 * density) <= 1.5f)
        check(abs(resized.height() - 80 * density) <= 1.5f)
    }
}

private fun AdapterInstrumentation.headingAppearance() {
    group("semantic heading default appearance enlarges measured text while explicit size overrides it") {
        resetRichMounted("Heading\nBody")
        main {
            val model = activity.owners.current
            model.dispatch(EditorIntent.SelectAll)
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(size = 0, font = StoredFont.NOTO_NASTALIQ)))
            model.dispatch(EditorIntent.Select(Selection(0, 7)))
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
            model.dispatch(EditorIntent.Select(Selection(0, 0)))
        }
        waitForIdleSync()
        val boxes = locations("Heading\nBody")
        val heading = boxes.first().height()
        val body = boxes.last().height()
        check(heading > body * 1.25f) { "Heading=$heading body=$body" }
        main {
            activity.owners.current.dispatch(EditorIntent.Select(Selection(0, 7)))
            activity.owners.current.dispatch(EditorIntent.SetTextStyle(TextStylePatch(size = 26)))
        }
        waitForIdleSync()
        val overridden = locations("Heading\nBody").first().height()
        check(abs(overridden - body) <= 2f) { "Explicit26 heading height=$overridden body26 height=$body" }
    }
}
