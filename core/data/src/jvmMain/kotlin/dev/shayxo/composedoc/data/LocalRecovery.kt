package dev.shayxo.composedoc.data

import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import javax.swing.SwingUtilities

/** Private prototype recovery envelope. The document remains the existing .astra codec. */
data class LocalCopy(
    val session: String,
    val sequence: Long,
    val timestamp: Long,
    val originalPath: String?,
    val document: Document,
)
data class LocalRecoveryEntry(val session: String, val copy: LocalCopy?, val warning: String?) {
    override fun toString(): String {
        val name = copy?.originalPath?.let { runCatching { Path.of(it).fileName.toString() }.getOrNull() } ?: "Untitled"
        val time = copy?.timestamp?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")) } ?: "unknown time"
        return "$name — $time" + if (warning != null) " · ${if (copy == null) "unreadable copy" else "valid fallback"}" else ""
    }
}

class LocalRecoveryStore(val root: Path, val interruption: (String) -> Unit = {}) {
    companion object {
        fun defaultRoot(): Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "AstraEditorPrototype", "recovery")
        private const val MAGIC = 0x41535243
        private const val MAX_RECORD = 21_000_000
        fun encode(copy: LocalCopy): ByteArray {
            require(copy.sequence > 0)
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(1)
                out.writeUTF(copy.session)
                out.writeLong(copy.sequence)
                out.writeLong(copy.timestamp)
                out.writeBoolean(copy.originalPath != null)
                copy.originalPath?.let { value ->
                    require(value.length <= 32768)
                    out.writeInt(value.length)
                    value.forEach { out.writeChar(it.code) }
                }
                val doc = DocumentFiles.encode(copy.document)
                out.writeInt(doc.size)
                out.write(doc)
            }
            val body = bytes.toByteArray()
            require(body.size + 32 <= MAX_RECORD)
            return body + MessageDigest.getInstance("SHA-256").digest(body)
        }
        fun decode(bytes: ByteArray): LocalCopy {
            require(bytes.size in 80..MAX_RECORD) { "Incomplete recovery record" }
            val body = bytes.copyOfRange(0, bytes.size - 32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size))) { "Recovery checksum failed" }
            return DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1) { "Unsupported recovery record" }
                val session = input.readUTF()
                require(UUID.fromString(session).toString() == session)
                val seq = input.readLong()
                require(seq > 0)
                val time = input.readLong()
                require(time > 0)
                val original = if (input.readBoolean()) {
                    val count = input.readInt()
                    require(count in 0..32768)
                    CharArray(count) { input.readChar() }.concatToString()
                } else {
                    null
                }
                val size = input.readInt()
                require(size in 44..DocumentFiles.MAX_ENCODED_BYTES && input.available() == size)
                LocalCopy(session, seq, time, original, DocumentFiles.decode(input.readNBytes(size)))
            }
        }
    }
    class Lease(val session: String, val directory: Path, val channel: FileChannel, val lock: FileLock) : AutoCloseable {
        override fun close() {
            lock.release()
            channel.close()
        }
    }
    private fun io() {
        check(!SwingUtilities.isEventDispatchThread()) { "Recovery IO on UI thread" }
    }
    private fun directory(session: String): Path {
        require(UUID.fromString(session).toString() == session)
        return root.resolve(session)
    }
    fun acquire(session: String): Lease {
        io()
        val dir = directory(session)
        Files.createDirectories(dir)
        val channel = FileChannel.open(dir.resolve("active.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            return Lease(session, dir, channel, checkNotNull(channel.tryLock()) { "Recovery copy is active in another window" })
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }
    private fun readEntry(dir: Path): LocalRecoveryEntry {
        val failures = ArrayList<String>()
        val valid = ArrayList<LocalCopy>()
        for (name in listOf("copy-0.bin", "copy-1.bin")) {
            val path = dir.resolve(name)
            if (Files.exists(path)) {
                try {
                    val copy = Files.newInputStream(path).use { decode(it.readNBytes(MAX_RECORD + 1)) }
                    require(copy.session == dir.fileName.toString()) { "Recovery owner mismatch" }
                    valid += copy
                } catch (e: Exception) {
                    failures += "$name: ${e.message}"
                }
            }
        }
        if (Files.exists(dir.resolve("pending.tmp"))) failures += "An interrupted unpublished write was ignored"
        return LocalRecoveryEntry(dir.fileName.toString(), valid.maxByOrNull { it.sequence }, failures.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }
    fun scan(): List<LocalRecoveryEntry> {
        io()
        if (!Files.exists(root)) return emptyList()
        return Files.list(root).use { dirs ->
            dirs.filter { Files.isDirectory(it) && runCatching { UUID.fromString(it.fileName.toString()).toString() == it.fileName.toString() }.getOrDefault(false) }
                .toList().mapNotNull { dir ->
                    val lease = try {
                        acquire(dir.fileName.toString())
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        return@mapNotNull null
                    } catch (_: IllegalStateException) {
                        return@mapNotNull null
                    }
                    lease.use {
                        if (Files.exists(dir.resolve("discarded"))) return@mapNotNull null
                        val entry = readEntry(dir)
                        entry.takeIf { it.copy != null || it.warning != null }
                    }
                }.sortedByDescending { it.copy?.timestamp ?: Long.MIN_VALUE }
        }
    }
    fun restore(entry: LocalRecoveryEntry): LocalCopy {
        io()
        acquire(entry.session).use { lease ->
            check(!Files.exists(lease.directory.resolve("discarded"))) { "Recovery copy was explicitly discarded" }
            val current = readEntry(lease.directory)
            val copy = checkNotNull(current.copy) { "No valid recovery copy; records retained" }
            check(copy.sequence == entry.copy?.sequence) { "Recovery copy changed; refresh the list" }
            return copy
        }
    }
    fun latest(lease: Lease): LocalCopy? {
        io()
        check(lease.lock.isValid)
        return readEntry(lease.directory).copy
    }
    fun publish(lease: Lease, copy: LocalCopy): Boolean {
        io()
        require(lease.session == copy.session && lease.lock.isValid)
        check(!Files.exists(lease.directory.resolve("discarded"))) { "Recovery session was discarded" }
        if ((readEntry(lease.directory).copy?.sequence ?: 0) >= copy.sequence) return false
        val bytes = encode(copy)
        val temp = lease.directory.resolve("pending.tmp")
        interruption("before-write")
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { c ->
            val half = bytes.size / 2
            val first = ByteBuffer.wrap(bytes, 0, half)
            while (first.hasRemaining()) c.write(first)
            interruption("mid-write")
            val second = ByteBuffer.wrap(bytes, half, bytes.size - half)
            while (second.hasRemaining()) c.write(second)
            c.force(true)
        }
        interruption("before-publish")
        Files.move(temp, lease.directory.resolve("copy-${copy.sequence % 2}.bin"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        interruption("after-publish")
        return true
    }
    fun discard(lease: Lease) {
        io()
        check(lease.lock.isValid)
        // A forced marker makes partial cleanup unambiguous if the process dies here.
        FileChannel.open(lease.directory.resolve("discard.tmp"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { c ->
            c.write(ByteBuffer.wrap(byteArrayOf(1)))
            c.force(true)
        }
        Files.move(lease.directory.resolve("discard.tmp"), lease.directory.resolve("discarded"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        for (name in listOf("copy-0.bin", "copy-1.bin", "pending.tmp")) Files.deleteIfExists(lease.directory.resolve(name))
    }
    fun discard(entry: LocalRecoveryEntry) {
        io()
        acquire(entry.session).use { lease ->
            val current = readEntry(lease.directory)
            check(current.copy?.sequence == entry.copy?.sequence) { "Recovery copy changed; refresh before discarding" }
            discard(lease)
        }
    }
}

/** One in-flight write and one coalesced newest snapshot. The fixed tick never starves under continuous typing. */
