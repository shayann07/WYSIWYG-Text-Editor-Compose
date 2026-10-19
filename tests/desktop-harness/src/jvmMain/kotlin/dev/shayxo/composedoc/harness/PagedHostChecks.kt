package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import dev.shayxo.composedoc.workspace.*
import java.awt.Point
import java.awt.event.InputMethodEvent
import java.awt.font.TextHitInfo
import java.io.File
import java.text.AttributedString
import kotlin.math.abs
import kotlin.math.roundToInt

/** Mounted AWT/Compose experiment with synthetic native callbacks, never OS IME qualification. */
fun runPagedHostChecks(surface: EditorSurface, resultFile: File) {
    val e = surface.engine
    check(surface.view.paged)
    val text = "یہ ایک اردو دستاویز ہے۔ مُحَمَّد اور تحقیق — English 123 (۲۰۲۶)۔ ".repeat(50) + "\nکتاب اور قلم"
    e.replace(text, "PagedFixture", paragraphBreaks = true)
    e.move(Selection(0, 4))
    e.toggleFormat(FormatKey.BOLD)
    surface.view.ensureCaret = false
    surface.view.scroll = 0f
    val geometry = checkNotNull(surface.geometry())
    check(geometry.pageCount >= 3)
    for (paragraph in geometry.paragraphs) {
        val sourceHeight = geometry.fragments.filter { it.paragraphIndex == paragraph.index }.sumOf { it.height.toDouble() }
        val extent = paragraph.height - paragraph.layout.getLineTop(0)
        check(abs(sourceHeight - extent) < .1) { "Pagination changed paragraph line advance/leading" }
    }
    check(geometry.fragments.size <= geometry.paragraphs.size + geometry.pageCount - 1) { "Paragraph painting was unnecessarily repeated per line" }
    val coverage = IntArray(text.length)
    for (f in geometry.fragments) {
        val p = geometry.paragraphs[f.paragraphIndex]
        val start = p.startOffset + p.layout.getLineStart(f.firstLine)
        val end = if (f.lastLine == p.lineCount - 1 && p.hasBreak) p.endOffset + 1 else p.startOffset + p.layout.getLineEnd(f.lastLine)
        for (i in start until end) coverage[i]++
    }
    check(coverage.all { it == 1 }) { "Paged mapping must account for each unit exactly once" }
    val firstOnPage = geometry.fragments.first { it.page == 1 }
    val p = geometry.paragraphs[firstOnPage.paragraphIndex]
    val boundary = p.startOffset + p.layout.getLineStart(firstOnPage.firstLine)
    check(p.isSoftWrapBoundary(boundary - p.startOffset))
    val upstream = geometry.caret(boundary, wrapAffinity = WrapAffinity.UPSTREAM)
    val downstream = geometry.caret(boundary)
    check(downstream.top - upstream.bottom >= geometry.pageGap) { "Page-boundary caret affinity ignored gap/margins" }
    val gapY = (upstream.bottom + downstream.top) / 2 - geometry.y
    val nearest = geometry.fragmentAtY(gapY)
    check(nearest.page in 0..1)
    val access = surface.accessibleContext.accessibleText
    val gapHit = access.getIndexAtPoint(Point((downstream.left / geometry.scale).roundToInt(), ((gapY + geometry.y) / geometry.scale).roundToInt()))
    check(gapHit in 0..text.length)
    e.move(Selection(boundary - 3, boundary + 3))
    check(access.selectedText == text.substring(boundary - 3, boundary + 3)) { "Page gap leaked into selection" }
    check(checkNotNull(surface.geometry()).hasRenderedSelection(e.snapshot().selection))
    e.move(Selection(boundary, boundary))
    val before = e.snapshot()
    fun input(value: String, committed: Int, caret: Int?) = surface.dispatchEvent(
        InputMethodEvent(
            surface,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            AttributedString(value).iterator,
            committed,
            caret?.let(TextHitInfo::leading),
            null,
        ),
    )
    input("ک候補", 1, 2)
    check(e.snapshot().committed.text == text.take(boundary) + "ک" + text.drop(boundary))
    val composing = e.snapshot()
    val composingGeometry = checkNotNull(surface.geometry())
    val markedStart = checkNotNull(composing.marked).first
    val caret = composingGeometry.caret(markedStart)
    val nativeRect = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    check(abs(nativeRect.y - (surface.locationOnScreen.y + caret.top / composingGeometry.scale).roundToInt()) <= 1)
    val charRect = checkNotNull(access.getCharacterBounds(markedStart))
    check(charRect.height > 0)
    input("تاب", 3, null)
    val settled = e.snapshot()
    check(settled.visible.text == text.take(boundary) + "کتاب" + text.drop(boundary))
    check(settled.undoCount == before.undoCount + 1 && settled.selection == Selection(boundary + 4, boundary + 4))
    check(settled.committed.units.subList(boundary, boundary + 4).all { it.style == before.typingStyle })
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == before.committed && e.snapshot().selection == before.selection)
    e.perform(EditAction.Redo)
    check(e.snapshot().committed == settled.committed && e.snapshot().selection == settled.selection)
    val snapshot = e.snapshot()
    surface.view.setPagedMode(false)
    check(e.snapshot() == snapshot && surface.view.geometry == null && surface.view.measure == null)
    surface.view.setPagedMode(true)
    check(e.snapshot() == snapshot)
    surface.view.scroll = (geometry.pageHeight / geometry.scale - 230f).coerceAtLeast(0f)
    surface.view.ensureCaret = false
    resultFile.parentFile.mkdirs()
    resultFile.writeText(
        evidenceJson(
            mapOf(
                "status" to "PASS", "scope" to "Mounted AWT + Compose; synthetic IME callbacks; not native OS qualification",
                "pages" to geometry.pageCount, "paintFragments" to geometry.fragments.size, "lines" to geometry.totalLineCount, "fontKey" to geometry.typographyKey,
                "densityScale" to geometry.scale, "textWidthPx" to geometry.textWidth,
                "viewportLogical" to listOf(geometry.hostWidth, geometry.hostHeight),
                "pageHeightPx" to geometry.pageHeight, "pageGapPx" to geometry.pageGap,
                "unitCoverage" to "EXACTLY_ONCE", "boundaryAffinity" to "PASS", "gapHitAndSelection" to "PASS",
                "nativeQueryGeometry" to "SYNTHETIC_PASS", "atGeometry" to "AUTOMATED_ACCESSIBLE_TEXT_QUERY_ONLY",
                "partialCommitAndAttributedHistory" to "PASS", "presentationSwitchPreservesState" to "PASS",
                "nativeIme" to "NOT RUN", "pdfAndPrint" to "NOT QUALIFIED",
            ),
        ),
        Charsets.UTF_8,
    )
    println("PASS editable paged host experiment; native IME/PDF qualification OPEN")
}
