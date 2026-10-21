@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.toString
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.Geometry
import dev.shayxo.composedoc.layout.ListColumn
import dev.shayxo.composedoc.layout.ListMarkerLayout
import dev.shayxo.composedoc.layout.ListNominal
import dev.shayxo.composedoc.layout.ParagraphFragment
import dev.shayxo.composedoc.layout.ParagraphLayout
import dev.shayxo.composedoc.layout.ParagraphSpan
import dev.shayxo.composedoc.layout.StoredFonts
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.layout.attributedDisplay
import dev.shayxo.composedoc.layout.buildDocumentPagePlan
import dev.shayxo.composedoc.layout.paragraphAlignment
import dev.shayxo.composedoc.layout.paragraphAttributedDisplay
import dev.shayxo.composedoc.layout.partitionParagraphs
import dev.shayxo.composedoc.layout.referenceMeasure
import dev.shayxo.composedoc.layout.runningReserves
import dev.shayxo.composedoc.layout.storedSpan
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphBlock
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.RunningFlow
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.listMarkers
import dev.shayxo.composedoc.model.mmToDocument
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.resolveParagraphDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as ComposeColor

/** Horizontal room kept beside the page when fitting it into the viewport, in document units. */
private const val VIEWPORT_GUTTER = 24f

/** Smallest fit-to-width factor; pages never scale below this. */
private const val MIN_FIT = .1f

/** Deepest supported list nesting. */
private const val MAX_LIST_DEPTH = 8

/** Bullet advance, marker gap and nesting step, as fractions of the marker em. */
private const val BULLET_ADVANCE_EM = .24f
private const val MARKER_GAP_EM = .35f

/** Bullet radius and fallback x-height, as fractions of the marker em. */
private const val BULLET_RADIUS_EM = .12f
private const val FALLBACK_X_HEIGHT_EM = .5f

/** Line spacing is stored in thousandths of the natural line advance. */
private const val PERMILLE = 1000f

/** Heading font sizes in sp, for heading levels 1 through 6. */
private const val HEADING_1_SP = 36
private const val HEADING_2_SP = 32
private const val HEADING_3_SP = 30
private const val HEADING_4_SP = 28
private const val HEADING_5_SP = 27
private const val HEADING_6_SP = 26

/** Inset of the layout-error message from the canvas corner. */
private const val ERROR_MESSAGE_INSET = 8f

/** Palette (ARGB). */
private const val INK_COLOR = 0xff202b3c
private const val CANVAS_BACKGROUND_COLOR = 0xffeef1f5
private const val PAGE_SHADOW_COLOR = 0xffe1e6ed
private const val FOCUSED_SELECTION_COLOR = 0xffc8dcff
private const val UNFOCUSED_SELECTION_COLOR = 0xffdce3ed
private const val ACTIVE_FIND_COLOR = 0xffffbf69
private const val FIND_COLOR = 0xffffeb99
private const val IMAGE_PLACEHOLDER_COLOR = 0xffdde6ed
private const val IMAGE_SELECTION_COLOR = 0xff174ea6
private const val PLACEHOLDER_TEXT_COLOR = 0xff9aa4b2
private const val COMPOSITION_UNDERLINE_COLOR = 0xff3467d5
private const val CARET_COLOR = 0xff2559ba
private const val SCROLLBAR_COLOR = 0xffb1bac8

/** The canvas renders at density 1, so a dp is one document unit. */
private fun Dp.toDocumentPx(): Float = value

@Composable
// Reason: public API (see apiCheck) and the Compose convention for @Composable Unit functions.
@Suppress("ktlint:standard:function-naming", "FunctionNaming") // Compose UI functions use PascalCase.
fun DocumentCanvas(state: ViewState) {
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val renderZoom = state.zoom
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1f),
    ) { zoomedDocumentCanvas(state, renderZoom, baseDensity.density) }
}

