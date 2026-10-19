package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.ParagraphProperties
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.model.TypographyProfileIds
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.WrapAffinity
import dev.shayxo.composedoc.model.freshStructureId
import dev.shayxo.composedoc.model.paragraphAnchor
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.model.patchParagraphs
import dev.shayxo.composedoc.model.validateStructure
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Clone identities start far above engine serials of ordinary documents. */
private const val FIRST_CLONE_ID = 1_000_000_000L
private const val TEMPLATE_MAGIC = 0x4154544d // ATTM
private const val MAX_USER_TEMPLATES = 128
private const val MAX_TEMPLATE_ENTRIES = 131
private const val MAX_TEMPLATE_LABEL_LENGTH = 128
private const val TEMPLATE_TEXT_SIZE = 26
private const val WORKSPACE_MAGIC = 0x41545753 // ATWS
private const val MAX_WORKSPACE_INDEX_BYTES = 4 * 1024 * 1024
private const val MAX_WORKSPACE_ENTRIES = 128
private const val MAX_ACTIVE_WINDOWS = 32
private const val UUID_LENGTH = 36
private const val MAX_NAME_LENGTH = 512
private const val SHA256_HEX_LENGTH = 64
private const val MAX_FONT_ID_LENGTH = 64
private const val MAX_VIEWPORT_EXTENT = 16384
private const val MAX_POSITION = 100000
private const val HALF_ZOOM = .5f
private const val THREE_QUARTER_ZOOM = .75f

/** Independent unsaved content: owner, units, paragraph, list and link identities are freshly allocated. */
fun cloneDocument(source: Document): Document {
    val units = source.units.map { freshCloneId.getAndIncrement() }
    val blocks = source.paragraphs().associate { it.anchor to freshCloneId.getAndIncrement() }
    val items = source.paragraphs().map {
        it.properties.item
    }.filter { it.isNotEmpty() }.distinct().associateWith { freshStructureId() }
    val links = HashMap<String, String>()
    fun style(s: TextStyleFlags) =
        s.copy(link = s.link?.let { it.copy(id = links.getOrPut(it.id) { freshStructureId() }) })
    fun p(p: ParagraphProperties) = p.copy(item = items[p.item] ?: "", parent = items[p.parent] ?: "")
    return source.copy(
        units = source.units.mapIndexed { at, u ->
            u.copy(
                id = units[at],
                style = style(u.style),
                paragraphStyle = style(u.paragraphStyle),
                followingParagraph = p(u.followingParagraph),
                followingBlockId = if (u.kind ==
                    UnitKind.PARAGRAPH_BREAK
                ) {
                    blocks.getValue(u.paragraphAnchor)
                } else {
                    Long.MIN_VALUE
                },
            )
        },
        rootBlockId = blocks.getValue(source.rootBlockId),
        startStyle = style(source.startStyle),
        startParagraph = p(source.startParagraph),
        resources = source.resources.toMap(),
        running = source.running.fresh(),
    ).also(::validateStructure)
}
private val freshCloneId = java.util.concurrent.atomic.AtomicLong(FIRST_CLONE_ID)

