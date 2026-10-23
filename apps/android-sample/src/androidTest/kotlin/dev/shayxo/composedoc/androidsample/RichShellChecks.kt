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

internal fun AdapterInstrumentation.toolbarOwnerChecks() {
    group("actual clipboard toolbar follows the mounted input owner after New without stale view publication") {
        replaceMounted("previous owner")
        val previous = main {
            activity.owners.current.dispatch(EditorIntent.SelectAll)
            hideToolbarKeyboard()
            activity.owners.current
        }
        val before = main { previous.snapshot() }
        waitUntil { toolbarLabel("New") != null }
        check(checkNotNull(toolbarLabel("New")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil {
            main { activity.inputView?.let { it.model === activity.owners.current && it.isAttachedToWindow } == true }
        }
        val current = main {
            activity.owners.current.also {
                check(it !== previous)
                it.dispatch(EditorIntent.Type("second اردو"))
                it.dispatch(EditorIntent.SelectAll)
                it.dispatch(EditorIntent.SetTextStyle(TextStylePatch(bold = true)))
                hideToolbarKeyboard()
            }
        }
        waitUntil { toolbarLabel("Clipboard") != null }
        check(checkNotNull(toolbarLabel("Clipboard")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil { toolbarLabel("Copy") != null }
        val publication = java.util.concurrent.CountDownLatch(1)
        val originalListener = main { checkNotNull(activity.inputView).onNativeEvent }
        val expected = main {
            val view = checkNotNull(activity.inputView)
            val listener = view.onNativeEvent
            view.onNativeEvent = { event ->
                listener(event)
                if (event == "Rich clipboard published") publication.countDown()
            }
            val manager = targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Owned sentinel", "owned sentinel"))
            current.snapshot()
        }
        check(checkNotNull(toolbarLabel("Copy")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        try {
            check(publication.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "Actual Copy did not publish the current mounted owner; events=${main { activity.owners.nativeEvents }}"
            }
            assertCurrentToolbarClipboard()
        } finally {
            main { checkNotNull(activity.inputView).onNativeEvent = originalListener }
        }
        main {
            val after = current.snapshot()
            val old = previous.snapshot()
            check(after.committed == expected.committed && after.undoCount == expected.undoCount)
            check(old.committed == before.committed && old.undoCount == before.undoCount)
        }
    }
}

private fun AdapterInstrumentation.assertCurrentToolbarClipboard() {
    val clip = main {
        val manager = targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        checkNotNull(manager.primaryClip)
    }
    val expectedPlain = clip.getItemAt(0).text.toString() == "second اردو"
    val hasRich = clip.description.hasMimeType("application/x-composedoc-fragment")
    check(clip.itemCount == 2 && expectedPlain) {
        "Expected current-owner rich clipboard: items=${clip.itemCount}, " +
            "plainMatches=$expectedPlain, richMime=$hasRich"
    }
    val rich = targetContext.contentResolver.openInputStream(checkNotNull(clip.getItemAt(1).uri))!!.use {
        dev.shayxo.composedoc.data.FragmentCodec.decode(it.readBytes())
    }
    check(rich.document.text == "second اردو" && rich.document.units.all { it.style.bold })
}

private fun AdapterInstrumentation.hideToolbarKeyboard() {
    val manager = targetContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
        as android.view.inputmethod.InputMethodManager
    manager.hideSoftInputFromWindow(checkNotNull(activity.inputView).windowToken, 0)
}

private fun AdapterInstrumentation.toolbarLabel(label: String): AccessibilityNodeInfo? {
    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = when {
        node == null -> null
        node.text?.toString() == label -> node
        else -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
    }
    return find(uiAutomation.rootInActiveWindow)
}