@Composable
private fun zoomedDocumentCanvas(state: ViewState, renderZoom: Float, deviceDensity: Float) {
    val measurer = rememberTextMeasurer()
    val fontFamilyResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val densityObj = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    Canvas(Modifier.fillMaxSize()) {
        if (renderZoom != state.zoom)return@Canvas
        val physicalSize = size
        val pageWidthUnits = mmToDocument(state.frame.visible.pageSettings.widthMm)
        val fit = (physicalSize.width / deviceDensity - VIEWPORT_GUTTER) / pageWidthUnits
        val appliedZoom = renderZoom * fit.coerceIn(MIN_FIT, 1f)
        val outputScale = deviceDensity * appliedZoom
        withTransform({ scale(outputScale, outputScale, Offset.Zero) }) {
            val previousSize = drawContext.size
            drawContext.size =
                androidx.compose.ui.geometry.Size(physicalSize.width / outputScale, physicalSize.height / outputScale)
            try {
                val scale = 1f / appliedZoom
                val canvasW = size.width
                val canvasH = size.height
                val profile = state.typography
                val style = TextStyle(
                    color = ComposeColor(INK_COLOR),
                    fontFamily = profile.family,
                    fontSize = profile.sizeSp.sp,
                    lineHeight = if (profile.id == "system") 30.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    textDirection = TextDirection.Content,
                )
                val env =
                    MeasureEnvironment(state, measurer, profile, style, densityObj, renderZoom, fit, canvasW, canvasH)
                drawDocument(env, scale, fontFamilyResolver, layoutDirection)
            } catch (failure: IllegalArgumentException) {
                state.geometry = null
                state.layoutError = failure.message ?: "Unsupported page layout"
                val message = measurer.measure(
                    AnnotatedString(
                        "Layout needs correction: ${state.layoutError}. Use Undo or Page / running-content settings.",
                    ),
                    style = TextStyle(color = ComposeColor.Red, fontSize = 14.sp),
                    constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1)),
                )
                drawText(message, topLeft = Offset(ERROR_MESSAGE_INSET, ERROR_MESSAGE_INSET))
            } finally {
                drawContext.size = previousSize
            }
        }
    }
}

/** Everything a measurement pass captures from the composition and the current draw. */
private data class MeasureEnvironment(
    val state: ViewState,
    val measurer: TextMeasurer,
    val profile: TypographyProfile,
    val style: TextStyle,
    val densityObj: androidx.compose.ui.unit.Density,
    val renderZoom: Float,
    val fit: Float,
    val canvasW: Float,
    val canvasH: Float,
)

private fun MeasureEnvironment.measureRunning(flow: RunningFlow, text: String, textWidth: Int): TextLayoutResult =
    synchronized(measurer) {
        val style = TextStyle(
            color = ComposeColor(INK_COLOR),
            fontFamily = StoredFonts.profile(flow.style.font)?.family ?: profile.family,
            fontSize = (if (flow.style.size == 0)18 else flow.style.size).sp,
            textDirection = when (flow.direction) {
                ParagraphDirection.RTL -> TextDirection.Rtl
                ParagraphDirection.LTR -> TextDirection.Ltr
                ParagraphDirection.AUTO -> TextDirection.Content
            },
            textAlign = paragraphAlignment(
                ParagraphProperties(alignment = flow.alignment),
                flow.direction != ParagraphDirection.LTR,
            ),
        )
        measurer.measure(
            AnnotatedString(text, spanStyles = listOf(AnnotatedString.Range(storedSpan(flow.style), 0, text.length))),
            style = style,
            constraints = Constraints(minWidth = textWidth, maxWidth = textWidth),
        )
    }

private fun MeasureEnvironment.measureWith(frame: EditorFrame, awtW: Int, awtH: Int): Geometry {
    val widthFit = if (awtW > 0)(awtW - VIEWPORT_GUTTER) / mmToDocument(frame.visible.pageSettings.widthMm) else fit
    val actualZoom = renderZoom * widthFit.coerceIn(MIN_FIT, 1f)
    val scale = 1f / actualZoom
    val w = if (awtW > 0) awtW * scale else canvasW
    val pageWidth = mmToDocument(frame.visible.pageSettings.widthMm)
    val pageLeft = (w - pageWidth) / 2
    val padding = pageWidth * frame.visible.pageSettings.marginMm / frame.visible.pageSettings.widthMm
    val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
    val measureRunning = { flow: RunningFlow, text: String -> measureRunning(flow, text, textWidth) }
    val runningSize = runningReserves(frame.visible, measureRunning)
    val spans = partitionParagraphs(frame.visible)
    val lists = ListGeometry(this, frame.visible, spans, textWidth)
    val paperHeight = pageWidth * frame.visible.pageSettings.heightMm / frame.visible.pageSettings.widthMm
    val bodyHeight = (paperHeight - padding * 2 - runningSize.sum()).coerceAtLeast(1f)
    val layouter = ParagraphLayouter(this, frame.visible, lists, textWidth, bodyHeight)
    val paragraphs = layouter.layoutAll(spans)
    val pageGap = 24.dp.toDocumentPx()
    val capturedPlan =
        buildDocumentPagePlan(frame.visible, frame.documentRevision, profile.key, paragraphs, pageGap, measureRunning)
    val totalHeight = if (state.paged) {
        capturedPlan.pageCount * paperHeight + (capturedPlan.pageCount - 1) * pageGap - 2 * padding
    } else {
        layouter.currentTop
    }
    return Geometry(
        paragraphs = paragraphs,
        totalHeight = totalHeight,
        x = pageLeft + padding,
        y = 28.dp.toDocumentPx() + padding - state.scroll * scale,
        scale = scale,
        revision = frame.documentRevision,
        textWidth = textWidth,
        scroll = state.scroll,
        hostWidth = if (awtW > 0) awtW else (canvasW / scale).roundToInt(),
        hostHeight = if (awtH > 0) awtH else (canvasH / scale).roundToInt(),
        typographyKey = profile.key + ":zoom=$renderZoom" + (if (state.paged) ":paged" else ":continuous"),
        fragments = if (state.paged) {
            capturedPlan.fragments
        } else {
            paragraphs.map { ParagraphFragment(it.index, 0, it.lineCount - 1, 0f, it.height, it.top, 0) }
        },
        pageHeight = if (state.paged) paperHeight else 0f,
        pageGap = if (state.paged) pageGap else 0f,
        pageCount = if (state.paged)capturedPlan.pageCount else 1, documentPagePlan = capturedPlan,
    )
}

