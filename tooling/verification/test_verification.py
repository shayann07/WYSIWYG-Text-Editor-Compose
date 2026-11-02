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
import final_gates
from contextlib import redirect_stdout, nullcontext
import io


class FinalGateRawEvidence(unittest.TestCase):
    def test_all_six_successful_workers_still_require_retained_raw_logs(self):
        for fail_log in (False, True):
            with self.subTest(fail_log=fail_log), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                receipt = root / 'tooling/local/final-verification.json'
                calls = []
                def execute(command, **kwargs):
                    calls.append(command)
                    raw = b'owned fixture worker\n'
                    if 'tooling/verification/verify.py' in command:
                        raw = ('Evidence: ' + str(root / 'tests/local-evidence/desktop/owned') + '\n').encode()
                    return subprocess.CompletedProcess(command, 0, raw)
                def git(*args):
                    return '' if args[0] == 'status' else 'a' * 40
                retention = patch.object(Path, 'write_bytes', side_effect=OSError('injected raw-log IO failure')) if fail_log else nullcontext()
                with patch.object(final_gates, 'ROOT', root), patch.object(final_gates, 'RECEIPT', receipt), \
                        patch.object(final_gates, 'git', side_effect=git), \
                        patch.object(final_gates.subprocess, 'run', side_effect=execute), retention, redirect_stdout(io.StringIO()):
                    self.assertEqual(1 if fail_log else 0, final_gates.main())
                value = json.loads(receipt.read_text())
                self.assertEqual(6, len(calls))
                self.assertTrue(all(g['exitCode'] == 0 for g in value['gates']))
                self.assertEqual('FAIL' if fail_log else 'PASS', value['status'])

    def test_receipt_failure_keeps_original_timeout_or_nonzero_worker_primary(self):
        for timed_out in (False, True):
            with self.subTest(timed_out=timed_out), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                raw = b'original full prefix\n'
                timeout = subprocess.TimeoutExpired('fixture', 1800, output=raw)
                original_write = Path.write_text
                def write(path, text, **kwargs):
                    if '"exitCode"' in text:
                        raise OSError('injected receipt failure')
                    return original_write(path, text, **kwargs)
                def git(*args):
                    return '' if args[0] == 'status' else 'a' * 40
                with patch.object(final_gates, 'ROOT', root), \
                        patch.object(final_gates, 'RECEIPT', root / 'tooling/local/final-verification.json'), \
                        patch.object(final_gates, 'git', side_effect=git), \
                        patch.object(final_gates.subprocess, 'run', side_effect=timeout if timed_out else None,
                                     return_value=subprocess.CompletedProcess('fixture', 7, raw)), \
                        patch.object(Path, 'write_text', write), redirect_stdout(io.StringIO()):
                    with self.assertRaises(subprocess.TimeoutExpired if timed_out else subprocess.CalledProcessError) as caught:
                        final_gates.main()
                self.assertEqual(raw, caught.exception.output)
                self.assertIn('receipt failure', caught.exception.__notes__[0])
                if timed_out:
                    self.assertIs(timeout, caught.exception)
                else:
                    self.assertEqual(7, caught.exception.returncode)

    def exercise(self, outcome, fail_log=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = root / 'tooling/local/final-verification.json'
            calls = []
            def execute(command, **kwargs):
                calls.append(command)
                if len(calls) == 1:
                    if isinstance(outcome, Exception):
                        raise outcome
                    return outcome
                return subprocess.CompletedProcess(command, 0, b'fixture worker output\n')
            def git(*args):
                return '' if args[0] == 'status' else 'a' * 40
            retention = patch.object(Path, 'write_bytes', side_effect=OSError('injected raw-log IO failure')) if fail_log else nullcontext()
            with patch.object(final_gates, 'ROOT', root), patch.object(final_gates, 'RECEIPT', receipt), \
                    patch.object(final_gates, 'git', side_effect=git), \
                    patch.object(final_gates.subprocess, 'run', side_effect=execute), \
                    retention, \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(1, final_gates.main())
            value = json.loads(receipt.read_text())
            self.assertEqual('FAIL', value['status'])
            entry = value['gates'][0]
            if fail_log:
                self.assertIn('injected raw-log IO failure', entry['retentionError'])
                self.assertFalse((root / entry['rawLog']).exists())
            else:
                self.assertEqual(b'original full prefix\n' + b'x' * 5000, (root / entry['rawLog']).read_bytes())
            self.assertEqual(len(calls), 5)  # No workspace evidence: mutations cannot be counted as executed.
            return entry

    def test_actual_final_runner_retains_full_failed_child_output(self):
        entry = self.exercise(subprocess.CompletedProcess('fixture', 7, b'original full prefix\n' + b'x' * 5000))
        self.assertEqual(7, entry['exitCode'])

    def test_actual_final_runner_retains_timeout_prefix_and_failure_status(self):
        entry = self.exercise(subprocess.TimeoutExpired('fixture', 1800, output=b'original full prefix\n' + b'x' * 5000))
        self.assertIsNone(entry['exitCode'])
        self.assertTrue(entry['timedOut'])

    def test_logging_failure_cannot_mask_nonzero_or_timeout_worker_result(self):
        failed = self.exercise(subprocess.CompletedProcess('fixture', 7, b'original full prefix\n' + b'x' * 5000), fail_log=True)
        self.assertEqual(7, failed['exitCode'])
        timeout = self.exercise(subprocess.TimeoutExpired('fixture', 1800, output=b'original full prefix\n' + b'x' * 5000), fail_log=True)
        self.assertIsNone(timeout['exitCode'])
        self.assertTrue(timeout['timedOut'])


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
