"""Evidence-contract tests: stale artifacts, failure, timeout and incomplete runs."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import verification as v


class VerificationContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.patches = [patch.object(v, 'PROJECT', self.root), patch.object(v, 'EVIDENCE', self.root / 'local-evidence'), patch.object(v, 'inputs', return_value={'source': {'sha256': 'current'}})]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.temp.cleanup()

    def batch(self):
        with patch.object(v.subprocess, 'check_output', return_value='commit'):
            return v.Verification(self.root / 'run')

    def test_incomplete_run_replaces_prior_pass_pointer(self):
        latest = self.root / 'local-evidence/latest-verification.json'
        latest.parent.mkdir()
        latest.write_text('{"status":"PASS","results":"old"}')
        b = self.batch()
        self.assertEqual(json.loads(latest.read_text())['status'], 'RUNNING')
        self.assertEqual(json.loads((b.output / 'results.json').read_text())['status'], 'RUNNING')

    def test_failed_command_records_exit_and_cannot_pass(self):
        b = self.batch()
        with patch.object(v.subprocess, 'run', return_value=subprocess.CompletedProcess(['fake'], 7, b'failure')):
            with self.assertRaises(RuntimeError):
                b.run('defect', ['fake'], 1)
        self.assertEqual(b.report['steps'][0]['exitCode'], 7)
        self.assertEqual(b.finish('failed'), 1)
        self.assertEqual(json.loads((self.root / 'local-evidence/latest-verification.json').read_text())['status'], 'FAIL')

    def test_timeout_records_no_exit_code_and_failure(self):
        b = self.batch()
        with patch.object(v.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['fake'], 1, b'partial')):
            with self.assertRaises(RuntimeError):
                b.run('timeout', ['fake'], 1)
        self.assertIsNone(b.report['steps'][0]['exitCode'])
        self.assertEqual(b.report['steps'][0]['status'], 'FAIL')

    def test_stale_source_and_changed_class_rejected(self):
        build = self.root / 'build'
        build.mkdir()
        artifact = build / 'class.bin'
        artifact.write_bytes(b'fresh')
        manifest = {'status': 'BUILT', 'inputs': v.inputs(), 'artifacts': {'class.bin': v.identity(artifact)}}
        (build / 'build-identity.json').write_text(json.dumps(manifest))
        self.assertEqual(v.validate_build(build), build.resolve())
        artifact.write_bytes(b'stale')
        with self.assertRaisesRegex(RuntimeError, 'artifact changed'):
            v.validate_build(build)
        manifest['inputs'] = {'source': {'sha256': 'old'}}
        (build / 'build-identity.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(RuntimeError, 'current sources'):
            v.validate_build(build)


if __name__ == '__main__':
    unittest.main()
