package dev.shayxo.composedoc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.domain.RecoveredDraft
import dev.shayxo.composedoc.layout.AndroidTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val CARET_ARGB = 0xff2559ba
private const val SELECTION_ARGB = 0xffc8dcff
private const val COMPOSITION_ARGB = 0x443467d5
private val CARET_COLOR = Color(CARET_ARGB)
private val SELECTION_COLOR = Color(SELECTION_ARGB)
private val COMPOSITION_COLOR = Color(COMPOSITION_ARGB)
private const val CARET_WIDTH_DP = 2f
private const val HANDLE_RADIUS_DP = 5f
private const val CARET_HALF_PERIOD_MS = 500L

private data class AndroidSurfaceCallbacks(
    val inputView: (AndroidEditorView) -> Unit,
    val unfinished: (RecoveredDraft) -> Unit,
    val focus: (Boolean) -> Unit = {},
)

private data class AndroidSurfaceContent(
    val frame: EditorFrame,
    val geometry: AndroidEditorGeometry,
    val images: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
)

/** Continuous Android Compose renderer with a buffer-free native input/accessibility host. */
@Composable
fun composeDocEditor(
    model: EditorViewModel,
    typography: AndroidTypography,
    modifier: Modifier = Modifier,
    onInputView: (AndroidEditorView) -> Unit = {},
    onUnfinishedInput: (RecoveredDraft) -> Unit = {},
) {
    key(model) {
        androidOwnerEditor(model, typography, modifier, AndroidSurfaceCallbacks(onInputView, onUnfinishedInput))
    }
}

@Composable
private fun androidOwnerEditor(
    model: EditorViewModel,
    typography: AndroidTypography,
    modifier: Modifier,
    callbacks: AndroidSurfaceCallbacks,
) {
    val state by model.uiState.collectAsState()
    val frame = state.frame
    val limitation = androidTextSliceLimitation(frame.visible)
    if (limitation != null) {
        BasicText("$limitation. Document and resources retained unchanged.", modifier)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val images = rememberAndroidImages(frame.visible.resources)
    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val geometry = remember(frame.visible, frame.typingStyle, width, typography, density) {
            androidTextGeometry(AndroidLayoutRequest(frame, typography, width, density, resolver), measurer)
        }
        androidViewport(model, AndroidSurfaceContent(frame, geometry, images.images), callbacks, height)
        if (images.errors.isNotEmpty()) BasicText(images.errors.joinToString("; "))
    }
}

