package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent
import java.io.File
import java.util.UUID

/** Mounted production retirement/result handlers; injected focus boundary, not real picker interaction. */
internal fun AdapterInstrumentation.imagePickerFocusChecks() {
    group("image insert and replacement survive picker session retirement without accepting stale document bases") {
        replaceMounted("kept Urdu اردو")
        val owners = activity.owners
        val original = main { owners.current.snapshot() }
        val file = File(targetContext.filesDir, "provider-fixtures/${UUID.randomUUID()}")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.writeBytes(testPng(4, 3, Color.BLUE))
        val uri = Uri.parse("content://${targetContext.packageName}.fixture/${file.name}")
        retiredPickerResult(AndroidPickerKind.IMAGE, uri)
        waitUntil { main { owners.current.snapshot().committed.resources.isNotEmpty() } }
        val inserted = main { owners.current.snapshot() }
        check(inserted.committed.text.replace("\uFFFC", "").replace("\n", "") == original.committed.text)
        check(
            inserted.committed.units.filter { it.kind == dev.shayxo.composedoc.model.UnitKind.TEXT } ==
                original.committed.units,
        )
        check(inserted.undoCount == original.undoCount + 1)
        val imageAt = inserted.committed.units.indexOfFirst { it.image != null }
        check(imageAt >= 0)
        main { owners.current.dispatch(EditorIntent.Select(Selection(imageAt, imageAt + 1))) }
        file.writeBytes(testPng(6, 2, Color.RED))
        retiredPickerResult(AndroidPickerKind.REPLACE_IMAGE, uri)
        waitUntil { main { owners.current.snapshot().undoCount == inserted.undoCount + 1 } }
        val replaced = main { owners.current.snapshot() }
        val image = replaced.committed.units.single { it.image != null }.image!!
        val resource = replaced.committed.resources.getValue(image.resource)
        check(resource.width == 6 && resource.height == 2)
        main {
            owners.current.dispatch(EditorIntent.Command(EditAction.Undo))
            check(owners.current.snapshot().committed.same(inserted.committed))
            owners.current.dispatch(EditorIntent.Command(EditAction.Redo))
            check(owners.current.snapshot().committed.same(replaced.committed))
        }
        main { owners.status = "awaiting stale image result" }
        retiredPickerResult(AndroidPickerKind.IMAGE, uri, changeSelection = true)
        waitUntil { main { owners.status.contains("target changed") } }
        main {
            check(owners.current.snapshot().committed == replaced.committed)
            check(owners.current.snapshot().undoCount == replaced.undoCount)
        }
    }
}

private fun AdapterInstrumentation.retiredPickerResult(
    kind: AndroidPickerKind,
    uri: Uri,
    changeSelection: Boolean = false,
) = main {
    val owners = activity.owners
    val entry = owners.tab
    connection(checkNotNull(activity.inputView))
    val capture = checkNotNull(entry.model.clipboardCapture())
    val request = AndroidPickerRequest(entry.id, ++entry.operation, kind, capture.basis.document, capture)
    owners.pickers[5020] = request
    // The real document picker takes window focus and retires the native session without editing accepted content.
    checkNotNull(activity.inputView).retireNativeSession()
    val after = checkNotNull(entry.model.clipboardCapture())
    check(after.basis == capture.basis && after.selection == capture.selection) {
        "Picker retirement changed basis ${capture.basis.revision}/${after.basis.revision}; " +
            "document=${after.basis.document == capture.basis.document}; " +
            "selection=${capture.selection}/${after.selection}"
    }
    check(after.sessionRevision != capture.sessionRevision)
    if (changeSelection) entry.model.dispatch(EditorIntent.Select(Selection(0, 0)))
    owners.pickerResult(activity, 5020, Activity.RESULT_OK, Intent().setData(uri))
}
