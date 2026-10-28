"""One-shot admission: real retained formats, no Android execution and no RAM threshold."""
import hashlib
from contextlib import nullcontext
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import android_check
from android_memory_readiness import admitted_instrument, observe_memory, parse_summary, MemoryServiceUnavailable

FIXTURES = Path(__file__).parent / 'fixtures/android-memory'
GOOD = (FIXTURES / 'api37-responsive.txt').read_bytes()
BAD = (FIXTURES / 'api37-timeout.txt').read_bytes()


class MemoryReadiness(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        self.calls = []

    def probe(self, raw=GOOD, code=0, stderr=b'', api=b'37', failure=None):
        def run(label, *command, **kwargs):
            self.calls.append((label, command, kwargs))
            if command == ('shell', 'getprop', 'ro.build.version.sdk'):
                return api
            if failure:
                raise failure
            return subprocess.CompletedProcess(command, code, raw, stderr)
        return run

    def test_retained_fixtures_have_exact_provenance(self):
        provenance = json.loads((FIXTURES / 'provenance.json').read_text())
        for name, record in provenance.items():
            data = (FIXTURES / name).read_bytes()
            self.assertEqual(len(data), record['sizeBytes'])
            self.assertEqual(hashlib.sha256(data).hexdigest(), record['sha256'])
        self.assertEqual(len(BAD), 118)
        self.assertEqual(len(GOOD), 23544)

    def test_real_passing_format_and_independent_numeric_expectations(self):
        self.assertEqual(parse_summary(GOOD, 0), dict(totalKiB=6067476, freeKiB=3631342, usedKiB=2149457))
        # No amount of free RAM is asserted to establish sufficiency.
        self.assertEqual(parse_summary(b'Total RAM: 1K\nFree RAM: 0K\nUsed RAM: 1K\n', 0)['freeKiB'], 0)

    def test_timeout_anywhere_even_after_complete_summary_cannot_pass(self):
        for raw, stderr in ((BAD, b''), (GOOD + BAD, b''), (BAD + GOOD, b''),
                            (GOOD, b'DUMP TIMEOUT (10000ms)'), (GOOD, b'timed out')):
            with self.subTest(raw_size=len(raw), stderr=stderr), self.assertRaises(ValueError):
                parse_summary(raw, 0, stderr)

    def test_nonzero_malformed_missing_duplicate_conflicting_summaries_rejected(self):
        with self.assertRaises(ValueError):
            parse_summary(GOOD, 1)
        for raw in (b'', b'Total RAM: 1K\nFree RAM: 0K', GOOD + b'\nTotal RAM: 5K\n',
                    GOOD + b'\nTotal RAM: broken\n', GOOD + GOOD,
                    b'Total RAM: 0K\nFree RAM: 0K\nUsed RAM: 0K\n',
                    b'Total RAM: 1,00K\nFree RAM: 0K\nUsed RAM: 1K\n', GOOD + b'\xff'):
            with self.subTest(raw_size=len(raw)), self.assertRaises(ValueError):
                parse_summary(raw, 0)

    def test_single_observation_retains_status_values_raw_and_remaining_budget(self):
        ticks = iter((10, 10, 12, 14))
        result = observe_memory(self.probe(), self.output, 'memory', 18, clock=lambda: next(ticks))
        self.assertEqual(result['status'], 'RESPONSIVE')
        self.assertEqual(result['exitCode'], 0)
        self.assertEqual(result['elapsedSeconds'], 4)
        self.assertEqual(self.calls[0][2]['timeout'], 5)
        self.assertEqual(self.calls[1][2]['timeout'], 6)
        self.assertEqual(len(self.calls), 2)
        self.assertEqual((self.output / 'memory.stdout').read_bytes(), GOOD)
        self.assertEqual((self.output / 'memory.stderr').read_bytes(), b'')
        self.assertEqual(json.loads((self.output / 'memory.json').read_text()), result)

    def test_command_cap_and_setup_deadline_are_not_extended(self):
        result = observe_memory(self.probe(), self.output, 'memory', 100, clock=lambda: 0)
        self.assertEqual(result['timeoutSeconds'], 15)
        self.calls.clear()
        with self.assertRaises(MemoryServiceUnavailable):
            observe_memory(self.probe(), self.output, 'expired', 0, clock=lambda: 0)
        self.assertEqual(self.calls, [])
        ticks = iter((0, 0, 5, 5))
        with self.assertRaises(MemoryServiceUnavailable):
            observe_memory(self.probe(), self.output, 'api-exhausted', 4, clock=lambda: next(ticks))
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.calls[0][2]['timeout'], 4)

    def test_partial_command_timeout_preserves_outputs_and_never_retries(self):
        failure = subprocess.TimeoutExpired('adb', 15, output=b'partial stdout', stderr=b'partial stderr')
        with self.assertRaises(MemoryServiceUnavailable) as caught:
            observe_memory(self.probe(failure=failure), self.output, 'memory', 100, clock=lambda: 0)
        self.assertTrue(caught.exception.record['timedOut'])
        self.assertIsNone(caught.exception.record['exitCode'])
        self.assertEqual(len(self.calls), 2)
        self.assertEqual((self.output / 'memory.stdout').read_bytes(), b'partial stdout')
        self.assertEqual((self.output / 'memory.stderr').read_bytes(), b'partial stderr')

    def test_unsuccessful_command_status_is_retained_including_signal_exit(self):
        for code in (1, -9, 3221225477):
            with self.subTest(code=code), self.assertRaises(MemoryServiceUnavailable) as caught:
                observe_memory(self.probe(code=code), self.output, 'memory', 100, clock=lambda: 0)
            self.assertEqual(caught.exception.record['exitCode'], code)
            self.assertIsNone(self.calls[-1][2]['allowed_codes'])
            self.assertEqual((self.output / 'memory.stdout').read_bytes(), GOOD)

    def test_transport_failure_preserves_previously_captured_raw(self):
        (self.output / 'memory.stdout').write_bytes(b'adb wrapper capture')
        with self.assertRaises(MemoryServiceUnavailable):
            observe_memory(self.probe(failure=RuntimeError('transport disconnected')), self.output, 'memory', 100, clock=lambda: 0)
        self.assertEqual((self.output / 'memory.stdout').read_bytes(), b'adb wrapper capture')
        self.assertEqual(len(self.calls), 2)

    def test_older_api_format_is_explicitly_unqualified_and_unchanged(self):
        result = observe_memory(self.probe(api=b'28'), self.output, 'memory', 100, clock=lambda: 0)
        self.assertEqual(result['status'], 'NOT_APPLICABLE')
        self.assertEqual(result['api'], 28)
        self.assertEqual(len(self.calls), 1)
        self.assertNotIn('values', result)

    def test_missing_api_cannot_silently_skip_admission(self):
        for api in (b'', b'Error 37', b'37\n0'):
            with self.subTest(api=api), self.assertRaises(MemoryServiceUnavailable):
                observe_memory(self.probe(api=api), self.output, 'memory', 100, clock=lambda: 0)

    def test_actual_instrument_admission_never_invokes_callback_after_rejection(self):
        invoked = []
        for raw, code in ((BAD, 0), (GOOD + BAD, 0), (GOOD, 2), (b'', 0)):
            receipt = {}
            with self.subTest(raw_size=len(raw), code=code), self.assertRaises(MemoryServiceUnavailable):
                admitted_instrument(self.probe(raw=raw, code=code), self.output, receipt,
                                    float('inf'), lambda: invoked.append('instrument'))
        self.assertEqual(invoked, [])
        receipt = {}
        admitted_instrument(self.probe(), self.output, receipt, float('inf'), lambda: invoked.append('instrument'))
        self.assertEqual(invoked, ['instrument'])
        self.assertEqual(receipt['memoryBeforeInstrumentation']['status'], 'RESPONSIVE')

    def test_retention_failure_cannot_hide_original_or_admit_instrumentation(self):
        for raw, expected in ((BAD, 'service timeout'), (GOOD, 'retain memory observation')):
            invoked = []
            with patch.object(Path, 'write_bytes', side_effect=OSError('disk full')):
                with self.assertRaisesRegex(MemoryServiceUnavailable, expected) as caught:
                    admitted_instrument(self.probe(raw=raw), self.output, {}, float('inf'), lambda: invoked.append(True))
            self.assertTrue(caught.exception.record['retentionErrors'])
            self.assertEqual(invoked, [])
            retained = json.loads((self.output / 'memory-before-instrumentation.json').read_text())
            self.assertEqual(retained['status'], 'BLOCKED')


