package dev.shayxo.composedoc.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.AndroidTypography
import dev.shayxo.composedoc.layout.renderedStyleRuns
import dev.shayxo.composedoc.model.DisplayStyle
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.listMarkers
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.resolveParagraphDirection
import android.graphics.Rect as NativeRect

internal const val ANDROID_INSET_DP = 16f
private const val ANDROID_FONT_SP = 26
private const val ANDROID_PARAGRAPH_GAP_DP = 8f
private const val OPAQUE_ARGB = 0xff000000L
private const val ANDROID_TEXT_ARGB = 0xff202b3c
private val ANDROID_TEXT_COLOR = Color(ANDROID_TEXT_ARGB)

internal data class AndroidLayoutRequest(
    val frame: EditorFrame,
    val typography: AndroidTypography,
    val width: Float,
    val density: Density,
    val resolver: FontFamily.Resolver,
)

/** Render-only projection. No edit, identity allocation or normalization occurs here. */
internal fun androidTextGeometry(request: AndroidLayoutRequest, measurer: TextMeasurer): AndroidEditorGeometry {
    val frame = request.frame
    val typography = request.typography
    val width = request.width
    val density = request.density
    val document = frame.visible
    val inset = ANDROID_INSET_DP * density.density
    val runs = renderedStyleRuns(document)
    val textWidth = (width - 2 * inset).toInt().coerceAtLeast(1)
    var y = inset
    var fallback = ParagraphDirection.RTL
    val blocks = document.paragraphs()
    val directions = blocks.map { block ->
        fallback = resolveParagraphDirection(document.units.subList(block.start, block.end), block.properties, fallback)
        fallback
    }
    val lists = AndroidListGeometry(request, measurer, blocks, directions, textWidth.toFloat())
    val paragraphs = blocks.map { block ->
        y += block.properties.spaceBefore * density.density
        val direction = directions[block.index]
        val column = lists.column(block.properties.item)
        val left = inset + (column?.left ?: 0f)
        val available = column?.width ?: textWidth.toFloat()
        val image = document.units.getOrNull(block.start)?.image?.takeIf { block.end == block.start + 1 }
        val content = if (image != null) AnnotatedString(" ") else paragraphText(document, block, runs, typography)
        val style = paragraphTextStyle(frame, block, typography, direction)
        val constraints =
            Constraints(minWidth = available.toInt().coerceAtLeast(1), maxWidth = available.toInt().coerceAtLeast(1))
        val natural = measurer.measure(content, style, constraints = constraints)
        val layout = spacedParagraph(measurer, natural, block.properties.linePermille, density)
        val resource = image?.let { document.resources[it.resource] }
        val imageWidth = if (image != null) minOf(image.width * density.density, available) else 0f
        val imageHeight = if (resource != null) imageWidth * resource.height / resource.width else 0f
        val imageLeft = when (block.properties.alignment) {
            ParagraphAlignment.CENTER -> left + (available - imageWidth) / 2
            ParagraphAlignment.END -> if (direction == ParagraphDirection.RTL) left else left + available - imageWidth
            ParagraphAlignment.START -> if (direction == ParagraphDirection.RTL) left + available - imageWidth else left
        }
        AndroidParagraph(
            block, y, layout, if (image != null) imageLeft else left,
            if (image != null) imageHeight else layout.size.height.toFloat(), direction,
            lists.marker(block, direction, left, available, layout), image, imageWidth,
        ).also {
            y += it.height + ANDROID_PARAGRAPH_GAP_DP * density.density + block.properties.spaceAfter * density.density
        }
    }
    return AndroidEditorGeometry(paragraphs, y + inset, frame.documentRevision)
}

private fun spacedParagraph(
    measurer: TextMeasurer,
    natural: androidx.compose.ui.text.TextLayoutResult,
    permille: Int,
    density: Density,
): androidx.compose.ui.text.TextLayoutResult {
    if (permille == 0) return natural
    val advance = if (natural.lineCount > 1) {
        (1 until natural.lineCount).maxOf { natural.getLineBaseline(it) - natural.getLineBaseline(it - 1) }
    } else {
        natural.getLineBottom(0) - natural.getLineTop(0)
    }
    val input = natural.layoutInput
    val height = with(density) { (advance * permille / LINE_PERMILLE).toSp() }
    return measurer.measure(input.text, input.style.copy(lineHeight = height), constraints = input.constraints)
}

private fun paragraphText(
    document: Document,
    block: ParagraphBlock,
    runs: List<DisplayStyle>,
    typography: AndroidTypography,
): AnnotatedString = buildAnnotatedString {
    append(document.units.subList(block.start, block.end).joinToString("") { it.char.toString() })
    runs.forEach { run ->
        val start = maxOf(run.start, block.start) - block.start
        val end = minOf(run.end, block.end) - block.start
        if (start <
            end
        ) {
            addStyle(inlineSpan(run.style, typography, HEADING_SIZES[block.properties.heading]), start, end)
        }
    }
}

private fun inlineSpan(style: TextStyleFlags, typography: AndroidTypography, defaultSize: Int) = SpanStyle(
    fontFamily = typography.family(style.font),
    fontSize = (if (style.size == 0) defaultSize else style.size).sp,
    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
    color = if (style.color < 0) Color.Unspecified else Color(OPAQUE_ARGB or style.color.toLong()),
    textDecoration = TextDecoration.combine(
        listOfNotNull(
            if (style.underline || style.link != null) TextDecoration.Underline else null,
            if (style.strike) TextDecoration.LineThrough else null,
        ),
    ),
)

