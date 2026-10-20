package dev.shayxo.composedoc.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.AndroidTypography
import dev.shayxo.composedoc.layout.renderedStyleRuns
import dev.shayxo.composedoc.model.DisplayStyle
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.resolveParagraphDirection

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
    val paragraphs = document.paragraphs().map { block ->
        y += block.properties.spaceBefore * density.density
        fallback = resolveParagraphDirection(document.units.subList(block.start, block.end), block.properties, fallback)
        val layout = measurer.measure(
            paragraphText(document, block, runs, typography),
            paragraphTextStyle(frame, block, typography, fallback),
            constraints = Constraints(minWidth = textWidth, maxWidth = textWidth),
        )
        AndroidParagraph(block, y, layout, inset).also {
            y += layout.size.height + ANDROID_PARAGRAPH_GAP_DP * density.density +
                block.properties.spaceAfter * density.density
        }
    }
    return AndroidEditorGeometry(paragraphs, y + inset, frame.documentRevision)
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
        if (start < end) addStyle(inlineSpan(run.style, typography), start, end)
    }
}

private fun inlineSpan(style: TextStyleFlags, typography: AndroidTypography) = SpanStyle(
    fontFamily = typography.family(style.font),
    fontSize = if (style.size == 0) TextUnit.Unspecified else style.size.sp,
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
        fontSize = (if (seed.size == 0) ANDROID_FONT_SP else seed.size).sp,
        textDirection = if (direction == ParagraphDirection.RTL) TextDirection.Rtl else TextDirection.Ltr,
        textAlign = alignment,
        color = ANDROID_TEXT_COLOR,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )
}
