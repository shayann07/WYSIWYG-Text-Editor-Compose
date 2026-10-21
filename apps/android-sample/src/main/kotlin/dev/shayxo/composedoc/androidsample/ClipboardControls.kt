package dev.shayxo.composedoc.androidsample

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent

@Composable
internal fun clipboardControls(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) {
    writingAction("Copy") { input?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null) }
    writingAction("Cut") { input?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CUT, null) }
    writingAction("Paste rich") { input?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_PASTE, null) }
    writingAction("Paste plain") { input?.performAccessibilityAction(android.R.id.pasteAsPlainText, null) }
    writingAction("Select all") { owners.current.dispatch(EditorIntent.SelectAll) }
    writingAction("Clear rich cache") {
        settled(owners) {
            android.app.AlertDialog.Builder(activity).setTitle("Clear only ComposeDoc clipboard exports?")
                .setMessage(
                    "Issued rich clipboard URIs stop opening. Pasted documents, history and recovery remain intact.",
                )
                .setPositiveButton("Clear") { _, _ ->
                    owners.work({ owners.clipboardFiles?.clear() }) {
                        owners.status =
                            "Clipboard cache cleared; documents/recovery retained"
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}
