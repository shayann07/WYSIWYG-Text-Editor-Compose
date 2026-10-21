package dev.shayxo.composedoc.androidsample

import androidx.compose.runtime.Composable
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.ui.EditorIntent

@Composable
internal fun structureControls(activity: MainActivity, owners: AndroidOwners) {
    val model = owners.current
    val role = editorQuery(model) { owner -> owner.paragraphState { it.heading } }
    writingAction("Role: ${role?.let { if (it == 0) "Normal" else "H$it" } ?: "mixed"}") {
        val labels = arrayOf("Normal", "Heading 1", "Heading 2", "Heading 3", "Heading 4", "Heading 5", "Heading 6")
        chooseValue(activity, owners, "Semantic paragraph role", labels) { level ->
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(level)))
        }
    }
    val list = editorQuery(model) { owner -> owner.paragraphState { it.list } }
    if (list == null) writingAction("List: mixed", enabled = false) {}
    ListKind.entries.forEach { kind ->
        writingAction(kind.name, selected = list == kind) {
            model.dispatch(EditorIntent.Structure(StructureCommand.ListType(kind)))
        }
    }
    writingAction("Indent", list != ListKind.NONE && list != null) {
        model.dispatch(EditorIntent.Structure(StructureCommand.Indent))
    }
    writingAction("Outdent", list != ListKind.NONE && list != null) {
        model.dispatch(EditorIntent.Structure(StructureCommand.Outdent))
    }
    writingAction("Restart", list == ListKind.NUMBER) {
        activity.editValue(owners, "Numbered-list start1–9999", "1", true) {
            val n = it.toIntOrNull()
            require(n != null && n in 1..MAX_LIST_START) { "Enter1–9999" }
            model.dispatch(EditorIntent.Structure(StructureCommand.Restart(n)))
        }
    }
    writingAction("New item/paragraph") { model.dispatch(EditorIntent.Structure(StructureCommand.Enter(false))) }
    writingAction("Same item paragraph", list != ListKind.NONE && list != null) {
        model.dispatch(EditorIntent.Structure(StructureCommand.Enter(true)))
    }
    writingAction("Soft break") { model.dispatch(EditorIntent.Type("\n", "Soft break")) }
    writingAction("Marker font/size", list != ListKind.NONE && list != null) {
        model.dispatch(
            EditorIntent.Structure(StructureCommand.MarkerStyle(MarkerTypography.from(model.snapshot().typingStyle))),
        )
    }
}

private const val MAX_LIST_START = 9999
