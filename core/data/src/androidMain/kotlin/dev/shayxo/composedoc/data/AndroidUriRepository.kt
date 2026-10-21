package dev.shayxo.composedoc.data

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.AtomicFile
import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.model.Document
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

data class AndroidDocumentRead(val document: Document, val sha256: String)

/** Worker-only provider adapter. Hash checking detects conflicts; it is NOT provider CAS. */
class AndroidUriRepository(context: Context, privateRoot: File) : DocumentRepository {
    private val resolver = context.applicationContext.contentResolver
    private val root by lazy { privateRoot.canonicalFile }

    override fun load(location: String): Document = read(location).document

    /** The generic port can create an empty target only; overwrites require an explicit captured digest. */
    override fun save(location: String, document: Document) {
        write(location, document, null)
    }

    fun read(location: String): AndroidDocumentRead {
        val bytes = readBytes(location, MAX_DOCUMENT_BYTES)
        return AndroidDocumentRead(AndroidDocumentCodec.decode(bytes), androidDigest(bytes))
    }

    fun write(location: String, document: Document, expectedSha256: String?): String {
        androidWorkerOnly()
        val bytes = AndroidDocumentCodec.encode(document)
        return replace(location, bytes, expectedSha256, documentBytes = true)
    }

    fun readBytes(location: String, limit: Int): ByteArray {
        androidWorkerOnly()
        require(limit in 1..MAX_URI_BYTES) { "Unsupported resource byte limit" }
        return checkNotNull(resolver.openInputStream(contentUri(location))) { "Provider returned no readable stream" }
            .use { it.readBytesBounded(limit) }
    }

    /** Generic exports cannot bypass document conflict protection: only an empty created URI is admitted. */
    fun writeBytes(location: String, bytes: ByteArray) {
        replace(location, bytes, null, documentBytes = false)
    }

    private fun replace(location: String, bytes: ByteArray, expected: String?, documentBytes: Boolean): String =
        synchronized(LOCK) {
            androidWorkerOnly()
            check(root.isDirectory || root.mkdirs()) { "Cannot create private save-safety directory" }
            RandomAccessFile(File(root, "save.lock"), "rw").use { file ->
                file.channel.use { channel ->
                    channel.lock().use {
                        replaceLocked(location, bytes, expected, documentBytes)
                    }
                }
            }
        }

    private fun replaceLocked(location: String, bytes: ByteArray, expected: String?, documentBytes: Boolean): String {
        require(bytes.size <= MAX_URI_BYTES) { "Export exceeds the resource byte limit" }
        val uri = contentUri(location)
        val original = readBytes(location, MAX_URI_BYTES)
        checkExpected(original, expected)
        check(root.isDirectory || root.mkdirs()) { "Cannot create private save-safety directory" }
        val used = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        check(used + bytes.size + original.size + SAFETY_RESERVE <= SAFETY_QUOTA) {
            "Save safety-copy quota is full. Export or discard old save attempts in Recovery, then retry."
        }
        val id = UUID.randomUUID().toString()
        // Every route has an immutable intended-content copy BEFORE opening a destructive provider stream.
        atomicBytes(File(root, "save-$id.${if (documentBytes) "astra" else "recovery"}"), bytes)
        atomicBytes(File(root, "previous-$id.bin"), original)
        atomicBytes(File(root, "save-$id.name"), location.toByteArray(Charsets.UTF_8))
        checkExpected(readBytes(location, MAX_URI_BYTES), expected)
        // A provider may still race this check, and may implement non-atomic truncate/write. Copies remain.
        checkNotNull(resolver.openOutputStream(uri, "wt")) {
            "Provider returned no writable stream; safety copies retained"
        }
            .use { output ->
                output.write(bytes)
                output.flush()
            }
        val digest = androidDigest(bytes)
        check(androidDigest(readBytes(location, MAX_URI_BYTES)) == digest) {
            "Provider verification failed; intended content and previous bytes remain in save safety copies"
        }
        return digest
    }

    private fun checkExpected(bytes: ByteArray, expected: String?) {
        if (expected == null) {
            check(bytes.isEmpty()) {
                "Target already contains data. Open it first or choose a new empty Save As target."
            }
        } else {
            check(androidDigest(bytes) == expected) {
                "Document changed outside this editor. Use Save As; local work is retained."
            }
        }
    }

    private fun contentUri(location: String): Uri {
        require(location.length <= MAX_URI_CHARACTERS) { "Provider URI is too long" }
        return Uri.parse(location).also {
            require(it.scheme == "content") { "Use a document-provider content URI, not a file path" }
        }
    }

    private companion object {
        private val LOCK = Any()
        private const val MAX_DOCUMENT_BYTES = 20_000_000
        private const val MAX_URI_BYTES = 64 * 1024 * 1024
        private const val MAX_URI_CHARACTERS = 32768
        private const val SAFETY_RESERVE = 65536
        private const val SAFETY_QUOTA = 128L * 1024 * 1024
    }
}

internal fun androidWorkerOnly() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Document/resource IO must run off the UI thread" }
}

internal fun androidDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun atomicBytes(path: File, bytes: ByteArray) {
    val file = AtomicFile(path)
    val output = file.startWrite()
    var finished = false
    try {
        output.write(bytes)
        file.finishWrite(output)
        finished = true
    } finally {
        if (!finished) file.failWrite(output)
    }
    // AtomicFile can log publication failures without throwing.
    // Read-back proves publication, not power-loss durability.
    check(path.isFile && path.length() == bytes.size.toLong() && androidFileDigest(path) == androidDigest(bytes)) {
        "Atomic publication verification failed; existing recovery/safety copies were retained"
    }
}

private fun androidFileDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val DIGEST_BUFFER_BYTES = 8192
