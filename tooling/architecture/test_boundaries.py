import unittest
from check_boundaries import ALLOWED, check_graph, check_source


class BoundaryChecks(unittest.TestCase):
    def test_outward_project_dependency_rejected(self):
        graph = {key: list(value) for key, value in ALLOWED.items()}
        self.assertFalse(check_graph(graph))
        graph[":core:model"] = [":core:data"]
        self.assertTrue(check_graph(graph))

    def test_import_and_qualified_bypasses_rejected(self):
        for module in (":core:model", ":core:domain"):
            for text in ("import javax.swing.JFrame", "val x = java.awt.Point()", "import androidx.compose.runtime.State"):
                self.assertTrue(check_source(module, text))
        self.assertTrue(check_source(":core:ui", "import dev.shayxo.composedoc.workspace.WorkspaceUi"))
        self.assertTrue(check_source(":core:layout", "import dev.shayxo.composedoc.ui.EditorViewModel"))

    def test_ui_and_app_code_cannot_bypass_view_model(self):
        for module in (":core:ui", ":apps:desktop-workspace"):
            self.assertTrue(check_source(module, "val e = EditorEngine(document)", "WorkspaceUi.kt"))
            self.assertTrue(check_source(module, "surface.viewModel.verificationEngine.move(s)", "EditorSurface.kt"))
            self.assertFalse(check_source(module, "viewModel.dispatch(EditorIntent.SelectAll)", "EditorSurface.kt"))
        self.assertFalse(check_source(":core:ui", "class EditorViewModel(private val engine: EditorEngine)", "EditorViewModel.kt"))

    def test_diagnostic_text_not_a_dependency(self):
        self.assertFalse(check_source(":core:model", 'val diagnostic = "java.awt is forbidden" // javax.swing.JFrame'))


if __name__ == "__main__":
    unittest.main()
