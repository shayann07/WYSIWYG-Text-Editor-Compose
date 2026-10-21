"""Guard job-step indentation, including transitions out of multiline shell blocks.

This narrow structural guard is not a replacement for GitHub's workflow parser.
It requires no new runtime or validation dependency.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def validate_step_indentation(text):
    steps_key = None
    scalar_key = None
    count = 0
    for number, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if scalar_key is not None:
            if indent > scalar_key:
                continue
            scalar_key = None
        if steps_key is not None and indent <= steps_key:
            steps_key = None
        if raw.strip() == "steps:":
            steps_key = indent
        elif steps_key is not None and re.match(r"\s*-\s+(?:name|uses|run|id|if):", raw):
            if indent != steps_key + 2:
                raise ValueError(f"Workflow step at line {number} has indent {indent}, expected {steps_key + 2}")
            count += 1
        if re.match(r"\s*(?:-\s+)?[\w-]+:\s*[|>][-+]?\s*(?:#.*)?$", raw):
            scalar_key = indent + (2 if raw.lstrip().startswith("- ") else 0)
    if not count:
        raise ValueError("No workflow steps were checked")
    return count


class WorkflowStepStructure(unittest.TestCase):
    def test_current_workflows_have_structurally_aligned_job_steps(self):
        for path in sorted((ROOT / ".github/workflows").glob("*.yml")):
            with self.subTest(path=path.name):
                self.assertGreater(validate_step_indentation(path.read_text(encoding="utf-8")), 0)

    def test_step_after_script_must_return_to_its_list_level(self):
        prefix = "jobs:\n  check:\n    steps:\n      - name: Script\n        run: |\n          echo test\n"
        self.assertEqual(2, validate_step_indentation(prefix + "      - name: Next\n        run: echo next\n"))
        with self.assertRaisesRegex(ValueError, "indent 8, expected 6"):
            validate_step_indentation(prefix + "        - name: Next\n          run: echo next\n")

    def test_literal_script_lines_are_not_workflow_steps(self):
        text = "jobs:\n  check:\n    steps:\n      - run: |\n          - name: literal shell input\n          echo end\n"
        self.assertEqual(1, validate_step_indentation(text))
        with self.assertRaisesRegex(ValueError, "No workflow steps"):
            validate_step_indentation("jobs:\n  empty:\n    steps:\n")
