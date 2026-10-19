package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest

/** Prototype-only snapshot format. Not the v5 production journal/container. */
object DocumentFiles : DocumentCodec, DocumentRepository {
    override fun load(location: String) = read(Path.of(location))
    override fun save(location: String, document: Document) = write(Path.of(location), document)
    const val MAX_UNITS = 100_000
    private const val MAGIC = 0x41535450 // ASTP
    private const val VERSION = 9
    private const val HEADER = 26
    private const val DIGEST = 32
    const val MAX_ENCODED_BYTES = 20_000_000

    override fun encode(document: Document): ByteArray {
        require(document.units.size <= MAX_UNITS) { "Prototype files are limited to $MAX_UNITS UTF-16 units." }
        validateStructure(document)
        val bytes = BoundedBytes(MAX_ENCODED_BYTES - DIGEST)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(document.units.size)
            writeStyle(out, document.startStyle.copy(link = null))
            writeParagraph(out, document.startParagraph)
            document.units.forEach { unit ->
                require(unit.kind == UnitKind.PARAGRAPH_BREAK || unit.paragraphStyle == TextStyleFlags()) { "Text units cannot own empty-paragraph styles" }
                require(unit.kind != UnitKind.PARAGRAPH_BREAK || unit.char == if (unit.structuralBreak == StructuralBreak.PAGE)'\u000c' else '\n') { "Invalid paragraph break" }
                require(unit.kind != UnitKind.PARAGRAPH_BREAK || unit.style == TextStyleFlags()) { "Paragraph break inline style must be unstyled" }
                out.writeChar(unit.char.code)
                out.writeByte(unit.kind.ordinal)
                writeStyle(out, if (unit.kind == UnitKind.PARAGRAPH_BREAK) unit.paragraphStyle.copy(link = null) else unit.style.copy(link = null))
                require(unit.kind == UnitKind.PARAGRAPH_BREAK || unit.followingParagraph == ParagraphProperties()) { "Text units cannot own paragraph properties" }
                writeParagraph(out, unit.followingParagraph)
            }
            writeStructure(out, document)
            out.writeByte(document.pageSettings.paper.ordinal)
            out.writeBoolean(document.pageSettings.landscape)
            out.writeByte(document.pageSettings.marginMm)
            writePageExtensions(out, document)
        }
        val payload = bytes.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    override fun decode(bytes: ByteArray): Document {
        require(bytes.size in 12 + DIGEST..MAX_ENCODED_BYTES) { "Invalid or oversized prototype file" }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size))) { "File checksum failed; document was not opened." }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Not an Astra prototype file" }
            val version = input.readInt()
            require(version in 1..VERSION) { "Unsupported prototype file version" }
            val count = input.readInt()
            require(
                count in 0..MAX_UNITS &&
                    (
                        if (version >= 6) {
                            payload.size >= HEADER + count * 17 + 20
                        } else {
                            payload.size == when (version) {
                                1 -> 12 + count * 3
                                4 -> 14 + count * 5
                                5 -> HEADER + count * 17
                                else -> 13 + count * 4
                            }
                        }
                        ),
            ) { "Invalid file length" }
            val startStyle = if (version == 1) {
                TextStyleFlags()
            } else if (version >= 5) {
                readStyle(input)
            } else {
                legacyStyle(input.readUnsignedByte())
            }
            val startParagraph = if (version >= 5) {
                readParagraph(input)
            } else if (version == 4) {
                ParagraphProperties.fromBits(input.readUnsignedByte())
            } else {
                ParagraphProperties()
            }
            val document = Document(
                List(count) { index ->
                    val char = input.readChar()
                    val tag = input.readUnsignedByte()
                    require(tag in 0..(if (version >= 6)2 else 1)) { "Unknown unit kind" }
                    val kind = UnitKind.entries[tag]
                    require(kind != UnitKind.PARAGRAPH_BREAK || char == '\n' || (version >= 9 && char == '\u000c')) { "Invalid paragraph break" }
                    val (style, paragraphStyle) = if (version == 1) {
                        TextStyleFlags() to TextStyleFlags()
                    } else {
                        val bits = if (version >= 5) readStyle(input) else legacyStyle(input.readUnsignedByte())
                        if (kind == UnitKind.PARAGRAPH_BREAK) {
                            TextStyleFlags() to bits
                        } else {
                            bits to TextStyleFlags()
                        }
                    }
                    val properties = if (version >= 5) {
                        readParagraph(input)
                    } else if (version == 4) {
                        ParagraphProperties.fromBits(input.readUnsignedByte())
                    } else {
                        ParagraphProperties()
                    }
                    require(kind == UnitKind.PARAGRAPH_BREAK || properties == ParagraphProperties()) { "Text units cannot own paragraph properties" }
                    TextUnit(index.toLong() + 1, char, kind, style, paragraphStyle, properties)
                },
                startStyle,
                startParagraph,
            )
            if (version >= 6) {
                readStructure(input, document, version).let { d ->
                    val result = if (version >= 8) {
                        val paper = input.readUnsignedByte()
                        require(paper in PaperSize.entries.indices)
                        d.copy(pageSettings = PageSettings(PaperSize.entries[paper], input.readBoolean(), input.readUnsignedByte()))
                    } else {
                        d
                    }
                    val extended = if (version >= 9)readPageExtensions(input, result) else result
                    require(input.available() == 0)
                    validateStructure(extended)
                    extended
                }
            } else {
                document
            }
        }
    }

    private class BoundedBytes(private val max: Int) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            require(count < max) { "File budget exceeded" }
            super.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            require(count.toLong() + len <= max) { "File budget exceeded" }
            super.write(b, off, len)
        }
    }
    private fun text(out: DataOutputStream, s: String, max: Int) {
        require(s.length <= max)
        out.writeInt(s.length)
        s.forEach { out.writeChar(it.code) }
    }
    private fun text(i: DataInputStream, max: Int): String {
        val n = i.readInt()
        require(n in 0..max && n.toLong() * 2 <= i.available())
        return CharArray(n) { i.readChar() }.concatToString()
    }
    private fun writeStructure(o: DataOutputStream, d: Document) {
        val ps = d.paragraphs().filter { it.properties.let { p -> p.heading != 0 || p.item.isNotEmpty() } }
        o.writeInt(ps.size)
        for (p in ps) {
            o.writeInt(p.index)
            val v = p.properties
            o.writeByte(v.heading)
            o.writeByte(v.list.ordinal)
            o.writeShort(v.restart)
            text(o, v.item, 64)
            text(o, v.parent, 64)
            o.writeByte(v.markerTypography.font.ordinal)
            o.writeByte(v.markerTypography.size)
        }
        val links = (listOf(d.startStyle.link) + d.units.flatMap { listOf(it.style.link, it.paragraphStyle.link) }).filterNotNull().distinct()
        o.writeInt(links.size)
        for (l in links) {
            text(o, l.id, 64)
            text(o, l.uri, 2048)
        }
        o.writeInt(if (d.startStyle.link == null) -1 else links.indexOf(d.startStyle.link))
        val linked = d.units.withIndex().filter { it.value.style.link != null || it.value.paragraphStyle.link != null }
        o.writeInt(linked.size)
        for ((index, u) in linked) {
            o.writeInt(index)
            o.writeInt(links.indexOf(u.style.link ?: u.paragraphStyle.link))
        }
        val images = d.units.withIndex().filter { it.value.image != null }
        o.writeInt(images.size)
        for ((index, u) in images) {
            o.writeInt(index)
            val im = u.image!!
            text(o, im.resource, 64)
            o.writeShort(im.width)
            text(o, im.alt, 2048)
        }
        o.writeInt(d.resources.size)
        for ((id, r) in d.resources.toSortedMap()) {
            text(o, id, 64)
            o.writeInt(r.width)
            o.writeInt(r.height)
            text(o, r.mime, 16)
            val bytes = r.bytes()
            o.writeInt(bytes.size)
            o.write(bytes)
        }
    }
    private fun readStructure(i: DataInputStream, base: Document, version: Int): Document {
        fun count(max: Int): Int = i.readInt().also { require(it in 0..max && it <= i.available() / 4) }
        var d = base
        val paragraphs = d.paragraphs()
        val patches = HashMap<Long?, ParagraphProperties>()
        repeat(count(paragraphs.size)) {
            val at = i.readInt()
            require(at in paragraphs.indices && !patches.containsKey(paragraphs[at].anchor))
            val h = i.readUnsignedByte()
            val k = i.readUnsignedByte()
            require(k in ListKind.entries.indices)
            val restart = i.readUnsignedShort()
            val item = text(i, 64)
            val parent = text(i, 64)
            val marker = if (version >= 7) {
                val font = i.readUnsignedByte()
                require(font in 1 until StoredFont.entries.size)
                MarkerTypography(StoredFont.entries[font], i.readUnsignedByte())
            } else {
                MarkerTypography()
            }
            patches[paragraphs[at].anchor] = paragraphs[at].properties.copy(heading = h, list = ListKind.entries[k], restart = restart, item = item, parent = parent, markerTypography = marker)
        }
        d = d.patchParagraphs(patches)
        val links = List(count(MAX_UNITS + 1)) { LinkValue(text(i, 64), text(i, 2048)) }
        require(links.map { it.id }.distinct().size == links.size)
        val root = i.readInt()
        require(root in -1 until links.size)
        if (root >= 0)d = d.copy(startStyle = d.startStyle.copy(link = links[root]))
        val units = d.units.toMutableList()
        val used = HashSet<Int>()
        repeat(count(MAX_UNITS)) {
            val at = i.readInt()
            val link = i.readInt()
            require(at in units.indices && used.add(at) && link in links.indices && units[at].kind != UnitKind.IMAGE)
            val u = units[at]
            units[at] = if (u.kind == UnitKind.PARAGRAPH_BREAK)u.copy(paragraphStyle = u.paragraphStyle.copy(link = links[link])) else u.copy(style = u.style.copy(link = links[link]))
        }
        used.clear()
        repeat(count(MAX_UNITS)) {
            val at = i.readInt()
            require(at in units.indices && used.add(at) && units[at].kind == UnitKind.IMAGE)
            units[at] = units[at].copy(image = ImageBlock(text(i, 64), i.readUnsignedShort(), text(i, 2048)))
        }
        val resources = HashMap<String, ImageResource>()
        var bytesTotal = 0L
        var pixelsTotal = 0L
        repeat(count(32)) {
            val id = text(i, 64)
            val w = i.readInt()
            val h = i.readInt()
            val mime = text(i, 16)
            require(w in 1..8192 && h in 1..8192)
            pixelsTotal += w.toLong() * h
            require(pixelsTotal <= 16_000_000) { "Aggregate decoded image dimensions exceed limits" }
            val size = i.readInt()
            require(size in 12..ImageDecoder.MAX_BYTES && size <= i.available())
            bytesTotal += size
            require(bytesTotal <= 9_000_000)
            val bytes = i.readNBytes(size)
            val (actual, r) = ImageDecoder.validate(bytes)
            require(id == actual && r.width == w && r.height == h && r.mime == mime && resources.put(id, r) == null)
        }
        return d.copy(units = units, resources = resources).let { if (version < 7)it.migrateMarkerTypography() else it }
    }

    private fun legacyStyle(bits: Int): TextStyleFlags {
        require(bits in 0..3)
        return TextStyleFlags.fromBits(bits)
    }
    private fun writeStyle(out: DataOutputStream, s: TextStyleFlags) {
        out.writeByte(s.bits)
        out.writeByte(s.font.ordinal)
        out.writeByte(s.size)
        out.writeInt(s.color)
    }
    private fun writePageExtensions(o: DataOutputStream, d: Document) {
        val ps = d.paragraphs()
        o.writeInt(ps.size)
        for (p in ps) {
            val r = p.properties.pagination
            o.writeByte((if (r.breakBefore)1 else 0) or (if (r.keepWithNext)2 else 0) or (if (r.keepLines)4 else 0))
            o.writeByte(r.widows)
            o.writeByte(r.orphans)
        }
        val breaks = d.units.indices.filter { d.units[it].structuralBreak == StructuralBreak.PAGE }
        o.writeInt(breaks.size)
        breaks.forEach(o::writeInt)
        o.writeBoolean(d.running.differentFirst)
        o.writeByte(d.running.digits.ordinal)
        for (flow in listOf(d.running.header, d.running.footer)) {
            o.writeBoolean(flow != null)
            if (flow != null) {
                text(o, flow.id, 36)
                text(o, flow.text, 512)
                writeStyle(o, flow.style)
                o.writeByte(flow.direction.ordinal)
                o.writeByte(flow.alignment.ordinal)
                o.writeByte(flow.field.ordinal)
            }
        }
    }
    private fun readPageExtensions(i: DataInputStream, base: Document): Document {
        val ps = base.paragraphs()
        require(i.readInt() == ps.size)
        val props = ps.associate { p ->
            val flags = i.readUnsignedByte()
            require(flags in 0..7)
            p.anchor to p.properties.copy(pagination = PaginationRules(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, i.readUnsignedByte(), i.readUnsignedByte()))
        }
        val n = i.readInt()
        require(n in 0..base.units.size && n <= i.available() / 4)
        val indices = List(n) { i.readInt() }
        require(indices.distinct().size == n && indices.all { it in base.units.indices && base.units[it].kind == UnitKind.PARAGRAPH_BREAK })
        val first = i.readBoolean()
        val digits = i.readUnsignedByte()
        require(digits in PageDigits.entries.indices)
        fun flow(): RunningFlow? {
            if (!i.readBoolean())return null
            val id = text(i, 36)
            val raw = text(i, 512)
            val style = readStyle(i)
            val dir = i.readUnsignedByte()
            val align = i.readUnsignedByte()
            val field = i.readUnsignedByte()
            require(dir in ParagraphDirection.entries.indices && align in ParagraphAlignment.entries.indices && field in RunningField.entries.indices)
            return RunningFlow(id, raw, style, ParagraphDirection.entries[dir], ParagraphAlignment.entries[align], RunningField.entries[field])
        }
        val running = RunningContent(flow(), flow(), first, PageDigits.entries[digits])
        val set = indices.toSet()
        return base.patchParagraphs(props).copy(units = base.patchParagraphs(props).units.mapIndexed { at, u -> if (at in set)u.copy(structuralBreak = StructuralBreak.PAGE) else u }, running = running)
    }
    private fun readStyle(i: DataInputStream): TextStyleFlags {
        val bits = TextStyleFlags.fromBits(i.readUnsignedByte())
        val font = i.readUnsignedByte()
        require(font in StoredFont.entries.indices)
        return bits.copy(font = StoredFont.entries[font], size = i.readUnsignedByte(), color = i.readInt())
    }
    private fun writeParagraph(out: DataOutputStream, p: ParagraphProperties) {
        out.writeByte(p.bits)
        out.writeShort(p.spaceBefore)
        out.writeShort(p.spaceAfter)
        out.writeShort(p.linePermille)
    }
    private fun readParagraph(i: DataInputStream) = ParagraphProperties.fromBits(i.readUnsignedByte()).copy(spaceBefore = i.readUnsignedShort(), spaceAfter = i.readUnsignedShort(), linePermille = i.readUnsignedShort())

    fun read(path: Path): Document = Files.newInputStream(path).use { input ->
        // Bounded even when a file grows between stat and read, or is not a regular file.
        input.readNBytes(MAX_ENCODED_BYTES + 1).let { bytes -> decode(bytes).also { SafeDocumentIO.remember(it, path, bytes) } }
    }

    fun write(path: Path, document: Document, expected: Document? = null) = writeImpl(path, document, expected) {}

    /** Fault seam only used by checks, after force and before the atomic replacement. */
    fun writeImpl(path: Path, document: Document, expected: Document? = null, beforeReplace: () -> Unit) {
        val bytes = encode(document)
        val destination = path.toAbsolutePath().normalize()
        fun checkOriginal() {
            if (expected != null) check(read(destination).same(expected)) { "This file changed outside this window. Use Save As to keep your edits in a separate file." }
        }
        checkOriginal()
        val temp = Files.createTempFile(destination.parent, ".astra-save-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeReplace()
            checkOriginal()
            // No unsafe truncate-in-place fallback on filesystems lacking atomic replacement.
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

/** UI save-point logic; selection and Undo depth are deliberately not dirty flags. */
class SavePoint(initial: Document) {
    private var saved = initial
    fun dirty(frame: EditorFrame) = frame.marked != null || frame.waiting || !saved.same(frame.visible)
    fun acknowledge(written: Document) {
        saved = written
    }
    fun capture(frame: EditorFrame): Document {
        check(frame.marked == null && !frame.waiting) { "Finish composing before saving." }
        return frame.committed
    }
}
