package dev.shayxo.composedoc.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

private const val RICH_MIME = "application/x-composedoc-fragment"
private const val CLIPBOARD_DIRECTORY = "composedoc-clipboard"
private const val CLIPBOARD_QUOTA = 64L * 1024 * 1024
private const val CLIPBOARD_FILES = 64

/** Immutable private blobs survive their publishing process. No document/recovery data lives here. */
class AndroidClipboardFiles(context: Context, private val authority: String) {
    private val context = context.applicationContext
    private val root by lazy { File(this.context.filesDir, CLIPBOARD_DIRECTORY) }

    fun publish(bytes: ByteArray): Uri = locked {
        androidWorkerOnly()
        require(bytes.size <= FragmentCodec.MAX_BYTES) { "Rich clipboard exceeds its byte limit" }
        FragmentCodec.decode(bytes)
        check(root.isDirectory || root.mkdirs())
        val files = root.listFiles().orEmpty().filter { it.isFile && it.name != "clipboard.lock" }
        check(files.size < CLIPBOARD_FILES && files.sumOf { it.length() } + bytes.size <= CLIPBOARD_QUOTA) {
            "Rich clipboard cache full. Clear it in Clipboard controls; documents/recovery are unaffected."
        }
        val name = "${UUID.randomUUID()}.asfr"
        atomicBytes(File(root, name), bytes)
        Uri.Builder().scheme("content").authority(authority).appendPath(name).build()
    }

    /** Explicit cleanup; pasted documents/history own their immutable resources. */
    fun clear(): Unit = locked {
        androidWorkerOnly()
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        val ours = clip != null && (0 until clip.itemCount).any { clip.getItemAt(it).uri?.authority == authority }
        if (ours) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("ComposeDoc clipboard cleared", ""))
            }
        }
        val names = root.listFiles().orEmpty().map { it.name.removeSuffix(".new").removeSuffix(".bak") }
            .filter(::validClipboardName).distinct()
        names.forEach { name ->
            val uri = Uri.Builder().scheme("content").authority(authority).appendPath(name).build()
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            android.util.AtomicFile(File(root, name)).delete()
            check(listOf(name, "$name.new", "$name.bak").none { File(root, it).exists() }) {
                "Could not remove a clipboard cache record; retry cleanup"
            }
        }
    }
    private fun <T> locked(action: () -> T): T {
        androidWorkerOnly()
        check(root.isDirectory || root.mkdirs()) { "Cannot create private clipboard cache" }
        return synchronized(LOCK) {
            RandomAccessFile(File(root, "clipboard.lock"), "rw").use { file ->
                file.channel.use { channel -> channel.lock().use { action() } }
            }
        }
    }
    private companion object {
        private val LOCK = Any()
    }
}

private fun validClipboardName(name: String): Boolean = name.endsWith(".asfr") &&
    runCatching {
        UUID.fromString(name.removeSuffix(".asfr")).toString() == name.removeSuffix(".asfr")
    }.getOrDefault(false)

/** Consumer registers a non-exported, grant-enabled authority. Read-only even for its own UID. */
class AndroidClipboardProvider : ContentProvider() {
    override fun onCreate() = true
    private fun target(uri: Uri): File {
        val name = uri.lastPathSegment ?: error("Missing clipboard identity")
        require(uri.pathSegments.size == 1 && validClipboardName(name)) { "Invalid clipboard resource" }
        return File(checkNotNull(context).filesDir, "$CLIPBOARD_DIRECTORY/$name")
    }
    override fun getType(uri: Uri): String {
        target(uri)
        return RICH_MIME
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Clipboard resources are read-only" }
        val file = target(uri)
        require(file.length() <= FragmentCodec.MAX_BYTES)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = target(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name, file.length()))
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only clipboard provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only clipboard provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only clipboard provider")
}
