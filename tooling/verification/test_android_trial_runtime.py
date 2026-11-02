"""Loader setup is distinct from guest admission and cannot silently apt-install unknowns."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import android_trial_runtime as runtime


class RuntimeAdmission(unittest.TestCase):
    def test_all_missing_sonames_are_collected_and_only_observed_pulse_is_installable(self):
        raw = 'libpulse.so.0 => not found\n libpulse-simple.so.0 => not found\n libc.so.6 => /lib/libc.so.6 (0x123)\n'
        self.assertEqual(['libpulse-simple.so.0', 'libpulse.so.0'], runtime.missing_libraries(raw, 0))
        self.assertEqual(['libpulse0'], runtime.required_packages(runtime.missing_libraries(raw, 0)))
        self.assertEqual([], runtime.required_packages([]))
        with self.assertRaisesRegex(ValueError, 'libunknown'):
            runtime.required_packages(['libpulse.so.0', 'libunknown.so'])
        for raw, code in [('', 0), ('not a dynamic executable', 1)]:
            with self.assertRaises(ValueError):
                runtime.missing_libraries(raw, code)

    def exercise(self, unknown=False):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'runtime'
            calls = []
            scans = [0]
            def execute(args, **kwargs):
                calls.append(args)
                raw = ''
                if args[0] == 'ldd':
                    scans[0] += 1
                    raw = ('libunknown.so => not found\n' if unknown else 'libpulse.so.0 => not found\n') if scans[0] <= 2 else 'libc.so.6 => /lib/libc.so.6 (0x123)\n'
                elif args[-1] == '-version':
                    raw = 'Android emulator version 37.2.13.0 (build_id 16740729)'
                return subprocess.CompletedProcess(args, 0, raw, '')
            with patch.object(runtime.subprocess, 'run', side_effect=execute):
                if unknown:
                    with self.assertRaisesRegex(ValueError, 'libunknown'):
                        runtime.prepare(Path(directory) / 'sdk', output)
                    self.assertEqual(2, len(calls))
                    self.assertFalse(any('sudo' in c for c in calls))
                    self.assertEqual('FAIL', json.loads((output / 'result.json').read_text())['status'])
                else:
                    result = runtime.prepare(Path(directory) / 'sdk', output)
                    self.assertEqual('HOST_RUNTIME_READY_ONLY', result['status'])
                    self.assertEqual(['libpulse0'], result['requiredHostPackages'])
                    self.assertEqual(4, scans[0])
                    self.assertEqual(['sudo', 'apt-get', 'install', '-y', '--no-install-recommends', 'libpulse0'], calls[3])
                    self.assertEqual('-version', calls[-1][-1])
    def test_actual_preflight_installs_only_observed_dependency_and_rechecks_both_binaries(self):
        self.exercise()

    def test_unknown_dependency_preserves_failure_and_never_installs_or_launches(self):
        self.exercise(unknown=True)
