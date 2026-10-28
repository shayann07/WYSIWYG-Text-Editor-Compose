"""Fault injection reaches the real android_check.run wrapper through main."""
from contextlib import nullcontext
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import android_check
from android_memory_readiness import MemoryServiceUnavailable
from test_android_memory_readiness import GOOD, BAD


class ProductionCommandRetention(unittest.TestCase):
    def exercise(self, outcome, failed_suffixes, fail_receipt=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app, tests = root / 'app.apk', root / 'test.apk'
            app.write_bytes(b'host-only app placeholder')
            tests.write_bytes(b'host-only test placeholder')
            output = root / 'evidence'
            commands = []
            observations = [0]

            def adb(command, **kwargs):
                args = command[3:]
                commands.append(args)
                if args == ['shell', 'getprop', 'ro.build.version.sdk']:
                    return subprocess.CompletedProcess(command, 0, b'37', b'')
                if args == ['shell', 'dumpsys', 'meminfo']:
                    observations[0] += 1
                    if observations[0] == 1:
                        if isinstance(outcome, Exception):
                            raise outcome
                        return outcome
                return subprocess.CompletedProcess(command, 0, GOOD, b'')

            write_bytes, write_text = Path.write_bytes, Path.write_text

            def retain_bytes(path, data):
                if path.name in ['pre-test-memory.' + suffix for suffix in failed_suffixes]:
                    raise OSError('injected raw-evidence disk failure')
                return write_bytes(path, data)

            def retain_text(path, data, **kwargs):
                if fail_receipt and path.name == 'results.json' and '"status": "BLOCKED"' in data:
                    raise OSError('injected final-receipt disk failure')
                if path.name in ['pre-test-memory.' + suffix for suffix in failed_suffixes]:
                    raise OSError('injected timeout-marker disk failure')
                return write_text(path, data, **kwargs)

            argv = ['android_check.py', '--serial', 'emulator-5580', '--avd-name', 'host-only',
                    '--apk', str(app), '--test-apk', str(tests), '--output-dir', str(output)]
            with patch('sys.argv', argv), patch.dict('os.environ', {'ANDROID_HOME': str(root)}), \
                    patch.object(android_check, 'wait_for_owned_emulator', return_value={}), \
                    patch.object(android_check, 'wait_for_services', return_value={}), \
                    patch.object(android_check, 'packaged_fonts', return_value={}), \
                    patch.object(android_check, 'wait_for_install_broadcasts', return_value={}), \
                    patch.object(android_check, 'owned_tap_fixture', return_value=nullcontext('/fixture')), \
                    patch.object(android_check.subprocess, 'run', side_effect=adb), \
                    patch.object(Path, 'write_bytes', retain_bytes), patch.object(Path, 'write_text', retain_text), \
                    patch.object(android_check, 'run_witness') as witness:
                with self.assertRaises(MemoryServiceUnavailable) as caught:
                    android_check.main()
                witness.assert_not_called()
            receipt = json.loads((output / 'results.json').read_text())
            self.assertEqual(receipt['status'], 'RUNNING' if fail_receipt else 'BLOCKED')
            self.assertEqual(receipt['qualification'], dict(editor='NOT RUN', recovery='NOT RUN', clipboard='NOT RUN'))
            self.assertFalse(any('instrument' in command for command in commands))
            self.assertTrue(caught.exception.record['retentionErrors'])
            if fail_receipt:
                self.assertIn('final-receipt disk failure', '\n'.join(caught.exception.__notes__))
            return caught.exception.record

    def test_timeout_cause_survives_raw_and_timeout_marker_retention_failure(self):
        for suffixes in (('stdout', 'stderr'), ('timeout',), ('stdout', 'stderr', 'timeout')):
            with self.subTest(suffixes=suffixes):
                failure = subprocess.TimeoutExpired('adb', 15, output=b'partial', stderr=b'timeout stderr')
                record = self.exercise(failure, suffixes)
                self.assertTrue(record['timedOut'])
                self.assertIsNone(record['exitCode'])
                self.assertIn('timed out', record['error'])

    def test_completed_status_and_primary_cause_survive_raw_retention_failure(self):
        for raw, code, expected in ((BAD, 0, 'service timeout'), (GOOD, 9, 'did not succeed'),
                                    (GOOD, 0, 'retain memory observation')):
            with self.subTest(code=code, expected=expected):
                record = self.exercise(subprocess.CompletedProcess('adb', code, raw, b'raw stderr'), ('stdout', 'stderr'))
                self.assertEqual(record['exitCode'], code)
                self.assertFalse(record['timedOut'])
                self.assertIn(expected, record['error'])

    def test_failure_receipt_write_cannot_replace_original_timeout(self):
        failure = subprocess.TimeoutExpired('adb', 15, output=b'partial', stderr=b'timeout stderr')
        record = self.exercise(failure, ('timeout',), fail_receipt=True)
        self.assertTrue(record['timedOut'])
        self.assertIn('timed out', record['error'])


if __name__ == '__main__':
    unittest.main()