class ProductionRunnerAdmission(unittest.TestCase):
    def test_blocked_runner_exits_nonzero_preserves_failure_and_never_starts_children(self):
        for reject_observation in (1, 2):
            with self.subTest(reject_observation=reject_observation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                apk, tests = root / 'app.apk', root / 'tests.apk'
                apk.write_bytes(b'host-only APK placeholder; packaged validation separately mocked')
                tests.write_bytes(b'host-only instrumentation placeholder')
                output = root / 'evidence'
                commands = []
                observations = [0]

                def adb(command, **kwargs):
                    args = command[3:]
                    commands.append(args)
                    if args == ['shell', 'getprop', 'ro.build.version.sdk']:
                        raw = b'37\n'
                    elif args == ['shell', 'dumpsys', 'meminfo']:
                        observations[0] += 1
                        if observations[0] > reject_observation:
                            raise RuntimeError('failure diagnostic transport unavailable')
                        raw = BAD if observations[0] == reject_observation else GOOD
                    else:
                        raw = b''
                    return subprocess.CompletedProcess(command, 0, raw, b'')

                argv = ['android_check.py', '--serial', 'emulator-5580', '--avd-name', 'host-test',
                        '--apk', str(apk), '--test-apk', str(tests), '--output-dir', str(output)]
                with patch('sys.argv', argv), patch.dict('os.environ', {'ANDROID_HOME': str(root)}), \
                        patch.object(android_check, 'wait_for_owned_emulator', return_value={'name': 'host-test'}), \
                        patch.object(android_check, 'wait_for_services', return_value={}), \
                        patch.object(android_check, 'packaged_fonts', return_value={}), \
                        patch.object(android_check, 'wait_for_install_broadcasts', return_value={'status': 'READY'}), \
                        patch.object(android_check, 'owned_tap_fixture', return_value=nullcontext('/owned-fixture')), \
                        patch.object(android_check.subprocess, 'run', side_effect=adb), \
                        patch.object(android_check, 'run_witness') as witness:
                    # main raises; the actual __main__ entry therefore exits nonzero.
                    with self.assertRaisesRegex(MemoryServiceUnavailable, 'service timeout'):
                        android_check.main()
                    witness.assert_not_called()
                receipt = json.loads((output / 'results.json').read_text())
                self.assertEqual(receipt['status'], 'BLOCKED')
                self.assertEqual(receipt['qualification'], dict(editor='NOT RUN', recovery='NOT RUN', clipboard='NOT RUN'))
                self.assertIn('service timeout', receipt['error'])
                self.assertIn('failure-memory', receipt['diagnosticErrors'])
                self.assertFalse(any('instrument' in command for command in commands))
                label = 'pre-test-memory' if reject_observation == 1 else 'memory-before-instrumentation'
                self.assertEqual((output / (label + '.stdout')).read_bytes(), BAD)
                self.assertEqual(receipt['memoryReadinessFailure']['exitCode'], 0)


if __name__ == '__main__':
    unittest.main()