@Composable
private fun androidViewport(
    model: EditorViewModel,
    content: AndroidSurfaceContent,
    callbacks: AndroidSurfaceCallbacks,
    height: Float,
) {
    val frame = content.frame
    val geometry = content.geometry
    val density = LocalDensity.current.density
    val inset = ANDROID_INSET_DP * density
    var scroll by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(focused, frame.selection, frame.documentRevision) {
        caretOn = true
        if (focused && frame.selection.collapsed) {
            while (isActive) {
                delay(CARET_HALF_PERIOD_MS)
                caretOn = !caretOn
            }
        }
    }
    val maximum = (geometry.height - height).coerceAtLeast(0f)
    fun scrollTo(value: Float) {
        scroll = value.coerceIn(0f, maximum)
    }
    LaunchedEffect(geometry, frame.selection, height) {
        val caret = geometry.caret(frame.selection.focus)
        when {
            caret.bottom - scroll > height - inset -> scrollTo(caret.bottom - height + inset)
            caret.top - scroll < inset -> scrollTo(caret.top - inset)
            scroll > maximum -> scrollTo(maximum)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        paintAndroidDocument(content, scroll, density, focused && caretOn)
    }
    androidNativeHost(model, geometry, scroll, callbacks.copy(focus = { focused = it })) { delta ->
        scrollTo(
            scroll + delta,
        )
    }
}

@Composable
private fun androidNativeHost(
    model: EditorViewModel,
    geometry: AndroidEditorGeometry,
    scroll: Float,
    callbacks: AndroidSurfaceCallbacks,
    scrollBy: (Float) -> Unit,
) {
    var input by remember { mutableStateOf<AndroidEditorView?>(null) }
    AndroidView(
        factory = { context ->
            AndroidEditorView(context, model).also {
                it.onUnfinishedInput = callbacks.unfinished
                it.onEditorFocusChanged = callbacks.focus
                input = it
                callbacks.inputView(it)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = {
            it.onUnfinishedInput = callbacks.unfinished
            it.onEditorFocusChanged = callbacks.focus
            callbacks.focus(it.hasFocus() && it.hasWindowFocus() && it.isShown)
            it.geometry = geometry
            it.scrollOffset = scroll
            it.scrollBy = scrollBy
            it.post {
                it.notifyCursor()
                it.invalidate()
            }
        },
    )
    DisposableEffect(model, input) {
        val host = input
        val publication: (EditorFrame) -> Unit = { host?.ownerPublished() }
        model.onStatePublished = publication
        onDispose {
            host?.retireNativeSession()
            if (model.onStatePublished === publication) model.onStatePublished = {}
        }
    }
}

private fun DrawScope.paintAndroidDocument(
    content: AndroidSurfaceContent,
    scroll: Float,
    scale: Float,
    caretVisible: Boolean,
) {
    val geometry = content.geometry
    val frame = content.frame
    val images = content.images
    drawRect(Color.White)
    clipRect {
        translate(top = -scroll) {
            geometry.paragraphs.forEach { paintAndroidParagraph(it, frame, images) }
            val selected = frame.selection
            val caret = geometry.caret(selected.focus)
            if (selected.collapsed && caretVisible) {
                drawLine(
                    CARET_COLOR,
                    Offset(caret.left, caret.top),
                    Offset(caret.left, caret.bottom),
                    scale * CARET_WIDTH_DP,
                )
            }
            if (!selected.collapsed) {
                for (at in listOf(selected.anchor, selected.focus)) {
                    val r = geometry.caret(at)
                    drawCircle(CARET_COLOR, scale * HANDLE_RADIUS_DP, Offset(r.left, r.bottom))
                }
            }
        }
    }
}

private fun DrawScope.paintAndroidParagraph(
    paragraph: AndroidParagraph,
    frame: EditorFrame,
    images: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
) {
    val image = paragraph.image
    if (image != null) {
        val bitmap = images[image.resource]
        if (bitmap !=
            null
        ) {
            drawImage(
                bitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(paragraph.left.toInt(), paragraph.top.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(
                    paragraph.imageWidth.toInt().coerceAtLeast(1),
                    paragraph.height.toInt().coerceAtLeast(1),
                ),
            )
        } else {
            drawRect(
                Color.LightGray,
                Offset(paragraph.left, paragraph.top),
                androidx.compose.ui.geometry.Size(paragraph.imageWidth, paragraph.height),
            )
        }
        if (frame.selection.start <= paragraph.block.start && frame.selection.end > paragraph.block.start) {
            drawRect(
                SELECTION_COLOR.copy(alpha = .35f),
                Offset(paragraph.left, paragraph.top),
                androidx.compose.ui.geometry.Size(paragraph.imageWidth, paragraph.height),
            )
        }
    } else {
        paintAndroidRange(paragraph, frame.selection.start, frame.selection.end, SELECTION_COLOR)
        drawText(paragraph.layout, topLeft = Offset(paragraph.left, paragraph.top))
        frame.marked?.let { paintAndroidRange(paragraph, it.first, it.last + 1, COMPOSITION_COLOR) }
    }
    paragraph.marker?.let { marker ->
        if (marker.layout != null) {
            drawText(marker.layout, topLeft = Offset(marker.left, paragraph.top + marker.top))
        } else {
            drawCircle(Color.Black, marker.radius, Offset(marker.left + marker.radius, paragraph.top + marker.center))
        }
    }
}
private fun DrawScope.paintAndroidRange(paragraph: AndroidParagraph, from: Int, until: Int, color: Color) {
    val start = maxOf(from, paragraph.block.start) - paragraph.block.start
    val end = minOf(until, paragraph.block.end) - paragraph.block.start
    if (start < end) {
        val path = paragraph.layout.getPathForRange(start, end)
        translate(paragraph.left, paragraph.top) { drawPath(path, color) }
    }
}
