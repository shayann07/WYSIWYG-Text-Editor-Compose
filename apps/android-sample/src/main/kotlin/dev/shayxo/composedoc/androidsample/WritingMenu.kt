package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import dev.shayxo.composedoc.ui.AndroidEditorView

/** Compact chrome keeps frequent writing controls reachable while the keyboard is visible. */
internal fun showWritingMenu(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) =
    settled(owners) {
        val labels = arrayOf(
            "New document", "Open document", "Save", "Save As",
            "Recovery ${owners.recoverableCount}", "Find and Replace", "Outline", "Close tab", "Show keyboard",
        )
        AlertDialog.Builder(activity).setTitle("Document actions · existing drafts are retained")
            .setItems(labels) { _, index ->
                when (index) {
                    NEW -> owners.newDocument()
                    OPEN -> owners.launchPicker(activity, AndroidPickerKind.OPEN)
                    SAVE -> if (!owners.saveBound()) owners.launchPicker(activity, AndroidPickerKind.SAVE_AS)
                    SAVE_AS -> owners.launchPicker(activity, AndroidPickerKind.SAVE_AS)
                    RECOVERY -> showRecovery(activity, owners)
                    FIND -> showFind(activity, owners, input)
                    OUTLINE -> showOutline(activity, owners, input)
                    CLOSE -> requestCloseTab(activity, owners, input)
                    else -> input?.showKeyboard()
                }
            }.setNegativeButton("Cancel", null).show()
    }

internal fun requestCloseTab(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) {
    val captured = owners.tab
    AlertDialog.Builder(activity).setTitle("Close ${captured.name}?")
        .setMessage("A completed private recovery checkpoint retains this writing. Cancel keeps editing.")
        .setPositiveButton("Keep recovery & close") { _, _ ->
            if (input?.model === captured.model) input.retireNativeSession()
            owners.closeDocument(captured)
        }.setNegativeButton("Cancel", null).show()
}

private const val NEW = 0
private const val OPEN = 1
private const val SAVE = 2
private const val SAVE_AS = 3
private const val RECOVERY = 4
private const val FIND = 5
private const val OUTLINE = 6
private const val CLOSE = 7
