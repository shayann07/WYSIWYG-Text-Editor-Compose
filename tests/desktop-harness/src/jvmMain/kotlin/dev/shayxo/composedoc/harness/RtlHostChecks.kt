package dev.shayxo.composedoc.harness

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.layout.paragraphBaseDirection
import dev.shayxo.composedoc.layout.partitionParagraphs
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Graphemes
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.WrapAffinity
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorSurface
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.font.TextHitInfo
import java.nio.file.Files

/** Real Compose geometry and AWT dispatch, with generated fixtures rather than an OS keyboard. */
fun runRtlHostChecks(surface: EditorSurface) {
    val e = surface.viewModel.verificationEngine
    fun key(code: Int, shift: Boolean = false) = surface.dispatchEvent(
        KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), if (shift) KeyEvent.SHIFT_DOWN_MASK else 0, code, KeyEvent.CHAR_UNDEFINED),
    )

    // 1. Basic RTL paragraph alignment, visual selection collapse, Shift+Left, format/Undo
    for (text in listOf("اردو میں لکھیں", "العربية جميلة", "اردو English 123 العربية")) {
        e.replace(text, "RTL fixture")
        e.move(Selection(0, 0))
        val g = checkNotNull(surface.view.measure).invoke(e.snapshot())
        check(g.getParagraphDirection(0) == ResolvedTextDirection.Rtl)
        check(g.getHorizontalPosition(0, true) > g.textWidth * .9f) { "RTL paragraph must start at the right page margin" }
        e.selectAll()
        key(KeyEvent.VK_LEFT)
        check(e.snapshot().selection == Selection(text.length, text.length)) { "Left must collapse RTL selection to its visual left edge" }
        e.selectAll()
        key(KeyEvent.VK_RIGHT)
        check(e.snapshot().selection == Selection(0, 0)) { "Right must collapse RTL selection to its visual right edge" }
        key(KeyEvent.VK_LEFT, true)
        check(e.snapshot().selection == Selection(0, Graphemes.stops(text)[1]))
        val stops = Graphemes.stops(text)
        e.move(Selection(text.length, text.length))
        key(KeyEvent.VK_RIGHT, true)
        check(e.snapshot().selection == Selection(text.length, stops[stops.size - 2])) { "Shift+Right from RTL visual left edge must extend inward: ${e.snapshot().selection}" }
        e.toggleFormat(FormatKey.BOLD)
        check(e.snapshot().visible.units.last().style.bold)
        e.perform(EditAction.Undo)
        check(e.snapshot().visible.units.none { it.style.bold })
        e.perform(EditAction.Undo)
        check(e.snapshot().visible.text.isEmpty())
    }

    // 2. Combining marks, tashkeel, harkat and medial letter deletion
    // 2a. Single combining mark (fatha)
    e.replace("سَ", "RTL combining fixture")
    e.delete(true)
    check(e.snapshot().visible.text.isEmpty())
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "سَ")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 2b. Multiple combining marks: Base + Shaddah (U+0651) + Fatha (U+064E) forming single cluster
    val shaddahFatha = "سَّلام"
    val clusterStops = Graphemes.stops(shaddahFatha)
    check(clusterStops.containsAll(listOf(0, 3, 4, 5, 6))) { "Shaddah + Fatha cluster must snap to 3 UTF-16 units" }
    e.replace(shaddahFatha, "Shaddah+Fatha fixture")
    e.move(Selection(3, 3))
    e.delete(true) // Backspace deletes the entire Base+Shaddah+Fatha cluster
    check(e.snapshot().visible.text == "لام") { "Deleting cluster with multiple marks must leave remaining characters intact" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == shaddahFatha)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 2c. Medial joining letter deletion and Undo: "کتاب" -> delete 'ت' at offset 2 -> "کاب"
    val medialWord = "کتاب"
    e.replace(medialWord, "Medial letter fixture")
    e.move(Selection(2, 2))
    e.delete(true)
    check(e.snapshot().visible.text == "کاب") { "Deleting medial joining letter must preserve preceding and succeeding text" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == medialWord)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 2d. Single combining mark forward delete
    e.replace("سَ", "RTL forward delete fixture")
    e.move(Selection(0, 0))
    e.delete(false)
    check(e.snapshot().visible.text.isEmpty())
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "سَ")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 2e. Multiple combining marks forward delete: Base + Shaddah + Fatha
    e.replace(shaddahFatha, "Shaddah+Fatha forward delete")
    e.move(Selection(0, 0))
    e.delete(false)
    check(e.snapshot().visible.text == "لام") { "Forward delete of cluster with multiple marks must leave remaining characters intact" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == shaddahFatha)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 2f. Medial joining letter forward delete: "کتاب" at offset 1 deleting 'ت'
    e.replace(medialWord, "Medial letter forward delete")
    e.move(Selection(1, 1))
    e.delete(false)
    check(e.snapshot().visible.text == "کاب") { "Forward deleting medial joining letter must preserve preceding and succeeding text" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == medialWord)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 3. Mixed digits, Arabic punctuation, and bidi transitions
    val mixedPunctuation = "اردو: ۱۲۳، English: 456؛ العربية: ۷۸۹؟"
    e.replace(mixedPunctuation, "Mixed digits and punctuation fixture")
    val mixedGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(mixedGeometry.getParagraphDirection(0) == ResolvedTextDirection.Rtl)
    e.move(Selection(0, 0))
    val stopsPunct = Graphemes.stops(mixedPunctuation).toSet()
    for (stop in stopsPunct) {
        e.move(Selection(stop, stop))
        check(e.snapshot().selection.focus in stopsPunct)
    }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 4. Visual Left/Right arrow navigation across 6 diverse mixed-script texts
    var navigationChecks = 0
    for (text in listOf("اردو میں لکھیں", "العربية جميلة", "اردو English 123 العربية", "abc العربية ١٢٣ xyz", "اردو\nEnglish\nالعربية", "سَلام 👩‍💻 دنیا")) {
        e.replace(text, "RTL navigation fixture", paragraphBreaks = true)
        val geometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
        val legal = Graphemes.stops(text).toSet()
        for (offset in legal) {
            e.move(Selection(offset, offset))
            val initial = e.snapshot().selection
            val startX = geometry.getHorizontalPosition(offset, true)
            val startLine = geometry.getLineForOffset(offset)
            key(KeyEvent.VK_LEFT)
            val left = e.snapshot().selection
            check(left.focus in legal && left.collapsed)
            val endLine = geometry.getLineForOffset(left.focus)
            val endX = geometry.getHorizontalPosition(left.focus, left.primary)
            check(endLine <= startLine && (endLine < startLine || endX <= startX + .5f)) { "Visual Left moved right: $text at $offset" }
            if (left != initial) {
                key(KeyEvent.VK_RIGHT)
                check(e.snapshot().selection == initial) { "Left/Right lost caret edge: $text at $offset" }
            }
            e.move(Selection(offset, offset))
            key(KeyEvent.VK_RIGHT)
            val right = e.snapshot().selection
            check(right.focus in legal && right.collapsed)
            if (right != initial) {
                key(KeyEvent.VK_LEFT)
                check(e.snapshot().selection == initial) { "Right/Left lost caret edge: $text at $offset" }
            }
            navigationChecks++
        }
        e.perform(EditAction.Undo)
        check(e.snapshot().visible.text.isEmpty())
    }

    // 5. Multi-line wrapping of long RTL paragraph and vertical arrow movement
    val longRtl = "اردو زبان برصغیر پاک و ہند کی ایک نہایت اہم، تاریخی اور خوبصورت زبان ہے جس کا تعلق ہند آریائی زبانوں کے وسیع خاندان سے ہے۔ یہ پاکستان کی قومی زبان اور متعدد ریاستوں میں تسلیم شدہ دفتری حیثیت رکھتی ہے۔"
    e.replace(longRtl, "Long RTL wrapping fixture")
    val wrapGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(wrapGeometry.totalLineCount >= 2) { "Long RTL text must wrap across multiple lines (got ${wrapGeometry.totalLineCount})" }
    check(wrapGeometry.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl) { "Long RTL paragraph must have RTL direction" }
    e.move(Selection(0, 0)) // start of RTL paragraph (line 0, visual right edge)
    key(KeyEvent.VK_DOWN)
    val downSelection = e.snapshot().selection
    val downLine = wrapGeometry.getLineForOffset(downSelection.focus)
    check(downLine == 1) { "VK_DOWN in wrapped RTL must move from line 0 to line 1" }
    key(KeyEvent.VK_UP)
    check(e.snapshot().selection.focus == 0) { "VK_UP must return to line 0 start in wrapped RTL" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 6. Multi-paragraph transitions with mixed content (RTL -> LTR -> RTL) and paragraph merge
    val multiParagraph = "اردو عنوان\nEnglish paragraph\nالعربية خاتمة"
    e.replace(multiParagraph, "Multi-paragraph fixture", paragraphBreaks = true)
    val multiGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(multiGeometry.paragraphs.size == 3)
    check(multiGeometry.totalLineCount >= 3)
    // Assert independent base directions for all three paragraphs:
    check(multiGeometry.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl) { "Paragraph 0 (Urdu) must have RTL direction" }
    check(multiGeometry.paragraphs[0].layout.getHorizontalPosition(0, true) > multiGeometry.textWidth * .9f) { "Paragraph 0 must align to right margin" }
    check(multiGeometry.paragraphs[1].baseDirection == ResolvedTextDirection.Ltr) { "Paragraph 1 (English) must have LTR direction" }
    check(multiGeometry.paragraphs[1].layout.getHorizontalPosition(0, true) < 5f) { "Paragraph 1 must align to left margin" }
    check(multiGeometry.paragraphs[2].baseDirection == ResolvedTextDirection.Rtl) { "Paragraph 2 (Arabic) must have RTL direction" }
    check(multiGeometry.paragraphs[2].layout.getHorizontalPosition(0, true) > multiGeometry.textWidth * .9f) { "Paragraph 2 must align to right margin" }

    // Vertical navigation across paragraph boundaries
    e.move(Selection(0, 0))
    key(KeyEvent.VK_DOWN)
    check(multiGeometry.getLineForOffset(e.snapshot().selection.focus) == 1) { "VK_DOWN must cross from line 0 to line 1" }
    key(KeyEvent.VK_DOWN)
    check(multiGeometry.getLineForOffset(e.snapshot().selection.focus) == 2) { "VK_DOWN must cross from line 1 to line 2" }
    key(KeyEvent.VK_UP)
    check(multiGeometry.getLineForOffset(e.snapshot().selection.focus) == 1) { "VK_UP must cross from line 2 back to line 1" }
    key(KeyEvent.VK_UP)
    check(multiGeometry.getLineForOffset(e.snapshot().selection.focus) == 0) { "VK_UP must cross from line 1 back to line 0" }
    // Merging paragraphs across boundary via Backspace
    val secondParaStart = multiParagraph.indexOf('\n') + 1
    e.move(Selection(secondParaStart, secondParaStart))
    key(KeyEvent.VK_BACK_SPACE)
    check(e.snapshot().visible.text == "اردو عنوانEnglish paragraph\nالعربية خاتمة") { "Backspace across paragraph break must merge paragraphs" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == multiParagraph) { "Undo must restore paragraph break and separation" }
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "اردو عنوانEnglish paragraph\nالعربية خاتمة")
    e.perform(EditAction.Undo)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 6b. Live paragraph creation via Enter in RTL and typing LTR
    e.replace("اردو", "RTL enter fixture")
    e.move(Selection(4, 4))
    key(KeyEvent.VK_ENTER)
    check(e.snapshot().visible.text == "اردو\n")
    check(e.snapshot().visible.units.last().kind == UnitKind.PARAGRAPH_BREAK)
    e.replace("English", "Typing", paragraphBreaks = false)
    check(e.snapshot().visible.text == "اردو\nEnglish")
    val enterGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(enterGeo.paragraphs.size == 2)
    check(enterGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl)
    check(enterGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Ltr)
    val engStart = e.snapshot().visible.text.indexOf("English")
    e.move(Selection(engStart, engStart))
    key(KeyEvent.VK_BACK_SPACE)
    check(e.snapshot().visible.text == "اردوEnglish")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "اردو\nEnglish")
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 7. Mouse hit testing: right margin vs left margin in RTL and drag selection
    val mouseText = "اردو متن برائے جانچ"
    e.replace(mouseText, "Mouse hit fixture")
    val mouseGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    val textWidth = mouseGeometry.textWidth
    val pos0 = mouseGeometry.getHorizontalPosition(0, true)
    val rightClickX = ((mouseGeometry.x + pos0) / mouseGeometry.scale).toInt()
    val leftClickX = ((mouseGeometry.x + 5f) / mouseGeometry.scale).toInt()
    val clickY = ((mouseGeometry.y + 10f) / mouseGeometry.scale).toInt()
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, rightClickX, clickY, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection.focus == 0) { "Mouse press at right margin of RTL text must place caret at offset 0 (start)" }
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, leftClickX, clickY, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection.focus == mouseText.length) { "Mouse press at left margin of RTL text must place caret at text.length (end)" }
    // 7b. Mouse drag selection: right to left (forward: 0 -> length) and left to right (backward: length -> 0)
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, rightClickX, clickY, 1, false, MouseEvent.BUTTON1))
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0, leftClickX, clickY, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection == Selection(0, mouseText.length)) { "Mouse drag right-to-left in RTL must select forward: ${e.snapshot().selection}" }
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, leftClickX, clickY, 1, false, MouseEvent.BUTTON1))
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0, rightClickX, clickY, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection == Selection(mouseText.length, 0)) { "Mouse drag left-to-right in RTL must select backward: ${e.snapshot().selection}" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 8. Attributed formatting on RTL text: Bold and Italic ranges
    val styledText = "اردو میں لکھیں"
    e.replace(styledText, "Styled RTL fixture")
    e.move(Selection(5, 8)) // Select "میں"
    e.toggleFormat(FormatKey.BOLD)
    check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold })
    check(e.snapshot().visible.units.take(5).none { it.style.bold })
    check(e.snapshot().visible.units.drop(8).none { it.style.bold })
    val styledGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    val boldSpan = styledGeo.paragraphs[0].layout.layoutInput.text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
    check(boldSpan.start == 5 && boldSpan.end == 8) { "Compose TextLayoutResult must reflect bold span range" }
    e.toggleFormat(FormatKey.ITALIC)
    check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold && it.style.italic })
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold && !it.style.italic })
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.units.none { it.style.bold })
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold })
    e.perform(EditAction.Undo)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 9. Document persistence (Save / Open) roundtrip with RTL, diacritics, Eastern digits and formatting
    val tempFile = Files.createTempFile("rtl-save-test-", ".astra")
    try {
        val complexDocUnits = mutableListOf<TextUnit>()
        var uId = 1L
        for (c in "English title\n") {
            complexDocUnits.add(
                TextUnit(
                    uId++,
                    c,
                    if (c == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                    style = if (c == '\n') TextStyleFlags() else TextStyleFlags(bold = true),
                    paragraphStyle = if (c == '\n') TextStyleFlags(bold = true) else TextStyleFlags(),
                ),
            )
        }
        for (c in "سَّلام: ۱۲۳۔") complexDocUnits.add(TextUnit(uId++, c, UnitKind.TEXT, TextStyleFlags(italic = true)))
        val complexDoc = Document(complexDocUnits, startStyle = TextStyleFlags())
        val encoded = DocumentFiles.encode(complexDoc)
        val decoded = DocumentFiles.decode(encoded)
        check(decoded.same(complexDoc)) { "DocumentFiles in-memory encode/decode must preserve RTL, diacritics, and styles exactly" }
        DocumentFiles.write(tempFile, complexDoc)
        val readBack = DocumentFiles.read(tempFile)
        check(readBack.same(complexDoc)) { "DocumentFiles disk write/read must roundtrip RTL document exactly" }
    } finally {
        Files.deleteIfExists(tempFile)
    }

    // 10. Single-layout bidi defect reproduction & English -> Urdu -> Arabic -> English orderings
    // 10a. Reproduce single-layout mixed-direction defect across both paragraphs
    // Under a single Compose TextLayoutResult, the base direction of the entire document
    // is globally determined by the first strong character (here 'E' in English -> LTR).
    // As a result, Paragraph 1 ("اردو") is incorrectly resolved as LTR instead of RTL,
    // and its text is pinned to the left margin (< maxWidth * 0.5f) instead of aligning to the right margin (> 0.9 * maxWidth).
    val singleMeasure = checkNotNull(surface.view.singleMeasure)
    val testMixedDoc = Document(
        "English\nاردو".mapIndexed { i, c ->
            TextUnit(i.toLong() + 1, c, if (c == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT)
        },
    )
    val singleLayout = singleMeasure(testMixedDoc)
    check(singleLayout.lineCount >= 2) { "Single layout must contain 2 lines" }
    // Paragraph 0 (line 0, English): LTR, left-aligned
    check(singleLayout.getLineForOffset(0) == 0)
    check(singleLayout.getParagraphDirection(0) == ResolvedTextDirection.Ltr)
    check(singleLayout.getHorizontalPosition(0, true) < 5f)
    // Paragraph 1 (line 1, Urdu at offset 8): incorrectly forced to LTR base direction
    check(singleLayout.getLineForOffset(8) == 1)
    val p1SingleDir = singleLayout.getParagraphDirection(8)
    check(p1SingleDir == ResolvedTextDirection.Ltr) { "Defect reproduced: single layout globally forces second paragraph into LTR" }
    val p1SingleX = singleLayout.getHorizontalPosition(8, true)
    check(p1SingleX < singleLayout.layoutInput.constraints.maxWidth * 0.5f) {
        "Defect reproduced: single layout prevents RTL paragraph from aligning to right margin: X=$p1SingleX"
    }

    // 10b. English -> Urdu -> Arabic -> English and reverse orderings under independent paragraph layout
    val forwardMixed = "English heading\nاردو متن\nالعربية هنا\nEnglish conclusion"
    e.replace(forwardMixed, "Forward mixed fixture", paragraphBreaks = true)
    val fwdGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(fwdGeo.paragraphs.size == 4)
    check(fwdGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr && fwdGeo.paragraphs[0].layout.getHorizontalPosition(0, true) < 5f)
    check(fwdGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl && fwdGeo.paragraphs[1].layout.getHorizontalPosition(0, true) > fwdGeo.textWidth * .9f)
    check(fwdGeo.paragraphs[2].baseDirection == ResolvedTextDirection.Rtl && fwdGeo.paragraphs[2].layout.getHorizontalPosition(0, true) > fwdGeo.textWidth * .9f)
    check(fwdGeo.paragraphs[3].baseDirection == ResolvedTextDirection.Ltr && fwdGeo.paragraphs[3].layout.getHorizontalPosition(0, true) < 5f)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    val reverseMixed = "English conclusion\nالعربية هنا\nاردو متن\nEnglish heading"
    e.replace(reverseMixed, "Reverse mixed fixture", paragraphBreaks = true)
    val revGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(revGeo.paragraphs.size == 4)
    check(revGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr && revGeo.paragraphs[0].layout.getHorizontalPosition(0, true) < 5f)
    check(revGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl && revGeo.paragraphs[1].layout.getHorizontalPosition(0, true) > revGeo.textWidth * .9f)
    check(revGeo.paragraphs[2].baseDirection == ResolvedTextDirection.Rtl && revGeo.paragraphs[2].layout.getHorizontalPosition(0, true) > revGeo.textWidth * .9f)
    check(revGeo.paragraphs[3].baseDirection == ResolvedTextDirection.Ltr && revGeo.paragraphs[3].layout.getHorizontalPosition(0, true) < 5f)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    val rtlStartMixed = "العربية بداية\nاردو وسط\nEnglish content\nالعربية نهاية"
    e.replace(rtlStartMixed, "RTL start fixture", paragraphBreaks = true)
    val rtlStartGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(rtlStartGeo.paragraphs.size == 4)
    check(rtlStartGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl && rtlStartGeo.paragraphs[0].layout.getHorizontalPosition(0, true) > rtlStartGeo.textWidth * .9f)
    check(rtlStartGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl && rtlStartGeo.paragraphs[1].layout.getHorizontalPosition(0, true) > rtlStartGeo.textWidth * .9f)
    check(rtlStartGeo.paragraphs[2].baseDirection == ResolvedTextDirection.Ltr && rtlStartGeo.paragraphs[2].layout.getHorizontalPosition(0, true) < 5f)
    check(rtlStartGeo.paragraphs[3].baseDirection == ResolvedTextDirection.Rtl && rtlStartGeo.paragraphs[3].layout.getHorizontalPosition(0, true) > rtlStartGeo.textWidth * .9f)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 11. Empty and neutral-only paragraphs under explicit documented direction policy
    // (a) Empty first paragraph defaults to LTR
    e.replace("", "Empty fixture")
    val emptyGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(emptyGeo.paragraphs.size == 1)
    check(emptyGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr)
    check(emptyGeo.caret(0).left < emptyGeo.x + 10f) { "Caret in empty initial paragraph must be at left margin" }

    // (b) Empty paragraph after RTL paragraph inherits RTL direction
    e.replace("اردو\n", "RTL trailing empty", paragraphBreaks = true)
    val rtlEmptyGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(rtlEmptyGeo.paragraphs.size == 2)
    check(rtlEmptyGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl)
    check(rtlEmptyGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl) { "Empty paragraph following RTL must inherit RTL base direction" }
    val trailingCaret = rtlEmptyGeo.caret(5) // offset 5 is in trailing empty paragraph
    check(trailingCaret.left > rtlEmptyGeo.x + rtlEmptyGeo.textWidth * .9f) { "Caret in empty paragraph following RTL must be at right margin: ${trailingCaret.left} vs ${rtlEmptyGeo.x + rtlEmptyGeo.textWidth * .9f}" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // (c) Neutral-only paragraph after RTL inherits RTL direction
    e.replace("العربية\n۱۲۳", "RTL followed by neutral digits", paragraphBreaks = true)
    val rtlNeutralGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(rtlNeutralGeo.paragraphs.size == 2)
    check(rtlNeutralGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl)
    check(rtlNeutralGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl) { "Neutral digits following RTL must inherit RTL base direction" }
    check(rtlNeutralGeo.paragraphs[1].layout.getHorizontalPosition(0, true) > rtlNeutralGeo.textWidth * .9f)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // (d) Empty paragraph after LTR inherits LTR
    e.replace("English\n", "LTR trailing empty", paragraphBreaks = true)
    val ltrEmptyGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(ltrEmptyGeo.paragraphs.size == 2)
    check(ltrEmptyGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr)
    check(ltrEmptyGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Ltr)
    val ltrTrailingCaret = ltrEmptyGeo.caret(8)
    check(ltrTrailingCaret.left < ltrEmptyGeo.x + 10f)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // (e) Neutral-only paragraph after LTR inherits LTR
    e.replace("English\n123", "LTR followed by neutral digits", paragraphBreaks = true)
    val ltrNeutralGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(ltrNeutralGeo.paragraphs.size == 2)
    check(ltrNeutralGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr)
    check(ltrNeutralGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Ltr)
    check(ltrNeutralGeo.paragraphs[1].layout.getHorizontalPosition(0, true) < 5f)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 12. Structural breaks vs soft breaks
    // (a) Structural break creates 2 paragraphs with independent base directions
    e.replace("English\nاردو", "Structural break fixture", paragraphBreaks = true)
    val structGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(structGeo.paragraphs.size == 2)
    check(structGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr)
    check(structGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // (b) Soft break (Shift+Enter / literal LF) preserves single paragraph layout
    e.replace("English\nاردو", "Soft break fixture", paragraphBreaks = false)
    val softGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(softGeo.paragraphs.size == 1) { "Soft break must remain inside a single structural paragraph" }
    check(softGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr) { "Soft break lines share the single paragraph's base direction" }
    check(softGeo.totalLineCount >= 2)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 13. Mouse placement and drag selection spanning paragraphs
    val spanDoc = "English paragraph\nاردو پیراگراف\nالعربية فقرة"
    e.replace(spanDoc, "Multi-paragraph span fixture", paragraphBreaks = true)
    val spanGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(spanGeo.paragraphs.size == 3)
    val p0Mid = spanDoc.indexOf("paragraph") + 2
    val p1Mid = spanDoc.indexOf("پیراگراف") + 2
    e.move(Selection(p0Mid, p1Mid))
    check(e.snapshot().selection == Selection(p0Mid, p1Mid))
    check(!e.snapshot().selection.collapsed)
    // Drag selection simulation across paragraphs: click in P0, drag into P1
    val p0Caret = spanGeo.caret(p0Mid)
    val p1Caret = spanGeo.caret(p1Mid)
    val clickX0 = (p0Caret.left / spanGeo.scale).toInt()
    val clickY0 = ((p0Caret.top + p0Caret.bottom) / 2 / spanGeo.scale).toInt()
    val dragX1 = (p1Caret.left / spanGeo.scale).toInt()
    val dragY1 = ((p1Caret.top + p1Caret.bottom) / 2 / spanGeo.scale).toInt()
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, clickX0, clickY0, 1, false, MouseEvent.BUTTON1))
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0, dragX1, dragY1, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection.start < spanDoc.indexOf('\n') && e.snapshot().selection.end > spanDoc.indexOf('\n')) { "Drag across paragraph break must select across paragraph boundary: ${e.snapshot().selection}" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 14. Left/Right and Up/Down boundary movement across mixed-direction paragraphs
    val bidiCross = "English\nاردو"
    e.replace(bidiCross, "Bidi cross fixture", paragraphBreaks = true)
    val bidiGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(bidiGeo.paragraphs.size == 2)
    // Caret at end of English paragraph (offset 7, visual right of line 0)
    e.move(Selection(7, 7))
    key(KeyEvent.VK_RIGHT)
    // Moving Right transitions across paragraph boundary into line 1 at visual left (offset 12)
    check(bidiGeo.getLineForOffset(e.snapshot().selection.focus) == 1) { "Moving Right must enter next paragraph line" }
    check(e.snapshot().selection.focus == 12) { "Visual Right enters RTL paragraph at visual left edge: ${e.snapshot().selection}" }
    // Traversing Right across RTL line reaches start of Urdu at visual right margin (offset 8)
    for (i in 0 until 4) key(KeyEvent.VK_RIGHT)
    check(e.snapshot().selection.focus == 8) { "Right traversal reaches RTL start at visual right margin: ${e.snapshot().selection}" }
    val urduCaret = bidiGeo.caret(8)
    check(urduCaret.left > bidiGeo.x + bidiGeo.textWidth * .9f) { "Start of Urdu paragraph must be at right margin: ${urduCaret.left}" }
    // Moving Left returns across Urdu line and then across paragraph boundary back to English paragraph end
    for (i in 0 until 5) key(KeyEvent.VK_LEFT)
    check(e.snapshot().selection.focus == 7) { "Moving backward across boundary must return to preceding paragraph end: ${e.snapshot().selection}" }
    // Down arrow from English (left) to Urdu (closest stop to preferredX)
    e.move(Selection(0, 0)) // start of English at visual left
    key(KeyEvent.VK_DOWN)
    check(bidiGeo.getLineForOffset(e.snapshot().selection.focus) == 1) { "VK_DOWN must cross to line 1" }
    key(KeyEvent.VK_UP)
    check(bidiGeo.getLineForOffset(e.snapshot().selection.focus) == 0 && e.snapshot().selection.focus == 0) { "VK_UP must return to line 0 start" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 15. Enter split, Backspace/Delete join, formatting and exact Undo/Redo
    e.replace("شمس وقمر", "Arabic split fixture")
    e.move(Selection(3, 3)) // after "شمس"
    key(KeyEvent.VK_ENTER)
    check(e.snapshot().visible.text == "شمس\n وقمر")
    val splitGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(splitGeo.paragraphs.size == 2)
    check(splitGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Rtl)
    check(splitGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "شمس وقمر")
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "شمس\n وقمر")

    // Backspace at offset 4 (start of P1) merges paragraphs
    val splitMid = e.snapshot().visible.text.indexOf('\n') + 1
    e.move(Selection(splitMid, splitMid))
    key(KeyEvent.VK_BACK_SPACE)
    check(e.snapshot().visible.text == "شمس وقمر")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "شمس\n وقمر")

    // Delete at offset 3 (end of P0) merges paragraphs
    e.move(Selection(3, 3))
    surface.dispatchEvent(KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED))
    check(e.snapshot().visible.text == "شمس وقمر")
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text == "شمس\n وقمر")

    // Formatting across paragraph boundary: select across '\n'
    e.move(Selection(2, 6))
    e.toggleFormat(FormatKey.BOLD)
    check(e.snapshot().visible.units.subList(2, 6).filter { it.kind != UnitKind.PARAGRAPH_BREAK }.all { it.style.bold })
    check(!e.snapshot().visible.units[3].style.bold) { "Paragraph break must remain unstyled" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.units.none { it.style.bold })
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.units.subList(2, 6).filter { it.kind != UnitKind.PARAGRAPH_BREAK }.all { it.style.bold })
    check(!e.snapshot().visible.units[3].style.bold) { "Paragraph break must remain unstyled" }
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 16. Composition geometry after scrolling
    e.replace("Paragraph 1\nParagraph 2\nParagraph 3\nParagraph 4\nParagraph 5", "Scroll test fixture", paragraphBreaks = true)
    e.move(Selection(0, 0))
    val initialLoc = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    surface.view.scroll = 50f
    val scrolledLoc = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    check(scrolledLoc.y < initialLoc.y) { "Scrolling down must shift candidate popup location upward: $scrolledLoc vs $initialLoc" }
    // Test active composition while scrolled
    e.inputMethod("active preedit", 0, 6)
    val compLocScrolled = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    check(compLocScrolled.y < initialLoc.y) { "Active preedit popup location must incorporate current scroll offset: $compLocScrolled vs $initialLoc" }
    surface.view.scroll = 0f
    val compLocUnscrolled = checkNotNull(surface.getTextLocation(TextHitInfo.leading(0)))
    check(compLocUnscrolled.y > compLocScrolled.y) { "Resetting scroll must move preedit popup downward" }
    e.abortComposition()
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 17. Save/Open preserves text, styles and structural-break distinctions
    val saveDocFile = Files.createTempFile("para-save-test-", ".astra")
    try {
        val testUnits = mutableListOf<TextUnit>()
        var uid = 1L
        for (c in "English bold\n") {
            testUnits.add(
                TextUnit(
                    uid++,
                    c,
                    if (c == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                    style = if (c == '\n') TextStyleFlags() else TextStyleFlags(bold = true),
                    paragraphStyle = if (c == '\n') TextStyleFlags(bold = true) else TextStyleFlags(),
                ),
            )
        }
        for (c in "اردو soft\nbreak\n") {
            val isParaBreak = (c == '\n' && uid == 23L)
            testUnits.add(
                TextUnit(
                    uid++,
                    c,
                    if (isParaBreak) UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT,
                    style = if (isParaBreak) TextStyleFlags() else TextStyleFlags(italic = true),
                    paragraphStyle = if (isParaBreak) TextStyleFlags(italic = true) else TextStyleFlags(),
                ),
            )
        }
        val docToSave = Document(testUnits)
        DocumentFiles.write(saveDocFile, docToSave)
        val loadedDoc = DocumentFiles.read(saveDocFile)
        check(loadedDoc.same(docToSave)) { "Loaded document must match saved document exactly" }
        val loadedSpans = partitionParagraphs(loadedDoc)
        check(loadedSpans.size >= 2) { "Loaded document must preserve structural paragraphs" }
        check(loadedDoc.units.any { it.kind == UnitKind.PARAGRAPH_BREAK } && loadedDoc.units.any { it.kind == UnitKind.TEXT && it.char == '\n' }) { "Document must preserve both structural breaks and soft breaks" }
    } finally {
        Files.deleteIfExists(saveDocFile)
    }

    // 17b. Independent fixture expectations and uncached reference layout comparison (KF-4)
    val refMeasure = checkNotNull(surface.view.referenceMeasure)
    val refDoc = Document(
        "First paragraph English\nدوسرا پیراگراف اردو\nThird paragraph".mapIndexed { i, c ->
            TextUnit(i.toLong() + 1, c, if (c == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT)
        },
    )
    e.replace(refDoc.text, "Reference comparison fixture", paragraphBreaks = true)
    val cachedGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    // Independent hardcoded fixture expectations (not derived from referenceMeasure or measureParagraphs)
    check(cachedGeo.paragraphs.size == 3)
    check(cachedGeo.paragraphs[0].startOffset == 0 && cachedGeo.paragraphs[0].endOffset == 23 && cachedGeo.paragraphs[0].hasBreak && cachedGeo.paragraphs[0].baseDirection == ResolvedTextDirection.Ltr)
    check(cachedGeo.paragraphs[1].startOffset == 24 && cachedGeo.paragraphs[1].endOffset == 43 && cachedGeo.paragraphs[1].hasBreak && cachedGeo.paragraphs[1].baseDirection == ResolvedTextDirection.Rtl)
    check(cachedGeo.paragraphs[2].startOffset == 44 && cachedGeo.paragraphs[2].endOffset == 59 && !cachedGeo.paragraphs[2].hasBreak && cachedGeo.paragraphs[2].baseDirection == ResolvedTextDirection.Ltr)
    val refResults = refMeasure(refDoc)
    check(cachedGeo.paragraphs.size == refResults.size) { "Paragraph counts must match between cached and uncached reference measure" }
    for (idx in refResults.indices) {
        val (refSpan, refLayout) = refResults[idx]
        val cachedPara = cachedGeo.paragraphs[idx]
        check(refSpan.startOffset == cachedPara.startOffset)
        check(refSpan.endOffset == cachedPara.endOffset)
        check(refSpan.hasBreak == cachedPara.hasBreak)
        check(refLayout.lineCount == cachedPara.layout.lineCount)
        check(refLayout.size.height == cachedPara.layout.size.height)
        check(refLayout.getParagraphDirection(0) == cachedPara.layout.getParagraphDirection(0))
    }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 18. Layout benchmark: multi-paragraph document vs one long paragraph
    val multiParaBuilder = StringBuilder()
    for (i in 1..50) {
        val line = when (i % 3) {
            0 -> "English paragraph number $i with standard text formatting."
            1 -> "اردو پیراگراف نمبر $i جس میں متن دائیں طرف سے شروع ہوتا ہے۔"
            else -> "الفقرة العربية رقم $i مع اتجاه النص من اليمين إلى اليسار."
        }
        multiParaBuilder.append(line).append(if (i < 50) "\n" else "")
    }
    e.replace(multiParaBuilder.toString(), "50-paragraph fixture", paragraphBreaks = true)
    val t0 = System.nanoTime()
    val benchMultiGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    val multiDurationMs = (System.nanoTime() - t0) / 1_000_000.0
    check(benchMultiGeo.paragraphs.size == 50) { "Must measure exactly 50 paragraphs" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    val longParaWords = (1..2000).joinToString(" ") { if (it % 2 == 0) "word$it" else "text$it" }
    e.replace(longParaWords, "Long paragraph fixture", paragraphBreaks = false)
    val t1 = System.nanoTime()
    val benchLongGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    val longDurationMs = (System.nanoTime() - t1) / 1_000_000.0
    check(benchLongGeo.paragraphs.size == 1) { "Must measure exactly 1 long paragraph" }
    check(benchLongGeo.totalLineCount >= 50) { "Long paragraph must wrap across many lines" }
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 19a. UAX #9 Directional Isolates in paragraphBaseDirection (KF-1)
    val isoCase1 = "\u2067\u0627\u2069A"
    val isoUnits1 = isoCase1.mapIndexed { idx, ch -> TextUnit(idx.toLong() + 1, ch) }
    check(paragraphBaseDirection(isoUnits1, ResolvedTextDirection.Rtl) == ResolvedTextDirection.Ltr) {
        "RLI..PDI isolate around Arabic followed by Latin 'A' must resolve to Ltr"
    }
    check(java.text.Bidi(isoCase1, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight())

    val isoCase2 = "\u2066A\u2069\u0627"
    val isoUnits2 = isoCase2.mapIndexed { idx, ch -> TextUnit(idx.toLong() + 1, ch) }
    check(paragraphBaseDirection(isoUnits2, ResolvedTextDirection.Ltr) == ResolvedTextDirection.Rtl) {
        "LRI..PDI isolate around Latin followed by Arabic Alef must resolve to Rtl"
    }
    check(!java.text.Bidi(isoCase2, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight())

    // 19b. Structural-break-only & empty-paragraph selection geometry (KF-2) and paragraphForOffset boundary stability (KF-3)
    e.replace("ab\n\ncd", "Break and empty selection fixture", paragraphBreaks = true)
    val kfGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(kfGeo.paragraphs.size == 3)
    // KF-3: Offset 2 is the end/break of paragraph 0; must never jump to paragraph 1
    val pAt2 = kfGeo.paragraphForOffset(2)
    check(pAt2.index == 0 && pAt2.toLocalOffset(2) == 2) { "paragraphForOffset(2) must stay in paragraph 0 at local offset 2" }
    val pAt3 = kfGeo.paragraphForOffset(3)
    check(pAt3.index == 1 && pAt3.toLocalOffset(3) == 0) { "paragraphForOffset(3) must map to empty paragraph 1 at local offset 0" }
    // KF-2: Selecting only the break at [2, 3) or the empty paragraph at [3, 4) produces visible selection rects
    val breakRects = kfGeo.breakAndEmptySelectionRects(Selection(2, 3))
    check(breakRects.size == 1 && breakRects[0].width > 0f && breakRects[0].height > 0f) { "Break-only selection [2,3) must produce visible highlight rect" }
    check(kfGeo.hasRenderedSelection(Selection(2, 3)))
    val emptyParaRects = kfGeo.breakAndEmptySelectionRects(Selection(3, 4))
    check(emptyParaRects.size == 1 && emptyParaRects[0].width > 0f && emptyParaRects[0].height > 0f) { "Empty-paragraph selection [3,4) must produce visible highlight rect" }
    check(kfGeo.hasRenderedSelection(Selection(3, 4)))
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    // 19c. Component resize before repaint (KF-6) and wrapped line VK_END soft-wrap affinity (B-1/B-2/B-3)
    val cjkWrapText = "あいうえおかきくけこ".repeat(12)
    e.replace(cjkWrapText, "CJK wrap fixture")
    val initialW = surface.width
    val initialH = surface.height
    val geoBefore = checkNotNull(surface.geometry())
    check(geoBefore.hostWidth == initialW)

    val newW = 500
    val newH = initialH
    surface.setSize(newW, newH)
    // Query EditorSurface.geometry() immediately BEFORE any Compose repaint pass
    val geoResized = checkNotNull(surface.geometry())
    check(geoResized.hostWidth == newW) { "EditorSurface.geometry() must invalidate on component width change before repaint" }
    // Paper layout is fixed in document units; resizing changes the host transform,
    // not paragraph wrapping. Both views use the same captured paper layout.
    check(geoResized.textWidth == geoBefore.textWidth) { "Resize must preserve document text width" }
    check(geoResized.scale > geoBefore.scale) { "Narrower host must immediately recompute the fit transform" }
    check(geoResized.paragraphs.map { it.layout.lineCount } == geoBefore.paragraphs.map { it.layout.lineCount }) { "Host resize must not repaginate fixed paper" }
    check(geoResized.totalLineCount >= 2) { "CJK fixture must wrap across at least 2 lines" }

    val line0WrapBoundary = geoResized.paragraphs[0].layout.getLineEnd(0, false)
    e.move(Selection(0, 0))
    key(KeyEvent.VK_END)
    val endSel = e.snapshot().selection
    check(endSel.focus == line0WrapBoundary) { "VK_END on wrapped line 0 must land on exact wrap boundary $line0WrapBoundary, got ${endSel.focus}" }
    check(endSel.wrapAffinity == WrapAffinity.UPSTREAM) { "VK_END at soft wrap boundary must adopt UPSTREAM affinity" }
    check(geoResized.getLineForOffset(endSel.focus, WrapAffinity.UPSTREAM) == 0) { "UPSTREAM affinity at wrap boundary must stay on line 0" }
    check(geoResized.getLineForOffset(endSel.focus, WrapAffinity.DOWNSTREAM) == 1) { "DOWNSTREAM affinity at wrap boundary must map to line 1" }
    val caretUpstream = geoResized.caret(endSel.focus, endSel.primary, WrapAffinity.UPSTREAM)
    val caretDownstream = geoResized.caret(endSel.focus, endSel.primary, WrapAffinity.DOWNSTREAM)
    val caretLine0Start = geoResized.caret(0)
    check(kotlin.math.abs(caretUpstream.top - caretLine0Start.top) < 1f) { "UPSTREAM caret must render on line 0" }
    check(caretUpstream.left > caretLine0Start.left + geoResized.textWidth * 0.4f) { "UPSTREAM caret must render at right edge of line 0" }
    check(caretDownstream.top > caretUpstream.top + 5f) { "DOWNSTREAM caret at same offset must render on line 1" }

    surface.setSize(initialW, initialH)
    check(checkNotNull(surface.geometry()).hostWidth == initialW)
    e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())

    println("PASS Urdu/Arabic RTL page alignment, visual selection collapse, Shift+Left, format/Undo and diacritic deletion")
    println("PASS Urdu/Arabic multi-mark combining clusters, medial letter deletion, Eastern digits and punctuation")
    println("PASS Multi-line RTL wrapping, vertical navigation, cross-paragraph transitions, merge and mouse hit testing")
    println("PASS RTL bold/italic attributed styling and exact .astra persistence roundtrip")
    println("PASS $navigationChecks mixed-script caret positions: legal grapheme stops, visual direction and reversible edge traversal")
    println("PASS Single-layout bidi defect reproduced on both paragraphs; independent paragraph layout verified")
    println("PASS Independent paragraph layout: English -> Urdu -> Arabic -> English and reverse orderings")
    println("PASS Paragraph direction policy: empty and neutral-only paragraphs inherit preceding base direction")
    println("PASS Structural break vs soft break preservation in layout and file persistence")
    println("PASS Cross-paragraph mouse selection, boundary traversal, Enter split and Backspace/Delete join")
    println("PASS Uncached reference layout comparison matches multi-paragraph layout structure")
    println("PASS Multi-paragraph layout benchmark (50 paragraphs: ${multiDurationMs.toInt()} ms; 2,000 words: ${longDurationMs.toInt()} ms)")
    println("PASS UAX #9 directional isolates (KF-1), break/empty selection rects (KF-2), paragraphForOffset stability (KF-3), resize invalidation (KF-6) and soft-wrap VK_END affinity (B-1/B-2/B-3)")
}
