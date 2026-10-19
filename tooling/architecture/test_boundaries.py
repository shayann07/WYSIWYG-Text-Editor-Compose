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

    def test_diagnostic_text_not_a_dependency(self):
        self.assertFalse(check_source(":core:model", 'val diagnostic = "java.awt is forbidden" // javax.swing.JFrame'))


if __name__ == "__main__":
    unittest.main()
