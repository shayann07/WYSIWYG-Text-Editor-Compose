package editor

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/** Private prototype recovery envelope. The document remains the existing .astra codec. */
internal data class LocalCopy(val session: String, val sequence: Long, val timestamp: Long,
    val originalPath: String?, val document: Document)
internal data class LocalRecoveryEntry(val session: String, val copy: LocalCopy?, val warning: String?) {
    override fun toString(): String {
        val name = copy?.originalPath?.let { runCatching { Path.of(it).fileName.toString() }.getOrNull() } ?: "Untitled"
        val time = copy?.timestamp?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")) } ?: "unknown time"
        return "$name — $time" + if (warning != null) " · ${if (copy == null) "unreadable copy" else "valid fallback"}" else ""
    }
}

internal class LocalRecoveryStore(val root: Path, val interruption: (String) -> Unit = {}) {
    companion object {
        fun defaultRoot(): Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "AstraEditorPrototype", "recovery")
        private const val MAGIC = 0x41535243
        private const val MAX_RECORD = 21_000_000
        fun encode(copy: LocalCopy): ByteArray {
            require(copy.sequence > 0)
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC); out.writeInt(1); out.writeUTF(copy.session)
                out.writeLong(copy.sequence); out.writeLong(copy.timestamp)
                out.writeBoolean(copy.originalPath != null)
                copy.originalPath?.let { value -> require(value.length <= 32768); out.writeInt(value.length); value.forEach { out.writeChar(it.code) } }
                val doc = DocumentFiles.encode(copy.document); out.writeInt(doc.size); out.write(doc)
            }
            val body = bytes.toByteArray(); require(body.size + 32 <= MAX_RECORD)
            return body + MessageDigest.getInstance("SHA-256").digest(body)
        }
        fun decode(bytes: ByteArray): LocalCopy {
            require(bytes.size in 80..MAX_RECORD) { "Incomplete recovery record" }
            val body = bytes.copyOfRange(0, bytes.size - 32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size))) { "Recovery checksum failed" }
            return DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1) { "Unsupported recovery record" }
                val session = input.readUTF(); require(UUID.fromString(session).toString() == session)
                val seq = input.readLong(); require(seq > 0)
                val time = input.readLong(); require(time > 0)
                val original = if (input.readBoolean()) { val count = input.readInt(); require(count in 0..32768); CharArray(count) { input.readChar() }.concatToString() } else null
                val size = input.readInt(); require(size in 44..DocumentFiles.MAX_ENCODED_BYTES && input.available() == size)
                LocalCopy(session, seq, time, original, DocumentFiles.decode(input.readNBytes(size)))
            }
        }
    }
    internal class Lease(val session: String, val directory: Path, val channel: FileChannel, val lock: FileLock) : AutoCloseable {
        override fun close() { lock.release(); channel.close() }
    }
    private fun io() { check(!SwingUtilities.isEventDispatchThread()) { "Recovery IO on UI thread" } }
    private fun directory(session: String): Path { require(UUID.fromString(session).toString() == session); return root.resolve(session) }
    fun acquire(session: String): Lease {
        io(); val dir = directory(session); Files.createDirectories(dir)
        val channel = FileChannel.open(dir.resolve("active.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try { return Lease(session, dir, channel, checkNotNull(channel.tryLock()) { "Recovery copy is active in another window" }) }
        catch (failure: Throwable) { channel.close(); throw failure }
    }
    private fun readEntry(dir: Path): LocalRecoveryEntry {
        val failures = ArrayList<String>(); val valid = ArrayList<LocalCopy>()
        for (name in listOf("copy-0.bin", "copy-1.bin")) {
            val path = dir.resolve(name)
            if (Files.exists(path)) try {
                val copy = Files.newInputStream(path).use { decode(it.readNBytes(MAX_RECORD + 1)) }
                require(copy.session == dir.fileName.toString()) { "Recovery owner mismatch" }; valid += copy
            } catch (e: Exception) { failures += "$name: ${e.message}" }
        }
        if (Files.exists(dir.resolve("pending.tmp"))) failures += "An interrupted unpublished write was ignored"
        return LocalRecoveryEntry(dir.fileName.toString(), valid.maxByOrNull { it.sequence }, failures.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }
    fun scan(): List<LocalRecoveryEntry> {
        io(); if (!Files.exists(root)) return emptyList()
        return Files.list(root).use { dirs -> dirs.filter { Files.isDirectory(it) && runCatching { UUID.fromString(it.fileName.toString()).toString() == it.fileName.toString() }.getOrDefault(false) }
            .toList().mapNotNull { dir ->
                val lease = try { acquire(dir.fileName.toString()) } catch (_: java.nio.channels.OverlappingFileLockException) { return@mapNotNull null }
                    catch (_: IllegalStateException) { return@mapNotNull null }
                lease.use {
                    if (Files.exists(dir.resolve("discarded"))) return@mapNotNull null
                    val entry = readEntry(dir)
                    entry.takeIf { it.copy != null || it.warning != null }
                }
            }.sortedByDescending { it.copy?.timestamp ?: Long.MIN_VALUE } }
    }
    fun restore(entry: LocalRecoveryEntry): LocalCopy {
        io(); acquire(entry.session).use { lease ->
            check(!Files.exists(lease.directory.resolve("discarded"))) { "Recovery copy was explicitly discarded" }
            val current = readEntry(lease.directory)
            val copy = checkNotNull(current.copy) { "No valid recovery copy; records retained" }
            check(copy.sequence == entry.copy?.sequence) { "Recovery copy changed; refresh the list" }
            return copy
        }
    }
    internal fun latest(lease:Lease):LocalCopy? { io();check(lease.lock.isValid);return readEntry(lease.directory).copy }
    fun publish(lease: Lease, copy: LocalCopy): Boolean {
        io(); require(lease.session == copy.session && lease.lock.isValid)
        check(!Files.exists(lease.directory.resolve("discarded"))) { "Recovery session was discarded" }
        if ((readEntry(lease.directory).copy?.sequence ?: 0) >= copy.sequence) return false
        val bytes = encode(copy); val temp = lease.directory.resolve("pending.tmp")
        interruption("before-write")
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { c ->
            val half = bytes.size / 2; val first = ByteBuffer.wrap(bytes, 0, half)
            while (first.hasRemaining()) c.write(first)
            interruption("mid-write")
            val second = ByteBuffer.wrap(bytes, half, bytes.size - half); while (second.hasRemaining()) c.write(second)
            c.force(true)
        }
        interruption("before-publish")
        Files.move(temp, lease.directory.resolve("copy-${copy.sequence % 2}.bin"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        interruption("after-publish")
        return true
    }
    fun discard(lease: Lease) {
        io(); check(lease.lock.isValid)
        // A forced marker makes partial cleanup unambiguous if the process dies here.
        FileChannel.open(lease.directory.resolve("discard.tmp"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { c -> c.write(ByteBuffer.wrap(byteArrayOf(1))); c.force(true) }
        Files.move(lease.directory.resolve("discard.tmp"), lease.directory.resolve("discarded"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        for (name in listOf("copy-0.bin", "copy-1.bin", "pending.tmp")) Files.deleteIfExists(lease.directory.resolve(name))
    }
    fun discard(entry: LocalRecoveryEntry) { io(); acquire(entry.session).use { lease ->
        val current = readEntry(lease.directory)
        check(current.copy?.sequence == entry.copy?.sequence) { "Recovery copy changed; refresh before discarding" }; discard(lease)
    } }
}

/** One in-flight write and one coalesced newest snapshot. The fixed tick never starves under continuous typing. */
internal class LocalRecoveryController(val store: LocalRecoveryStore, val refresh: () -> Unit, interval: Int = 2000) {
    val session = UUID.randomUUID().toString()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "astra-recovery-$session") }
    private var observed: LocalCopy? = null
    private var pending: LocalCopy? = null
    private var running = false
    private var closing: Boolean? = null // true: explicit normal close/discard; false: keep on unexpected disposal
    private var completed = 0L
    private var completedTime = 0L
    private var failure: String? = null
    internal var maximumPending = 0; private set
    private val timer = javax.swing.Timer(interval) { checkpointNow() }.apply { start() }
    @Synchronized fun observe(document: Document, original: Path?) {
        if (closing != null) return
        val path = original?.toAbsolutePath()?.normalize()?.toString()
        val old = observed
        if (old != null && old.originalPath == path && (old.document === document || old.document.same(document))) return
        observed = LocalCopy(session, (old?.sequence ?: 0) + 1, System.currentTimeMillis(), path, document)
    }
    @Synchronized fun checkpointNow() {
        if (closing != null) return
        val copy = observed ?: return
        if (copy.sequence <= completed) return
        pending = copy; maximumPending = maxOf(maximumPending, 1)
        startWorker()
    }
    @Synchronized private fun startWorker() {
        if (running) return
        running = true
        executor.execute {
            var lease: LocalRecoveryStore.Lease? = synchronized(this) { heldLease }
            try {
                while (true) {
                    val copy = synchronized(this) { if (closing == true) pending = null; pending.also { pending = null } }
                    if (copy != null) {
                        try {
                            if (lease == null) lease = store.acquire(session)
                            store.publish(checkNotNull(lease), copy)
                            synchronized(this) { completed = maxOf(completed, copy.sequence); completedTime = System.currentTimeMillis(); failure = null }
                        } catch (e: Exception) { synchronized(this) { failure = e.message ?: e.javaClass.simpleName } }
                        SwingUtilities.invokeLater(refresh)
                    }
                    synchronized(this) {
                        if (pending != null) continue
                        if (closing != null) {
                            try { if (closing == true && lease != null) store.discard(checkNotNull(lease)) }
                            catch (e: Exception) { failure = "Cleanup failed; copy kept: ${e.message}" }
                            lease?.close(); lease = null; executor.shutdown()
                        }
                        running = false; return@execute
                    }
                }
            } finally {
                // A live controller keeps its lease between writes, so it is never offered as a crashed session.
                synchronized(this) { heldLease = lease }
            }
        }
    }
    private var heldLease: LocalRecoveryStore.Lease? = null
    @Synchronized fun finish(discard: Boolean) {
        if (closing != null) return
        timer.stop(); closing = discard
        if (!discard && observed?.sequence != completed) pending = observed
        startWorker()
    }
    @Synchronized fun status(): String = when {
        failure != null -> "Recovery copy failed — retrying"
        completed == 0L -> "Recovery copy pending"
        completed < (observed?.sequence ?: 0) -> "Recovery pending (last ${time()})"
        else -> "Recovery copy ${time()}"
    }
    @Synchronized fun detail(): String = failure ?: "Only committed content. 2-second scheduling plus disk time; only the last completed copy is protected."
    private fun time(): String = java.time.Instant.ofEpochMilli(completedTime).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    @Synchronized internal fun state(): Map<String, Any?> = mapOf("session" to session, "observed" to observed?.sequence, "completed" to completed,
        "running" to running, "pending" to (pending != null), "failure" to failure, "closing" to closing, "maximumPending" to maximumPending)
}
