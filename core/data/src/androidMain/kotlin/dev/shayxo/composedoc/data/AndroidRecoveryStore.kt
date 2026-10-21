package dev.shayxo.composedoc.data

import android.util.AtomicFile
import dev.shayxo.composedoc.model.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Private two-slot store. Every failure throws an actionable reason; no unresolved owner is evicted. */
class AndroidRecoveryStore(root: File, private val quotaBytes: Long = DEFAULT_QUOTA) {
    private val root by lazy { root.canonicalFile }

    // Reason: preserve the owner-approved additive public checkpoint signature and its named arguments.
    @Suppress("LongParameterList")
    fun checkpoint(
        owner: String,
        sequence: Long,
        name: String,
        document: Document,
        proposal: Document? = null,
        evidence: String = "",
    ): Boolean = locked {
        require(sequence > 0)
        val directory = directory(owner)
        val existing = slots(directory)
        val ledger = ledger(directory)
        val accepted = AndroidDocumentCodec.encode(document)
        val proposed = proposal?.let(AndroidDocumentCodec::encode) ?: byteArrayOf()
        val hash = AndroidRecoveryFormat.contentHash(name, accepted, proposed, evidence)
        val newest = existing.maxByOrNull { it.second.entry.sequence }
        val high = maxOf(ledger.sequence, newest?.second?.entry?.sequence ?: 0)
        check(sequence >= high) { "A newer checkpoint already exists; this stale write was refused" }
        check(sequence > ledger.resolved) { "This recovery generation was already discarded; use a new generation" }
        if (sequence == high) {
            val previousHash = if (newest?.second?.entry?.sequence == high) newest.second.contentHash else ledger.hash
            check(previousHash == hash) { "Recovery owner/sequence cannot be reused for different content" }
            if (existing.any { it.second.entry.sequence == sequence }) {
                writeLedger(directory, Ledger(high, ledger.resolved, hash))
                return@locked true
            }
        }
        val bytes = AndroidRecoveryFormat.encode(owner, sequence, RecoveryPayload(name, accepted, proposed, evidence))
        val owners = root.listFiles().orEmpty().count { it.isDirectory && unresolved(it) }
        check(unresolved(directory) || owners < OWNER_LIMIT) {
            "Recovery draft limit reached; resolve old drafts before retrying"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private recovery directory" }
        check(diskBytes() + LEDGER_LIMIT <= quotaBytes) {
            "Cannot reserve recovery identity: storage full; old records retained"
        }
        // Reserve an admitted identity before publication/quota failure. Retrying it cannot bind different contents.
        writeLedger(directory, Ledger(sequence, ledger.resolved, hash))
        check(diskBytes() + bytes.size + DECISION_RESERVE <= quotaBytes) {
            "Recovery quota is full; export/discard old drafts, then retry. Previous copies remain valid."
        }
        atomicBytes(File(directory, "slot-${sequence % 2}.bin"), bytes)
        true
    }

    /** Streaming checksum/header scan: does not decode documents, images, proposals or evidence strings. */
    fun entries(): List<AndroidRecoveryEntry> = locked {
        root.listFiles().orEmpty().filter { it.isDirectory && validOwner(it.name) }.mapNotNull { directory ->
            val valid = slots(directory)
            val order = runCatching { ledger(directory) }
            val ledger = order.getOrDefault(Ledger())
            val copy = valid.filter {
                it.second.entry.sequence > ledger.resolved
            }.maxByOrNull { it.second.entry.sequence }
            val slotCount = slotFiles(directory).count { file -> slotEvidence(file).any { it.exists() } }
            if (copy != null) {
                copy.second.entry.copy(
                    warning = when {
                        order.isFailure ->
                            "Ordering metadata corrupt; evidence retained. " +
                                order.exceptionOrNull()?.message
                        valid.size < slotCount -> "A corrupt slot was retained; showing a valid fallback"
                        ledger.sequence > copy.second.entry.sequence ->
                            "Latest attempt did not publish; showing last valid copy"
                        else -> null
                    },
                )
            } else if ((slotCount > 0 && ledger.sequence == 0L) || needsInspection(ledger, order.isFailure)) {
                AndroidRecoveryEntry(
                    directory.name,
                    ledger.sequence,
                    directory.lastModified(),
                    "Unreadable recovery evidence",
                    false,
                    slotFiles(directory).sumOf { it.length() },
                    "No valid checkpoint; evidence retained",
                )
            } else {
                null
            }
        }.sortedByDescending { it.timestamp }
    }

    fun read(owner: String, sequence: Long): AndroidRecoveryRecord = locked {
        val directory = directory(owner)
        check(sequence > ledger(directory).resolved) {
            "This generation was discarded; recovery record retained unchanged"
        }
        val file = slots(directory).firstOrNull { it.second.entry.sequence == sequence }?.first
            ?: error("Captured recovery generation is unavailable; newer/invalid records were retained")
        AndroidRecoveryFormat.load(file)
    }

    /** Resolve only the captured generation and its older fallback; a new arrival always survives. */
    fun discard(owner: String, sequence: Long): Boolean = locked {
        val directory = directory(owner)
        val ledger = ledger(directory)
        val valid = slots(directory)
        val newest = valid.maxByOrNull { it.second.entry.sequence }
        val high = maxOf(ledger.sequence, newest?.second?.entry?.sequence ?: 0)
        if (sequence <= ledger.resolved || sequence > high) return@locked false
        if (sequence != ledger.sequence && valid.none { it.second.entry.sequence == sequence }) return@locked false
        val hash = if (newest?.second?.entry?.sequence == high) newest.second.contentHash else ledger.hash
        writeLedger(directory, Ledger(high, maxOf(ledger.resolved, sequence), hash))
        for (file in slotFiles(directory)) {
            val header = runCatching { AndroidRecoveryFormat.header(file, verify = false) }.getOrNull()
            if (header != null && header.entry.sequence <= sequence) AtomicFile(file).delete()
        }
        true
    }

    /** Recovery envelope, explicitly distinct from an accepted .astra document. Failure never resolves it. */
    fun export(owner: String, sequence: Long): ByteArray = locked {
        val directory = directory(owner)
        check(sequence > ledger(directory).resolved) { "This generation was discarded" }
        val file = slots(directory).firstOrNull { it.second.entry.sequence == sequence }?.first
            ?: error("Cannot export the captured recovery generation; records retained")
        file.inputStream().use { it.readBytesBounded(AndroidRecoveryFormat.MAX_RECORD) }
    }

    private fun directory(owner: String) = File(root, AndroidRecoveryFormat.owner(owner))
    private fun validOwner(value: String) = runCatching {
        AndroidRecoveryFormat.owner(value)
        true
    }.getOrDefault(false)
    private fun slotFiles(directory: File) = listOf(File(directory, "slot-0.bin"), File(directory, "slot-1.bin"))
    private fun slotEvidence(file: File) = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
    private fun hasSlotEvidence(directory: File) = slotFiles(directory).any { slotEvidence(it).any(File::exists) }
    private fun unresolved(directory: File): Boolean {
        if (hasSlotEvidence(directory)) return true
        return runCatching { ledger(directory).let { it.sequence > it.resolved } }
            .getOrDefault(File(directory, "order.bin").exists())
    }
    private fun slots(directory: File): List<Pair<File, RecoveryHeader>> = slotFiles(directory).mapNotNull { file ->
        // Invalid bytes are retained, with listing warning/fallback; a scan must not destroy evidence.
        runCatching {
            if (file.exists() || File(file.path + ".bak").exists()) AtomicFile(file).openRead().close()
            file to AndroidRecoveryFormat.header(file).also { check(it.entry.owner == directory.name) }
        }.getOrNull()
    }

    private data class Ledger(val sequence: Long = 0, val resolved: Long = 0, val hash: String = "")
    private fun needsInspection(ledger: Ledger, unreadable: Boolean) = unreadable || ledger.sequence > ledger.resolved
    private fun ledger(directory: File): Ledger {
        val file = File(directory, "order.bin")
        if (!file.exists() && !File(file.path + ".bak").exists()) return Ledger()
        val raw = AtomicFile(file).openRead().use { it.readBytesBounded(LEDGER_LIMIT) }
        check(raw.size > DIGEST_HEX_BYTES) { "Recovery ordering ledger is incomplete; preserve it and use a new owner" }
        val body = raw.copyOfRange(0, raw.size - DIGEST_HEX_BYTES)
        check(androidDigest(body) == raw.copyOfRange(body.size, raw.size).toString(Charsets.US_ASCII)) {
            "Recovery ordering ledger is corrupt; preserve it and use a new owner"
        }
        return DataInputStream(ByteArrayInputStream(body)).use {
            check(it.readInt() == LEDGER_MAGIC)
            Ledger(it.readLong(), it.readLong(), it.readUTF()).also { value -> check(value.resolved <= value.sequence) }
        }
    }
    private fun writeLedger(directory: File, ledger: Ledger) {
        check(directory.isDirectory || directory.mkdirs())
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(LEDGER_MAGIC)
            it.writeLong(ledger.sequence)
            it.writeLong(ledger.resolved)
            it.writeUTF(ledger.hash)
        }
        val body = output.toByteArray()
        atomicBytes(File(directory, "order.bin"), body + androidDigest(body).toByteArray(Charsets.US_ASCII))
    }
    private fun diskBytes() = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun <T> locked(action: () -> T): T {
        androidWorkerOnly()
        require(quotaBytes > DECISION_RESERVE)
        check(root.isDirectory || root.mkdirs()) { "Cannot open private recovery storage" }
        return synchronized(LOCK) {
            RandomAccessFile(File(root, "store.lock"), "rw").use { file ->
                file.channel.use { channel -> channel.lock().use { action() } }
            }
        }
    }
    private companion object {
        private val LOCK = Any()
        private const val DEFAULT_QUOTA = 64L * 1024 * 1024
        private const val DECISION_RESERVE = 65536
        private const val OWNER_LIMIT = 16
        private const val LEDGER_LIMIT = 256
        private const val LEDGER_MAGIC = 0x414e4c47
        private const val DIGEST_HEX_BYTES = 64
    }
}
