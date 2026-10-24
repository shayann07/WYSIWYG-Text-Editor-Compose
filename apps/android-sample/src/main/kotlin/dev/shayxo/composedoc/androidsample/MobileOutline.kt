package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent

internal fun showOutline(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) = settled(owners) {
    val model = owners.current
    val view = ComposeView(activity)
    view.setViewTreeLifecycleOwner(activity)
    view.setViewTreeSavedStateRegistryOwner(activity)
    val dialog = AlertDialog.Builder(activity).setTitle("Document outline")
        .setView(view).setNegativeButton("Close", null).create()
    // Window recomposition resolves owners from the dialog root, not just the embedded ComposeView.
    checkNotNull(dialog.window).decorView.apply {
        setViewTreeLifecycleOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
    }
    view.setContent {
        val state by model.uiState.collectAsState()
        val blocks = state.frame.committed.paragraphs()
        val current = blocks.lastOrNull { it.properties.heading > 0 && it.start <= state.frame.selection.focus }?.anchor
        val rows = model.outline()
        Column(Modifier.fillMaxWidth().heightIn(max = OUTLINE_MAX_DP.dp).verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) BasicText("No semantic headings yet", Modifier.padding(12.dp))
            rows.forEach { row ->
                val selected = row.anchor == current
                BasicText(
                    "H${row.level} · ${row.label}${if (selected) " · current section" else ""}",
                    Modifier.fillMaxWidth().background(Color(if (selected) SELECTED_SECTION else OTHER_SECTION))
                        .clickable {
                            if (owners.current === model && model.dispatch(EditorIntent.NavigateOutline(row.anchor))) {
                                dialog.dismiss()
                            }
                        }.padding(
                            start = (12 + (row.level - 1) * INDENT_DP).dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                    style = TextStyle(
                        fontSize = OUTLINE_FONT_SP.sp,
                        fontFamily = activity.typography?.family(StoredFont.NOTO_NASTALIQ),
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                )
            }
        }
    }
    dialog.setOnDismissListener {
        view.disposeComposition()
        input?.requestFocus()
    }
    dialog.show()
}

private const val OUTLINE_MAX_DP = 360
private const val OUTLINE_FONT_SP = 20
private const val INDENT_DP = 12
private const val SELECTED_SECTION = 0xffdce8fc
private const val OTHER_SECTION = 0xffffffff
