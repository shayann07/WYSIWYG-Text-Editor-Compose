package dev.shayxo.composedoc.ui

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun typedIntentsMutateOnlyThroughDispatchAndQueriesStayReadOnly() {
        val viewModel = EditorViewModel()
        viewModel.dispatch(EditorIntent.Type("اردو text"))
        assertEquals("اردو text", viewModel.uiState.value.frame.committed.text)
        viewModel.dispatch(EditorIntent.SelectAll)
        assertEquals(Selection(0, 9), viewModel.uiState.value.frame.selection)
        val before = viewModel.uiState.value.frame
        assertEquals(null, viewModel.formatState(FormatKey.BOLD)?.takeIf { it })
        assertEquals(before, viewModel.snapshot())
        assertEquals(before, viewModel.uiState.value.frame)
        viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
        assertEquals(true, viewModel.formatState(FormatKey.BOLD))
        viewModel.dispatch(EditorIntent.Select(Selection(9, 9)))
        viewModel.dispatch(EditorIntent.Delete(backward = true))
        assertEquals("اردو tex", viewModel.uiState.value.frame.committed.text)
        val capture = assertNotNull(viewModel.clipboardCapture())
        val applied: Boolean = viewModel.dispatch(EditorIntent.ApplyClipboard(capture, null, plain = "t"))
        assertTrue(applied)
        assertEquals("اردو text", viewModel.uiState.value.frame.committed.text)
        val navigated: Boolean = viewModel.dispatch(EditorIntent.NavigateOutline(anchor = null))
        assertTrue(viewModel.outline().isEmpty())
        assertFalse(navigated, "Outline navigation without a heading must be refused")
        viewModel.detach()
    }
}