/** List columns and marker layouts for one measurement of [document]. */
private class ListGeometry(
    private val env: MeasureEnvironment,
    document: Document,
    spans: List<ParagraphSpan>,
    private val textWidth: Int,
) {
    val blocks = document.paragraphs()
    private val directions = ArrayList<ResolvedTextDirection>()
    init {
        var inherited = ParagraphDirection.LTR
        for (span in spans) {
            inherited = resolveParagraphDirection(span.units, span.properties, inherited)
            directions += if (inherited == ParagraphDirection.RTL) {
                ResolvedTextDirection.Rtl
            } else {
                ResolvedTextDirection.Ltr
            }
        }
    }
    val markers = document.listMarkers()
    private val items = blocks.filter { it.properties.item.isNotEmpty() }.distinctBy { it.properties.item }
    private val itemById = items.associateBy { it.properties.item }
    private val ancestry = items.associate { item -> item.properties.item to ancestorChain(item.properties.item) }
    private val nominal = items.associate {
        it.properties.item to ListNominal(it.properties.markerTypography.font, it.properties.markerTypography.size)
    }
    private val markerLayouts = HashMap<String, TextLayoutResult>()
    private val markerKeys = HashMap<String, String>()
    private val groupWidths = HashMap<String, Float>()
    private val groupEm = HashMap<String, Float>()
    init {
        for (block in items) {
            measureMarker(block)
        }
    }

    private fun ancestorChain(start: String): List<String> {
        val chain = ArrayList<String>()
        var id = start
        while (id.isNotEmpty()) {
            require(id !in chain && chain.size < MAX_LIST_DEPTH) { "Invalid list ancestry" }
            chain += id
            id = itemById.getValue(id).properties.parent
        }
        return chain
    }

    private fun rootRtl(item: String) =
        directions[itemById.getValue(ancestry.getValue(item).last()).index] == ResolvedTextDirection.Rtl

    private fun group(item: String): String {
        val p = itemById.getValue(item).properties
        return "${p.parent}:${rootRtl(item)}"
    }

    private fun measureMarker(block: ParagraphBlock) {
        val item = block.properties.item
        val n = nominal.getValue(item)
        val family = StoredFonts.profile(n.font)?.family ?: env.profile.family
        val em = n.size * env.densityObj.density * env.densityObj.fontScale
        val label = markers[block.anchor] ?: return
        if (block.properties.list == ListKind.NUMBER) {
            markerLayouts[item] = synchronized(env.measurer) {
                env.measurer.measure(
                    AnnotatedString(label),
                    style = env.style.copy(
                        fontFamily = family,
                        fontSize = n.size.sp,
                        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Left,
                    ),
                )
            }
        }
        markerKeys[item] = StoredFonts.profile(n.font)?.key ?: env.profile.key
        val advance = markerLayouts[item]?.size?.width?.toFloat() ?: em * BULLET_ADVANCE_EM
        val key = group(item)
        groupWidths[key] = maxOf(groupWidths[key] ?: 0f, advance + em * MARKER_GAP_EM)
        groupEm[key] = maxOf(groupEm[key] ?: 0f, em)
    }

    private val sharedColumns by lazy {
        SharedListColumns(
            blocks,
            directions.map {
                if (it ==
                    ResolvedTextDirection.Rtl
                ) {
                    ParagraphDirection.RTL
                } else {
                    ParagraphDirection.LTR
                }
            },
            textWidth.toFloat(),
            groupWidths,
            groupEm,
            40.dp.toDocumentPx(),
        )
    }
    fun listColumn(item: String): ListColumn? = sharedColumns.column(item)?.let {
        ListColumn(it.left, it.width, it.rootRtl, it.gutter)
    }

    fun markerFor(
        span: ParagraphSpan,
        dir: ResolvedTextDirection,
        column: ListColumn?,
        layout: TextLayoutResult,
    ): ListMarkerLayout? = markers[blocks[span.index].anchor]?.let { text ->
        val n = nominal.getValue(span.properties.item)
        val em = n.size * env.densityObj.density * env.densityObj.fontScale
        val label = markerLayouts[span.properties.item]
        val radius = if (label == null)em * BULLET_RADIUS_EM else 0f
        val width = label?.size?.width?.toFloat() ?: radius * 2
        val gap = em * MARKER_GAP_EM
        val markerRtl = dir == ResolvedTextDirection.Rtl
        val left = if (markerRtl) {
            checkNotNull(column).left + column.width + gap
        } else {
            checkNotNull(column).left - gap - width
        }
        val baseline = layout.getLineBaseline(0)
        // Circle optical center uses measured x ink, not the paragraph box.
        val face = org.jetbrains.skia.FontMgr.default.matchFamilyStyle("Arial", org.jetbrains.skia.FontStyle.NORMAL)
        val xHeight = org.jetbrains.skia.Font(face, em).use {
            it.metrics.xHeight.takeIf { x -> x > 0 } ?: em * FALLBACK_X_HEIGHT_EM
        }
        face?.close()
        val opticalCenter = baseline - xHeight / 2
        val top = if (label == null)opticalCenter - radius else baseline - label.getLineBaseline(0)
        ListMarkerLayout(
            text,
            label,
            left,
            top,
            width,
            label?.size?.height?.toFloat() ?: radius * 2,
            baseline,
            radius,
            opticalCenter,
            n.size,
            markerKeys.getValue(span.properties.item),
            markerRtl,
        )
    }
}

