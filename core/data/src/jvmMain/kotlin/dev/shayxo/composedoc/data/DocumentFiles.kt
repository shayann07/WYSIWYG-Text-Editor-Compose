package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.DocumentCodec
import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ImageResource
import dev.shayxo.composedoc.model.LinkValue
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.PageDigits
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.PaperSize
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.RunningContent
import dev.shayxo.composedoc.model.RunningField
import dev.shayxo.composedoc.model.RunningFlow
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.StructuralBreak
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextUnit
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.migrateMarkerTypography
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.model.validateStructure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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

    // Format versions that introduced each section or record layout.
    private const val VERSION_PARAGRAPH_BITS = 4
    private const val VERSION_RICH_RECORDS = 5
    private const val VERSION_STRUCTURE = 6
    private const val VERSION_MARKER_TYPOGRAPHY = 7
    private const val VERSION_PAGE_SETTINGS = 8
    private const val VERSION_PAGE_EXTENSIONS = 9

    // Exact legacy payload layouts: fixed header bytes plus bytes per unit record.
    private const val V1_HEADER = 12
    private const val V1_UNIT_BYTES = 3
    private const val V2_HEADER = 13
    private const val V2_UNIT_BYTES = 4
    private const val V4_HEADER = 14
    private const val V4_UNIT_BYTES = 5
    private const val RICH_UNIT_BYTES = 17
    private const val MIN_STRUCTURE_BYTES = 20

    // Field length limits of the structure and page extension sections.
    private const val MAX_ID_LENGTH = 64
    private const val MAX_URI_LENGTH = 2048
    private const val MAX_ALT_LENGTH = 2048
    private const val MAX_MIME_LENGTH = 16
    private const val RUNNING_ID_LENGTH = 36
    private const val MAX_RUNNING_TEXT_LENGTH = 512

    // Image resource budgets.
    private const val MAX_RESOURCES = 32
    private const val MAX_IMAGE_DIMENSION = 8192
    private const val MAX_TOTAL_PIXELS = 16_000_000
    private const val MIN_IMAGE_BYTES = 12
    private const val MAX_TOTAL_IMAGE_BYTES = 9_000_000

    private const val LEGACY_STYLE_BITS = 3
    private const val BREAK_BEFORE_FLAG = 1
    private const val KEEP_WITH_NEXT_FLAG = 2
    private const val KEEP_LINES_FLAG = 4
    private const val PAGINATION_FLAGS = 7

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
                require(unit.kind == UnitKind.PARAGRAPH_BREAK || unit.paragraphStyle == TextStyleFlags()) {
                    "Text units cannot own empty-paragraph styles"
                }
                require(
                    unit.kind != UnitKind.PARAGRAPH_BREAK ||
                        unit.char == if (unit.structuralBreak ==
                            StructuralBreak.PAGE
                        ) {
                            '\u000c'
                        } else {
                            '\n'
                        },
                ) { "Invalid paragraph break" }
                require(unit.kind != UnitKind.PARAGRAPH_BREAK || unit.style == TextStyleFlags()) {
                    "Paragraph break inline style must be unstyled"
                }
                out.writeChar(unit.char.code)
                out.writeByte(unit.kind.ordinal)
                writeStyle(
                    out,
                    if (unit.kind ==
                        UnitKind.PARAGRAPH_BREAK
                    ) {
                        unit.paragraphStyle.copy(link = null)
                    } else {
                        unit.style.copy(link = null)
                    },
                )
                require(unit.kind == UnitKind.PARAGRAPH_BREAK || unit.followingParagraph == ParagraphProperties()) {
                    "Text units cannot own paragraph properties"
                }
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
        require(bytes.size in V1_HEADER + DIGEST..MAX_ENCODED_BYTES) { "Invalid or oversized prototype file" }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST)
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(payload),
                bytes.copyOfRange(payload.size, bytes.size),
            ),
        ) {
            "File checksum failed; document was not opened."
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Not an Astra prototype file" }
            val version = input.readInt()
            require(version in 1..VERSION) { "Unsupported prototype file version" }
            val count = input.readInt()
            require(count in 0..MAX_UNITS && validLength(payload.size, version, count)) { "Invalid file length" }
            val startStyle = if (version == 1) {
                TextStyleFlags()
            } else if (version >= VERSION_RICH_RECORDS) {
                readStyle(input)
            } else {
                legacyStyle(input.readUnsignedByte())
            }
            val startParagraph = readVersionedParagraph(input, version)
            val document = Document(
                List(count) { index -> readUnit(input, version, index) },
                startStyle,
                startParagraph,
            )
            if (version >= VERSION_STRUCTURE) readSections(input, document, version) else document
        }
    }

    private fun validLength(size: Int, version: Int, count: Int): Boolean = if (version >= VERSION_STRUCTURE) {
        size >= HEADER + count * RICH_UNIT_BYTES + MIN_STRUCTURE_BYTES
    } else {
        size == when (version) {
            1 -> V1_HEADER + count * V1_UNIT_BYTES
            VERSION_PARAGRAPH_BITS -> V4_HEADER + count * V4_UNIT_BYTES
            VERSION_RICH_RECORDS -> HEADER + count * RICH_UNIT_BYTES
            else -> V2_HEADER + count * V2_UNIT_BYTES
        }
    }

    private fun readVersionedParagraph(input: DataInputStream, version: Int) = if (version >= VERSION_RICH_RECORDS) {
        readParagraph(input)
    } else if (version == VERSION_PARAGRAPH_BITS) {
        ParagraphProperties.fromBits(input.readUnsignedByte())
    } else {
        ParagraphProperties()
    }

    private fun readUnit(input: DataInputStream, version: Int, index: Int): TextUnit {
        val char = input.readChar()
        val tag = input.readUnsignedByte()
        require(tag in 0..(if (version >= VERSION_STRUCTURE)2 else 1)) { "Unknown unit kind" }
        val kind = UnitKind.entries[tag]
        val pageBreak = version >= VERSION_PAGE_EXTENSIONS && char == '\u000c'
        require(kind != UnitKind.PARAGRAPH_BREAK || char == '\n' || pageBreak) {
            "Invalid paragraph break"
        }
        val (style, paragraphStyle) = if (version == 1) {
            TextStyleFlags() to TextStyleFlags()
        } else {
            val bits = if (version >= VERSION_RICH_RECORDS) readStyle(input) else legacyStyle(input.readUnsignedByte())
            if (kind == UnitKind.PARAGRAPH_BREAK) {
                TextStyleFlags() to bits
            } else {
                bits to TextStyleFlags()
            }
        }
        val properties = readVersionedParagraph(input, version)
        require(kind == UnitKind.PARAGRAPH_BREAK || properties == ParagraphProperties()) {
            "Text units cannot own paragraph properties"
        }
        return TextUnit(index.toLong() + 1, char, kind, style, paragraphStyle, properties)
    }

    /** Version 6+ trailing sections, read in file order; the payload must end exactly after them. */
    private fun readSections(input: DataInputStream, document: Document, version: Int): Document {
        val d = readStructure(input, document, version)
        val result = if (version >= VERSION_PAGE_SETTINGS) {
            val paper = input.readUnsignedByte()
            require(paper in PaperSize.entries.indices)
            d.copy(
                pageSettings = PageSettings(
                    PaperSize.entries[paper],
                    input.readBoolean(),
                    input.readUnsignedByte(),
                ),
            )
        } else {
            d
        }
        val extended = if (version >= VERSION_PAGE_EXTENSIONS)readPageExtensions(input, result) else result
        require(input.available() == 0)
        validateStructure(extended)
        return extended
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
            text(o, v.item, MAX_ID_LENGTH)
            text(o, v.parent, MAX_ID_LENGTH)
            o.writeByte(v.markerTypography.font.ordinal)
            o.writeByte(v.markerTypography.size)
        }
        val links = (
            listOf(d.startStyle.link) + d.units.flatMap {
                listOf(it.style.link, it.paragraphStyle.link)
            }
            ).filterNotNull().distinct()
        o.writeInt(links.size)
        for (l in links) {
            text(o, l.id, MAX_ID_LENGTH)
            text(o, l.uri, MAX_URI_LENGTH)
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
            text(o, im.resource, MAX_ID_LENGTH)
            o.writeShort(im.width)
            text(o, im.alt, MAX_ALT_LENGTH)
        }
        o.writeInt(d.resources.size)
        for ((id, r) in d.resources.toSortedMap()) {
            text(o, id, MAX_ID_LENGTH)
            o.writeInt(r.width)
            o.writeInt(r.height)
            text(o, r.mime, MAX_MIME_LENGTH)
            val bytes = r.bytes()
            o.writeInt(bytes.size)
            o.write(bytes)
        }
    }

    /** Reads a section count that is bounded by [max] and by the remaining four-byte entries. */
    private fun count(i: DataInputStream, max: Int): Int =
        i.readInt().also { require(it in 0..max && it <= i.available() / Int.SIZE_BYTES) }

    private fun readStructure(i: DataInputStream, base: Document, version: Int): Document {
        var d = base.patchParagraphs(readParagraphPatches(i, base, version))
        val links = List(count(i, MAX_UNITS + 1)) { LinkValue(text(i, MAX_ID_LENGTH), text(i, MAX_URI_LENGTH)) }
        require(links.map { it.id }.distinct().size == links.size)
        val root = i.readInt()
        require(root in -1 until links.size)
        if (root >= 0)d = d.copy(startStyle = d.startStyle.copy(link = links[root]))
        val units = d.units.toMutableList()
        readLinkedUnits(i, units, links)
        readImageUnits(i, units)
        val resources = readResources(i)
        return d.copy(units = units, resources = resources).let {
            if (version < VERSION_MARKER_TYPOGRAPHY)it.migrateMarkerTypography() else it
        }
    }

    private fun readParagraphPatches(
        i: DataInputStream,
        base: Document,
        version: Int,
    ): HashMap<Long?, ParagraphProperties> {
        val paragraphs = base.paragraphs()
        val patches = HashMap<Long?, ParagraphProperties>()
        repeat(count(i, paragraphs.size)) {
            val at = i.readInt()
            require(at in paragraphs.indices && !patches.containsKey(paragraphs[at].anchor))
            val h = i.readUnsignedByte()
            val k = i.readUnsignedByte()
            require(k in ListKind.entries.indices)
            val restart = i.readUnsignedShort()
            val item = text(i, MAX_ID_LENGTH)
            val parent = text(i, MAX_ID_LENGTH)
            val marker = if (version >= VERSION_MARKER_TYPOGRAPHY) {
                val font = i.readUnsignedByte()
                require(font in 1 until StoredFont.entries.size)
                MarkerTypography(StoredFont.entries[font], i.readUnsignedByte())
            } else {
                MarkerTypography()
            }
            patches[paragraphs[at].anchor] =
                paragraphs[at].properties.copy(
                    heading = h,
                    list = ListKind.entries[k],
                    restart = restart,
                    item = item,
                    parent = parent,
                    markerTypography = marker,
                )
        }
        return patches
    }

    private fun readLinkedUnits(i: DataInputStream, units: MutableList<TextUnit>, links: List<LinkValue>) {
        val used = HashSet<Int>()
        repeat(count(i, MAX_UNITS)) {
            val at = i.readInt()
            val link = i.readInt()
            require(at in units.indices && used.add(at) && link in links.indices && units[at].kind != UnitKind.IMAGE)
            val u = units[at]
            units[at] =
                if (u.kind ==
                    UnitKind.PARAGRAPH_BREAK
                ) {
                    u.copy(paragraphStyle = u.paragraphStyle.copy(link = links[link]))
                } else {
                    u.copy(style = u.style.copy(link = links[link]))
                }
        }
    }

    private fun readImageUnits(i: DataInputStream, units: MutableList<TextUnit>) {
        val used = HashSet<Int>()
        repeat(count(i, MAX_UNITS)) {
            val at = i.readInt()
            require(at in units.indices && used.add(at) && units[at].kind == UnitKind.IMAGE)
            units[at] = units[at].copy(
                image = ImageBlock(text(i, MAX_ID_LENGTH), i.readUnsignedShort(), text(i, MAX_ALT_LENGTH)),
            )
        }
    }

    private fun readResources(i: DataInputStream): HashMap<String, ImageResource> {
        val resources = HashMap<String, ImageResource>()
        var bytesTotal = 0L
        var pixelsTotal = 0L
        repeat(count(i, MAX_RESOURCES)) {
            val id = text(i, MAX_ID_LENGTH)
            val w = i.readInt()
            val h = i.readInt()
            val mime = text(i, MAX_MIME_LENGTH)
            require(w in 1..MAX_IMAGE_DIMENSION && h in 1..MAX_IMAGE_DIMENSION)
            pixelsTotal += w.toLong() * h
            require(pixelsTotal <= MAX_TOTAL_PIXELS) { "Aggregate decoded image dimensions exceed limits" }
            val size = i.readInt()
            require(size in MIN_IMAGE_BYTES..ImageDecoder.MAX_BYTES && size <= i.available())
            bytesTotal += size
            require(bytesTotal <= MAX_TOTAL_IMAGE_BYTES)
            val bytes = i.readNBytes(size)
            val (actual, r) = ImageDecoder.validate(bytes)
            require(id == actual && r.width == w && r.height == h && r.mime == mime && resources.put(id, r) == null)
        }
        return resources
    }

    private fun legacyStyle(bits: Int): TextStyleFlags {
        require(bits in 0..LEGACY_STYLE_BITS)
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
            val breakBefore = if (r.breakBefore) BREAK_BEFORE_FLAG else 0
            val keepWithNext = if (r.keepWithNext) KEEP_WITH_NEXT_FLAG else 0
            val keepLines = if (r.keepLines) KEEP_LINES_FLAG else 0
            o.writeByte(breakBefore or keepWithNext or keepLines)
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
                text(o, flow.id, RUNNING_ID_LENGTH)
                text(o, flow.text, MAX_RUNNING_TEXT_LENGTH)
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
            require(flags in 0..PAGINATION_FLAGS)
            p.anchor to
                p.properties.copy(
                    pagination = PaginationRules(
                        flags and BREAK_BEFORE_FLAG != 0,
                        flags and KEEP_WITH_NEXT_FLAG != 0,
                        flags and KEEP_LINES_FLAG != 0,
                        i.readUnsignedByte(),
                        i.readUnsignedByte(),
                    ),
                )
        }
        val n = i.readInt()
        require(n in 0..base.units.size && n <= i.available() / Int.SIZE_BYTES)
        val indices = List(n) { i.readInt() }
        require(
            indices.distinct().size == n &&
                indices.all { it in base.units.indices && base.units[it].kind == UnitKind.PARAGRAPH_BREAK },
        )
        val first = i.readBoolean()
        val digits = i.readUnsignedByte()
        require(digits in PageDigits.entries.indices)
        fun flow(): RunningFlow? {
            if (!i.readBoolean())return null
            val id = text(i, RUNNING_ID_LENGTH)
            val raw = text(i, MAX_RUNNING_TEXT_LENGTH)
            val style = readStyle(i)
            val dir = i.readUnsignedByte()
            val align = i.readUnsignedByte()
            val field = i.readUnsignedByte()
            require(
                dir in ParagraphDirection.entries.indices &&
                    align in ParagraphAlignment.entries.indices &&
                    field in RunningField.entries.indices,
            )
            return RunningFlow(
                id,
                raw,
                style,
                ParagraphDirection.entries[dir],
                ParagraphAlignment.entries[align],
                RunningField.entries[field],
            )
        }
        val running = RunningContent(flow(), flow(), first, PageDigits.entries[digits])
        val set = indices.toSet()
        return base.patchParagraphs(props).copy(
            units = base.patchParagraphs(props).units.mapIndexed { at, u ->
                if (at in
                    set
                ) {
                    u.copy(structuralBreak = StructuralBreak.PAGE)
                } else {
                    u
                }
            },
            running = running,
        )
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
    private fun readParagraph(i: DataInputStream) = ParagraphProperties.fromBits(
        i.readUnsignedByte(),
    ).copy(
        spaceBefore = i.readUnsignedShort(),
        spaceAfter = i.readUnsignedShort(),
        linePermille = i.readUnsignedShort(),
    )

    fun read(path: Path): Document = Files.newInputStream(path).use { input ->
        // Bounded even when a file grows between stat and read, or is not a regular file.
        input.readNBytes(MAX_ENCODED_BYTES + 1).let { bytes ->
            decode(bytes).also { SafeDocumentIO.remember(it, path, bytes) }
        }
    }

    fun write(path: Path, document: Document, expected: Document? = null) = writeImpl(path, document, expected) {}

    /** Fault seam only used by checks, after force and before the atomic replacement. */
    fun writeImpl(path: Path, document: Document, expected: Document? = null, beforeReplace: () -> Unit) {
        val bytes = encode(document)
        val destination = path.toAbsolutePath().normalize()
        fun checkOriginal() {
            if (expected !=
                null
            ) {
                check(read(destination).same(expected)) {
                    "This file changed outside this window. Use Save As to keep your edits in a separate file."
                }
            }
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
