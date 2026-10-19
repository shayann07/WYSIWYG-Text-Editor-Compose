@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

import androidx.compose.foundation.text.input.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle

private val bold = SpanStyle(fontWeight = FontWeight.Bold)
private val italic = SpanStyle(fontStyle = FontStyle.Italic)
private fun styles(s: TextFieldState) = s.textStyles.getSpanStyles(TextRange(0, s.text.length)).map {
    "${it.start}:${it.end}:" + when (it.item) { bold -> "B"; italic -> "I"; else -> it.item.toString() }
}
private fun fresh(policy: ExpandPolicy): TextFieldState = TextFieldState("ab").also { s ->
    s.edit { addStyle(bold, TextRange(0, 1), policy); addStyle(italic, TextRange(1, 2), policy) }
    check(styles(s) == listOf("0:1:B", "1:2:I"))
    s.undoState.clearHistory()
}

// Test seam only: install the state an accepted native set-composition command produces.
// This deliberately does not claim to drive an OS keyboard or test a platform adapter.
private fun seedComposition(s: TextFieldState) {
    val field = TextFieldState::class.java.getDeclaredField("mainBuffer").apply { isAccessible = true }
    val buffer = field.get(s)
    val setter = buffer.javaClass.declaredMethods.single { it.name.startsWith("setComposition$") && it.parameterCount == 3 }
    setter.invoke(buffer, 0, 2, null)
    val commit = TextFieldState::class.java.declaredMethods.single { it.name == "commitEditAsUser" && it.parameterCount == 3 }.apply { isAccessible = true }
    val merge = commit.parameterTypes[2].enumConstants.single { it.toString() == "MergeIfPossible" }
    commit.invoke(s, null, false, merge)
    check(s.composition == TextRange(0, 2))
}

fun main() {
    println("Published artifact: org.jetbrains.compose.foundation:foundation-desktop:1.12.1")
    for ((name, policy) in listOf("InsideOnly" to ExpandPolicy.InsideOnly, "AtStart" to ExpandPolicy.AtStart, "AtEnd" to ExpandPolicy.AtEnd, "AtBoth" to ExpandPolicy.AtBoth)) {
        val s = fresh(policy)
        s.edit { replace(0, 2, "x") }
        println("$name ab->x: text=${s.text} styles=${styles(s)}")
        s.edit { replace(0, 1, "ab") }
        println("$name x->ab: text=${s.text} styles=${styles(s)}")
        s.undoState.undo()
        println("$name undo to x: text=${s.text} styles=${styles(s)}")
        s.undoState.undo()
        println("$name undo to ab: text=${s.text} styles=${styles(s)}")
        check(s.text.toString() == "ab")
        check(styles(s).isEmpty()) // Pins the observed limitation, not desired editor behavior.
    }
    val formatting = fresh(ExpandPolicy.InsideOnly)
    println("before style-only canUndo=${formatting.undoState.canUndo}")
    formatting.edit { addStyle(bold, TextRange(0, 2), ExpandPolicy.InsideOnly) }
    println("style-only canUndo=${formatting.undoState.canUndo} styles=${styles(formatting)}")
    formatting.undoState.undo()
    println("style-only after undo: text=${formatting.text} styles=${styles(formatting)}")
    check(styles(formatting).isEmpty())
    for (action in listOf("add", "remove", "change", "range")) {
        val s = fresh(ExpandPolicy.InsideOnly)
        seedComposition(s)
        s.edit {
            when (action) {
                "add" -> addStyle(bold, TextRange(0, 2), ExpandPolicy.InsideOnly)
                "remove" -> removeStyle(getSpanStyles(TextRange(1, 2)).single())
                "change" -> getSpanStyles(TextRange(1, 2)).single().spanStyle = bold
                "range" -> getSpanStyles(TextRange(1, 2)).single().textRange = TextRange(0, 2)
            }
        }
        println("during seeded composition $action: composition=${s.composition} canUndo=${s.undoState.canUndo} styles=${styles(s)}")
        check(s.composition == null)
        check(s.undoState.canUndo == (action == "add"))
    }
    val collapse = fresh(ExpandPolicy.AtBoth)
    collapse.edit {
        val tracked = getSpanStyles(TextRange(1, 2)).single()
        delete(1, 2)
        println("collapsed tracked valid=${tracked.isValid}")
        check(!tracked.isValid)
    }
    collapse.edit { insert(1, "b") }
    println("delete b then reinsert: text=${collapse.text} styles=${styles(collapse)}")
    transactionProbe()
    println("Probe completed")
}

private class IntentionalAbort : RuntimeException()

private fun transactionProbe() {
    for (action in listOf("empty", "add-remove", "change-back", "range-back", "policy-back", "revert-all", "abort-style", "abort-text-style")) {
        val s = fresh(ExpandPolicy.InsideOnly)
        seedComposition(s)
        val beforeStyles = styles(s)
        val beforeSelection = s.selection
        try {
            s.edit {
                when (action) {
                    "empty" -> Unit
                    "add-remove" -> removeStyle(addStyle(bold, TextRange(0, 2), ExpandPolicy.InsideOnly))
                    "change-back" -> {
                        val handle = getSpanStyles(TextRange(1, 2)).single()
                        handle.spanStyle = bold
                        handle.spanStyle = italic
                    }
                    "range-back" -> {
                        val handle = getSpanStyles(TextRange(1, 2)).single()
                        handle.textRange = TextRange(0, 2)
                        handle.textRange = TextRange(1, 2)
                    }
                    "policy-back" -> {
                        val handle = getSpanStyles(TextRange(1, 2)).single()
                        handle.expandPolicy = ExpandPolicy.AtBoth
                        handle.expandPolicy = ExpandPolicy.InsideOnly
                    }
                    "revert-all" -> {
                        replace(0, 2, "xyz")
                        addStyle(bold, TextRange(0, 3), ExpandPolicy.AtBoth)
                        revertAllChanges()
                    }
                    "abort-style" -> {
                        getSpanStyles(TextRange(1, 2)).single().spanStyle = bold
                        throw IntentionalAbort()
                    }
                    "abort-text-style" -> {
                        replace(0, 2, "xyz")
                        addStyle(bold, TextRange(0, 3), ExpandPolicy.AtBoth)
                        throw IntentionalAbort()
                    }
                }
            }
        } catch (_: IntentionalAbort) { }
        check(s.text.toString() == "ab")
        check(styles(s) == beforeStyles)
        check(s.selection == beforeSelection)
        println("net-zero $action: composition=${s.composition} canUndo=${s.undoState.canUndo}")
        check((s.composition == null) == (action in listOf("add-remove", "revert-all")))
        check(s.undoState.canUndo == (action == "add-remove"))
    }
}
