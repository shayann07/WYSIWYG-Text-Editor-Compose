package dev.shayxo.composedoc.androidsample

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Debug-only disposable provider. Native picker evidence uses Android's real document provider. */
class ProviderFixture : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val id = checkNotNull(uri.lastPathSegment)
        require(uri.pathSegments.size == 1 && UUID.fromString(id).toString() == id)
        return File(checkNotNull(context).filesDir, "provider-fixtures/$id")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = file(uri)
        check(file.isFile) { "Missing disposable provider resource" }
        if (File(file.path + ".denied").exists()) throw SecurityException("Disposable grant revoked")
        if (mode != "r" && File(file.path + ".readonly").exists()) {
            throw SecurityException("Disposable provider rejected writing")
        }
        if (mode != "r" && File(file.path + ".corrupt").exists()) return partialWrite(file)
        val delay = File(file.path + ".hold")
        val holdRead = File(file.path + ".hold-read")
        val gate = if (mode == "r") holdRead else delay
        if (gate.exists()) {
            File(file.path + ".entered").writeText("write open")
            val until = SystemClock.uptimeMillis() + HOLD_LIMIT_MS
            while (gate.exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(POLL_MS)
            check(!gate.exists()) { "Disposable provider hold timed out" }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
    private fun partialWrite(file: File): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            val prefix = ByteArray(PARTIAL_BYTES)
            var kept = 0
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                val buffer = ByteArray(READ_BUFFER)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val take = minOf(count, prefix.size - kept)
                    if (take > 0) {
                        buffer.copyInto(prefix, kept, 0, take)
                        kept += take
                    }
                }
            }
            file.writeBytes(prefix.copyOf(kept))
            File(file.path + ".partial").writeText("Incomplete provider write")
        }, "DisposablePartialProvider").apply {
            isDaemon = true
            start()
        }
        return pipe[1]
    }
    override fun getType(uri: Uri): String {
        file(uri)
        return "application/octet-stream"
    }
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name + ".astra", file.length()))
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    companion object {
        private const val HOLD_LIMIT_MS = 10000L
        private const val POLL_MS = 20L
        private const val PARTIAL_BYTES = 12
        private const val READ_BUFFER = 8192
    }
}
