package dev.shayxo.composedoc.ui

import androidx.lifecycle.ViewModel
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.model.Selection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorUiState(val frame: EditorFrame)

sealed interface EditorIntent {
    data class Command(val action: EditAction) : EditorIntent
    data class Select(val selection: Selection) : EditorIntent
    data class NativeText(val text: String, val committedCount: Int, val caret: Int?) : EditorIntent
    data object AbortComposition : EditorIntent
}

/** Owns presentation publication; the domain engine remains the sole mutation owner. */
class EditorViewModel(val engine: EditorEngine) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorUiState(engine.snapshot()))
    val uiState: StateFlow<EditorUiState> = mutableState.asStateFlow()
    var onStatePublished: (EditorFrame) -> Unit = {}
    private val publish: (EditorFrame) -> Unit = { frame ->
        mutableState.value = EditorUiState(frame)
        onStatePublished(frame)
    }

    init {
        engine.onChange = publish
    }

    fun dispatch(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.Command -> engine.perform(intent.action)
            is EditorIntent.Select -> engine.move(intent.selection)
            is EditorIntent.NativeText -> engine.inputMethod(intent.text, intent.committedCount, intent.caret)
            EditorIntent.AbortComposition -> engine.abortComposition()
        }
    }

    fun detach() {
        if (engine.onChange === publish) engine.onChange = {}
        onStatePublished = {}
    }

    override fun onCleared() {
        detach()
    }
}
