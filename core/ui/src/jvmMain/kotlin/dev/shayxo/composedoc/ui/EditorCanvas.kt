@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import javax.accessibility.*
import javax.swing.*
import kotlin.math.*
import androidx.compose.ui.graphics.Color as ComposeColor

@Composable
@Suppress("ktlint:standard:function-naming") // Compose UI functions use PascalCase.
fun DocumentCanvas(state: ViewState) {
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val renderZoom = state.zoom
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1f)) { ZoomedDocumentCanvas(state, renderZoom, baseDensity.density) }
}

@Composable
@Suppress("ktlint:standard:function-naming") // Compose UI functions use PascalCase.
private fun ZoomedDocumentCanvas(state: ViewState, renderZoom: Float, deviceDensity: Float) {
    val measurer = rememberTextMeasurer()
    val fontFamilyResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val densityObj = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    Canvas(Modifier.fillMaxSize()) {
        if (renderZoom != state.zoom)return@Canvas
        val physicalSize = size
        val fit = (physicalSize.width / deviceDensity - 24f) / mmToDocument(state.frame.visible.pageSettings.widthMm)
        val appliedZoom = renderZoom * fit.coerceIn(.1f, 1f)
        val outputScale = deviceDensity * appliedZoom
        withTransform({ scale(outputScale, outputScale, Offset.Zero) }) {
            val previousSize = drawContext.size
            drawContext.size = androidx.compose.ui.geometry.Size(physicalSize.width / outputScale, physicalSize.height / outputScale)
            try {
                fun androidx.compose.ui.unit.Dp.toPx(): Float = value
                val scale = 1f / appliedZoom
                val canvasW = size.width
                val canvasH = size.height
                val profile = state.typography
                val style = TextStyle(
                    color = ComposeColor(0xff202b3c),
                    fontFamily = profile.family,
                    fontSize = profile.sizeSp.sp,
                    lineHeight = if (profile.id == "system") 30.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    textDirection = TextDirection.Content,
                )
                fun measureWith(frame: EditorFrame, awtW: Int, awtH: Int): Geometry {
                    val actualZoom = renderZoom * (if (awtW > 0)(awtW - 24f) / mmToDocument(frame.visible.pageSettings.widthMm) else fit).coerceIn(.1f, 1f)
                    val scale = 1f / actualZoom
                    val w = if (awtW > 0) awtW * scale else canvasW
                    val h = if (awtH > 0) awtH * scale else canvasH
                    val pageWidth = mmToDocument(frame.visible.pageSettings.widthMm)
                    val pageLeft = (w - pageWidth) / 2
                    val padding = pageWidth * frame.visible.pageSettings.marginMm / frame.visible.pageSettings.widthMm
                    val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
                    fun measureRunning(flow: RunningFlow, text: String): TextLayoutResult = synchronized(measurer) {
                        val style = TextStyle(
                            color = ComposeColor(0xff202b3c),
                            fontFamily = StoredFonts.profile(flow.style.font)?.family ?: profile.family,
                            fontSize = (if (flow.style.size == 0)18 else flow.style.size).sp,
                            textDirection = when (flow.direction) {
                                ParagraphDirection.RTL -> TextDirection.Rtl
                                ParagraphDirection.LTR -> TextDirection.Ltr
                                ParagraphDirection.AUTO -> TextDirection.Content
                            },
                            textAlign = paragraphAlignment(ParagraphProperties(alignment = flow.alignment), flow.direction != ParagraphDirection.LTR),
                        )
                        measurer.measure(AnnotatedString(text, spanStyles = listOf(AnnotatedString.Range(storedSpan(flow.style), 0, text.length))), style = style, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth))
                    }
                    val runningSize = runningReserves(frame.visible, ::measureRunning)
                    val spans = partitionParagraphs(frame.visible)
                    val blocks = frame.visible.paragraphs()
                    val directions = ArrayList<ResolvedTextDirection>()
                    var inherited = ParagraphDirection.LTR
                    for (span in spans) {
                        inherited = resolveParagraphDirection(span.units, span.properties, inherited)
                        directions += if (inherited == ParagraphDirection.RTL)ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr
                    }
                    val markers = frame.visible.listMarkers()
                    val items = blocks.filter { it.properties.item.isNotEmpty() }.distinctBy { it.properties.item }
                    val itemById = items.associateBy { it.properties.item }
                    val ancestry = items.associate { item ->
                        val chain = ArrayList<String>()
                        var id = item.properties.item
                        while (id.isNotEmpty()) {
                            require(id !in chain && chain.size < 8) { "Invalid list ancestry" }
                            chain += id
                            id = itemById.getValue(id).properties.parent
                        }
                        item.properties.item to chain
                    }
                    val maximumDepth = ancestry.values.maxOfOrNull { it.size } ?: 1
                    val nominal = items.associate { it.properties.item to ListNominal(it.properties.markerTypography.font, it.properties.markerTypography.size) }
                    val markerLayouts = HashMap<String, TextLayoutResult>()
                    val markerKeys = HashMap<String, String>()
                    val groupWidths = HashMap<String, Float>()
                    val groupEm = HashMap<String, Float>()
                    fun rootRtl(item: String) = directions[itemById.getValue(ancestry.getValue(item).last()).index] == ResolvedTextDirection.Rtl
                    fun group(item: String): String {
                        val p = itemById.getValue(item).properties
                        return "${p.parent}:${rootRtl(item)}"
                    }
                    val oppositeGroups = items.filter { (directions[it.index] == ResolvedTextDirection.Rtl) != rootRtl(it.properties.item) }.map { group(it.properties.item) }.toSet()
                    for (block in items) {
                        val item = block.properties.item
                        val n = nominal.getValue(item)
                        val family = StoredFonts.profile(n.font)?.family ?: profile.family
                        val em = n.size * densityObj.density * densityObj.fontScale
                        val label = markers[block.anchor] ?: continue
                        if (block.properties.list == ListKind.NUMBER)markerLayouts[item] = synchronized(measurer) { measurer.measure(AnnotatedString(label), style = style.copy(fontFamily = family, fontSize = n.size.sp, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, textDirection = TextDirection.Ltr, textAlign = TextAlign.Left)) }
                        markerKeys[item] = StoredFonts.profile(n.font)?.key ?: profile.key
                        val advance = markerLayouts[item]?.size?.width?.toFloat() ?: em * .24f
                        val key = group(item)
                        groupWidths[key] = maxOf(groupWidths[key] ?: 0f, advance + em * .35f)
                        groupEm[key] = maxOf(groupEm[key] ?: 0f, em)
                    }
                    fun listColumn(item: String): ListColumn? {
                        if (item.isEmpty())return null
                        val chain = ancestry.getValue(item)
                        val gutter = chain.maxOf { groupWidths.getValue(group(it)) }
                        val nominalStep = chain.maxOf { groupEm.getValue(group(it)) * 1.5f }
                        // Marker width is reserved once, not multiplied at every ancestor.
                        // Reduce nesting step uniformly in narrow viewports, retaining the gutter.
                        val step = minOf(nominalStep, ((textWidth - gutter - 40.dp.toPx()) / maximumDepth).coerceAtLeast(0f))
                        val indent = maxOf(gutter, step) + step * (chain.size - 1)
                        val rootSide = rootRtl(item)
                        val opposite = group(item) in oppositeGroups
                        val oppositeGutter = if (opposite)groupWidths.getValue(group(item)) else 0f
                        val available = (textWidth - indent - oppositeGutter).coerceAtLeast(1f)
                        val width = available
                        val left = if (rootSide)textWidth - indent - width else indent
                        return ListColumn(left, width, rootSide, groupWidths.getValue(group(item)))
                    }
                    var fallback = ResolvedTextDirection.Ltr
                    var currentTop = 0f
                    var startLineIndex = 0
                    val defaultLineHeight = synchronized(measurer) {
                        measurer.measure(AnnotatedString(" "), style = style, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth)).size.height.toFloat()
                    }
                    val paragraphs = ArrayList<ParagraphLayout>(spans.size)
                    for (span in spans) {
                        val resolved = resolveParagraphDirection(span.units, span.properties, if (fallback == ResolvedTextDirection.Rtl) ParagraphDirection.RTL else ParagraphDirection.LTR)
                        val dir = if (resolved == ParagraphDirection.RTL) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr
                        fallback = dir
                        val image = span.units.singleOrNull()?.image
                        val imageResource = image?.let { frame.visible.resources[it.resource] }
                        val annotated = if (image != null) AnnotatedString(" ") else paragraphAttributedDisplay(span.units)
                        val emptySeed = if (span.startOffset == 0) frame.visible.startStyle else frame.visible.units[span.startOffset - 1].paragraphStyle
                        val headingBase = if (span.properties.heading > 0)style.copy(fontWeight = FontWeight.Bold, fontSize = listOf(0, 36, 32, 30, 28, 27, 26)[span.properties.heading].sp) else style
                        val paragraphBase = if (span.units.isEmpty()) headingBase.merge(storedSpan(emptySeed)) else headingBase
                        val needsNatural = span.properties.heading > 0 || span.units.any { it.style.size != 0 || it.style.font != StoredFont.DEFAULT } || (span.units.isEmpty() && (emptySeed.size != 0 || emptySeed.font != StoredFont.DEFAULT))
                        val column = listColumn(span.properties.item)
                        val availableWidth = (column?.width?.roundToInt() ?: textWidth).coerceAtLeast(1)
                        val paraStyle = paragraphBase.copy(
                            lineHeight = if (needsNatural) androidx.compose.ui.unit.TextUnit.Unspecified else paragraphBase.lineHeight,
                            textDirection = if (dir == ResolvedTextDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr,
                            textIndent = androidx.compose.ui.text.style.TextIndent.None,
                            textAlign = paragraphAlignment(span.properties, dir == ResolvedTextDirection.Rtl),
                        )
                        val natural = synchronized(measurer) { measurer.measure(annotated, style = paraStyle, constraints = Constraints(minWidth = availableWidth, maxWidth = availableWidth)) }
                        val naturalAdvance = (0 until natural.lineCount).maxOf { line -> natural.getLineBottom(line) - natural.getLineTop(line) }
                        val layout = if (span.properties.linePermille == 0) {
                            natural
                        } else {
                            synchronized(measurer) {
                                measurer.measure(annotated, style = paraStyle.copy(lineHeight = (naturalAdvance / densityObj.density / densityObj.fontScale * span.properties.linePermille / 1000f).sp), constraints = Constraints(minWidth = availableWidth, maxWidth = availableWidth))
                            }
                        }
                        currentTop += span.properties.spaceBefore.dp.toPx()
                        val requestedWidth = image?.width?.dp?.toPx() ?: 0f
                        val imageContentWidth = column?.width ?: textWidth.toFloat()
                        val fit = if (imageResource != null) minOf(1f, imageContentWidth / requestedWidth, (pageWidth * frame.visible.pageSettings.heightMm / frame.visible.pageSettings.widthMm - padding * 2 - runningSize.sum()).coerceAtLeast(1f) / (requestedWidth * imageResource.height / imageResource.width)) else 1f
                        val imageWidth = requestedWidth * fit
                        val contentLeft = column?.left ?: 0f
                        val contentRight = contentLeft + imageContentWidth
                        val imageLeft = when (span.properties.alignment) {
                            ParagraphAlignment.CENTER -> (contentLeft + contentRight - imageWidth) / 2
                            ParagraphAlignment.START -> if (dir == ResolvedTextDirection.Rtl)contentRight - imageWidth else contentLeft
                            ParagraphAlignment.END -> if (dir == ResolvedTextDirection.Rtl)contentLeft else contentRight - imageWidth
                        }
                        val pHeight = if (imageResource != null) imageWidth * imageResource.height / imageResource.width else max(layout.size.height.toFloat(), if (needsNatural) 1f else defaultLineHeight)
                        val rawText = span.units.joinToString("") { it.char.toString() }
                        val marker = markers[blocks[span.index].anchor]?.let { text ->
                            val n = nominal.getValue(span.properties.item)
                            val em = n.size * densityObj.density * densityObj.fontScale
                            val label = markerLayouts[span.properties.item]
                            val radius = if (label == null)em * .12f else 0f
                            val width = label?.size?.width?.toFloat() ?: radius * 2
                            val gap = em * .35f
                            val markerRtl = dir == ResolvedTextDirection.Rtl
                            val left = if (markerRtl)checkNotNull(column).left + column.width + gap else checkNotNull(column).left - gap - width
                            val baseline = layout.getLineBaseline(0)
                            // Circle optical center uses measured x ink, not the paragraph box.
                            val face = org.jetbrains.skia.FontMgr.default.matchFamilyStyle("Arial", org.jetbrains.skia.FontStyle.NORMAL)
                            val xHeight = org.jetbrains.skia.Font(face, em).use { it.metrics.xHeight.takeIf { x -> x > 0 } ?: em * .5f }
                            face?.close()
                            val opticalCenter = baseline - xHeight / 2
                            val top = if (label == null)opticalCenter - radius else baseline - label.getLineBaseline(0)
                            ListMarkerLayout(text, label, left, top, width, label?.size?.height?.toFloat() ?: radius * 2, baseline, radius, opticalCenter, n.size, markerKeys.getValue(span.properties.item), markerRtl)
                        }
                        paragraphs.add(
                            ParagraphLayout(
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
                            ),
                        )
                        currentTop += pHeight + span.properties.spaceAfter.dp.toPx()
                        startLineIndex += maxOf(1, layout.lineCount)
                    }
                    val paperHeight = pageWidth * frame.visible.pageSettings.heightMm / frame.visible.pageSettings.widthMm
                    val pageGap = 24.dp.toPx()
                    val capturedPlan = buildDocumentPagePlan(frame.visible, frame.documentRevision, profile.key, paragraphs, pageGap, ::measureRunning)
                    val totalHeight = if (state.paged)capturedPlan.pageCount * paperHeight + (capturedPlan.pageCount - 1) * pageGap - 2 * padding else currentTop
                    return Geometry(
                        paragraphs = paragraphs,
                        totalHeight = totalHeight,
                        x = pageLeft + padding,
                        y = 28.dp.toPx() + padding - state.scroll * scale,
                        scale = scale,
                        revision = frame.documentRevision,
                        textWidth = textWidth,
                        scroll = state.scroll,
                        hostWidth = if (awtW > 0) awtW else (canvasW / scale).roundToInt(),
                        hostHeight = if (awtH > 0) awtH else (canvasH / scale).roundToInt(),
                        typographyKey = profile.key + ":zoom=$renderZoom" + (if (state.paged) ":paged" else ":continuous"),
                        fragments = if (state.paged)capturedPlan.fragments else paragraphs.map { ParagraphFragment(it.index, 0, it.lineCount - 1, 0f, it.height, it.top, 0) },
                        pageHeight = if (state.paged) paperHeight else 0f,
                        pageGap = if (state.paged) pageGap else 0f,
                        pageCount = if (state.paged)capturedPlan.pageCount else 1, documentPagePlan = capturedPlan,
                    )
                }
                fun measure(frame: EditorFrame): Geometry = measureWith(frame, state.hostWidth, state.hostHeight)
                if (renderZoom != state.zoom || profile.key != state.typography.key)return@Canvas
                state.measure = ::measure
                state.measureAtSize = { frame, awtW, awtH -> measureWith(frame, awtW.roundToInt(), awtH.roundToInt()) }
                val frame = state.frame
                val pageWidth = mmToDocument(frame.visible.pageSettings.widthMm)
                val pageLeft = (size.width - pageWidth) / 2
                val padding = pageWidth * frame.visible.pageSettings.marginMm / frame.visible.pageSettings.widthMm
                val textWidth = (pageWidth - padding * 2).roundToInt().coerceAtLeast(1)
                state.singleMeasure = { doc ->
                    val annotated = attributedDisplay(doc)
                    synchronized(measurer) {
                        measurer.measure(annotated, style = style, constraints = Constraints(minWidth = textWidth, maxWidth = textWidth))
                    }
                }
                state.referenceMeasure = { doc ->
                    val uncachedMeasurer = TextMeasurer(fontFamilyResolver, densityObj, layoutDirection, cacheSize = 0)
                    referenceMeasure(doc, textWidth, uncachedMeasurer, style)
                }
                val markers = frame.visible.listMarkers()
                var geo = measure(frame)
                state.layoutError = null
                val contentHeight = geo.totalHeight + padding * 2 + 56.dp.toPx()
                val maxScroll = max(0f, (contentHeight - size.height) / scale)
                var scroll = state.scroll.coerceIn(0f, maxScroll)
                // Reflow/view switching can shrink the scroll range. Caret geometry must use
                // that clamped origin before calculating a further reveal adjustment.
                if (scroll != state.scroll) {
                    state.scroll = scroll
                    geo = measure(frame)
                }
                val findVisible = frame.marked == null && state.findDocument === frame.committed
                val reveal = state.findReveal.takeIf { findVisible }
                state.findReveal = null
                if (reveal != null) {
                    val start = geo.caret(reveal.start)
                    val end = geo.caret(reveal.end)
                    val available = size.height - 60.dp.toPx()
                    val target = if (end.bottom - start.top <= available) androidx.compose.ui.geometry.Rect(start.left, start.top, start.right, end.bottom) else start
                    if (target.bottom > size.height - 30.dp.toPx()) scroll += (target.bottom - size.height + 30.dp.toPx()) / scale
                    if (target.top < 24.dp.toPx()) scroll -= (24.dp.toPx() - target.top) / scale
                    state.ensureCaret = false
                } else if (state.ensureCaret) {
                    val caret = geo.caret(frame.selection.focus, frame.selection.primary, frame.selection.wrapAffinity)
                    if (caret.bottom > size.height - 30.dp.toPx()) scroll += (caret.bottom - size.height + 30.dp.toPx()) / scale
                    if (caret.top < 24.dp.toPx()) scroll -= (24.dp.toPx() - caret.top) / scale
                    state.ensureCaret = false
                }
                scroll = scroll.coerceIn(0f, maxScroll)
                if (scroll != state.scroll) {
                    state.scroll = scroll
                    geo = measure(frame)
                }
                state.geometry = geo
                drawRect(ComposeColor(0xffeef1f5))
                val pageY = 28.dp.toPx() - state.scroll * scale
                val pageHeight = if (state.paged) geo.pageHeight else max(size.height - 56.dp.toPx(), geo.totalHeight + padding * 2)
                for (page in 0 until geo.pageCount) {
                    val top = pageY + page * (pageHeight + geo.pageGap)
                    drawRect(ComposeColor(0xffe1e6ed), Offset(pageLeft + 2.dp.toPx(), top + 3.dp.toPx()), androidx.compose.ui.geometry.Size(pageWidth, pageHeight))
                    drawRect(ComposeColor.White, Offset(pageLeft, top), androidx.compose.ui.geometry.Size(pageWidth, pageHeight))
                }
                fun clippedFragment(fragment: ParagraphFragment, block: () -> Unit) {
                    if (state.paged) {
                        clipRect(pageLeft, max(pageY + fragment.page * (pageHeight + geo.pageGap), geo.y + fragment.top - 2.dp.toPx()), pageLeft + pageWidth, min(pageY + fragment.page * (pageHeight + geo.pageGap) + pageHeight, geo.y + fragment.top + fragment.height + 2.dp.toPx())) { block() }
                    } else {
                        block()
                    }
                }
                val s = frame.selection
                if (!s.collapsed) {
                    val selColor = ComposeColor(if (state.focused) 0xffc8dcff else 0xffdce3ed)
                    for (fragment in geo.fragments) {
                        val p = geo.paragraphs[fragment.paragraphIndex]
                        val selStart = max(s.start, p.startOffset)
                        val selEnd = min(s.end, p.endOffset)
                        if (selStart < selEnd && p.image == null) {
                            val localA = p.toLocalOffset(selStart)
                            val localB = p.toLocalOffset(selEnd)
                            val path = p.layout.getPathForRange(localA, localB)
                            clippedFragment(fragment) {
                                withTransform({ translate(geo.x + p.contentLeft, geo.y + fragment.top - fragment.sourceTop) }) { drawPath(path, selColor) }
                            }
                        }
                    }
                    for (r in geo.breakAndEmptySelectionRects(s)) {
                        drawRect(selColor, r.topLeft, r.size)
                    }
                }
                if (findVisible) {
                    for (fragment in geo.fragments) {
                        if (geo.y + fragment.top > size.height || geo.y + fragment.top + fragment.height < 0) continue
                        val p = geo.paragraphs[fragment.paragraphIndex]
                        for ((index, match) in state.findMatches.withIndex()) {
                            if (match.start < p.startOffset || match.end > p.endOffset) continue
                            val a = p.toLocalOffset(match.start)
                            val b = p.toLocalOffset(match.end)
                            if (a >= b) continue
                            clippedFragment(fragment) {
                                withTransform({ translate(geo.x + p.contentLeft, geo.y + fragment.top - fragment.sourceTop) }) {
                                    drawPath(p.layout.getPathForRange(a, b), ComposeColor(if (index == state.findActive) 0xffffbf69 else 0xffffeb99))
                                }
                            }
                        }
                    }
                }
                for (fragment in geo.fragments) {
                    val p = geo.paragraphs[fragment.paragraphIndex]
                    clippedFragment(fragment) {
                        val imageBlock = p.image
                        if (imageBlock != null) {
                            val bitmap = ImageResources.cached(imageBlock.resource)
                            val left = geo.x + p.imageLeft
                            if (bitmap != null) {
                                drawImage(bitmap, dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), (geo.y + fragment.top).roundToInt()), dstSize = androidx.compose.ui.unit.IntSize(p.imageWidth.roundToInt().coerceAtLeast(1), p.height.roundToInt().coerceAtLeast(1)))
                            } else {
                                drawRect(ComposeColor(0xffdde6ed), Offset(left, geo.y + fragment.top), androidx.compose.ui.geometry.Size(p.imageWidth, p.height))
                            }
                            if (frame.selection.start <= p.startOffset && frame.selection.end > p.startOffset)drawRect(ComposeColor(0xff174ea6), Offset(left, geo.y + fragment.top), androidx.compose.ui.geometry.Size(p.imageWidth, p.height), style = Stroke(2.dp.toPx()))
                        } else {
                            synchronized(p.layout.multiParagraph) { drawText(p.layout, topLeft = Offset(geo.x + p.contentLeft, geo.y + fragment.top - fragment.sourceTop)) }
                        }
                        if (fragment.firstLine == 0) {
                            p.marker?.let { marker ->
                                val markerLayout = marker.layout
                                if (markerLayout != null) {
                                    synchronized(markerLayout.multiParagraph) { drawText(markerLayout, topLeft = Offset(geo.x + marker.left, geo.y + fragment.top + marker.top)) }
                                } else {
                                    drawCircle(ComposeColor(0xff202b3c), marker.bulletRadius, Offset(geo.x + marker.left + marker.bulletRadius, geo.y + fragment.top + marker.opticalCenter))
                                }
                            }
                        }
                    }
                }
                if (state.paged) {
                    geo.documentPagePlan?.running?.forEach { running ->
                        synchronized(running.layout.multiParagraph) { drawText(running.layout, topLeft = Offset(pageLeft + running.left, pageY + running.page * (pageHeight + geo.pageGap) + running.top)) }
                    }
                }
                if (frame.visible.text.isEmpty() && frame.marked == null) {
                    val placeholder = measurer.measure(AnnotatedString("Start typing…"), style = style.copy(color = ComposeColor(0xff9aa4b2)))
                    drawText(placeholder, topLeft = Offset(geo.x, geo.y))
                }
                frame.marked?.takeUnless { it.isEmpty() }?.let { range ->
                    for (p in geo.paragraphs) {
                        val markA = max(range.first, p.startOffset)
                        val markB = min(range.last + 1, p.endOffset)
                        if (markA < markB) {
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
                                    val lineY = geo.y + geo.paragraphTop(p, line) + p.layout.getLineBottom(line) - 2.dp.toPx()
                                    drawLine(ComposeColor(0xff3467d5), Offset(geo.x + p.contentLeft + min(x1, x2), lineY), Offset(geo.x + p.contentLeft + max(x1, x2), lineY), 1.5.dp.toPx())
                                }
                            }
                        }
                    }
                }
                if (state.focused && state.blink && s.collapsed) {
                    val caret = geo.caret(s.focus, s.primary, s.wrapAffinity)
                    drawRect(ComposeColor(0xff2559ba), caret.topLeft, caret.size)
                }
                if (maxScroll > 0) {
                    val h = max(32.dp.toPx(), size.height * size.height / contentHeight)
                    val y = (size.height - h) * state.scroll / maxScroll
                    drawRoundRect(ComposeColor(0xffb1bac8), Offset(size.width - 7.dp.toPx(), y), androidx.compose.ui.geometry.Size(4.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            } catch (failure: IllegalArgumentException) {
                state.geometry = null
                state.layoutError = failure.message ?: "Unsupported page layout"
                val message = measurer.measure(AnnotatedString("Layout needs correction: ${state.layoutError}. Use Undo or Page / running-content settings."), style = TextStyle(color = ComposeColor.Red, fontSize = 14.sp), constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1)))
                drawText(message, topLeft = Offset(8f, 8f))
            } finally {
                drawContext.size = previousSize
            }
        }
    }
}