private fun headingStyle(base: TextStyle, heading: Int): TextStyle = if (heading > 0) {
    val sizes = listOf(0, HEADING_1_SP, HEADING_2_SP, HEADING_3_SP, HEADING_4_SP, HEADING_5_SP, HEADING_6_SP)
    base.copy(fontWeight = FontWeight.Bold, fontSize = sizes[heading].sp)
} else {
    base
}

private fun needsNaturalLineHeight(span: ParagraphSpan, emptySeed: TextStyleFlags): Boolean =
    span.properties.heading > 0 ||
        span.units.any { it.style.size != 0 || it.style.font != StoredFont.DEFAULT } ||
        (span.units.isEmpty() && (emptySeed.size != 0 || emptySeed.font != StoredFont.DEFAULT))

private fun imageLeft(
    alignment: ParagraphAlignment,
    dir: ResolvedTextDirection,
    contentLeft: Float,
    contentRight: Float,
    imageWidth: Float,
): Float = when (alignment) {
    ParagraphAlignment.CENTER -> (contentLeft + contentRight - imageWidth) / 2
    ParagraphAlignment.START -> if (dir == ResolvedTextDirection.Rtl)contentRight - imageWidth else contentLeft
    ParagraphAlignment.END -> if (dir == ResolvedTextDirection.Rtl)contentLeft else contentRight - imageWidth
}

