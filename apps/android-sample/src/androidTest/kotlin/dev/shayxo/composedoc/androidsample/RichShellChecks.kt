package dev.shayxo.composedoc.androidsample

import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.EditorIntent

internal fun AdapterInstrumentation.richShellChecks() {
    group("actual toolbar mixed/active state follows selection and accessible clicks without reopening its group") {
        replaceMounted("AB")
        main {
            val model = activity.owners.current
            model.dispatch(EditorIntent.SelectAll)
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(bold = false)))
            model.dispatch(EditorIntent.Select(Selection(1, 0)))
            model.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
            model.dispatch(EditorIntent.SelectAll)
            check(model.formatState(FormatKey.BOLD) == null)
            val manager = targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.inputView!!.windowToken, 0)
        }
        waitUntil { toolbarLabel("bold mixed") != null }
        val before = main { activity.owners.current.snapshot() }
        check(checkNotNull(toolbarLabel("bold mixed")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil { toolbarLabel("bold ✓") != null }
        main {
            val model = activity.owners.current
            check(model.snapshot().committed.units.all { it.style.bold })
            check(model.snapshot().selection == before.selection)
            check(model.snapshot().undoCount == before.undoCount + 1)
            model.dispatch(EditorIntent.Command(EditAction.Undo))
            check(model.snapshot().committed.same(before.committed))
        }
        waitUntil { toolbarLabel("bold mixed") != null }
    }
}

private fun AdapterInstrumentation.toolbarLabel(label: String): AccessibilityNodeInfo? {
    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = when {
        node == null -> null
        node.text?.toString() == label -> node
        else -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
    }
    return find(uiAutomation.rootInActiveWindow)
}