private fun paragraphTextStyle(
    frame: EditorFrame,
    block: ParagraphBlock,
    typography: AndroidTypography,
    direction: ParagraphDirection,
): TextStyle {
    val emptyCaret = block.start == block.end && frame.selection.collapsed && frame.selection.focus == block.start
    val seed = when {
        emptyCaret -> frame.typingStyle
        block.start < block.end -> frame.visible.units[block.start].style
        block.start == 0 -> frame.visible.startStyle
        else -> frame.visible.units[block.start - 1].paragraphStyle
    }
    val alignment = when (block.properties.alignment) {
        ParagraphAlignment.START -> TextAlign.Start
        ParagraphAlignment.CENTER -> TextAlign.Center
        ParagraphAlignment.END -> TextAlign.End
    }
    return TextStyle(
        fontFamily = typography.family(seed.font),
        fontSize = (
            if (seed.size != 0) {
                seed.size
            } else if (block.properties.heading > 0) {
                HEADING_SIZES[block.properties.heading]
            } else if (seed.size == 0) {
                ANDROID_FONT_SP
            } else {
                seed.size
            }
            ).sp,
        fontWeight = if (seed.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (seed.italic) FontStyle.Italic else FontStyle.Normal,
        textDirection = if (direction == ParagraphDirection.RTL) TextDirection.Rtl else TextDirection.Ltr,
        textAlign = alignment,
        color = ANDROID_TEXT_COLOR,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )
}

private const val HEADING_ONE_SP = 36
private const val HEADING_TWO_SP = 32
private const val HEADING_THREE_SP = 30
private const val HEADING_FOUR_SP = 28
private const val HEADING_FIVE_SP = 27
private val HEADING_SIZES = listOf(
    ANDROID_FONT_SP,
    HEADING_ONE_SP,
    HEADING_TWO_SP,
    HEADING_THREE_SP,
    HEADING_FOUR_SP,
    HEADING_FIVE_SP,
    ANDROID_FONT_SP,
)
private const val LINE_PERMILLE = 1000f
private const val BULLET_FALLBACK_DIVISOR = 4f
private const val MARKER_GAP_EM = .35f
private const val BULLET_RADIUS_EM = .12f
private const val MINIMUM_COLUMN_DP = 40f

private class AndroidListGeometry(
    private val request: AndroidLayoutRequest,
    measurer: TextMeasurer,
    blocks: List<ParagraphBlock>,
    directions: List<ParagraphDirection>,
    width: Float,
) {
    private val labels = request.frame.visible.listMarkers()
    private val layouts = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()
    private val widths = HashMap<String, Float>()
    private val ems = HashMap<String, Float>()
    private val columns = SharedListColumns(
        blocks,
        directions,
        width,
        widths,
        ems,
        MINIMUM_COLUMN_DP * request.density.density,
    )
    init {
        blocks.filter { it.properties.item.isNotEmpty() }.distinctBy { it.properties.item }.forEach { block ->
            val seed = block.properties.markerTypography
            val em = with(request.density) { seed.size.sp.toPx() }
            val label = labels.getValue(block.anchor)
            val layout = if (block.properties.list == ListKind.NUMBER) {
                measurer.measure(
                    AnnotatedString(label),
                    TextStyle(
                        fontFamily = request.typography.family(seed.font),
                        fontSize = seed.size.sp,
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Left,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                )
            } else {
                null
            }
            layout?.let { layouts[block.properties.item] = it }
            val group = columns.group(block.properties.item)
            widths[group] =
                maxOf(
                    widths[group] ?: 0f,
                    (layout?.size?.width?.toFloat() ?: em * BULLET_RADIUS_EM * 2) + em * MARKER_GAP_EM,
                )
            ems[group] = maxOf(ems[group] ?: 0f, em)
        }
    }
    fun column(item: String) = columns.column(item)
    fun marker(
        block: ParagraphBlock,
        direction: ParagraphDirection,
        left: Float,
        width: Float,
        text: androidx.compose.ui.text.TextLayoutResult,
    ): AndroidMarker? {
        if (labels[block.anchor] == null) return null
        val seed = block.properties.markerTypography
        val em = with(request.density) { seed.size.sp.toPx() }
        val label = layouts[block.properties.item]
        val radius = if (label == null) em * BULLET_RADIUS_EM else 0f
        val advance = label?.size?.width?.toFloat() ?: radius * 2
        val x = if (direction ==
            ParagraphDirection.RTL
        ) {
            left + width + em * MARKER_GAP_EM
        } else {
            left - em * MARKER_GAP_EM - advance
        }
        val baseline = text.getLineBaseline(0)
        val face = request.resolver.resolve(request.typography.family(seed.font)).value as Typeface
        val paint = Paint().apply {
            typeface = face
            textSize = em
        }
        val ink = NativeRect().also { paint.getTextBounds("x", 0, 1, it) }
        val center = baseline + if (!ink.isEmpty) (ink.top + ink.bottom) / 2f else -em / BULLET_FALLBACK_DIVISOR
        return AndroidMarker(
            label,
            x,
            if (label ==
                null
            ) {
                center - radius
            } else {
                baseline - label.getLineBaseline(0)
            },
            radius,
            center,
        )
    }
}
