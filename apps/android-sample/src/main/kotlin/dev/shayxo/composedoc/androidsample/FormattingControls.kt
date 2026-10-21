package dev.shayxo.composedoc.androidsample

import androidx.compose.runtime.Composable
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStylePatch
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent

@Composable
internal fun textControls(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) {
    val model = owners.current
    val formats = editorQuery(model) { owner -> FormatKey.entries.associateWith(owner::formatState) }
    val frame = editorQuery(model) { it.snapshot() }
    FormatKey.entries.forEach { key ->
        val state = formats[key]
        writingAction(
            "${key.name.lowercase()}${if (state == null) {
                " mixed"
            } else if (state) {
                " ✓"
            } else {
                ""
            }}",
            selected =
            state == true,
        ) {
            model.dispatch(EditorIntent.ToggleFormat(key))
        }
    }
    fontSizeControls(activity, owners)
    val color = editorQuery(model) { owner -> owner.textStyleState { it.color } }
    writingAction("Color: ${color?.let { if (it < 0) "default" else "#%06X".format(it) } ?: "mixed"}") {
        activity.editValue(
            owners,
            "Text color #RRGGBB; default resets",
            color?.let {
                if (it <
                    0
                ) {
                    "default"
                } else {
                    "#%06X".format(it)
                }
            }.orEmpty(),
        ) {
            val rgb = if (it.equals("default", true)) {
                -1
            } else {
                require(it.matches(Regex("#[0-9a-fA-F]{6}"))) { "Use #RRGGBB or default" }
                it.drop(1).toInt(COLOR_RADIX)
            }
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(color = rgb)))
        }
    }
    writingAction("Undo", frame.undoCount > 0) { model.dispatch(EditorIntent.Command(EditAction.Undo)) }
    writingAction("Redo", frame.redoCount > 0) { model.dispatch(EditorIntent.Command(EditAction.Redo)) }
    writingAction("Select all") { model.dispatch(EditorIntent.SelectAll) }
    writingAction("Cancel input") {
        owners.retainUnfinished()
        input?.cancelProvisionalInput()
    }
}

@Composable
private fun fontSizeControls(activity: MainActivity, owners: AndroidOwners) {
    val model = owners.current
    val font = editorQuery(model) { owner -> owner.textStyleState { it.font } }
    writingAction("Font: ${font?.name ?: "mixed"}") {
        val values = StoredFont.entries.toTypedArray()
        chooseValue(
            activity,
            owners,
            "Stored font · default is Noto Nastaliq",
            values.map {
                it.name
            }.toTypedArray(),
        ) { index ->
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(font = values[index])))
        }
    }
    val size = editorQuery(model) { owner -> owner.textStyleState { it.size } }
    writingAction("Size: ${size?.let { if (it == 0) "default" else it.toString() } ?: "mixed"}") {
        activity.editValue(owners, "Font size 8–96; 0 resets default", size?.toString().orEmpty(), true) {
            val n = it.toIntOrNull() ?: error("Enter a whole number")
            require(n == 0 || n in MIN_TEXT_SIZE..MAX_TEXT_SIZE) { "Size must be8–96 or0" }
            model.dispatch(EditorIntent.SetTextStyle(TextStylePatch(size = n)))
        }
    }
}

@Composable
internal fun paragraphControls(activity: MainActivity, owners: AndroidOwners) {
    val model = owners.current
    val selectedDirection = editorQuery(model) { it.paragraphDirectionState() }
    val selectedAlignment = editorQuery(model) { it.paragraphAlignmentState() }
    if (selectedDirection == null) writingAction("Direction: mixed", enabled = false) {}
    if (selectedAlignment == null) writingAction("Alignment: mixed", enabled = false) {}
    ParagraphDirection.entries.forEach { direction ->
        writingAction(direction.name, selected = selectedDirection == direction) {
            model.dispatch(EditorIntent.SetParagraphProperties(direction = direction))
        }
    }
    ParagraphAlignment.entries.forEach { alignment ->
        writingAction(alignment.name, selected = selectedAlignment == alignment) {
            model.dispatch(EditorIntent.SetParagraphProperties(alignment = alignment))
        }
    }
    spacingControls(activity, owners)
}

@Composable
private fun spacingControls(activity: MainActivity, owners: AndroidOwners) {
    val model = owners.current
    val before = editorQuery(model) { owner -> owner.paragraphState { it.spaceBefore } }
    val after = editorQuery(model) { owner -> owner.paragraphState { it.spaceAfter } }
    val line = editorQuery(model) { owner -> owner.paragraphState { it.linePermille } }
    writingAction("Before: ${before ?: "mixed"}") {
        activity.editValue(owners, "Paragraph space before 0–200", before?.toString().orEmpty(), true) {
            val n = it.toIntOrNull()
            require(n != null && n in 0..MAX_PARAGRAPH_SPACE) { "Enter0–200 document units" }
            model.dispatch(EditorIntent.SetParagraphProperties(before = n))
        }
    }
    writingAction("After: ${after ?: "mixed"}") {
        activity.editValue(owners, "Paragraph space after 0–200", after?.toString().orEmpty(), true) {
            val n = it.toIntOrNull()
            require(n != null && n in 0..MAX_PARAGRAPH_SPACE) { "Enter0–200 document units" }
            model.dispatch(EditorIntent.SetParagraphProperties(after = n))
        }
    }
    writingAction("Line: ${line?.let { if (it == 0) "natural" else "${it / PERMILLE_PER_PERCENT}%" } ?: "mixed"}") {
        activity.editValue(
            owners,
            "Line spacing percent100–300; 0 is natural",
            line?.let {
                it / PERMILLE_PER_PERCENT
            }?.toString().orEmpty(),
            true,
        ) {
            val n = it.toIntOrNull()
            require(n != null && (n == 0 || n in MIN_LINE_PERCENT..MAX_LINE_PERCENT)) { "Enter100–300 percent or0" }
            model.dispatch(EditorIntent.SetParagraphProperties(line = n * PERMILLE_PER_PERCENT))
        }
    }
}

private const val MIN_TEXT_SIZE = 8
private const val MAX_TEXT_SIZE = 96
private const val COLOR_RADIX = 16
private const val MAX_PARAGRAPH_SPACE = 200
private const val PERMILLE_PER_PERCENT = 10
private const val MIN_LINE_PERCENT = 100
private const val MAX_LINE_PERCENT = 300
