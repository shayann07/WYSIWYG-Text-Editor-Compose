package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import dev.shayxo.composedoc.ui.AndroidEditorView

internal fun chooseValue(
    activity: MainActivity,
    owners: AndroidOwners,
    title: String,
    labels: Array<String>,
    apply: (Int) -> Unit,
) = settled(owners) {
    val target = MobileTarget(owners)
    AlertDialog.Builder(activity).setTitle(title).setItems(labels) { _, index ->
        runCatching {
            target.requireCurrent()
            apply(index)
        }.onFailure {
            owners.note(target.model, "Control refused: ${it.message}")
        }
    }.setNegativeButton("Cancel", null).setOnDismissListener { activity.inputView?.requestFocus() }.show()
}

internal fun settled(owners: AndroidOwners, action: () -> Unit) {
    val frame = owners.current.snapshot()
    if (owners.current.compositionCaptureToken() != null || frame.waiting) {
        owners.status =
            "Finish/cancel the current keyboard candidate before opening this control; its draft is retained"
    } else {
        action()
    }
}

/** Validation and stale-target failure keep both entered text and the dialog. Cancel never dispatches. */
internal fun MainActivity.editValue(
    owners: AndroidOwners,
    title: String,
    value: String,
    numeric: Boolean = false,
    apply: (String) -> Unit,
) = settled(owners) {
    val target = MobileTarget(owners)
    val field = EditText(this).apply {
        setText(value)
        contentDescription = title
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }
    val dialog = AlertDialog.Builder(this).setTitle(title).setView(field)
        .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                target.requireCurrent()
                apply(field.text.toString())
            }.onSuccess { dialog.dismiss() }.onFailure { field.error = it.message ?: "Value rejected" }
        }
    }
    dialog.setOnDismissListener { inputView?.requestFocus() }
    dialog.show()
}

internal fun showDocumentTabs(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) =
    settled(owners) {
        val captured = owners.tabs.toList()
        AlertDialog.Builder(activity).setTitle("Independent documents · existing drafts are kept")
            .setItems(captured.map { "${it.name}${if (it.dirty) " · unsaved" else ""}" }.toTypedArray()) { _, index ->
                val target = captured[index]
                val at = owners.tabs.indexOfFirst { it === target }
                if (at >= 0) {
                    owners.retainUnfinished()
                    input?.retireNativeSession()
                    owners.active = at
                }
            }.setNeutralButton("Open second process") { _, _ ->
                activity.startActivity(
                    android.content.Intent(
                        activity,
                        PeerActivity::class.java,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .setNegativeButton("Cancel", null).show()
    }
