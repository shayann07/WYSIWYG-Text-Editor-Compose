@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditingBuffer
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.TextEditingScope

private fun units(s: String) = s.map { it.code.toString(16).padStart(4, '0') }

fun main() {
    val pair = "\uD83D\uDE00"
    for (action in listOf("before-low", "after-high", "collapsed-selection", "forward-selection", "reversed-selection")) {
        val s = TextFieldState(pair)
        s.edit {
            when (action) {
                "before-low" -> placeCursorBeforeCharAt(1)
                "after-high" -> placeCursorAfterCharAt(0)
                "collapsed-selection" -> selection = TextRange(1)
                "forward-selection" -> selection = TextRange(0, 1)
                "reversed-selection" -> selection = TextRange(1, 0)
            }
        }
        println("$action: ${s.selection}")
        check(if (action.endsWith("selection") && action != "collapsed-selection") s.selection.min == 0 && s.selection.max == 1 else s.selection == TextRange(1))
    }
    val raw = TextFieldState(pair)
    raw.edit { delete(0, 1) }
    println("TextFieldState delete high surrogate: ${units(raw.text.toString())}")
    check(raw.text.toString() == "\uDE00")

    val byUnits = EditingBuffer(AnnotatedString(pair), TextRange(2))
    DeleteSurroundingTextCommand(1, 0).applyTo(byUnits)
    check(byUnits.toString() == "\uD83D")
    println("Legacy command delete 1 UTF-16 unit: ${units(byUnits.toString())}")
    val byScalars = EditingBuffer(AnnotatedString(pair), TextRange(2))
    DeleteSurroundingTextInCodePointsCommand(1, 0).applyTo(byScalars)
    check(byScalars.toString().isEmpty())
    println("Legacy command delete 1 code point: ${units(byScalars.toString())}")
    val malformed = EditingBuffer(AnnotatedString("a\uD83D"), TextRange(2))
    DeleteSurroundingTextInCodePointsCommand(1, 0).applyTo(malformed)
    println("Legacy code-point command on malformed range: ${units(malformed.toString())}")
    check(malformed.toString() == "a") // Observed deviation from the documented no-op contract.
    val commands = mutableListOf<EditCommand>()
    val legacyFactory = Class.forName("androidx.compose.foundation.text.input.internal.LegacyPlatformTextInputServiceAdapter_skikoKt")
        .declaredMethods.single { it.name == "TextEditingScope" && it.parameterCount == 1 }.apply { isAccessible = true }
    val legacyScope = legacyFactory.invoke(null, commands) as TextEditingScope
    legacyScope.deleteSurroundingTextInCodePoints(1, 0)
    val legacyBuffer = EditingBuffer(AnnotatedString(pair), TextRange(2))
    commands.forEach { it.applyTo(legacyBuffer) }
    println("Published legacy adapter queued: $commands; result=${units(legacyBuffer.toString())}")
    check(commands.single() is DeleteSurroundingTextCommand)
    check(legacyBuffer.toString() == "\uD83D")

    val stateFactory = Class.forName("androidx.compose.foundation.text.input.internal.TextInputSession_skikoKt")
        .declaredMethods.single { it.name == "TextEditingScope" && it.parameterCount == 1 }.apply { isAccessible = true }
    for (input in listOf(pair, "a\uD83D")) {
        val state = TextFieldState(input, TextRange(input.length))
        state.edit { (stateFactory.invoke(null, this) as TextEditingScope).deleteSurroundingTextInCodePoints(1, 0) }
        println("Published state adapter ${units(input)} -> ${units(state.text.toString())}")
        check(state.text.toString() == if (input == pair) "" else "a")
    }
    println("Unicode protocol probe completed: headless state/command APIs and private Skiko adapter factories; no OS keyboard, input connection, or UI session was exercised.")
}