/** Lays out paragraphs top to bottom, tracking the running top offset and line index. */
private class ParagraphLayouter(
    private val env: MeasureEnvironment,
    private val document: Document,
    private val lists: ListGeometry,
    private val textWidth: Int,
    private val bodyHeight: Float,
) {
    private val defaultLineHeight = synchronized(env.measurer) {
        env.measurer.measure(
            AnnotatedString(" "),
            style = env.style,
            constraints = Constraints(minWidth = textWidth, maxWidth = textWidth),
        ).size.height.toFloat()
    }
    var currentTop = 0f
        private set
    private var startLineIndex = 0

    fun layoutAll(spans: List<ParagraphSpan>): ArrayList<ParagraphLayout> {
        var fallback = ResolvedTextDirection.Ltr
        val paragraphs = ArrayList<ParagraphLayout>(spans.size)
        for (span in spans) {
            val fallbackDirection =
                if (fallback == ResolvedTextDirection.Rtl) ParagraphDirection.RTL else ParagraphDirection.LTR
            val resolved = resolveParagraphDirection(span.units, span.properties, fallbackDirection)
            val dir = if (resolved == ParagraphDirection.RTL) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr
            fallback = dir
            paragraphs.add(layoutParagraph(span, dir))
        }
        return paragraphs
    }

    private fun baseStyle(span: ParagraphSpan, emptySeed: TextStyleFlags): TextStyle {
        val headingBase = headingStyle(env.style, span.properties.heading)
        return if (span.units.isEmpty()) headingBase.merge(storedSpan(emptySeed)) else headingBase
    }

    private fun paragraphStyle(
        paragraphBase: TextStyle,
        span: ParagraphSpan,
        dir: ResolvedTextDirection,
        needsNatural: Boolean,
    ): TextStyle = paragraphBase.copy(
        lineHeight = if (needsNatural) androidx.compose.ui.unit.TextUnit.Unspecified else paragraphBase.lineHeight,
        textDirection = if (dir == ResolvedTextDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr,
        textIndent = androidx.compose.ui.text.style.TextIndent.None,
        textAlign = paragraphAlignment(span.properties, dir == ResolvedTextDirection.Rtl),
    )

    private fun measureParagraph(
        annotated: AnnotatedString,
        paraStyle: TextStyle,
        linePermille: Int,
        availableWidth: Int,
    ): TextLayoutResult {
        val natural = synchronized(env.measurer) {
            env.measurer.measure(
                annotated,
                style = paraStyle,
                constraints = Constraints(minWidth = availableWidth, maxWidth = availableWidth),
            )
        }
        val naturalAdvance =
            (0 until natural.lineCount).maxOf { line -> natural.getLineBottom(line) - natural.getLineTop(line) }
        return if (linePermille == 0) {
            natural
        } else {
            synchronized(env.measurer) {
                val density = env.densityObj
                val lineHeight = naturalAdvance / density.density / density.fontScale * linePermille / PERMILLE
                env.measurer.measure(
                    annotated,
                    style = paraStyle.copy(lineHeight = lineHeight.sp),
                    constraints = Constraints(minWidth = availableWidth, maxWidth = availableWidth),
                )
            }
        }
    }

    private fun layoutParagraph(span: ParagraphSpan, dir: ResolvedTextDirection): ParagraphLayout {
        val image = span.units.singleOrNull()?.image
        val imageResource = image?.let { document.resources[it.resource] }
        val annotated = if (image != null) AnnotatedString(" ") else paragraphAttributedDisplay(span.units)
        val emptySeed =
            if (span.startOffset == 0) document.startStyle else document.units[span.startOffset - 1].paragraphStyle
        val paragraphBase = baseStyle(span, emptySeed)
        val needsNatural = needsNaturalLineHeight(span, emptySeed)
        val column = lists.listColumn(span.properties.item)
        val availableWidth = (column?.width?.roundToInt() ?: textWidth).coerceAtLeast(1)
        val paraStyle = paragraphStyle(paragraphBase, span, dir, needsNatural)
        val layout = measureParagraph(annotated, paraStyle, span.properties.linePermille, availableWidth)
        currentTop += span.properties.spaceBefore.dp.toDocumentPx()
        val requestedWidth = image?.width?.dp?.toDocumentPx() ?: 0f
        val imageContentWidth = column?.width ?: textWidth.toFloat()
        val fit = if (imageResource != null) {
            minOf(
                1f,
                imageContentWidth / requestedWidth,
                bodyHeight / (requestedWidth * imageResource.height / imageResource.width),
            )
        } else {
            1f
        }
        val imageWidth = requestedWidth * fit
        val contentLeft = column?.left ?: 0f
        val contentRight = contentLeft + imageContentWidth
        val imageLeft = imageLeft(span.properties.alignment, dir, contentLeft, contentRight, imageWidth)
        val pHeight = if (imageResource != null) {
            imageWidth * imageResource.height / imageResource.width
        } else {
            max(layout.size.height.toFloat(), if (needsNatural) 1f else defaultLineHeight)
        }
        val rawText = span.units.joinToString("") { it.char.toString() }
        val marker = lists.markerFor(span, dir, column, layout)
        val paragraph = ParagraphLayout(
            index = span.index,
            startOffset = span.startOffset,
            endOffset = span.endOffset,
            hasBreak = span.hasBreak,
            text = rawText,
            annotatedString = annotated,
            baseDirection = dir,
            layout = layout,
            top = currentTop,
            height = pHeight,
            startLineIndex = startLineIndex, image = image, imageWidth = imageWidth, imageLeft = imageLeft,
            contentLeft = contentLeft, contentWidth = imageContentWidth, listColumn = column, marker = marker,
        )
        currentTop += pHeight + span.properties.spaceAfter.dp.toDocumentPx()
        startLineIndex += maxOf(1, layout.lineCount)
        return paragraph
    }
}

private fun DrawScope.drawDocument(
    env: MeasureEnvironment,
    scale: Float,
    fontFamilyResolver: FontFamily.Resolver,
    layoutDirection: LayoutDirection,
) {
    val state = env.state
    fun measure(frame: EditorFrame): Geometry = env.measureWith(frame, state.hostWidth, state.hostHeight)
    if (env.renderZoom != state.zoom || env.profile.key != state.typography.key)return
    state.measure = ::measure
    state.measureAtSize = { frame, awtW, awtH -> env.measureWith(frame, awtW.roundToInt(), awtH.roundToInt()) }
    val frame = state.frame
    val pageWidth = mmToDocument(frame.visible.pageSettings.widthMm)
    val pageLeft = (size.width - pageWidth) / 2
    val padding = pageWidth * frame.visible.pageSettings.marginMm / frame.visible.pageSettings.widthMm
    val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
    installDocumentMeasurers(env, textWidth, fontFamilyResolver, layoutDirection)
    var geo = measure(frame)
    state.layoutError = null
    val contentHeight = geo.totalHeight + padding * 2 + 56.dp.toDocumentPx()
    val maxScroll = max(0f, (contentHeight - size.height) / scale)
    var scroll = state.scroll.coerceIn(0f, maxScroll)
    // Reflow/view switching can shrink the scroll range. Caret geometry must use
    // that clamped origin before calculating a further reveal adjustment.
    if (scroll != state.scroll) {
        state.scroll = scroll
        geo = measure(frame)
    }
    val findVisible = frame.marked == null && state.findDocument === frame.committed
    val target = revealTarget(state, geo, frame, findVisible)
    if (target != null) {
        scroll = scrollToReveal(target, scroll, scale)
        state.ensureCaret = false
    }
    scroll = scroll.coerceIn(0f, maxScroll)
    if (scroll != state.scroll) {
        state.scroll = scroll
        geo = measure(frame)
    }
    state.geometry = geo
    drawRect(ComposeColor(CANVAS_BACKGROUND_COLOR))
    val pageY = 28.dp.toDocumentPx() - state.scroll * scale
    val pageHeight = if (state.paged) {
        geo.pageHeight
    } else {
        max(size.height - 56.dp.toDocumentPx(), geo.totalHeight + padding * 2)
    }
    drawContent(PageFrame(state, geo, pageLeft, pageWidth, pageY, pageHeight), frame, findVisible, env)
    if (maxScroll > 0) drawScrollbar(state, contentHeight, maxScroll)
}

/** Publishes the single-paragraph and reference measurers that tests and probes use. */
private fun installDocumentMeasurers(
    env: MeasureEnvironment,
    textWidth: Int,
    fontFamilyResolver: FontFamily.Resolver,
    layoutDirection: LayoutDirection,
) {
    env.state.singleMeasure = { doc ->
        val annotated = attributedDisplay(doc)
        synchronized(env.measurer) {
            env.measurer.measure(
                annotated,
                style = env.style,
                constraints = Constraints(minWidth = textWidth, maxWidth = textWidth),
            )
        }
    }
    env.state.referenceMeasure = { doc ->
        val uncachedMeasurer = TextMeasurer(fontFamilyResolver, env.densityObj, layoutDirection, cacheSize = 0)
        referenceMeasure(doc, textWidth, uncachedMeasurer, env.style)
    }
}

/** The rectangle to scroll into view: a pending find reveal, else the caret when requested. */
private fun DrawScope.revealTarget(
    state: ViewState,
    geo: Geometry,
    frame: EditorFrame,
    findVisible: Boolean,
): androidx.compose.ui.geometry.Rect? {
    val reveal = state.findReveal.takeIf { findVisible }
    state.findReveal = null
    return when {
        reveal != null -> {
            val start = geo.caret(reveal.start)
            val end = geo.caret(reveal.end)
            val available = size.height - 60.dp.toDocumentPx()
            if (end.bottom - start.top <= available) {
                androidx.compose.ui.geometry.Rect(start.left, start.top, start.right, end.bottom)
            } else {
                start
            }
        }
        state.ensureCaret -> geo.caret(frame.selection.focus, frame.selection.primary, frame.selection.wrapAffinity)
        else -> null
    }
}

private fun DrawScope.scrollToReveal(target: androidx.compose.ui.geometry.Rect, scroll: Float, scale: Float): Float {
    var result = scroll
    if (target.bottom > size.height - 30.dp.toDocumentPx()) {
        result += (target.bottom - size.height + 30.dp.toDocumentPx()) / scale
    }
    if (target.top < 24.dp.toDocumentPx()) result -= (24.dp.toDocumentPx() - target.top) / scale
    return result
}

private fun DrawScope.drawContent(page: PageFrame, frame: EditorFrame, findVisible: Boolean, env: MeasureEnvironment) {
    val state = page.state
    val geo = page.geo
    drawPages(page)
    drawSelection(page, frame)
    if (findVisible) drawFindMatches(page)
    for (fragment in geo.fragments) {
        drawFragment(page, frame, fragment)
    }
    if (state.paged) drawRunning(page)
    if (frame.visible.text.isEmpty() && frame.marked == null) {
        val placeholder = env.measurer.measure(
            AnnotatedString("Start typing…"),
            style = env.style.copy(color = ComposeColor(PLACEHOLDER_TEXT_COLOR)),
        )
        drawText(placeholder, topLeft = Offset(geo.x, geo.y))
    }
    frame.marked?.takeUnless { it.isEmpty() }?.let { range ->
        for (p in geo.paragraphs) {
            drawCompositionUnderline(geo, p, range)
        }
    }
    val s = frame.selection
    if (state.focused && state.blink && s.collapsed) {
        val caret = geo.caret(s.focus, s.primary, s.wrapAffinity)
        drawRect(ComposeColor(CARET_COLOR), caret.topLeft, caret.size)
    }
}

/** Page placement for one draw pass. */
private class PageFrame(
    val state: ViewState,
    val geo: Geometry,
    val pageLeft: Float,
    val pageWidth: Float,
    val pageY: Float,
    val pageHeight: Float,
)

private fun DrawScope.clippedFragment(page: PageFrame, fragment: ParagraphFragment, block: () -> Unit) {
    if (page.state.paged) {
        val geo = page.geo
        val pageTop = page.pageY + fragment.page * (page.pageHeight + geo.pageGap)
        clipRect(
            page.pageLeft,
            max(pageTop, geo.y + fragment.top - 2.dp.toDocumentPx()),
            page.pageLeft + page.pageWidth,
            min(
                page.pageY + fragment.page * (page.pageHeight + geo.pageGap) + page.pageHeight,
                geo.y + fragment.top + fragment.height + 2.dp.toDocumentPx(),
            ),
        ) { block() }
    } else {
        block()
    }
}

private fun DrawScope.drawFragmentPath(
    page: PageFrame,
    fragment: ParagraphFragment,
    p: ParagraphLayout,
    path: Path,
    color: ComposeColor,
) {
    val geo = page.geo
    clippedFragment(page, fragment) {
        withTransform({ translate(geo.x + p.contentLeft, geo.y + fragment.top - fragment.sourceTop) }) {
            drawPath(path, color)
        }
    }
}

private fun DrawScope.drawPages(page: PageFrame) {
    val geo = page.geo
    for (index in 0 until geo.pageCount) {
        val top = page.pageY + index * (page.pageHeight + geo.pageGap)
        drawRect(
            ComposeColor(PAGE_SHADOW_COLOR),
            Offset(page.pageLeft + 2.dp.toDocumentPx(), top + 3.dp.toDocumentPx()),
            androidx.compose.ui.geometry.Size(page.pageWidth, page.pageHeight),
        )
        drawRect(
            ComposeColor.White,
            Offset(page.pageLeft, top),
            androidx.compose.ui.geometry.Size(page.pageWidth, page.pageHeight),
        )
    }
}

private fun DrawScope.drawSelection(page: PageFrame, frame: EditorFrame) {
    val geo = page.geo
    val s = frame.selection
    if (s.collapsed) return
    val selColor = ComposeColor(if (page.state.focused) FOCUSED_SELECTION_COLOR else UNFOCUSED_SELECTION_COLOR)
    for (fragment in geo.fragments) {
        val p = geo.paragraphs[fragment.paragraphIndex]
        val selStart = max(s.start, p.startOffset)
        val selEnd = min(s.end, p.endOffset)
        if (selStart < selEnd && p.image == null) {
            val localA = p.toLocalOffset(selStart)
            val localB = p.toLocalOffset(selEnd)
            val path = p.layout.getPathForRange(localA, localB)
            drawFragmentPath(page, fragment, p, path, selColor)
        }
    }
    for (r in geo.breakAndEmptySelectionRects(s)) {
        drawRect(selColor, r.topLeft, r.size)
    }
}

private fun DrawScope.drawFindMatches(page: PageFrame) {
    val geo = page.geo
    for (fragment in geo.fragments) {
        if (geo.y + fragment.top > size.height || geo.y + fragment.top + fragment.height < 0) continue
        val p = geo.paragraphs[fragment.paragraphIndex]
        drawParagraphMatches(page, fragment, p)
    }
}

private fun DrawScope.drawParagraphMatches(page: PageFrame, fragment: ParagraphFragment, p: ParagraphLayout) {
    val state = page.state
    for ((index, match) in state.findMatches.withIndex()) {
        val a = p.toLocalOffset(match.start)
        val b = p.toLocalOffset(match.end)
        if (match.start >= p.startOffset && match.end <= p.endOffset && a < b) {
            val color = ComposeColor(if (index == state.findActive) ACTIVE_FIND_COLOR else FIND_COLOR)
            drawFragmentPath(page, fragment, p, p.layout.getPathForRange(a, b), color)
        }
    }
}

private fun DrawScope.drawFragment(page: PageFrame, frame: EditorFrame, fragment: ParagraphFragment) {
    val geo = page.geo
    val p = geo.paragraphs[fragment.paragraphIndex]
    clippedFragment(page, fragment) {
        val imageBlock = p.image
        if (imageBlock != null) {
            drawImageBlock(geo, frame, fragment, p, imageBlock)
        } else {
            synchronized(p.layout.multiParagraph) {
                drawText(p.layout, topLeft = Offset(geo.x + p.contentLeft, geo.y + fragment.top - fragment.sourceTop))
            }
        }
        if (fragment.firstLine == 0) {
            p.marker?.let { marker -> drawMarker(geo, fragment, marker) }
        }
    }
}

private fun DrawScope.drawImageBlock(
    geo: Geometry,
    frame: EditorFrame,
    fragment: ParagraphFragment,
    p: ParagraphLayout,
    imageBlock: ImageBlock,
) {
    val bitmap = ImageResources.cached(imageBlock.resource)
    val left = geo.x + p.imageLeft
    if (bitmap != null) {
        drawImage(
            bitmap,
            dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), (geo.y + fragment.top).roundToInt()),
            dstSize = androidx.compose.ui.unit.IntSize(
                p.imageWidth.roundToInt().coerceAtLeast(1),
                p.height.roundToInt().coerceAtLeast(1),
            ),
        )
    } else {
        drawRect(
            ComposeColor(IMAGE_PLACEHOLDER_COLOR),
            Offset(left, geo.y + fragment.top),
            androidx.compose.ui.geometry.Size(p.imageWidth, p.height),
        )
    }
    if (frame.selection.start <= p.startOffset && frame.selection.end > p.startOffset) {
        drawRect(
            ComposeColor(IMAGE_SELECTION_COLOR),
            Offset(left, geo.y + fragment.top),
            androidx.compose.ui.geometry.Size(p.imageWidth, p.height),
            style = Stroke(2.dp.toDocumentPx()),
        )
    }
}

