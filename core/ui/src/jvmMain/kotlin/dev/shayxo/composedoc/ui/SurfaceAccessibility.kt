package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.Geometry
import dev.shayxo.composedoc.layout.ParagraphLayout
import dev.shayxo.composedoc.layout.StoredFonts
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.itemDepth
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.resolveParagraphDirection
import java.awt.Color
import java.awt.Rectangle
import java.text.BreakIterator
import javax.accessibility.AccessibleAttributeSequence
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleText
import javax.accessibility.AccessibleTextSequence
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import kotlin.math.roundToInt

private fun unitStyle(document: Document, index: Int): TextStyleFlags = document.units[index].let {
    if (it.kind == UnitKind.PARAGRAPH_BREAK) it.paragraphStyle else it.style
}

internal fun EditorSurface.accessibleStyle(
    style: TextStyleFlags,
    profile: TypographyProfile = view.typography,
): AttributeSet = SimpleAttributeSet().apply {
    StyleConstants.setBold(this, style.bold)
    StyleConstants.setItalic(this, style.italic)
    StyleConstants.setUnderline(this, style.underline)
    style.link?.let {
        addAttribute("LinkURI", it.uri)
        addAttribute("LinkId", it.id)
    }
    StyleConstants.setStrikeThrough(this, style.strike)
    if (style.color >= 0) StyleConstants.setForeground(this, Color(style.color))
    // Requested presentation, not a claim about the physical fallback face for every glyph.
    StyleConstants.setFontFamily(
        this,
        StoredFonts.profile(style.font)?.requestedFamilyName ?: profile.requestedFamilyName,
    )
    StyleConstants.setFontSize(this, if (style.size == 0) profile.sizeSp else style.size)
}

internal fun EditorSurface.paragraphAccessibleStyle(document: Document, index: Int): AttributeSet {
    val at = index.coerceIn(0, document.units.size)
    val target = document.paragraphAt(at)
    var resolved = ParagraphDirection.LTR
    for (p in document.paragraphs().take(target.index + 1)) {
        resolved = resolveParagraphDirection(document.units.subList(p.start, p.end), p.properties, resolved)
    }
    val flags = document.units.getOrNull(at)?.let {
        if (it.kind == UnitKind.PARAGRAPH_BREAK) it.paragraphStyle else it.style
    } ?: document.startStyle
    return SimpleAttributeSet(accessibleStyle(flags)).apply {
        addAttribute("ParagraphDirection", target.properties.direction.name)
        addAttribute("HeadingLevel", target.properties.heading)
        addAttribute("ListKind", target.properties.list.name)
        addAttribute("ListItem", target.properties.item)
        if (target.properties.item.isNotEmpty())addAttribute("ListDepth", document.itemDepth(target.properties.item))
        document.units.getOrNull(at)?.image?.let {
            addAttribute("ImageAlternativeText", it.alt)
            addAttribute("ImageResource", it.resource)
        }
        addAttribute("ParagraphAlignment", target.properties.alignment.name)
        StyleConstants.setBidiLevel(this, if (resolved == ParagraphDirection.RTL) 1 else 0)
        StyleConstants.setAlignment(this, swingAlignment(target.properties.alignment, resolved))
    }
}

private fun swingAlignment(alignment: ParagraphAlignment, resolved: ParagraphDirection): Int = when (alignment) {
    ParagraphAlignment.CENTER -> StyleConstants.ALIGN_CENTER
    ParagraphAlignment.START -> if (resolved == ParagraphDirection.RTL) {
        StyleConstants.ALIGN_RIGHT
    } else {
        StyleConstants.ALIGN_LEFT
    }
    ParagraphAlignment.END -> if (resolved == ParagraphDirection.RTL) {
        StyleConstants.ALIGN_LEFT
    } else {
        StyleConstants.ALIGN_RIGHT
    }
}

/** Re-announces every style run of the published document under a new typography profile. */
internal fun EditorSurface.fireTypographyAttributeChanges(previous: TypographyProfile, profile: TypographyProfile) {
    val document = published.visible
    val units = document.units
    var start = 0
    while (start < units.size) {
        val flags = unitStyle(document, start)
        var end = start + 1
        while (end < units.size && unitStyle(document, end) == flags) end++
        getAccessibleContext().firePropertyChange(
            AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED,
            AccessibleAttributeSequence(start, end, accessibleStyle(flags, previous)),
            AccessibleAttributeSequence(start, end, accessibleStyle(flags, profile)),
        )
        start = end
    }
}

internal fun EditorSurface.publishAccessibleChange(old: EditorFrame, current: EditorFrame) {
    val context = getAccessibleContext()
    val before = old.visible.text
    val after = current.visible.text
    if (before != after) {
        fireTextChange(context, before, after)
    } else {
        fireStyleChanges(context, old, current)
    }
    if (old.selection != current.selection) {
        context.firePropertyChange(AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY, null, null)
    }
    if (before == after && paragraphPropertiesChanged(old, current)) {
        fireParagraphChanges(context, old, current)
    }
    context.firePropertyChange(
        AccessibleContext.ACCESSIBLE_CARET_PROPERTY,
        old.selection.focus,
        current.selection.focus,
    )
}

private fun paragraphPropertiesChanged(old: EditorFrame, current: EditorFrame): Boolean =
    old.visible.paragraphs().map { it.properties } != current.visible.paragraphs().map { it.properties }