data class TemplateEntry(val id: String, val label: String, val builtIn: Boolean) {
    override fun toString() = label
}
class TemplateStore(val root: Path) {
    val builtIns =
        listOf(
            TemplateEntry("blank", "Blank Urdu", true),
            TemplateEntry("letter", "Urdu Letter", true),
            TemplateEntry("article", "Urdu Article / Report", true),
        )
    fun list(): List<TemplateEntry> {
        if (!Files.exists(root))return builtIns
        val user = Files.list(root).use { files ->
            files.filter { it.fileName.toString().endsWith(".template") }.limit(MAX_USER_TEMPLATES + 1L).toList()
        }
        require(user.size <= MAX_USER_TEMPLATES)
        return builtIns + user.map { path ->
            val id = path.fileName.toString().removeSuffix(".template")
            WorkspaceBytes.uuid(id)
            WorkspaceBytes.unpack(WorkspaceBytes.read(path), TEMPLATE_MAGIC) { i ->
                val label = WorkspaceBytes.text(i, MAX_TEMPLATE_LABEL_LENGTH)
                WorkspaceBytes.bytes(i)
                TemplateEntry(id, label, false)
            }
        }
    }
    fun source(entry: TemplateEntry): Document {
        if (entry.builtIn) {
            require(entry in builtIns)
            return builtIn(entry.id)
        }
        return WorkspaceBytes.unpack(
            WorkspaceBytes.read(root.resolve(WorkspaceBytes.uuid(entry.id) + ".template")),
            TEMPLATE_MAGIC,
        ) { i ->
            WorkspaceBytes.text(i, MAX_TEMPLATE_LABEL_LENGTH)
            DocumentFiles.decode(WorkspaceBytes.bytes(i))
        }
    }
    fun create(entry: TemplateEntry) = cloneDocument(source(entry))
    fun save(label: String, d: Document): TemplateEntry {
        require(label.isNotBlank() && label.length <= MAX_TEMPLATE_LABEL_LENGTH)
        check(list().size < MAX_TEMPLATE_ENTRIES) { "User template limit reached (128)" }
        val id = UUID.randomUUID().toString()
        val b = WorkspaceBytes.pack(TEMPLATE_MAGIC) { o ->
            WorkspaceBytes.text(o, label, MAX_TEMPLATE_LABEL_LENGTH)
            WorkspaceBytes.bytes(o, DocumentFiles.encode(d))
        }
        WorkspaceBytes.atomic(root.resolve("$id.template"), b, replace = false)
        return TemplateEntry(id, label, false)
    }
    fun remove(entry: TemplateEntry) {
        require(!entry.builtIn)
        Files.delete(root.resolve(WorkspaceBytes.uuid(entry.id) + ".template"))
    }
    private fun builtIn(id: String): Document {
        val e = EditorEngine()
        e.setTextStyle(TextStylePatch(font = StoredFont.NOTO_NASTALIQ))
        e.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START)
        if (id ==
            "blank"
        ) {
            return e.snapshot().committed.copy(
                startStyle = TextStyleFlags(font = StoredFont.NOTO_NASTALIQ, size = TEMPLATE_TEXT_SIZE),
            )
        }
        val text = if (id ==
            "letter"
        ) {
            "خط کا عنوان\nتاریخ: ۱۹ اکتوبر ۲۰۲۶\nمحترم دوست،\nیہاں اپنا پیغام لکھیں۔\n" +
                "نیک تمناؤں کے ساتھ،\nآپ کا نام\nOriginal template — not expert linguistically reviewed."
        } else {
            "مضمون کا عنوان\nمصنف کا نام — تاریخ\nتعارف\nیہاں اپنے مضمون کا تعارف لکھیں۔\n" +
                "اہم نکات\nپہلا نکتہ\nدوسرا نکتہ\nEnglish notes — café, 123 and اردو\n" +
                "نتیجہ\nیہاں اپنی بات مکمل کریں۔\nOriginal template — not expert linguistically reviewed."
        }
        e.replace(text, "Template", paragraphBreaks = true)
        fun at(label: String, command: StructureCommand) {
            val n = text.indexOf(label)
            e.move(Selection(n, n))
            e.structure(command)
        }
        at(if (id == "letter")"خط کا عنوان" else "مضمون کا عنوان", StructureCommand.Role(1))
        if (id == "article") {
            listOf("تعارف", "اہم نکات", "نتیجہ").forEach { at(it, StructureCommand.Role(2)) }
            listOf("پہلا نکتہ", "دوسرا نکتہ").forEach { at(it, StructureCommand.ListType(ListKind.BULLET)) }
            val n = text.indexOf("English notes")
            e.move(Selection(n, n))
            e.setParagraphProperties(ParagraphDirection.LTR, ParagraphAlignment.START)
        }
        val result = e.snapshot().committed
        val ids = result.paragraphs().map {
            it.properties.item
        }.filter { it.isNotEmpty() }.distinct().mapIndexed { index, value ->
            value to
                UUID.nameUUIDFromBytes(("builtin-template-v1:$id:$index").toByteArray()).toString()
        }.toMap()
        return result.patchParagraphs(
            result.paragraphs().associate {
                it.anchor to
                    it.properties.copy(item = ids[it.properties.item] ?: "", parent = ids[it.properties.parent] ?: "")
            },
        )
    }
}

