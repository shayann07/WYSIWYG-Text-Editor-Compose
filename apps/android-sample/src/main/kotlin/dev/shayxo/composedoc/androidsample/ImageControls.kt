package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.ImageResource
import dev.shayxo.composedoc.ui.EditorIntent
import kotlin.math.roundToInt

@Composable
internal fun imageControls(activity: MainActivity, owners: AndroidOwners) {
    val frame = editorQuery(owners.current) { it.snapshot() }
    val selected = frame.committed.units.subList(frame.selection.start, frame.selection.end).singleOrNull()?.image
    writingAction("Insert PNG/JPEG") { owners.launchPicker(activity, AndroidPickerKind.IMAGE) }
    writingAction("Image properties", selected != null) { imageProperties(activity, owners) }
    writingAction("Replace image", selected != null) { owners.launchPicker(activity, AndroidPickerKind.REPLACE_IMAGE) }
    writingAction("Remove image", selected != null) { owners.current.dispatch(EditorIntent.Delete(true)) }
}

internal fun imageProperties(activity: MainActivity, owners: AndroidOwners) = settled(owners) {
    val frame = owners.current.snapshot()
    val image = frame.committed.units.subList(frame.selection.start, frame.selection.end).singleOrNull()?.image
    if (image == null) {
        owners.status = "Select one image block"
    } else {
        MobileImageDialog(activity, owners, image).show()
    }
}

/** Native dialog owns only pending values, never a second editable document. */
private class MobileImageDialog(
    private val activity: MainActivity,
    owners: AndroidOwners,
    private val image: ImageBlock,
) {
    private val target = MobileTarget(owners)
    private val resource = target.model.snapshot().committed.resources.getValue(image.resource)
    private val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val width = number("Requested width 10–600 document units", image.width)
    private val height = number("Requested height; aspect ratio locked", proportionalHeight(image.width, resource))
    private val alt = EditText(activity).apply {
        hint = "Alternative text, up to 2048 UTF-16 units"
        contentDescription = "Image alternative text"
        setText(image.alt)
        fields.addView(this)
    }
    private val message = TextView(activity).apply {
        text = "Aspect ratio locked. Change width OR height. Display fitting preserves the stored size."
        fields.addView(this)
    }
    private fun number(label: String, value: Int) = EditText(activity).apply {
        contentDescription = label
        hint = label
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        fields.addView(this)
    }
    fun show() {
        val content = android.widget.ScrollView(activity).apply { addView(fields) }
        val dialog = AlertDialog.Builder(activity).setTitle("Image size and alternative text").setView(content)
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { applyValues() }.onSuccess { dialog.dismiss() }.onFailure {
                    message.text = it.message
                    width.error = it.message
                }
            }
        }
        dialog.setOnDismissListener { activity.inputView?.requestFocus() }
        dialog.show()
    }
    private fun applyValues() {
        target.requireCurrent()
        val adjusted = aspectWidth(image, resource, width.text.toString(), height.text.toString())
        require(alt.text.length <= MAX_ALT_UNITS) { "Alternative text exceeds 2048 UTF-16 units" }
        val capture = target.model.clipboardCapture() ?: error("Finish the keyboard candidate before applying")
        val command = StructureCommand.ImageProperties(adjusted, alt.text.toString())
        check(target.model.dispatch(EditorIntent.ApplyStructure(capture, command))) {
            "Image command was rejected; document retained"
        }
    }
}

/** Validate pending fields before dispatch. Changing both fields must retain the exact rounded aspect ratio. */
internal fun aspectWidth(image: ImageBlock, resource: ImageResource, width: String, height: String): Int {
    val w = width.toIntOrNull() ?: error("Width must be a whole number")
    val h = height.toIntOrNull() ?: error("Height must be a whole number")
    require(w in MIN_IMAGE_WIDTH..MAX_IMAGE_WIDTH) { "Width must be 10–600 document units" }
    require(h > 0) { "Height must be positive" }
    val changedHeight = h != proportionalHeight(image.width, resource)
    if (w != image.width && changedHeight) {
        val proportional = proportionalHeight(w, resource)
        require(h == proportional) { "Width $w requires height $proportional. Change one field or use that height." }
    }
    val adjusted = if (w == image.width &&
        changedHeight
    ) {
        (h.toDouble() * resource.width / resource.height).roundToInt()
    } else {
        w
    }
    require(adjusted in MIN_IMAGE_WIDTH..MAX_IMAGE_WIDTH) { "Aspect-preserving width must be 10–600 document units" }
    return adjusted
}

private fun proportionalHeight(width: Int, resource: ImageResource) =
    (width.toDouble() * resource.height / resource.width).roundToInt().coerceAtLeast(1)
private const val MIN_IMAGE_WIDTH = 10
private const val MAX_IMAGE_WIDTH = 600
private const val MAX_ALT_UNITS = 2048
