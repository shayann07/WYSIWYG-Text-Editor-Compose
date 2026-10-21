package dev.shayxo.composedoc.androidsample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.shayxo.composedoc.ui.EditorViewModel

/** Imperative public queries must subscribe to published state even when parent chrome is skipped. */
@Composable
internal fun <T> editorQuery(model: EditorViewModel, read: (EditorViewModel) -> T): T {
    val published by model.uiState.collectAsState()
    return remember(model, published, read) { read(model) }
}