data class WorkspaceEntry(
    val id: String,
    val path: String?,
    val name: String,
    val fingerprint: FileFingerprint?,
    val opened: Long,
    val recovery: String,
    val unfinished: String,
    val selection: Selection = Selection(0, 0),
    val scroll: Float = 0f,
    val paged: Boolean = true,
    val zoom: Float = 1f,
    val font: String = "noto-nastaliq",
    val active: Boolean = true,
    val processId: Long = ProcessHandle.current().pid(),
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val contentHash: String = "",
) {
    override fun toString(): String {
        val location = if (path != null)" — $path" else " (unsaved recovery)"
        val state = if (active)"open at last checkpoint" else "deliberately closed"
        return "$name$location · $state"
    }
}

/** Bounded two-slot index, merged under a process lock. Paths are references, not owner identities. */
class WorkspaceStore(val root: Path) {
    @Volatile var warning: String? = null
        private set
    private val monitor = globalMonitors.computeIfAbsent(root.toAbsolutePath().normalize().toString()) { Any() }
    companion object {
        private val globalMonitors = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
    private fun encode(generation: Long, entries: List<WorkspaceEntry>) = WorkspaceBytes.pack(WORKSPACE_MAGIC) { o ->
        o.writeLong(generation)
        o.writeInt(entries.size)
        for (e in entries) {
            WorkspaceBytes.text(o, e.id, UUID_LENGTH)
            o.writeBoolean(e.path != null)
            e.path?.let { WorkspaceBytes.text(o, it) }
            WorkspaceBytes.text(o, e.name, MAX_NAME_LENGTH)
            o.writeBoolean(e.fingerprint != null)
            e.fingerprint?.let {
                o.writeBoolean(it.exists)
                o.writeLong(it.size)
                WorkspaceBytes.text(o, it.sha256, SHA256_HEX_LENGTH)
            }
            o.writeLong(e.opened)
            WorkspaceBytes.text(o, e.recovery, UUID_LENGTH)
            WorkspaceBytes.text(o, e.unfinished, UUID_LENGTH)
            o.writeInt(e.selection.anchor)
            o.writeInt(e.selection.focus)
            o.writeBoolean(e.selection.primary)
            o.writeByte(e.selection.wrapAffinity.ordinal)
            o.writeFloat(e.scroll)
            o.writeBoolean(e.paged)
            o.writeFloat(e.zoom)
            WorkspaceBytes.text(o, e.font, MAX_FONT_ID_LENGTH)
            o.writeBoolean(e.active)
            o.writeLong(e.processId)
            o.writeInt(e.viewportWidth)
            o.writeInt(e.viewportHeight)
            WorkspaceBytes.text(o, e.contentHash, SHA256_HEX_LENGTH)
        }
    }
    private fun read(): Pair<Long, List<WorkspaceEntry>> {
        var failures = 0
        val valid = (0..1).mapNotNull { slot ->
            val p = root.resolve("workspace-$slot.bin")
            if (!Files.exists(p)) {
                null
            } else {
                runCatching {
                    WorkspaceBytes.unpack(WorkspaceBytes.read(p, MAX_WORKSPACE_INDEX_BYTES), WORKSPACE_MAGIC) { i ->
                        val g = i.readLong()
                        require(g > 0)
                        val n = i.readInt()
                        require(n in 0..MAX_WORKSPACE_ENTRIES)
                        g to List(n) { readEntry(i) }.also { require(it.map { it.id }.distinct().size == it.size) }
                    }
                }.getOrElse {
                    failures++
                    null
                }
            }
        }
        warning = if (failures == 0) {
            null
        } else if (valid.isEmpty()) {
            "Workspace index unreadable. Documents and recovery records are intact; open/recover them explicitly."
        } else {
            "Workspace index used its valid fallback after an interrupted/corrupt publication."
        }
        return valid.maxByOrNull { it.first } ?: (0L to emptyList())
    }
    private fun readEntry(i: java.io.DataInputStream): WorkspaceEntry {
        val id = WorkspaceBytes.uuid(WorkspaceBytes.text(i, UUID_LENGTH))
        val path = if (i.readBoolean())WorkspaceBytes.text(i) else null
        val name = WorkspaceBytes.text(i, MAX_NAME_LENGTH)
        val fp = if (i.readBoolean()) {
            FileFingerprint(
                i.readBoolean(),
                i.readLong(),
                WorkspaceBytes.text(i, SHA256_HEX_LENGTH),
            )
        } else {
            null
        }
        val time = i.readLong()
        val r = WorkspaceBytes.uuid(WorkspaceBytes.text(i, UUID_LENGTH))
        val u = WorkspaceBytes.uuid(WorkspaceBytes.text(i, UUID_LENGTH))
        val a = i.readInt()
        val f = i.readInt()
        val primary = i.readBoolean()
        val affinity = i.readUnsignedByte()
        require(affinity in WrapAffinity.entries.indices)
        val scroll = i.readFloat()
        val paged = i.readBoolean()
        val zoom = i.readFloat()
        val font = WorkspaceBytes.text(i, MAX_FONT_ID_LENGTH)
        val active = i.readBoolean()
        val process = i.readLong()
        require(process > 0)
        val vw = i.readInt()
        val vh = i.readInt()
        require(vw in 0..MAX_VIEWPORT_EXTENT && vh in 0..MAX_VIEWPORT_EXTENT)
        val content = WorkspaceBytes.text(i, SHA256_HEX_LENGTH)
        require(content.isEmpty() || content.matches(Regex("[0-9a-f]{64}")))
        require(
            a in 0..MAX_POSITION &&
                f in 0..MAX_POSITION &&
                scroll.isFinite() &&
                scroll >= 0 &&
                zoom in setOf(HALF_ZOOM, THREE_QUARTER_ZOOM, 1f) &&
                font in TypographyProfileIds.supported,
        )
        return WorkspaceEntry(
            id, path, name, fp, time, r, u,
            Selection(
                a,
                f,
                primary,
                WrapAffinity.entries[affinity],
            ),
            scroll, paged, zoom, font, active, process, vw, vh, content,
        )
    }
    fun entries() = locked { read().second }
    fun update(entry: WorkspaceEntry, interrupt: (String) -> Unit = {}) = mutate(interrupt) { old ->
        val replacement = old.filter { it.id != entry.id } + entry
        check(
            replacement.count {
                it.active
            } <= MAX_ACTIVE_WINDOWS,
        ) { "Workspace has 32 active window references; close a window first" }
        // Recent history may be bounded, but unresolved/active window references cannot be evicted.
        replacement.sortedByDescending { it.opened }.let { all ->
            all.filter { it.active } +
                all.filter { !it.active }.take((MAX_WORKSPACE_ENTRIES - all.count { it.active }).coerceAtLeast(0))
        }
    }
    fun remove(id: String) = mutate { it.filter { e -> e.id != id } }
    fun clear() = mutate { emptyList() }
    private fun mutate(interrupt: (String) -> Unit = {}, change: (List<WorkspaceEntry>) -> List<WorkspaceEntry>) =
        locked {
            val (g, old) = read()
            val entries = change(old)
            require(entries.size <= MAX_WORKSPACE_ENTRIES)
            WorkspaceBytes.atomic(root.resolve("workspace-${(g + 1) % 2}.bin"), encode(g + 1, entries), interrupt)
        }
    private fun <T> locked(action: () -> T): T {
        check(!javax.swing.SwingUtilities.isEventDispatchThread())
        Files.createDirectories(root)
        synchronized(monitor) {
            java.nio.channels.FileChannel.open(
                root.resolve("index.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { c ->
                c.lock().use { return action() }
            }
        }
    }
}

fun workspacePositionsValid(entry: WorkspaceEntry, document: Document) = entry.contentHash.isNotEmpty() &&
    entry.contentHash == WorkspaceBytes.hash(DocumentFiles.encode(document)) &&
    entry.selection.end <= document.units.size