private fun fireTextChange(context: AccessibleContext, before: String, after: String) {
    var prefix = 0
    while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(before.length, after.length) - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
    ) {
        suffix++
    }
    val oldEnd = before.length - suffix
    val newEnd = after.length - suffix
    val removed = if (oldEnd > prefix) {
        AccessibleTextSequence(prefix, oldEnd, before.substring(prefix, oldEnd))
    } else {
        null
    }
    val inserted = if (newEnd > prefix) {
        AccessibleTextSequence(prefix, newEnd, after.substring(prefix, newEnd))
    } else {
        null
    }
    context.firePropertyChange(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY, removed, inserted)
}

private fun EditorSurface.fireStyleChanges(context: AccessibleContext, old: EditorFrame, current: EditorFrame) {
    val size = current.visible.units.size
    var i = 0
    while (i < size) {
        val previous = unitStyle(old.visible, i)
        val next = unitStyle(current.visible, i)
        if (previous == next) {
            i++
        } else {
            var end = i + 1
            while (end < size && unitStyle(old.visible, end) == previous && unitStyle(current.visible, end) == next) {
                end++
            }
            context.firePropertyChange(
                AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED,
                AccessibleAttributeSequence(i, end, accessibleStyle(previous)),
                AccessibleAttributeSequence(i, end, accessibleStyle(next)),
            )
            i = end
        }
    }
}

private fun EditorSurface.fireParagraphChanges(context: AccessibleContext, old: EditorFrame, current: EditorFrame) {
    for (p in current.visible.paragraphs()) {
        context.firePropertyChange(
            AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED,
            AccessibleAttributeSequence(p.start, p.end, paragraphAccessibleStyle(old.visible, p.start)),
            AccessibleAttributeSequence(p.start, p.end, paragraphAccessibleStyle(current.visible, p.start)),
        )
    }
    context.firePropertyChange(AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY, null, current.documentRevision)
}

/** [AccessibleText.getCharacterBounds] for this host, in component coordinates. */
internal fun EditorSurface.accessibleCharacterBounds(i: Int): Rectangle? {
    val g = geometry()
    if (g == null || i !in published.visible.text.indices) return null
    val p = g.paragraphForOffset(i)
    val local = p.toLocalOffset(i)
    return when {
        i == p.breakOffset -> breakBounds(g, p)
        p.image != null -> imageBounds(g, p)
        else -> glyphBounds(g, p, local)
    }
}

private fun breakBounds(g: Geometry, p: ParagraphLayout): Rectangle {
    val r = p.layout.getCursorRect(p.textLength)
    val top = g.paragraphTop(p, p.lineCount - 1)
    return Rectangle(
        ((g.x + p.contentLeft + r.left) / g.scale).toInt(),
        ((g.y + top + r.top) / g.scale).toInt(),
        0,
        (r.height / g.scale).toInt(),
    )
}

private fun imageBounds(g: Geometry, p: ParagraphLayout): Rectangle {
    val c = g.caret(p.startOffset)
    return Rectangle(
        (minOf(g.caret(p.endOffset).left, c.left) / g.scale).roundToInt(),
        (c.top / g.scale).roundToInt(),
        (p.imageWidth / g.scale).roundToInt(),
        (p.height / g.scale).roundToInt(),
    )
}

private fun glyphBounds(g: Geometry, p: ParagraphLayout, local: Int): Rectangle {
    val r = p.layout.getBoundingBox(local)
    val top = g.paragraphTop(p, p.layout.getLineForOffset(local))
    return Rectangle(
        ((g.x + p.contentLeft + r.left) / g.scale).toInt(),
        ((g.y + top + r.top) / g.scale).toInt(),
        (r.width / g.scale).toInt(),
        (r.height / g.scale).toInt(),
    )
}

/** The character, word or sentence at, after (step 1) or before (step -1) [index]. */
internal fun accessibleSegment(text: String, part: Int, index: Int, step: Int): String? = when {
    index !in text.indices -> null
    part == AccessibleText.CHARACTER -> text.getOrNull(index + step)?.toString()
    else -> boundarySegment(text, part, index, step)
}

private fun boundarySegment(text: String, part: Int, index: Int, step: Int): String? {
    val boundaries = if (part == AccessibleText.WORD) {
        BreakIterator.getWordInstance()
    } else {
        BreakIterator.getSentenceInstance()
    }.apply { setText(text) }
    var a = boundaries.preceding(index + 1).coerceAtLeast(0)
    var b = boundaries.following(index).let { if (it < 0)text.length else it }
    if (step > 0) {
        a = b
        b = boundaries.following(a)
    } else if (step < 0) {
        b = a
        a = boundaries.preceding(b)
    }
    return if (a < 0 || b < 0 || a >= b)null else text.substring(a, b)
}

/** Applies bold/italic accessibility attributes to [target]; the caller holds the admitted basis. */
internal fun EditorSurface.applyAccessibleAttributes(target: Selection, capturedAttributes: AttributeSet) {
    require(!target.collapsed || (published.selection.collapsed && published.selection.focus == target.focus)) {
        "Collapsed accessibility formatting requires selection at the target caret"
    }
    val keys = capturedAttributes.attributeNames.toList()
    require(keys.all { it == StyleConstants.Bold || it == StyleConstants.Italic }) {
        "Only bold/italic accessibility attributes are supported by this prototype"
    }
    fun flag(key: Any): Boolean? {
        if (!capturedAttributes.isDefined(key)) return null
        return capturedAttributes.getAttribute(key) as? Boolean
            ?: throw IllegalArgumentException("Accessibility style must be Boolean")
    }
    val bold = flag(StyleConstants.Bold)
    val italic = flag(StyleConstants.Italic)
    if (bold != null || italic != null) {
        viewModel.dispatch(EditorIntent.Command(EditAction.FormatAttributes(viewModel.capture(target), bold, italic)))
    }
}
