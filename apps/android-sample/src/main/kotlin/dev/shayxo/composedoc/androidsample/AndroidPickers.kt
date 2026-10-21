package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.ui.EditorIntent

internal enum class AndroidPickerKind { OPEN, SAVE_AS, IMAGE, REPLACE_IMAGE, EXPORT_RECOVERY, EXPORT_SAFETY }
internal data class AndroidPickerRequest(
    val owner: String,
    val generation: Long,
    val kind: AndroidPickerKind,
    val captured: Document,
    val range: ClipboardCapture?,
    val recoveryOwner: String? = null,
    val recoverySequence: Long = 0,
    val safety: SaveSafetyCopy? = null,
)

/** Capture ownership before launching a provider UI; request-code consumption makes duplicate results inert. */
internal fun AndroidOwners.launchPicker(
    activity: Activity,
    kind: AndroidPickerKind,
    recoveryOwner: String? = null,
    recoverySequence: Long = 0,
    safety: SaveSafetyCopy? = null,
) {
    val entry = tab
    if (entry.model.compositionCaptureToken() != null || entry.model.snapshot().waiting) {
        status = "Finish/cancel the keyboard candidate before opening a picker; its evidence is retained"
        return
    }
    if (entry.saveInFlight && kind == AndroidPickerKind.SAVE_AS) {
        status = "A save is already in progress"
        return
    }
    val request = AndroidPickerRequest(
        entry.id,
        ++entry.operation,
        kind,
        entry.model.snapshot().committed,
        entry.model.clipboardCapture(),
        recoveryOwner,
        recoverySequence,
        safety,
    )
    val code = nextRequest++
    check(code <= MAX_REQUEST) { "Picker request limit reached; close and reopen the sample" }
    val create =
        kind in setOf(AndroidPickerKind.SAVE_AS, AndroidPickerKind.EXPORT_RECOVERY, AndroidPickerKind.EXPORT_SAFETY)
    val intent = Intent(if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (kind == AndroidPickerKind.IMAGE || kind == AndroidPickerKind.REPLACE_IMAGE) "image/*" else "*/*"
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        if (create) {
            putExtra(
                Intent.EXTRA_TITLE,
                when (kind) {
                    AndroidPickerKind.EXPORT_RECOVERY -> "ComposeDoc-recovery.anrc"
                    AndroidPickerKind.EXPORT_SAFETY -> checkNotNull(safety).name
                    else -> "${entry.name.removeSuffix(".astra")}.astra"
                },
            )
        }
    }
    pickers[code] = request
    runCatching { activity.startActivityForResult(intent, code) }.onFailure {
        pickers.remove(code)
        status = "Document picker unavailable: ${it.message}; existing draft retained"
    }
}

internal fun AndroidOwners.pickerResult(activity: Activity, code: Int, result: Int, data: Intent?) {
    val request = pickers.remove(code) ?: return
    val entry = tabs.firstOrNull { it.id == request.owner && it.operation == request.generation } ?: return
    if (result != Activity.RESULT_OK ||
        data?.data == null
    ) {
        note(entry.model, "Picker cancelled; draft and source unchanged")
    } else {
        acceptedPicker(activity, request, entry, data)
    }
}

private fun AndroidOwners.acceptedPicker(
    activity: Activity,
    request: AndroidPickerRequest,
    entry: AndroidTab,
    data: Intent,
) {
    val uri = checkNotNull(data.data)
    val repository = checkNotNull(uriRepository)
    val context = activity.applicationContext
    when (request.kind) {
        AndroidPickerKind.OPEN -> work({
            repository.read(uri.toString()) to providerName(context, uri)
        }) { (read, name) ->
            if (entry.operation != request.generation || tabs.none { it === entry }) return@work
            val foreground = current === entry.model && entry.model.snapshot().committed.same(request.captured)
            add(read.document, name, uri.toString(), read.sha256, activate = foreground)
            persistGrant(context, uri, data.flags, entry)
            note(
                entry.model,
                if (foreground) {
                    "Opened independent document; prior draft kept"
                } else {
                    "Opened background tab; writing kept"
                },
            )
        }
        AndroidPickerKind.SAVE_AS -> {
            val wasActive = current === entry.model
            saveTo(entry, uri.toString(), null, request.captured) {
                persistGrant(context, uri, data.flags, entry)
                work({ providerName(context, uri) }, entry.model) { name ->
                    if (entry.uri == uri.toString() && entry.operation == request.generation) {
                        entry.name = name
                        checkpoints.changed(entry)
                    }
                }
                if (wasActive && current === entry.model && !entry.dirty) {
                    status = "Saved provider document; prior drafts/recovery retained"
                }
            }
        }
        AndroidPickerKind.IMAGE, AndroidPickerKind.REPLACE_IMAGE -> imagePicked(request, entry, uri)
        AndroidPickerKind.EXPORT_RECOVERY -> work({
            val bytes = recoveryStore.export(checkNotNull(request.recoveryOwner), request.recoverySequence)
            repository.writeBytes(uri.toString(), bytes)
        }, entry.model) { note(entry.model, "Recovery envelope exported; original recovery record retained") }
        AndroidPickerKind.EXPORT_SAFETY -> work({
            val bytes = SaveSafetyCopies(safetyRoot).read(checkNotNull(request.safety))
            repository.writeBytes(uri.toString(), bytes)
        }, entry.model) { note(entry.model, "Exact safety bytes exported and verified; source retained") }
    }
}

private fun AndroidOwners.imagePicked(request: AndroidPickerRequest, entry: AndroidTab, uri: Uri) {
    val repository = checkNotNull(uriRepository)
    work({
        ImageDecoder.validate(repository.readBytes(uri.toString(), ImageDecoder.MAX_BYTES))
    }, entry.model) { (id, resource) ->
        if (entry.operation != request.generation || tabs.none { it === entry }) return@work
        val capture = request.range ?: return@work
        val command = if (request.kind ==
            AndroidPickerKind.REPLACE_IMAGE
        ) {
            StructureCommand.ReplaceImage(id, resource)
        } else {
            StructureCommand.InsertImage(ImageBlock(id), resource)
        }
        val applied = entry.model.dispatch(EditorIntent.ApplyStructure(capture, command))
        note(
            entry.model,
            if (applied) {
                "Image owned; edit alt text in Image controls"
            } else {
                "Image target changed; result rejected; existing content retained"
            },
        )
    }
}

private fun providerName(context: android.content.Context, uri: Uri): String = context.contentResolver.query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME),
    null,
    null,
    null,
)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0)?.take(MAX_NAME) else null
} ?: "Opened document"

private fun AndroidOwners.persistGrant(context: android.content.Context, uri: Uri, flags: Int, entry: AndroidTab) {
    val permitted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, permitted) }
            .onFailure { note(entry.model, "Provider did not retain permission; reselect the URI if access expires") }
    }
}

private const val MAX_REQUEST = 60000
private const val MAX_NAME = 1024
