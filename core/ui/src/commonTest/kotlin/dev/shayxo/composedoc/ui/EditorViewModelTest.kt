package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.Selection
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorViewModelTest {
    @Test
    fun intentsPublishTheAuthoritativeFrameAndDetachDoesNotStealANewOwner() {
        val engine = EditorEngine()
        val first = EditorViewModel(engine)
        first.dispatch(EditorIntent.Command(EditAction.Replace(engine.capture(), "سلام")))
        assertEquals(engine.snapshot(), first.uiState.value.frame)
        val second = EditorViewModel(engine)
        first.detach()
        second.dispatch(EditorIntent.Select(Selection(0, 4)))
        assertEquals(Selection(0, 4), second.uiState.value.frame.selection)
        second.dispatch(EditorIntent.Command(EditAction.Undo))
        assertEquals("", second.uiState.value.frame.committed.text)
        second.detach()
    }
}