private fun DrawScope.drawMarker(geo: Geometry, fragment: ParagraphFragment, marker: ListMarkerLayout) {
    // Marker metrics, like text/caret/selection metrics, use the original paragraph origin.
    // Subtract removed positive leading. A negative rounded first-line top denotes ink/rounding
    // bleed, not leading: retain the approved paragraph-origin marker alignment in that case.
    val paragraphY = geo.y + fragment.top - fragment.sourceTop.coerceAtLeast(0f)
    val markerLayout = marker.layout
    if (markerLayout != null) {
        synchronized(markerLayout.multiParagraph) {
            drawText(markerLayout, topLeft = Offset(geo.x + marker.left, paragraphY + marker.top))
        }
    } else {
        drawCircle(
            ComposeColor(INK_COLOR),
            marker.bulletRadius,
            Offset(geo.x + marker.left + marker.bulletRadius, paragraphY + marker.opticalCenter),
        )
    }
}

private fun DrawScope.drawRunning(page: PageFrame) {
    val geo = page.geo
    geo.documentPagePlan?.running?.forEach { running ->
        synchronized(running.layout.multiParagraph) {
            drawText(
                running.layout,
                topLeft = Offset(
                    page.pageLeft + running.left,
                    page.pageY + running.page * (page.pageHeight + geo.pageGap) + running.top,
                ),
            )
        }
    }
}

