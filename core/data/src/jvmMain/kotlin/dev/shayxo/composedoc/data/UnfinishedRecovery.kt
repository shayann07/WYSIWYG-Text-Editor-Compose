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

private const val DEFAULT_QUOTA_BYTES = 64L * 1024 * 1024
private const val DEFAULT_RECORD_LIMIT = 16
private const val RECORD_MAGIC = 0x41554652 // AUFR
private const val DELTA_MAGIC = 0x4155444c // AUDL
private const val RESOLUTION_MAGIC = 0x41555253 // AURS
private const val EXPORT_MAGIC = 0x41555245 // AURE
private const val DECISION_RESERVE_BYTES = 65536L
private const val DECISION_RESERVE_DIVISOR = 8
private const val UUID_LENGTH = 36
private const val SHA256_HEX_LENGTH = 64
private const val MAX_POSITION = 100000
private const val MAX_TEXT_LENGTH = 100000
private const val MAX_PROVENANCE_LENGTH = 103000
private const val MAX_RESOLUTION_BYTES = 1024
private const val MAX_RECORD_GROUPS = 256
private const val MAX_RECORD_BYTES = 256000
private const val RECORD_OVERHEAD_BYTES = 4096L
private const val METADATA_ENVELOPE_BYTES = 4L * 1024 * 1024
private const val DIGEST_BYTES = 32
private const val COPY_BUFFER_BYTES = 65536
private const val MAX_EXPORT_BYTES = 64L * 1024 * 1024

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
    override fun toString() = "$name · ${kind.name.lowercase().replace(
        '_',
        ' ',
    )} · ${java.time.Instant.ofEpochMilli(
        timestamp,
    ).atZone(
        java.time.ZoneId.systemDefault(),
    ).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"))}"
}
data class UnfinishedEntry(
    val record: UnfinishedRecord?,
    val owner: String,
    val id: String,
    val warning: String? = null,
) {
    override fun toString() = (record?.toString() ?: "Unreadable unfinished record $id") +
        if (warning != null) " · warning / valid fallback: $warning" else ""
}

