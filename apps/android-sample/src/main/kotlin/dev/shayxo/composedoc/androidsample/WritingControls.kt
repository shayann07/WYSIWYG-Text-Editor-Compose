package dev.shayxo.composedoc.androidsample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent

@Composable
internal fun writingAction(label: String, enabled: Boolean = true, selected: Boolean = false, run: () -> Unit) {
    BasicText(
        label,
        Modifier.padding(2.dp).focusProperties { canFocus = false }
            .background(
                Color(
                    if (selected) {
                        SELECTED
                    } else if (enabled) {
                        ACTION
                    } else {
                        DISABLED
                    },
                ),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = run)
            .heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        style = TextStyle(fontSize = 14.sp),
        maxLines = 1,
    )
}

@Composable
internal fun writingControls(
    activity: MainActivity,
    owners: AndroidOwners,
    input: AndroidEditorView?,
    compact: Boolean,
) {
    val frame = editorQuery(owners.current) { it.snapshot() }
    var panel by remember { mutableStateOf("Text") }
    val groups = listOf("Text", "Paragraph", "Structure", "Clipboard", "Image")
    if (compact) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            writingAction("File") { showWritingMenu(activity, owners, input) }
            writingAction("Docs ${owners.models.size}") { showDocumentTabs(activity, owners, input) }
            writingAction("Tools: $panel") { panel = groups[(groups.indexOf(panel) + 1) % groups.size] }
            toolGroup(panel, activity, owners, input)
        }
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            writingAction("${owners.tab.name}${if (owners.tab.dirty) " *" else ""}") {
                showDocumentTabs(activity, owners, input)
            }
            writingAction("New") { owners.newDocument() }
            writingAction("Open") { owners.launchPicker(activity, AndroidPickerKind.OPEN) }
            writingAction("Save", enabled = !owners.tab.saveInFlight) {
                if (!owners.saveBound()) owners.launchPicker(activity, AndroidPickerKind.SAVE_AS)
            }
            writingAction("Save As", enabled = !owners.tab.saveInFlight) {
                owners.launchPicker(activity, AndroidPickerKind.SAVE_AS)
            }
            writingAction("Close tab") { requestCloseTab(activity, owners, input) }
            writingAction("Recovery ${owners.recoverableCount}") { showRecovery(activity, owners) }
            writingAction("Find") { showFind(activity, owners, input) }
            writingAction("Outline") { showOutline(activity, owners, input) }
            writingAction("Keyboard") { input?.showKeyboard() }
        }
        if (!compact) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                groups.forEach { group -> writingAction(group, selected = panel == group) { panel = group } }
                writingAction("Undo", frame.undoCount > 0) {
                    owners.current.dispatch(EditorIntent.Command(EditAction.Undo))
                }
                writingAction("Redo", frame.redoCount > 0) {
                    owners.current.dispatch(EditorIntent.Command(EditAction.Redo))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                toolGroup(panel, activity, owners, input)
            }
        }
    }
}

@Composable
private fun toolGroup(group: String, activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) {
    when (group) {
        "Text" -> textControls(activity, owners, input)
        "Paragraph" -> paragraphControls(activity, owners)
        "Structure" -> structureControls(activity, owners)
        "Clipboard" -> clipboardControls(activity, owners, input)
        "Image" -> imageControls(activity, owners)
    }
}

private const val ACTION = 0xffdbe4f0
private const val SELECTED = 0xff9cc8fa
private const val DISABLED = 0xffe3e3e3
