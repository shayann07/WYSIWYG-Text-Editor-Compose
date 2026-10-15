package editor

import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

fun runFileChecks() {
    var cases = 0
    fun test(name: String, action: () -> Unit) { action(); cases++; println("PASS $name") }
    fun document(text: String) = Document(text.mapIndexed { i, c -> TextUnit(i + 100L, c) })
    val fixture = Document(document("A😀\ud800\udfff\uffff\u0000\n\nZ").units.mapIndexed { i, u -> if (i == 8) u.copy(kind = UnitKind.PARAGRAPH_BREAK) else u })
    test("file roundtrip preserves raw UTF-16 and hard/soft breaks, including all 65536 code units") {
        check(DocumentFiles.decode(DocumentFiles.encode(fixture)).same(fixture))
        val all = document((0..65535).map(Int::toChar).joinToString(""))
        check(DocumentFiles.decode(DocumentFiles.encode(all)).same(all))
    }
    test("every truncated fixture and every single-byte corruption is rejected") {
        val bytes = DocumentFiles.encode(fixture)
        for (end in bytes.indices) check(runCatching { DocumentFiles.decode(bytes.copyOf(end)) }.isFailure)
        for (at in bytes.indices) {
            val corrupted = bytes.copyOf(); corrupted[at] = (corrupted[at].toInt() xor 1).toByte()
            check(runCatching { DocumentFiles.decode(corrupted) }.isFailure)
        }
        check(runCatching { DocumentFiles.decode(bytes + 0.toByte()) }.isFailure)
    }
    test("valid checksum cannot bypass version, length, kind or paragraph validation") {
        fun invalid(change: (ByteArray) -> Unit) {
            val bytes = DocumentFiles.encode(fixture); val payload = bytes.copyOf(bytes.size - 32); change(payload)
            check(runCatching { DocumentFiles.decode(payload + MessageDigest.getInstance("SHA-256").digest(payload)) }.isFailure)
        }
        invalid { ByteBuffer.wrap(it).putInt(4, 3) }
        invalid { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) }
        invalid { ByteBuffer.wrap(it).putInt(8, -1) }
        invalid { it[15] = 8 }
        invalid { it[15] = UnitKind.PARAGRAPH_BREAK.ordinal.toByte() }
        invalid { it[16] = 8 }
        invalid { it[12] = 8 }
    }
    test("save-point follows written snapshot, not later edits or selection") {
        val e = EditorEngine(fixture); val point = SavePoint(fixture)
        check(!point.dirty(e.snapshot())); e.move(Selection(0, 2)); check(!point.dirty(e.snapshot()))
        e.replace("next", "Edit"); val written = point.capture(e.snapshot())
        e.replace("later", "Edit"); point.acknowledge(written); check(point.dirty(e.snapshot()))
        e.perform(EditAction.Undo); check(!point.dirty(e.snapshot()))
        e.perform(EditAction.Redo); check(point.dirty(e.snapshot()))
    }
    test("even unchanged preedit cannot be saved or treated as clean") {
        val e = EditorEngine(document("abc")); val point = SavePoint(e.snapshot().committed)
        e.selectAll(); e.inputMethod("abc", 0, 3)
        check(point.dirty(e.snapshot())); check(runCatching { point.capture(e.snapshot()) }.isFailure)
        e.inputMethod("abc", 3, null); check(!point.dirty(e.snapshot())); check(point.capture(e.snapshot()).text == "abc")
    }
    test("opened document starts with unique identities and new independent history") {
        val e = EditorEngine(DocumentFiles.decode(DocumentFiles.encode(fixture)))
        check(e.snapshot().undoCount == 0); e.move(Selection(2, 2)); e.replace("x", "Edit")
        check(e.snapshot().visible.units.map { it.id }.distinct().size == e.snapshot().visible.units.size)
        e.perform(EditAction.Undo); check(e.snapshot().visible.same(fixture))
    }
    val root = Files.createTempDirectory("astra-file-check-")
    try {
        test("atomic overwrite roundtrip and interrupted save preserve previous file") {
            val path = root.resolve("sample.astra"); DocumentFiles.write(path, fixture)
            val original = Files.readAllBytes(path)
            check(runCatching { DocumentFiles.writeImpl(path, document("new")) { throw java.io.IOException("Injected interruption") } }.isFailure)
            check(Files.readAllBytes(path).contentEquals(original))
            Files.list(root).use { check(it.count() == 1L) }
            DocumentFiles.write(path, document("new")); check(DocumentFiles.read(path).text == "new")
        }
        test("invalid save leaves previous file intact and failed replace removes temp") {
            val path = root.resolve("sample.astra"); val before = Files.readAllBytes(path)
            val invalid = Document(listOf(TextUnit(1, 'x', UnitKind.PARAGRAPH_BREAK)))
            check(runCatching { DocumentFiles.write(path, invalid) }.isFailure)
            check(Files.readAllBytes(path).contentEquals(before))
            val directory = Files.createDirectory(root.resolve("directory")); Files.writeString(directory.resolve("keep"), "keep")
            check(runCatching { DocumentFiles.write(directory, fixture) }.isFailure)
            check(Files.readString(directory.resolve("keep")) == "keep")
            Files.list(root).use { paths -> check(paths.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
        }
        test("oversized file and oversized document reject without giant allocation") {
            val huge = root.resolve("oversize.astra"); Files.write(huge, ByteArray(400_046))
            check(runCatching { DocumentFiles.read(huge) }.isFailure)
            check(runCatching { DocumentFiles.encode(document("a".repeat(DocumentFiles.MAX_UNITS + 1))) }.isFailure)
        }
        test("stale window cannot overwrite a newer completed save; mid-write conflict preserves newer file") {
            val path = root.resolve("conflict.astra")
            val old = document("old"); val newer = document("newer"); val staleEdit = document("stale edit")
            DocumentFiles.write(path, old); DocumentFiles.write(path, newer, old)
            check(runCatching { DocumentFiles.write(path, staleEdit, old) }.isFailure)
            check(DocumentFiles.read(path).same(newer))
            check(runCatching { DocumentFiles.writeImpl(path, staleEdit, newer) { DocumentFiles.write(path, old) } }.isFailure)
            check(DocumentFiles.read(path).same(old))
            Files.list(root).use { paths -> check(paths.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
        }
    } finally {
        // Only the directory created above is removed; never a project or user path.
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    println("ALL $cases file checks passed. No power-loss durability or production journal claim.")
}
