package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.WrapAffinity
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

enum class UnfinishedKind { ACTIVE_CANDIDATE, RETAINED_DRAFT, QUARANTINE }

/** Immutable owner capture. OS objects/handles and callbacks are never serialized or resumed. */
data class UnfinishedCapture(
    val id: String,
    val generation: Long,
    val kind: UnfinishedKind,
    val basis: Document,
    val draft: Document?,
    val selection: Selection,
    val marked: IntRange?,
    val text: String = "",
    val committed: Int = 0,
    val caret: Int? = null,
    val epoch: Int = 0,
    val basisRevision: Long = 0,
    val identityGeneration: Long = 0,
    val provenance: String = "owner capture; no verified native replay authority",
)
data class UnfinishedRecord(
    val owner: String,
    val id: String,
    val generation: Long,
    val timestamp: Long,
    val kind: UnfinishedKind,
    val name: String,
    val basisHash: String,
    val deltaHash: String,
    val selection: Selection,
    val markedStart: Int,
    val markedEnd: Int,
    val text: String,
    val committed: Int,
    val caret: Int?,
    val epoch: Int,
    val basisRevision: Long,
    val identityGeneration: Long,
    val provenance: String,
) {
    override fun toString() = "$name · ${kind.name.lowercase().replace('_',' ')} · ${java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"))}"
}
data class UnfinishedEntry(val record: UnfinishedRecord?, val owner: String, val id: String, val warning: String? = null) {
    override fun toString() = (record?.toString() ?: "Unreadable unfinished record $id") + if (warning != null) " · warning / valid fallback: $warning" else ""
}

