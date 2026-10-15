package editor

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

fun runFormatChecks() {
    var cases = 0
    fun test(name: String, body: () -> Unit) { body(); cases++; println("PASS $name") }
    val bold = TextStyleFlags(bold = true); val italic = TextStyleFlags(italic = true)
    fun doc(text: String, styles: List<TextStyleFlags>) = Document(text.mapIndexed { i,c -> TextUnit(i.toLong(), c, style = styles[i]) })
    test("collapsed toggle changes intent only; inserted style and intent restore through Undo") {
        val e = EditorEngine(); val rev = e.snapshot().documentRevision
        e.toggleFormat(FormatKey.BOLD)
        check(e.snapshot().documentRevision == rev && e.snapshot().undoCount == 0)
        e.replace("Bold"); check(e.snapshot().visible.units.all { it.style == bold })
        e.perform(EditAction.Undo); check(e.snapshot().visible.text.isEmpty()); check(e.snapshot().typingStyle == bold)
        e.perform(EditAction.Redo); check(e.snapshot().visible.units.all { it.style == bold })
    }
    test("style change separates contiguous typing history") {
        val e = EditorEngine { 1000L }; e.replace("a"); e.toggleFormat(FormatKey.BOLD); e.replace("b")
        check(e.snapshot().undoCount == 2); e.perform(EditAction.Undo); check(e.snapshot().visible.text == "a")
        e.perform(EditAction.Redo); check(e.snapshot().visible.units.map { it.style } == listOf(TextStyleFlags(), bold))
    }
    test("logical boundary chooses one side and relocation clears explicit intent") {
        val original = doc("ab", listOf(bold, italic)); val e = EditorEngine(original)
        e.move(Selection(1,1),side=TypingSide.LEFT); e.replace("L"); check(e.snapshot().visible.units[1].style == bold)
        e.perform(EditAction.Undo); e.move(Selection(1,1),side=TypingSide.RIGHT); e.replace("R"); check(e.snapshot().visible.units[1].style == italic)
        e.perform(EditAction.Undo); e.move(Selection(0,0)); check(e.snapshot().typingStyle == bold)
        e.move(Selection(2,2)); check(e.snapshot().typingStyle == italic)
    }
    test("range replacement seed ignores selection direction and supports plain paste") {
        for (selection in listOf(Selection(0,2),Selection(2,0))) {
            val e=EditorEngine(doc("ab",listOf(bold,italic)));e.move(selection);e.replace("paste","Paste")
            check(e.snapshot().visible.units.all { it.style == bold });e.perform(EditAction.Undo)
            check(e.snapshot().selection==selection && e.snapshot().visible.units.map { it.style }==listOf(bold,italic))
        }
    }
    test("mixed selection toggle patches only chosen property and restores exact history") {
        val original=doc("abc",listOf(bold,italic,TextStyleFlags()));val e=EditorEngine(original)
        e.move(Selection(2,0));check(e.formatState(FormatKey.BOLD)==null)
        e.toggleFormat(FormatKey.BOLD);check(e.snapshot().visible.units.map { it.style }==listOf(bold,TextStyleFlags(true,true),TextStyleFlags()))
        check(e.snapshot().selection==Selection(2,0));e.perform(EditAction.Undo);check(e.snapshot().visible.same(original));check(e.snapshot().selection==Selection(2,0))
        e.perform(EditAction.Redo);check(e.formatState(FormatKey.BOLD)==true)
        e.toggleFormat(FormatKey.BOLD);check(e.snapshot().visible.units.take(2).none { it.style.bold })
    }
    test("grapheme presentation preserves hidden raw styles until explicit format") {
        val original=doc("👩‍💻",listOf(bold,bold,TextStyleFlags(),italic,italic));val e=EditorEngine(original)
        check(displayedStyles(original).single().style==bold);check(attributedDisplay(original).spanStyles.single().start==0)
        check(DocumentFiles.decode(DocumentFiles.encode(original)).same(original))
        e.move(Selection(3,5));check(e.formatState(FormatKey.BOLD)==true);e.toggleFormat(FormatKey.ITALIC)
        check(e.snapshot().visible.units.all { it.style.italic });check(e.snapshot().visible.units[0].style.bold)
        e.perform(EditAction.Undo);check(e.snapshot().visible.same(original))
        e.move(Selection(2,3));e.inputMethod("",0,null) // Raw native removal of the ZWJ, not grapheme Delete.
        check(e.snapshot().visible.text=="👩💻");check(displayedStyles(e.snapshot().visible).map { it.style }==listOf(bold,italic))
    }
    test("format expands to cluster edges but skips paragraph separator") {
        val initial=Document(listOf(TextUnit(1,'a'),TextUnit(2,'\u0301'),TextUnit(3,'\n',UnitKind.PARAGRAPH_BREAK),TextUnit(4,'b')))
        val e=EditorEngine(initial);e.move(Selection(1,4));e.toggleFormat(FormatKey.BOLD)
        check(e.snapshot().visible.units.map { it.style.bold }==listOf(true,true,false,true))
    }
    test("IME detour reuses original raw styles, unmatched units use frozen seed") {
        val original=doc("ab",listOf(bold,italic));val e=EditorEngine(original);e.selectAll()
        e.inputMethod("X",0,1);check(e.snapshot().visible.units.single().style==bold)
        e.inputMethod("ab",0,2);check(e.snapshot().visible.same(original))
        val rev=e.snapshot().documentRevision;e.inputMethod("ab",2,null);check(e.snapshot().documentRevision==rev && e.snapshot().undoCount==0)
    }
    test("format while composing waits, follows final composition extent, and undoes separately") {
        val e=EditorEngine();e.inputMethod("ni",0,2);e.toggleFormat(FormatKey.BOLD)
        check(e.snapshot().waiting && e.snapshot().visible.units.none { it.style.bold })
        e.inputMethod("你好",2,null);check(e.snapshot().visible.units.all { it.style.bold });check(e.snapshot().undoCount==2)
        e.perform(EditAction.Undo);check(e.snapshot().visible.text=="你好" && e.snapshot().visible.units.none { it.style.bold })
        e.perform(EditAction.Undo);check(e.snapshot().visible.text.isEmpty())
    }
    test("composition abort cancels its queued formatting and absorbs its Undo credit") {
        val e=EditorEngine();e.replace("old","Fixture");e.inputMethod("ni",0,2)
        e.toggleFormat(FormatKey.BOLD);e.perform(EditAction.Undo);e.abortComposition()
        check(e.snapshot().visible.text=="old");check(e.snapshot().visible.units.none { it.style.bold });check(e.snapshot().undoCount==1)
    }
    test("partial commit retains styles and frozen seed across the suffix epoch") {
        val e=EditorEngine();e.toggleFormat(FormatKey.ITALIC);e.inputMethod("你hao",1,2)
        check(e.snapshot().committed.units.single().style==italic);e.inputMethod("好",1,null)
        check(e.snapshot().visible.units.all { it.style==italic });e.perform(EditAction.Undo);check(e.snapshot().visible.text.isEmpty())
    }
    test("queued Undo before format cannot retarget formatting to unrelated text or reject terminal input") {
        val e=EditorEngine();e.replace("old","Fixture");e.inputMethod("ni",0,2)
        e.perform(EditAction.Undo);e.toggleFormat(FormatKey.BOLD);e.inputMethod("你好",2,null)
        check(e.snapshot().marked==null && !e.snapshot().waiting);check(e.snapshot().visible.text=="old")
        check(e.snapshot().visible.units.none { it.style.bold });check(e.snapshot().undoCount==1)
    }
    test("native delete-to-empty saves the seed; empty Delete does not persist a mere typing toggle") {
        val e=EditorEngine(doc("a",listOf(bold)));e.selectAll();e.inputMethod("",0,null)
        check(e.snapshot().committed.startStyle==bold);e.perform(EditAction.Undo);check(e.snapshot().visible.text=="a")
        val empty=EditorEngine();empty.toggleFormat(FormatKey.BOLD);empty.delete(true)
        check(empty.snapshot().documentRevision==0L && empty.snapshot().undoCount==0);check(empty.snapshot().committed.startStyle==TextStyleFlags())
    }
    test("delete-to-empty retains effective style, Enter carries ordinary presentation") {
        val e=EditorEngine(doc("a",listOf(bold)));e.selectAll();e.delete(true)
        check(e.snapshot().visible.startStyle==bold);e.replace("b");check(e.snapshot().visible.units.single().style==bold)
        e.replace("\n","Enter",true);e.replace("c");check(e.snapshot().visible.units.last().style==bold)
        e.delete(true);val saved=DocumentFiles.decode(DocumentFiles.encode(e.snapshot().committed));check(saved.units.last().style==bold)
        val reopened=EditorEngine(saved);reopened.move(Selection(saved.units.size,saved.units.size));reopened.replace("d");check(reopened.snapshot().visible.units.last().style==bold)
    }
    test("backspace inherits surviving left; forward delete inherits surviving right") {
        val original=doc("axb",listOf(bold,TextStyleFlags(),italic))
        val a=EditorEngine(original);a.move(Selection(2,2));a.delete(true);check(a.snapshot().typingStyle==bold)
        val b=EditorEngine(original);b.move(Selection(1,1));b.delete(false);check(b.snapshot().typingStyle==italic)
    }
    test("v1 prototype files remain readable; v2 restores style metadata") {
        val payload=ByteArrayOutputStream().apply { DataOutputStream(this).use { it.writeInt(0x41535450);it.writeInt(1);it.writeInt(2);it.writeChar('a'.code);it.writeByte(0);it.writeChar('\n'.code);it.writeByte(1) } }.toByteArray()
        val old=DocumentFiles.decode(payload+MessageDigest.getInstance("SHA-256").digest(payload));check(old.text=="a\n" && old.units.last().kind==UnitKind.PARAGRAPH_BREAK)
        val styled=Document(old.units.map { it.copy(style=bold) },italic);check(DocumentFiles.decode(DocumentFiles.encode(styled)).same(styled))
        val e=EditorEngine(styled);val point=SavePoint(styled);e.move(Selection(0,1));e.toggleFormat(FormatKey.ITALIC);check(point.dirty(e.snapshot()))
    }
    println("ALL $cases formatting checks passed; physical IME remains unqualified.")
}
