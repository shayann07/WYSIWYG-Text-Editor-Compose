"""Actual runner routing with synthetic command outcomes; no device process is run."""
from contextlib import nullcontext
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import android_check


class WitnessEvidenceRouting(unittest.TestCase):
    def assert_destinations(self, witnessed, output):
        self.assertEqual([name for name, _ in witnessed], ['first', 'second'])
        for (_, actual), expected in zip(witnessed, (output, output / 'after-close-interruption')):
            self.assertTrue(actual.samefile(expected), f'{actual} is not the expected directory {expected}')

    def test_filesystem_alias_is_accepted_but_an_existing_wrong_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'evidence'
            (output / 'after-close-interruption').mkdir(parents=True)
            (output / 'nested').mkdir()
            alias = output / 'nested' / '..'
            self.assert_destinations([('first', alias), ('second', alias / 'after-close-interruption')], output)
            wrong = root / 'other'
            (wrong / 'after-close-interruption').mkdir(parents=True)
            with self.assertRaises(AssertionError):
                self.assert_destinations([('first', wrong), ('second', wrong / 'after-close-interruption')], output)

    def test_both_witness_raw_streams_survive_success_and_second_witness_failure(self):
        for fail_second in (False, True):
            with self.subTest(fail_second=fail_second), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app, tests = root / 'app.apk', root / 'test.apk'
                app.write_bytes(b'host-only app placeholder')
                tests.write_bytes(b'host-only test placeholder')
                output = root / 'evidence'
                result = dict(status='PASS', fatal=None, api=28,
                              apkSha256=hashlib.sha256(app.read_bytes()).hexdigest(),
                              groups=[dict(name=str(i), status='PASS') for i in range(android_check.EXPECTED_GROUPS)])

                def adb(command, **kwargs):
                    args = command[3:]
                    if args == ['shell', 'getprop', 'ro.build.version.sdk']:
                        stdout = b'28'
                    elif args and args[0] == 'host-only-timeout':
                        raise subprocess.TimeoutExpired(args, 7, output=args[1].encode() + b'-partial',
                                                        stderr=args[1].encode() + b'-timeout-stderr')
                    elif args and args[0] == 'host-only-witness':
                        stdout = args[1].encode()
                        self.assertEqual(kwargs['timeout'], 7)
                        return subprocess.CompletedProcess(command, 0, stdout, stdout + b'-stderr')
                    elif 'cat' in args:
                        stdout = json.dumps(result).encode()
                    else:
                        stdout = b''
                    return subprocess.CompletedProcess(command, 0, stdout, b'')

                witnessed = []

                def witness(run, destination, close_after_discard=False):
                    name = 'second' if close_after_discard else 'first'
                    witnessed.append((name, destination))
                    completed = run('kill-start', 'host-only-witness', name, timeout=7,
                                    allowed_codes=(0,), with_status=True)
                    self.assertEqual(completed.stdout, name.encode())
                    try:
                        # Exercise raw timeout routing, independent of real device polling.
                        run('kill-wait', 'host-only-timeout', name, timeout=7)
                    except subprocess.TimeoutExpired:
                        if fail_second and close_after_discard:
                            raise
                    finally:
                        run('kill-clean-owned-child', 'host-only-witness', name, timeout=7)
                    receipt = dict(status='PASS', syntheticWitness=name)
                    (destination / 'process-interruption.json').write_text(json.dumps(receipt))
                    return receipt

                argv = ['android_check.py', '--serial', 'emulator-5580', '--avd-name', 'host-only',
                        '--apk', str(app), '--test-apk', str(tests), '--output-dir', str(output)]
                with patch('sys.argv', argv), patch.dict('os.environ', {'ANDROID_HOME': str(root)}), \
                        patch.object(android_check, 'wait_for_owned_emulator', return_value={}), \
                        patch.object(android_check, 'wait_for_services', return_value={}), \
                        patch.object(android_check, 'packaged_fonts', return_value={}), \
                        patch.object(android_check, 'wait_for_install_broadcasts', return_value={}), \
                        patch.object(android_check, 'owned_tap_fixture', return_value=nullcontext('/fixture')), \
                        patch.object(android_check.subprocess, 'run', side_effect=adb), \
                        patch.object(android_check, 'run_witness', side_effect=witness), \
                        patch('sys.stdout', new_callable=io.StringIO):
                    # main's success message belongs to mocked host outcomes here;
                    # keep it out of raw test logs so it cannot resemble a device PASS.
                    if fail_second:
                        with self.assertRaises(subprocess.TimeoutExpired):
                            android_check.main()
                    else:
                        android_check.main()
                self.assert_destinations(witnessed, output)
                # Independent expected raw bytes: the second witness cannot overwrite the first.
                self.assertEqual((output / 'kill-start.stdout').read_bytes(), b'first')
                self.assertEqual((output / 'kill-start.stderr').read_bytes(), b'first-stderr')
                self.assertEqual((output / 'after-close-kill-start.stdout').read_bytes(), b'second')
                self.assertEqual((output / 'after-close-kill-start.stderr').read_bytes(), b'second-stderr')
                for prefix, name in (('', 'first'), ('after-close-', 'second')):
                    self.assertEqual((output / (prefix + 'kill-wait.stdout')).read_bytes(), name.encode() + b'-partial')
                    self.assertEqual((output / (prefix + 'kill-wait.stderr')).read_bytes(), name.encode() + b'-timeout-stderr')
                    self.assertIn(name, (output / (prefix + 'kill-wait.timeout')).read_text())
                    self.assertEqual((output / (prefix + 'kill-clean-owned-child.stdout')).read_bytes(), name.encode())
                    self.assertEqual((output / (prefix + 'kill-clean-owned-child.stderr')).read_bytes(), name.encode() + b'-stderr')
                self.assertEqual(json.loads((output / 'process-interruption.json').read_text())['syntheticWitness'], 'first')
                receipt = json.loads((output / 'results.json').read_text())
                self.assertEqual(receipt['status'], 'FAIL' if fail_second else 'PASS')
                self.assertEqual(receipt['qualification']['clipboard'], 'NOT RUN')
                if not fail_second:
                    self.assertEqual(json.loads((output / 'after-close-interruption/process-interruption.json').read_text())
                                     ['syntheticWitness'], 'second')


if __name__ == '__main__':
    unittest.main()