private fun DrawScope.drawCompositionUnderline(geo: Geometry, p: ParagraphLayout, range: IntRange) {
    val markA = max(range.first, p.startOffset)
    val markB = min(range.last + 1, p.endOffset)
    if (markA >= markB) return
    val localA = p.toLocalOffset(markA)
    val localB = p.toLocalOffset(markB)
    val first = p.layout.getLineForOffset(localA)
    val last = p.layout.getLineForOffset(localB)
    for (line in first..last) {
        val a = max(localA, p.layout.getLineStart(line))
        val b = min(localB, p.layout.getLineEnd(line, true))
        if (a <= b) {
            val x1 = p.layout.getHorizontalPosition(a, true)
            val x2 = p.layout.getHorizontalPosition(b, true)
            val lineY = geo.y + geo.paragraphTop(p, line) + p.layout.getLineBottom(line) - 2.dp.toDocumentPx()
            drawLine(
                ComposeColor(COMPOSITION_UNDERLINE_COLOR),
                Offset(geo.x + p.contentLeft + min(x1, x2), lineY),
                Offset(geo.x + p.contentLeft + max(x1, x2), lineY),
                1.5.dp.toDocumentPx(),
            )
        }
    }
}

private fun DrawScope.drawScrollbar(state: ViewState, contentHeight: Float, maxScroll: Float) {
    val h = max(32.dp.toDocumentPx(), size.height * size.height / contentHeight)
    val y = (size.height - h) * state.scroll / maxScroll
    drawRoundRect(
        ComposeColor(SCROLLBAR_COLOR),
        Offset(size.width - 7.dp.toDocumentPx(), y),
        androidx.compose.ui.geometry.Size(4.dp.toDocumentPx(), h),
        androidx.compose.ui.geometry.CornerRadius(2.dp.toDocumentPx()),
    )
}
