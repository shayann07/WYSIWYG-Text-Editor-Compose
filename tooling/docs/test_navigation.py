"""Exercise the real validator with disposable broken active-index links."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


class NavigationCoverage(unittest.TestCase):
    def test_suite_index_broken_link_is_rejected_and_bytes_restored(self):
        path = ROOT / 'tests/suites/README.md'
        original = path.read_bytes()
        try:
            path.write_bytes(original + b'\n[Negative navigation fixture](missing-test-only-link.md)\n')
            result = subprocess.run([sys.executable, str(ROOT / 'tooling/docs/validate_docs.py')],
                                    cwd=ROOT, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b'Broken active navigation links', result.stderr)
            self.assertIn(b'missing-test-only-link.md', result.stdout)
        finally:
            path.write_bytes(original)


if __name__ == '__main__': unittest.main()
