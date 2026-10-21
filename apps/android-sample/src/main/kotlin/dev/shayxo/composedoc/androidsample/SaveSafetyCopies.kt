package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import android.os.Looper
import dev.shayxo.composedoc.data.AndroidDocumentCodec
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

internal data class SaveSafetyCopy(val name: String, val byteCount: Long, val digest: String)

/** App-owned immutable save-attempt files only. Provider documents and recovery slots are excluded. */
internal class SaveSafetyCopies(private val root: File) {
    fun entries(): List<SaveSafetyCopy> = locked {
        root.listFiles().orEmpty().filter { it.isFile && validName(it.name) }
            .sortedByDescending { it.lastModified() }.map { SaveSafetyCopy(it.name, it.length(), digest(it)) }
    }
    fun read(copy: SaveSafetyCopy): ByteArray = locked {
        val file = target(copy)
        check(file.length() <= MAX_BYTES) { "Safety copy is too large for this export; original retained" }
        file.readBytes()
    }
    fun discard(copy: SaveSafetyCopy): Unit = locked {
        check(target(copy).delete()) { "Could not discard the captured safety copy; retry" }
    }
    private fun target(copy: SaveSafetyCopy): File {
        require(validName(copy.name))
        val file = File(root, copy.name).canonicalFile
        check(file.parentFile == root.canonicalFile)
        check(file.isFile && file.length() == copy.byteCount && digest(file) == copy.digest) {
            "Safety copy changed since selection; nothing was removed or exported"
        }
        return file
    }
    private fun validName(name: String): Boolean {
        val base = name.removeSuffix(".new").removeSuffix(".bak")
        val prefix = if (base.startsWith("save-")) {
            "save-"
        } else if (base.startsWith("previous-")) {
            "previous-"
        } else {
            return false
        }
        val id = base.removePrefix(prefix).substringBefore('.')
        val knownExtension = base.substringAfterLast('.') in setOf("astra", "recovery", "bin", "name")
        return knownExtension && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun <T> locked(action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(root.isDirectory || root.mkdirs())
        return synchronized(LOCK) {
            RandomAccessFile(File(root, "save.lock"), "rw").use { file ->
                file.channel.use { channel -> channel.lock().use { action() } }
            }
        }
    }
    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 64 * 1024 * 1024
        private const val BUFFER_SIZE = 8192
    }
}

internal fun showSaveSafety(activity: MainActivity, owners: AndroidOwners) {
    val copies = SaveSafetyCopies(owners.safetyRoot)
    owners.work({ copies.entries() }) { entries ->
        if (activity.isDestroyed || activity.isFinishing) return@work
        AlertDialog.Builder(activity).setTitle("Save safety files · intended and previous bytes")
            .setItems(entries.map { "${it.name} · ${it.byteCount} bytes" }.toTypedArray()) { _, index ->
                safetyActions(activity, owners, copies, entries[index])
            }.setNegativeButton("Close", null).show()
    }
}

private fun safetyActions(
    activity: MainActivity,
    owners: AndroidOwners,
    copies: SaveSafetyCopies,
    copy: SaveSafetyCopy,
) {
    val source = owners.tab
    val basis = source.model.snapshot().committed
    AlertDialog.Builder(activity).setTitle(copy.name)
        .setItems(
            arrayOf("Open supported document as unsaved copy", "Export exact bytes", "Discard this safety file"),
        ) {
                _,
                index,
            ->
            when (index) {
                0 -> owners.work({ AndroidDocumentCodec.decode(copies.read(copy)) }, source.model) {
                    val foreground = owners.current === source.model && source.model.snapshot().committed.same(basis)
                    owners.add(it, "Save safety copy (unsaved)", activate = foreground)
                    owners.note(source.model, "Unsaved copy opened; safety file and provider retained")
                }
                1 -> owners.launchPicker(activity, AndroidPickerKind.EXPORT_SAFETY, safety = copy)
                else -> AlertDialog.Builder(activity).setTitle("Discard captured safety file?")
                    .setMessage("Only ${copy.name}. Provider documents and recovery drafts stay intact.")
                    .setPositiveButton("Discard") { _, _ ->
                        owners.work({ copies.discard(copy) }, source.model) {
                            owners.note(source.model, "Captured safety file discarded")
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }.setNegativeButton("Cancel", null).show()
}
