package dev.shayxo.composedoc.androidsample

import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent

internal fun AdapterInstrumentation.outlineUiChecks() {
    group("actual outline dialog opens, navigates stable headings and reopens after Undo/Redo without edits") {
        val text = "اردو Heading\nEnglish child\nTrailing"
        replaceMounted(text)
        val second = text.indexOf('\n') + 1
        val before = main {
            val model = activity.owners.current
            model.dispatch(EditorIntent.Select(Selection(0, 0)))
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
            model.dispatch(EditorIntent.Select(Selection(second, second)))
            model.dispatch(EditorIntent.Structure(StructureCommand.Role(2)))
            model.snapshot()
        }
        main { showOutline(activity, activity.owners, activity.inputView) }
        waitUntil { outlineNode("H2 · English child") != null }
        checkNotNull(outlineNode("H1 · اردو Heading")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        waitUntil { main { activity.owners.current.snapshot().selection.focus == 0 } }
        main {
            val after = activity.owners.current.snapshot()
            check(after.committed.same(before.committed) && after.undoCount == before.undoCount)
            showOutline(activity, activity.owners, activity.inputView)
        }
        waitUntil { outlineNode("H1 · اردو Heading · current section") != null }
        closeOutline()
        main { activity.owners.current.dispatch(EditorIntent.Command(EditAction.Undo)) }
        main { showOutline(activity, activity.owners, activity.inputView) }
        waitUntil { outlineNode("H1 · اردو Heading") != null }
        check(outlineNode("H2 · English child") == null)
        closeOutline()
        main { activity.owners.current.dispatch(EditorIntent.Command(EditAction.Redo)) }
        main { showOutline(activity, activity.owners, activity.inputView) }
        waitUntil { outlineNode("H2 · English child") != null }
        closeOutline()
        main { check(activity.owners.current.snapshot().committed.same(before.committed)) }
    }
}

private fun AdapterInstrumentation.closeOutline() {
    checkNotNull(outlineNode("Close")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    waitUntil { outlineNode("Document outline") == null }
}

private fun AdapterInstrumentation.outlineNode(prefix: String): AccessibilityNodeInfo? {
    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = when {
        node == null -> null
        node.text?.toString()?.startsWith(prefix, ignoreCase = true) == true -> node
        else -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
    }
    return find(uiAutomation.rootInActiveWindow)
}
