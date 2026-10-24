import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from poppler_tool import probe_pdftoppm


class PopplerDiscovery(unittest.TestCase):
    def test_generated_crash_and_replay_outputs_are_ignored_without_hiding_reports(self):
        root = Path(__file__).resolve().parents[2]
        for name in ('hs_err_pid123.log', 'replay_pid123.log',
                     'apps/android-sample/hs_err_pid123.log', 'apps/android-sample/replay_pid123.log'):
            with self.subTest(path=name):
                result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', '--', name], cwd=root)
                self.assertEqual(0, result.returncode, 'Generated diagnostic must remain machine-local: ' + name)
        result = subprocess.run(['git', 'check-ignore', '--no-index', '-q', '--',
                                 'docs/reports/runtime-diagnostic-review.md'], cwd=root)
        self.assertEqual(1, result.returncode, 'A maintained diagnostic report must remain reviewable')

    def test_actual_entrypoint_help_needs_no_optional_tools_or_pdf_parsers(self):
        import sys
        root = Path(__file__).resolve().parents[2]
        result = subprocess.run([sys.executable, str(root / 'tests/suites/pagination/output_bridge/independent_rejection.py'), '--help'],
                                cwd=root, capture_output=True, env=dict(os.environ, POPPLER_PDFTOPPM='/missing/tool'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b'--candidate', result.stdout)

    def test_no_tool_and_bad_explicit_path_are_actionable(self):
        with patch.dict(os.environ, {}, clear=True), patch('poppler_tool.shutil.which', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'install Poppler'): probe_pdftoppm()
        with patch.dict(os.environ, {'POPPLER_PDFTOPPM': '/missing/disposable/poppler'}):
            with self.assertRaisesRegex(RuntimeError, 'correct the configured path'): probe_pdftoppm()

    def test_explicit_and_path_discovery_probe_the_selected_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = Path(directory) / 'pdftoppm'; tool.write_bytes(b'test-owned placeholder')
            for explicit in (True, False):
                env = {'POPPLER_PDFTOPPM': str(tool)} if explicit else {}
                with patch.dict(os.environ, env, clear=True), patch('poppler_tool.shutil.which', return_value=str(tool)), \
                        patch('poppler_tool.subprocess.run', return_value=subprocess.CompletedProcess([], 0, '', 'pdftoppm version 25')) as run:
                    self.assertEqual(tool, probe_pdftoppm()[0])
                    self.assertEqual([str(tool), '-v'], run.call_args.args[0])
                    self.assertEqual(15, run.call_args.kwargs['timeout'])

    def test_launch_version_and_timeout_failures_are_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = Path(directory) / 'pdftoppm'; tool.touch()
            with patch.dict(os.environ, {'POPPLER_PDFTOPPM': str(tool)}):
                for failure in [PermissionError('denied'), subprocess.TimeoutExpired('pdftoppm', 15)]:
                    with patch('poppler_tool.subprocess.run', side_effect=failure), self.assertRaisesRegex(RuntimeError, 'Cannot run'):
                        probe_pdftoppm()
                for code, text in [(1, 'failed'), (0, 'unrelated binary')]:
                    with patch('poppler_tool.subprocess.run', return_value=subprocess.CompletedProcess([], code, text, '')):
                        with self.assertRaisesRegex(RuntimeError, 'version check failed'): probe_pdftoppm()


if __name__ == '__main__': unittest.main()