/** Per-document lock is shared with committed recovery. All on-disk bytes, including temp/orphans, count. */
class UnfinishedStore(
    val recovery: LocalRecoveryStore,
    val quota: Long = DEFAULT_QUOTA_BYTES,
    val recordLimit: Int = DEFAULT_RECORD_LIMIT,
) {
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
        val reserve = if (decision)0 else minOf(DECISION_RESERVE_BYTES, quota / DECISION_RESERVE_DIVISOR)
        check(accounted(owner) + extra <= quota - reserve) {
            "RecoveryCaptureFailed: retained payload budget reached; decision reserve kept for explicit discard; " +
                "export/discard before retry"
        }
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
    private fun getBlob(owner: String, hash: String): ByteArray = WorkspaceBytes.read(blobPath(owner, hash)).also {
        require(
            WorkspaceBytes.hash(it) == hash,
        ) { "Missing/corrupt pinned prerequisite" }
    }
    private fun delta(base: ByteArray, target: ByteArray): ByteArray {
        var a = 0
        while (a < minOf(base.size, target.size) && base[a] == target[a])a++
        var z = 0
        while (z < minOf(base.size, target.size) - a && base[base.lastIndex - z] == target[target.lastIndex - z])z++
        return WorkspaceBytes.pack(DELTA_MAGIC) { o ->
            o.writeInt(a)
            o.writeInt(z)
            o.writeInt(target.size)
            WorkspaceBytes.bytes(o, target.copyOfRange(a, target.size - z))
        }
    }
    private fun inflate(base: ByteArray, b: ByteArray) = WorkspaceBytes.unpack(b, DELTA_MAGIC) { i ->
        val a = i.readInt()
        val z = i.readInt()
        val size = i.readInt()
        require(a >= 0 && z >= 0 && a.toLong() + z <= base.size && size in 0..WorkspaceBytes.MAX)
        val middle = WorkspaceBytes.bytes(i)
        require(a.toLong() + z + middle.size == size.toLong())
        base.copyOfRange(0, a) + middle + base.copyOfRange(base.size - z, base.size)
    }
    private fun encode(r: UnfinishedRecord) = WorkspaceBytes.pack(RECORD_MAGIC) { o ->
        WorkspaceBytes.text(o, r.owner, UUID_LENGTH)
        WorkspaceBytes.text(o, r.id, UUID_LENGTH)
        o.writeLong(r.generation)
        o.writeLong(r.timestamp)
        o.writeByte(r.kind.ordinal)
        WorkspaceBytes.text(o, r.name)
        WorkspaceBytes.text(o, r.basisHash, SHA256_HEX_LENGTH)
        WorkspaceBytes.text(o, r.deltaHash, SHA256_HEX_LENGTH)
        o.writeInt(r.selection.anchor)
        o.writeInt(r.selection.focus)
        o.writeBoolean(r.selection.primary)
        o.writeByte(r.selection.wrapAffinity.ordinal)
        o.writeInt(r.markedStart)
        o.writeInt(r.markedEnd)
        WorkspaceBytes.text(o, r.text, MAX_TEXT_LENGTH)
        o.writeInt(r.committed)
        o.writeBoolean(r.caret != null)
        r.caret?.let(o::writeInt)
        o.writeInt(r.epoch)
        o.writeLong(r.basisRevision)
        o.writeLong(r.identityGeneration)
        WorkspaceBytes.text(o, r.provenance, MAX_PROVENANCE_LENGTH)
    }
    private fun decode(b: ByteArray) = WorkspaceBytes.unpack(b, RECORD_MAGIC) { i ->
        val owner = WorkspaceBytes.uuid(WorkspaceBytes.text(i, UUID_LENGTH))
        val id = WorkspaceBytes.uuid(WorkspaceBytes.text(i, UUID_LENGTH))
        val gen = i.readLong()
        val time = i.readLong()
        require(gen > 0 && time > 0)
        val kind = i.readUnsignedByte()
        require(kind in UnfinishedKind.entries.indices)
        val name = WorkspaceBytes.text(i)
        val basis = WorkspaceBytes.text(i, SHA256_HEX_LENGTH)
        val delta = WorkspaceBytes.text(i, SHA256_HEX_LENGTH)
        val a = i.readInt()
        val f = i.readInt()
        val primary = i.readBoolean()
        val wrap = i.readUnsignedByte()
        require(a in 0..MAX_POSITION && f in 0..MAX_POSITION && wrap in WrapAffinity.entries.indices)
        val ms = i.readInt()
        val me = i.readInt()
        require(ms in -1..MAX_POSITION && me in -1..MAX_POSITION && ms <= me)
        val text = WorkspaceBytes.text(i, MAX_TEXT_LENGTH)
        val committed = i.readInt()
        val caret = if (i.readBoolean())i.readInt() else null
        val epoch = i.readInt()
        UnfinishedRecord(
            owner, id, gen, time, UnfinishedKind.entries[kind], name, basis, delta,
            Selection(
                a,
                f,
                primary,
                WrapAffinity.entries[wrap],
            ),
            ms, me, text, committed, caret, epoch, i.readLong(), i.readLong(),
            WorkspaceBytes.text(i, MAX_PROVENANCE_LENGTH),
        )
    }
    private fun resolution(owner: String, id: String): Long {
        val p = dir(owner).resolve("$id.resolved")
        if (!Files.exists(p))return 0
        return WorkspaceBytes.unpack(WorkspaceBytes.read(p, MAX_RESOLUTION_BYTES), RESOLUTION_MAGIC) { i ->
            require(WorkspaceBytes.text(i, UUID_LENGTH) == owner && WorkspaceBytes.text(i, UUID_LENGTH) == id)
            i.readLong().also { require(it > 0) }
        }
    }
    fun entries(owner: String): List<UnfinishedEntry> {
        val d = dir(owner)
        if (!Files.exists(d))return emptyList()
        val groups = Files.list(d).use { ps ->
            ps.filter {
                it.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.[01]\\.record"))
            }.toList().groupBy { it.fileName.toString().take(UUID_LENGTH) }
        }
        require(groups.size <= MAX_RECORD_GROUPS) { "Excess local record metadata; manual inspection required" }
        var resident = 0L
        var unresolved = 0
        return groups.mapNotNull { (id, paths) ->
            var failure: String? = null
            val valid = paths.mapNotNull { p ->
                readRecord(p, owner, id) { failure = it }
            }.maxByOrNull { it.generation }
            val resolved = runCatching { resolution(owner, id) }.getOrElse {
                failure = "Resolution unreadable: ${it.message}"
                0
            }
            if (valid != null && valid.generation <= resolved) {
                null
            } else {
                resident += (valid?.let { encode(it).size.toLong() + RECORD_OVERHEAD_BYTES } ?: RECORD_OVERHEAD_BYTES)
                require(++unresolved <= recordLimit && resident <= METADATA_ENVELOPE_BYTES) {
                    "Recovery metadata envelope exceeded; records kept for lossless export"
                }
                UnfinishedEntry(valid, owner, id, failure)
            }
        }.sortedByDescending { it.record?.timestamp ?: 0 }
    }

    // Reason: any unreadable record generation (IO, checksum, decode, ownership) is reported as a
    // warning with a valid fallback; an unexpected exception type must not hide the other generation.
    @Suppress("TooGenericExceptionCaught")
    private fun readRecord(p: Path, owner: String, id: String, failed: (String?) -> Unit): UnfinishedRecord? = try {
        decode(WorkspaceBytes.read(p, MAX_RECORD_BYTES)).also { require(it.owner == owner && it.id == id) }
    } catch (e: Exception) {
        failed(e.message)
        null
    }
    fun publish(
        lease: LocalRecoveryStore.Lease,
        c: UnfinishedCapture,
        name: String,
        interrupt: (String) -> Unit = {},
    ): UnfinishedRecord {
        check(lease.lock.isValid)
        val owner = lease.session
        WorkspaceBytes.uuid(c.id)
        val current = entries(owner)
        val old = current.firstOrNull { it.id == c.id }?.record
        if (old != null && old.generation >= c.generation)return old
        require(old != null || current.size < recordLimit) {
            "RecoveryCaptureFailed: 16 unresolved draft records reached; export/discard before retry"
        }
        requireCapturable(c)
        val base = ExactDocument.encode(c.basis)
        require(base.size <= WorkspaceBytes.MAX)
        val target = c.draft?.let(ExactDocument::encode)
        val bHash = putBlob(owner, base)
        interrupt("after-base")
        val dHash = target?.let { putBlob(owner, delta(base, it)) } ?: ""
        interrupt("after-delta")
        val r = UnfinishedRecord(
            owner, c.id, c.generation, System.currentTimeMillis(), c.kind, name, bHash, dHash, c.selection,
            c.marked?.first ?: -1, c.marked?.let { it.last + 1 } ?: -1,
            c.text, c.committed, c.caret, c.epoch, c.basisRevision, c.identityGeneration, c.provenance,
        )
        val b = encode(r)
        requireMetadataEnvelope(b, current, c.id)
        capacity(owner, b.size.toLong())
        WorkspaceBytes.atomic(dir(owner).resolve("${c.id}.${c.generation % 2}.record"), b, interrupt)
        clean(owner)
        return r
    }

    private fun requireCapturable(c: UnfinishedCapture) {
        require(
            c.text.length <= MAX_TEXT_LENGTH &&
                c.selection.start >= 0 &&
                c.selection.end <= MAX_POSITION &&
                (c.draft == null || c.selection.end <= c.draft.units.size),
        )
    }
    private fun requireMetadataEnvelope(b: ByteArray, current: List<UnfinishedEntry>, id: String) {
        require(
            b.size <= MAX_RECORD_BYTES &&
                current.filter { it.id != id }.sumOf {
                    it.record?.let { value -> encode(value).size.toLong() + RECORD_OVERHEAD_BYTES }
                        ?: RECORD_OVERHEAD_BYTES
                } +
                b.size +
                RECORD_OVERHEAD_BYTES <=
                METADATA_ENVELOPE_BYTES,
        ) {
            "RecoveryCaptureFailed: 4 MiB metadata/preview envelope exceeded; export/discard before retry"
        }
    }

    /** Revalidate selected generation and prerequisites. Never interprets callback text as accepted text. */
    fun load(r: UnfinishedRecord): Pair<Document, Document?> {
        val live = entries(r.owner).firstOrNull { it.id == r.id }?.record
        check(live == r) { "Recovery record changed; refresh" }
        val base = getBlob(r.owner, r.basisHash)
        val basis = ExactDocument.decode(base)
        val draft = r.deltaHash.takeIf {
            it.isNotEmpty()
        }?.let { ExactDocument.decode(inflate(base, getBlob(r.owner, it))) }
        if (draft != null)require(r.selection.end <= draft.units.size)
        return basis to draft
    }
    fun resolve(
        lease: LocalRecoveryStore.Lease,
        r: UnfinishedRecord,
        accepted: Document? = null,
        interrupt: (String) -> Unit = {},
    ) {
        require(lease.session == r.owner && lease.lock.isValid)
        check(
            entries(r.owner).firstOrNull {
                it.id == r.id
            }?.record == r,
        ) { "Record changed; discard only the selected generation" }
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
                rescue.publish(
                    acceptedLease,
                    LocalCopy(
                        rescueId,
                        maxOf(existing + 1, r.generation),
                        System.currentTimeMillis(),
                        r.name.takeIf {
                            it !=
                                "Untitled"
                        },
                        accepted,
                    ),
                )
            }
            interrupt("after-accepted")
        }
        val b = WorkspaceBytes.pack(RESOLUTION_MAGIC) { o ->
            WorkspaceBytes.text(o, r.owner, UUID_LENGTH)
            WorkspaceBytes.text(o, r.id, UUID_LENGTH)
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
        val pinned = deleteResolvedRecords(d, owner)
        val blobs = d.resolve("blobs")
        if (Files.exists(blobs)) {
            Files.list(blobs).use { ps ->
                ps.filter {
                    it.fileName.toString().endsWith(".bin") &&
                        it.fileName.toString().removeSuffix(".bin") !in pinned
                }.forEach(Files::deleteIfExists)
            }
        }
        // Under the exclusive inactive/live-owner lease, known unpublished temp files have no published references.
        // Unknown files and anything behind unreadable metadata remain accounted and untouched.
        Files.walk(d).use { ps ->
            ps.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().startsWith(".publication-") &&
                    it.fileName.toString().endsWith(".tmp")
            }.forEach(Files::deleteIfExists)
        }
    }

    /** Deletes resolved record generations and returns the blob hashes pinned by unresolved ones. */
    private fun deleteResolvedRecords(d: Path, owner: String): HashSet<String> {
        val pinned = HashSet<String>()
        Files.list(d).use { ps ->
            ps.filter { it.fileName.toString().endsWith(".record") }.forEach { p -> deleteOrPin(p, owner, pinned) }
        }
        return pinned
    }
    private fun deleteOrPin(p: Path, owner: String, pinned: MutableSet<String>) {
        val r = runCatching { decode(WorkspaceBytes.read(p, MAX_RECORD_BYTES)) }.getOrNull() ?: return
        if (r.generation <= resolution(owner, r.id)) {
            Files.deleteIfExists(p)
        } else {
            pinned += r.basisHash
            if (r.deltaHash.isNotEmpty())pinned += r.deltaHash
        }
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
                out.writeInt(EXPORT_MAGIC)
                out.writeInt(1)
                WorkspaceBytes.bytes(out, encode(r))
                for (hash in listOf(r.basisHash, r.deltaHash)) writeExportBlob(out, r.owner, hash)
                out.flush()
                hashStream.on(false)
                out.write(digest.digest())
                out.flush()
                require(channel.position() <= MAX_EXPORT_BYTES)
                channel.force(true)
            }
            check(!Files.exists(p))
            Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
    private fun writeExportBlob(out: DataOutputStream, owner: String, hash: String) {
        if (hash.isEmpty()) {
            out.writeInt(0)
        } else {
            val blob = blobPath(owner, hash)
            val size = Files.size(blob)
            require(size <= WorkspaceBytes.MAX + DIGEST_BYTES)
            out.writeInt(size.toInt())
            Files.newInputStream(blob).use { it.copyTo(out, COPY_BUFFER_BYTES) }
        }
    }
}

/** One capture in flight, one coalesced complete owner snapshot. Independent records are never coalesced away. */
