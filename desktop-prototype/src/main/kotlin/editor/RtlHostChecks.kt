package editor

import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.ResolvedTextDirection

/** Real Compose geometry and AWT dispatch, with generated fixtures rather than an OS keyboard. */
fun runRtlHostChecks(surface: EditorSurface) {
    val e = surface.engine
    fun key(code: Int, shift: Boolean = false) = surface.dispatchEvent(
        KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), if (shift) KeyEvent.SHIFT_DOWN_MASK else 0, code, KeyEvent.CHAR_UNDEFINED)
    )

    // 1. Basic RTL paragraph alignment, visual selection collapse, Shift+Left, format/Undo
    for (text in listOf("اردو میں لکھیں", "العربية جميلة", "اردو English 123 العربية")) {
        e.replace(text, "RTL fixture"); e.move(Selection(0, 0))
        val g = checkNotNull(surface.view.measure).invoke(e.snapshot())
        check(g.layout.getParagraphDirection(0) == ResolvedTextDirection.Rtl)
        check(g.layout.getHorizontalPosition(0, true) > g.layout.layoutInput.constraints.maxWidth * .9f) { "RTL paragraph must start at the right page margin" }
        e.selectAll(); key(KeyEvent.VK_LEFT); check(e.snapshot().selection == Selection(text.length, text.length)) { "Left must collapse RTL selection to its visual left edge" }
        e.selectAll(); key(KeyEvent.VK_RIGHT); check(e.snapshot().selection == Selection(0, 0)) { "Right must collapse RTL selection to its visual right edge" }
        key(KeyEvent.VK_LEFT, true); check(e.snapshot().selection == Selection(0, Graphemes.stops(text)[1]))
        e.toggleFormat(FormatKey.BOLD); check(e.snapshot().visible.units.first().style.bold)
        e.perform(EditAction.Undo); check(e.snapshot().visible.units.none { it.style.bold })
        e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())
    }

    // 2. Combining marks, tashkeel, harkat and medial letter deletion
    // 2a. Single combining mark (fatha)
    e.replace("سَ", "RTL combining fixture"); e.delete(true); check(e.snapshot().visible.text.isEmpty())
    e.perform(EditAction.Undo); check(e.snapshot().visible.text == "سَ")
    e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 2b. Multiple combining marks: Base + Shaddah (U+0651) + Fatha (U+064E) forming single cluster
    val shaddahFatha = "سَّلام"
    val clusterStops = Graphemes.stops(shaddahFatha)
    check(clusterStops.containsAll(listOf(0, 3, 4, 5, 6))) { "Shaddah + Fatha cluster must snap to 3 UTF-16 units" }
    e.replace(shaddahFatha, "Shaddah+Fatha fixture"); e.move(Selection(3, 3))
    e.delete(true) // Backspace deletes the entire Base+Shaddah+Fatha cluster
    check(e.snapshot().visible.text == "لام") { "Deleting cluster with multiple marks must leave remaining characters intact" }
    e.perform(EditAction.Undo); check(e.snapshot().visible.text == shaddahFatha)
    e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 2c. Medial joining letter deletion and Undo: "کتاب" -> delete 'ت' at offset 2 -> "کاب"
    val medialWord = "کتاب"
    e.replace(medialWord, "Medial letter fixture"); e.move(Selection(2, 2))
    e.delete(true)
    check(e.snapshot().visible.text == "کاب") { "Deleting medial joining letter must preserve preceding and succeeding text" }
    e.perform(EditAction.Undo); check(e.snapshot().visible.text == medialWord)
    e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 3. Mixed digits, Arabic punctuation, and bidi transitions
    val mixedPunctuation = "اردو: ۱۲۳، English: 456؛ العربية: ۷۸۹؟"
    e.replace(mixedPunctuation, "Mixed digits and punctuation fixture")
    val mixedGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(mixedGeometry.layout.getParagraphDirection(0) == ResolvedTextDirection.Rtl)
    e.move(Selection(0, 0))
    val stopsPunct = Graphemes.stops(mixedPunctuation).toSet()
    for (stop in stopsPunct) {
        e.move(Selection(stop, stop))
        check(e.snapshot().selection.focus in stopsPunct)
    }
    e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 4. Visual Left/Right arrow navigation across 6 diverse mixed-script texts
    var navigationChecks = 0
    for (text in listOf("اردو میں لکھیں", "العربية جميلة", "اردو English 123 العربية", "abc العربية ١٢٣ xyz", "اردو\nEnglish\nالعربية", "سَلام 👩‍💻 دنیا")) {
        e.replace(text, "RTL navigation fixture", paragraphBreaks = true)
        val geometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
        val legal = Graphemes.stops(text).toSet()
        for (offset in legal) {
            e.move(Selection(offset, offset)); val initial = e.snapshot().selection
            val startX = geometry.layout.getHorizontalPosition(offset, true)
            val startLine = geometry.layout.getLineForOffset(offset)
            key(KeyEvent.VK_LEFT)
            val left = e.snapshot().selection
            check(left.focus in legal && left.collapsed)
            val endLine = geometry.layout.getLineForOffset(left.focus)
            val endX = geometry.layout.getHorizontalPosition(left.focus, left.primary)
            check(endLine <= startLine && (endLine < startLine || endX <= startX + .5f)) { "Visual Left moved right: $text at $offset" }
            if (left != initial) { key(KeyEvent.VK_RIGHT); check(e.snapshot().selection == initial) { "Left/Right lost caret edge: $text at $offset" } }
            e.move(Selection(offset, offset)); key(KeyEvent.VK_RIGHT)
            val right = e.snapshot().selection
            check(right.focus in legal && right.collapsed)
            if (right != initial) { key(KeyEvent.VK_LEFT); check(e.snapshot().selection == initial) { "Right/Left lost caret edge: $text at $offset" } }
            navigationChecks++
        }
        e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())
    }

    // 5. Multi-line wrapping of long RTL paragraph and vertical arrow movement
    val longRtl = "اردو زبان برصغیر پاک و ہند کی ایک نہایت اہم، تاریخی اور خوبصورت زبان ہے جس کا تعلق ہند آریائی زبانوں کے وسیع خاندان سے ہے۔ یہ پاکستان کی قومی زبان اور متعدد ریاستوں میں تسلیم شدہ دفتری حیثیت رکھتی ہے۔"
    e.replace(longRtl, "Long RTL wrapping fixture")
    val wrapGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(wrapGeometry.layout.lineCount >= 2) { "Long RTL text must wrap across multiple lines (got ${wrapGeometry.layout.lineCount})" }
    for (lineIdx in 0 until wrapGeometry.layout.lineCount) {
        val lineStart = wrapGeometry.layout.getLineStart(lineIdx)
        check(wrapGeometry.layout.getParagraphDirection(lineStart) == ResolvedTextDirection.Rtl) { "Every line in RTL paragraph must have RTL direction" }
    }
    e.move(Selection(0, 0)) // start of RTL paragraph (line 0, visual right edge)
    key(KeyEvent.VK_DOWN)
    val downSelection = e.snapshot().selection
    val downLine = wrapGeometry.layout.getLineForOffset(downSelection.focus)
    check(downLine == 1) { "VK_DOWN in wrapped RTL must move from line 0 to line 1" }
    key(KeyEvent.VK_UP)
    check(e.snapshot().selection.focus == 0) { "VK_UP must return to line 0 start in wrapped RTL" }
    e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 6. Multi-paragraph transitions with mixed content (RTL -> LTR -> RTL) and paragraph merge
    val multiParagraph = "اردو عنوان\nEnglish paragraph\nالعربية خاتمة"
    e.replace(multiParagraph, "Multi-paragraph fixture", paragraphBreaks = true)
    val multiGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    check(multiGeometry.layout.lineCount >= 3)
    val baseDir = multiGeometry.layout.getParagraphDirection(0)
    check(baseDir == ResolvedTextDirection.Rtl) { "Multi-paragraph document starting with RTL text must have RTL base direction" }
    // Vertical navigation across paragraph boundaries
    e.move(Selection(0, 0))
    key(KeyEvent.VK_DOWN); check(multiGeometry.layout.getLineForOffset(e.snapshot().selection.focus) == 1) { "VK_DOWN must cross from line 0 to line 1" }
    key(KeyEvent.VK_DOWN); check(multiGeometry.layout.getLineForOffset(e.snapshot().selection.focus) == 2) { "VK_DOWN must cross from line 1 to line 2" }
    key(KeyEvent.VK_UP); check(multiGeometry.layout.getLineForOffset(e.snapshot().selection.focus) == 1) { "VK_UP must cross from line 2 back to line 1" }
    key(KeyEvent.VK_UP); check(multiGeometry.layout.getLineForOffset(e.snapshot().selection.focus) == 0) { "VK_UP must cross from line 1 back to line 0" }
    // Merging paragraphs across boundary via Backspace
    val secondParaStart = multiParagraph.indexOf('\n') + 1
    e.move(Selection(secondParaStart, secondParaStart))
    key(KeyEvent.VK_BACK_SPACE)
    check(e.snapshot().visible.text == "اردو عنوانEnglish paragraph\nالعربية خاتمة") { "Backspace across paragraph break must merge paragraphs" }
    e.perform(EditAction.Undo); check(e.snapshot().visible.text == multiParagraph) { "Undo must restore paragraph break and separation" }
    e.perform(EditAction.Redo); check(e.snapshot().visible.text == "اردو عنوانEnglish paragraph\nالعربية خاتمة")
    e.perform(EditAction.Undo); e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 7. Mouse hit testing: right margin vs left margin in RTL
    val mouseText = "اردو متن برائے جانچ"
    e.replace(mouseText, "Mouse hit fixture")
    val mouseGeometry = checkNotNull(surface.view.measure).invoke(e.snapshot())
    val textWidth = mouseGeometry.layout.layoutInput.constraints.maxWidth
    val pos0 = mouseGeometry.layout.getHorizontalPosition(0, true)
    val rightClickX = ((mouseGeometry.x + pos0) / mouseGeometry.scale).toInt()
    val leftClickX = ((mouseGeometry.x + 5f) / mouseGeometry.scale).toInt()
    val clickY = ((mouseGeometry.y + 10f) / mouseGeometry.scale).toInt()
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, rightClickX, clickY, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection.focus == 0) { "Mouse press at right margin of RTL text must place caret at offset 0 (start)" }
    surface.dispatchEvent(MouseEvent(surface, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, leftClickX, clickY, 1, false, MouseEvent.BUTTON1))
    check(e.snapshot().selection.focus == mouseText.length) { "Mouse press at left margin of RTL text must place caret at text.length (end)" }
    e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 8. Attributed formatting on RTL text: Bold and Italic ranges
    val styledText = "اردو میں لکھیں"
    e.replace(styledText, "Styled RTL fixture")
    e.move(Selection(5, 8)) // Select "میں"
    e.toggleFormat(FormatKey.BOLD)
    check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold })
    check(e.snapshot().visible.units.take(5).none { it.style.bold })
    check(e.snapshot().visible.units.drop(8).none { it.style.bold })
    val styledGeo = checkNotNull(surface.view.measure).invoke(e.snapshot())
    val boldSpan = styledGeo.layout.layoutInput.text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
    check(boldSpan.start == 5 && boldSpan.end == 8) { "Compose TextLayoutResult must reflect bold span range" }
    e.toggleFormat(FormatKey.ITALIC)
    check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold && it.style.italic })
    e.perform(EditAction.Undo); check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold && !it.style.italic })
    e.perform(EditAction.Undo); check(e.snapshot().visible.units.none { it.style.bold })
    e.perform(EditAction.Redo); check(e.snapshot().visible.units.subList(5, 8).all { it.style.bold })
    e.perform(EditAction.Undo); e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty())

    // 9. Document persistence (Save / Open) roundtrip with RTL, diacritics, Eastern digits and formatting
    val tempFile = Files.createTempFile("rtl-save-test-", ".astra")
    try {
        val complexDocUnits = mutableListOf<TextUnit>()
        var uId = 1L
        for (c in "English title\n") complexDocUnits.add(TextUnit(uId++, c, if (c == '\n') UnitKind.PARAGRAPH_BREAK else UnitKind.TEXT, TextStyleFlags(bold = true)))
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

    println("PASS Urdu/Arabic RTL page alignment, visual selection collapse, Shift+Left, format/Undo and diacritic deletion")
    println("PASS Urdu/Arabic multi-mark combining clusters, medial letter deletion, Eastern digits and punctuation")
    println("PASS Multi-line RTL wrapping, vertical navigation, cross-paragraph transitions, merge and mouse hit testing")
    println("PASS RTL bold/italic attributed styling and exact .astra persistence roundtrip")
    println("PASS $navigationChecks mixed-script caret positions: legal grapheme stops, visual direction and reversible edge traversal")
}