/** Per-document lock is shared with committed recovery. All on-disk bytes, including temp/orphans, count. */
class UnfinishedStore(val recovery: LocalRecoveryStore, val quota: Long = 64L * 1024 * 1024, val recordLimit: Int = 16) {
    private val magic = 0x41554652
    private fun dir(owner: String) = recovery.root.resolve(WorkspaceBytes.uuid(owner)).resolve("unfinished")
    private fun blobPath(owner: String, hash: String): Path {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return dir(owner).resolve("blobs").resolve(hash + ".bin")
    }
    fun accounted(owner: String): Long {
        val d = dir(owner)
        if (!Files.exists(d))return 0
        return Files.walk(d).use { it.filter(Files::isRegularFile).mapToLong { p -> Files.size(p) }.sum() }
    }
    private fun capacity(owner: String, extra: Long, decision: Boolean = false) {
        val reserve = if (decision)0 else minOf(65536L, quota / 8)
        check(accounted(owner) + extra <= quota - reserve) { "RecoveryCaptureFailed: retained payload budget reached; decision reserve kept for explicit discard; export/discard before retry" }
    }
    private fun putBlob(owner: String, b: ByteArray): String {
        val hash = WorkspaceBytes.hash(b)
        val p = blobPath(owner, hash)
        if (Files.exists(p)) {
            require(WorkspaceBytes.hash(WorkspaceBytes.read(p)) == hash)
            return hash
        }
        capacity(owner, b.size.toLong())
        WorkspaceBytes.atomic(p, b)
        return hash
    }
    private fun getBlob(owner: String, hash: String): ByteArray = WorkspaceBytes.read(blobPath(owner, hash)).also { require(WorkspaceBytes.hash(it) == hash) { "Missing/corrupt pinned prerequisite" } }
    private fun delta(base: ByteArray, target: ByteArray): ByteArray {
        var a = 0
        while (a < minOf(base.size, target.size) && base[a] == target[a])a++
        var z = 0
        while (z < minOf(base.size, target.size) - a && base[base.lastIndex - z] == target[target.lastIndex - z])z++
        return WorkspaceBytes.pack(0x4155444c) { o ->
            o.writeInt(a)
            o.writeInt(z)
            o.writeInt(target.size)
            WorkspaceBytes.bytes(o, target.copyOfRange(a, target.size - z))
        }
    }
    private fun inflate(base: ByteArray, b: ByteArray) = WorkspaceBytes.unpack(b, 0x4155444c) { i ->
        val a = i.readInt()
        val z = i.readInt()
        val size = i.readInt()
        require(a >= 0 && z >= 0 && a.toLong() + z <= base.size && size in 0..WorkspaceBytes.MAX)
        val middle = WorkspaceBytes.bytes(i)
        require(a.toLong() + z + middle.size == size.toLong())
        base.copyOfRange(0, a) + middle + base.copyOfRange(base.size - z, base.size)
    }
    private fun encode(r: UnfinishedRecord) = WorkspaceBytes.pack(magic) { o ->
        WorkspaceBytes.text(o, r.owner, 36)
        WorkspaceBytes.text(o, r.id, 36)
        o.writeLong(r.generation)
        o.writeLong(r.timestamp)
        o.writeByte(r.kind.ordinal)
        WorkspaceBytes.text(o, r.name)
        WorkspaceBytes.text(o, r.basisHash, 64)
        WorkspaceBytes.text(o, r.deltaHash, 64)
        o.writeInt(r.selection.anchor)
        o.writeInt(r.selection.focus)
        o.writeBoolean(r.selection.primary)
        o.writeByte(r.selection.wrapAffinity.ordinal)
        o.writeInt(r.markedStart)
        o.writeInt(r.markedEnd)
        WorkspaceBytes.text(o, r.text, 100000)
        o.writeInt(r.committed)
        o.writeBoolean(r.caret != null)
        r.caret?.let(o::writeInt)
        o.writeInt(r.epoch)
        o.writeLong(r.basisRevision)
        o.writeLong(r.identityGeneration)
        WorkspaceBytes.text(o, r.provenance, 103000)
    }
    private fun decode(b: ByteArray) = WorkspaceBytes.unpack(b, magic) { i ->
        val owner = WorkspaceBytes.uuid(WorkspaceBytes.text(i, 36))
        val id = WorkspaceBytes.uuid(WorkspaceBytes.text(i, 36))
        val gen = i.readLong()
        val time = i.readLong()
        require(gen > 0 && time > 0)
        val kind = i.readUnsignedByte()
        require(kind in UnfinishedKind.entries.indices)
        val name = WorkspaceBytes.text(i)
        val basis = WorkspaceBytes.text(i, 64)
        val delta = WorkspaceBytes.text(i, 64)
        val a = i.readInt()
        val f = i.readInt()
        val primary = i.readBoolean()
        val wrap = i.readUnsignedByte()
        require(a in 0..100000 && f in 0..100000 && wrap in WrapAffinity.entries.indices)
        val ms = i.readInt()
        val me = i.readInt()
        require(ms in -1..100000 && me in -1..100000 && ms <= me)
        val text = WorkspaceBytes.text(i, 100000)
        val committed = i.readInt()
        val caret = if (i.readBoolean())i.readInt() else null
        val epoch = i.readInt()
        UnfinishedRecord(owner, id, gen, time, UnfinishedKind.entries[kind], name, basis, delta, Selection(a, f, primary, WrapAffinity.entries[wrap]), ms, me, text, committed, caret, epoch, i.readLong(), i.readLong(), WorkspaceBytes.text(i, 103000))
    }
    private fun resolution(owner: String, id: String): Long {
        val p = dir(owner).resolve("$id.resolved")
        if (!Files.exists(p))return 0
        return WorkspaceBytes.unpack(WorkspaceBytes.read(p, 1024), 0x41555253) { i ->
            require(WorkspaceBytes.text(i, 36) == owner && WorkspaceBytes.text(i, 36) == id)
            i.readLong().also { require(it > 0) }
        }
    }
    fun entries(owner: String): List<UnfinishedEntry> {
        val d = dir(owner)
        if (!Files.exists(d))return emptyList()
        val groups = Files.list(d).use { ps -> ps.filter { it.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.[01]\\.record")) }.toList().groupBy { it.fileName.toString().take(36) } }
        require(groups.size <= 256) { "Excess local record metadata; manual inspection required" }
        var resident = 0L
        var unresolved = 0
        return groups.mapNotNull { (id, paths) ->
            var failure: String? = null
            val valid = paths.mapNotNull { p ->
                try {
                    decode(WorkspaceBytes.read(p, 256000)).also { require(it.owner == owner && it.id == id) }
                } catch (e: Exception) {
                    failure = e.message
                    null
                }
            }.maxByOrNull { it.generation }
            val resolved = runCatching { resolution(owner, id) }.getOrElse {
                failure = "Resolution unreadable: ${it.message}"
                0
            }
            if (valid != null && valid.generation <= resolved) {
                null
            } else {
                resident += (valid?.let { encode(it).size.toLong() + 4096 } ?: 4096)
                require(++unresolved <= recordLimit && resident <= 4L * 1024 * 1024) { "Recovery metadata envelope exceeded; records kept for lossless export" }
                UnfinishedEntry(valid, owner, id, failure)
            }
        }.sortedByDescending { it.record?.timestamp ?: 0 }
    }
    fun publish(lease: LocalRecoveryStore.Lease, c: UnfinishedCapture, name: String, interrupt: (String) -> Unit = {}): UnfinishedRecord {
        check(lease.lock.isValid)
        val owner = lease.session
        WorkspaceBytes.uuid(c.id)
        val current = entries(owner)
        val old = current.firstOrNull { it.id == c.id }?.record
        if (old != null && old.generation >= c.generation)return old
        require(old != null || current.size < recordLimit) { "RecoveryCaptureFailed: 16 unresolved draft records reached; export/discard before retry" }
        require(c.text.length <= 100000 && c.selection.start >= 0 && c.selection.end <= 100000 && (c.draft == null || c.selection.end <= c.draft.units.size))
        val base = ExactDocument.encode(c.basis)
        require(base.size <= WorkspaceBytes.MAX)
        val target = c.draft?.let(ExactDocument::encode)
        val bHash = putBlob(owner, base)
        interrupt("after-base")
        val dHash = target?.let { putBlob(owner, delta(base, it)) } ?: ""
        interrupt("after-delta")
        val r = UnfinishedRecord(
            owner, c.id, c.generation, System.currentTimeMillis(), c.kind, name, bHash, dHash, c.selection, c.marked?.first ?: -1, c.marked?.let { it.last + 1 } ?: -1,
            c.text, c.committed, c.caret, c.epoch, c.basisRevision, c.identityGeneration, c.provenance,
        )
        val b = encode(r)
        require(b.size <= 256000 && current.filter { it.id != c.id }.sumOf { it.record?.let { value -> encode(value).size.toLong() + 4096 } ?: 4096L } + b.size + 4096 <= 4L * 1024 * 1024) {
            "RecoveryCaptureFailed: 4 MiB metadata/preview envelope exceeded; export/discard before retry"
        }
        capacity(owner, b.size.toLong())
        WorkspaceBytes.atomic(dir(owner).resolve("${c.id}.${c.generation % 2}.record"), b, interrupt)
        clean(owner)
        return r
    }

    /** Revalidate selected generation and prerequisites. Never interprets callback text as accepted text. */
    fun load(r: UnfinishedRecord): Pair<Document, Document?> {
        val live = entries(r.owner).firstOrNull { it.id == r.id }?.record
        check(live == r) { "Recovery record changed; refresh" }
        val base = getBlob(r.owner, r.basisHash)
        val basis = ExactDocument.decode(base)
        val draft = r.deltaHash.takeIf { it.isNotEmpty() }?.let { ExactDocument.decode(inflate(base, getBlob(r.owner, it))) }
        if (draft != null)require(r.selection.end <= draft.units.size)
        return basis to draft
    }
    fun resolve(lease: LocalRecoveryStore.Lease, r: UnfinishedRecord, accepted: Document? = null, interrupt: (String) -> Unit = {}) {
        require(lease.session == r.owner && lease.lock.isValid)
        check(entries(r.owner).firstOrNull { it.id == r.id }?.record == r) { "Record changed; discard only the selected generation" }
        // Apply/automatic lifecycle completion first protects the accepted result independently.
        if (accepted != null) {
            val b = ExactDocument.encode(accepted)
            putBlob(r.owner, b)
            capacity(r.owner, b.size.toLong())
            WorkspaceBytes.atomic(dir(r.owner).resolve("accepted.bin"), b)
            // Publish an ordinary separately discoverable committed copy before a resolution can hide this draft.
            val rescue = LocalRecoveryStore(recovery.root.parent)
            val rescueId = UUID.nameUUIDFromBytes((r.owner + "accepted-result").toByteArray()).toString()
            rescue.acquire(rescueId).use { acceptedLease ->
                val existing = rescue.latest(acceptedLease)?.sequence ?: 0L
                rescue.publish(acceptedLease, LocalCopy(rescueId, maxOf(existing + 1, r.generation), System.currentTimeMillis(), r.name.takeIf { it != "Untitled" }, accepted))
            }
            interrupt("after-accepted")
        }
        val b = WorkspaceBytes.pack(0x41555253) { o ->
            WorkspaceBytes.text(o, r.owner, 36)
            WorkspaceBytes.text(o, r.id, 36)
            o.writeLong(r.generation)
        }
        capacity(r.owner, b.size.toLong(), decision = true)
        WorkspaceBytes.atomic(dir(r.owner).resolve("${r.id}.resolved"), b, interrupt)
        // Conservative cleanup only after publication; unresolved and fallback prerequisites stay pinned.
        clean(r.owner)
    }
    private fun clean(owner: String) {
        val d = dir(owner)
        if (!Files.exists(d))return
        val live = entries(owner)
        if (live.any { it.record == null || it.warning != null })return
        val pinned = HashSet<String>()
        Files.list(d).use { ps ->
            ps.filter { it.fileName.toString().endsWith(".record") }.forEach { p ->
                val r = runCatching { decode(WorkspaceBytes.read(p, 256000)) }.getOrNull() ?: return@forEach
                if (r.generation <= resolution(owner, r.id)) {
                    Files.deleteIfExists(p)
                } else {
                    pinned += r.basisHash
                    if (r.deltaHash.isNotEmpty())pinned += r.deltaHash
                }
            }
        }
        val blobs = d.resolve("blobs")
        if (Files.exists(blobs))Files.list(blobs).use { ps -> ps.filter { it.fileName.toString().endsWith(".bin") && it.fileName.toString().removeSuffix(".bin") !in pinned }.forEach(Files::deleteIfExists) }
        // Under the exclusive inactive/live-owner lease, known unpublished temp files have no published references.
        // Unknown files and anything behind unreadable metadata remain accounted and untouched.
        Files.walk(d).use { ps -> ps.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith(".publication-") && it.fileName.toString().endsWith(".tmp") }.forEach(Files::deleteIfExists) }
    }
    fun scan(): List<UnfinishedEntry> {
        if (!Files.exists(recovery.root))return emptyList()
        return Files.list(recovery.root).use { dirs ->
            dirs.filter {
                Files.isDirectory(it) &&
                    runCatching {
                        WorkspaceBytes.uuid(it.fileName.toString())
                        true
                    }.getOrDefault(false)
            }.toList().flatMap { d ->
                val lease = try {
                    recovery.acquire(d.fileName.toString())
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    return@flatMap emptyList()
                } catch (_: IllegalStateException) {
                    return@flatMap emptyList()
                }
                lease.use {
                    clean(it.session)
                    entries(it.session)
                }
            }
        }
    }
    fun export(r: UnfinishedRecord, p: Path) {
        load(r)
        check(!Files.exists(p)) { "Choose a new export path; existing files are preserved" }
        Files.createDirectories(p.toAbsolutePath().parent)
        val temp = Files.createTempFile(p.toAbsolutePath().parent, ".unfinished-export-", ".tmp")
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val stream = java.nio.channels.Channels.newOutputStream(channel)
                val hashStream = java.security.DigestOutputStream(stream, digest)
                val out = DataOutputStream(hashStream)
                out.writeInt(0x41555245)
                out.writeInt(1)
                WorkspaceBytes.bytes(out, encode(r))
                for (hash in listOf(r.basisHash, r.deltaHash)) {
                    if (hash.isEmpty()) {
                        out.writeInt(0)
                    } else {
                        val blob = blobPath(r.owner, hash)
                        val size = Files.size(blob)
                        require(size <= WorkspaceBytes.MAX + 32)
                        out.writeInt(size.toInt())
                        Files.newInputStream(blob).use { it.copyTo(out, 65536) }
                    }
                }
                out.flush()
                hashStream.on(false)
                out.write(digest.digest())
                out.flush()
                require(channel.position() <= 64L * 1024 * 1024)
                channel.force(true)
            }
            check(!Files.exists(p))
            Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

/** One capture in flight, one coalesced complete owner snapshot. Independent records are never coalesced away. */
