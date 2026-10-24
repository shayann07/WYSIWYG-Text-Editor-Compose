import unittest
import threading
import subprocess
import base64
from android_startup_probe import Sampler, observe, failed_probe, coverage, read_probe


class StartupProbe(unittest.TestCase):
    def test_qualification_result_is_returned_without_retry_and_sampler_stops(self):
        calls = []
        sampler = Sampler(lambda n: dict(returncode=0, stdout='pid=123', stderr=''), interval=.001)
        self.assertEqual(7, observe(sampler, lambda: calls.append('once') or 7))
        self.assertEqual(['once'], calls)
        self.assertTrue(sampler.stop.is_set())
        self.assertFalse(any(t.name == 'bounded-android-startup-probe' for t in threading.enumerate()))

    def test_cleanup_occurs_after_command_exception(self):
        sampler = Sampler(lambda n: dict(returncode=0, stdout='', stderr=''), interval=.001)
        def fail(): raise ValueError('owned command failed')
        with self.assertRaisesRegex(ValueError, 'owned command failed'): observe(sampler, fail)
        self.assertTrue(sampler.stop.is_set())
        self.assertFalse(any(t.name == 'bounded-android-startup-probe' for t in threading.enumerate()))

    def test_timeout_records_actual_attempt_cost_and_cleans_up(self):
        ticks = iter((0, 0, .3))
        def fail(index): raise TimeoutError('owned adb deadline')
        sampler = Sampler(fail, clock=lambda: next(ticks))
        sampler.run()
        self.assertEqual(1, len(sampler.records))
        self.assertEqual('TimeoutError', sampler.records[0]['probeException'])
        self.assertEqual(300, sampler.records[0]['commandCostMs'])
        self.assertIn('owned adb deadline', sampler.records[0]['diagnosticError'])
        self.assertEqual('PROBE_BLOCKED', sampler.ending)

    def test_baseline_is_taken_before_command_and_failed_baseline_refuses_execution(self):
        events = []
        sampler = Sampler(lambda n: dict(returncode=0, stdout='', stderr=''), interval=.001)
        def baseline():
            events.append('baseline')
            return dict(returncode=0, stdout='owned baseline', stderr='')
        self.assertEqual(9, observe(sampler, lambda: events.append('command') or 9, baseline))
        self.assertEqual(['baseline', 'command'], events)
        refused = Sampler(lambda n: self.fail('Sampler must not start'))
        self.assertIsNone(observe(refused, lambda: self.fail('Qualification must not start'),
                                  lambda: dict(returncode=1, stdout='', stderr='Permission denied')))
        self.assertEqual('PROBE_BLOCKED', refused.ending)
        self.assertIn('NOT RUN', refused.error)

    def test_timeout_partial_bytes_are_preserved_with_explicit_truncation(self):
        error = subprocess.TimeoutExpired('owned adb', 3, output=b'prefix\xff', stderr=b'x'*5000)
        result = failed_probe(error)
        self.assertEqual(b'prefix\xff', base64.b64decode(result['stdoutBase64']))
        self.assertEqual(b'x'*4096, base64.b64decode(result['stderrBase64']))
        self.assertEqual(5000, result['stderrCapturedBytes'])
        self.assertTrue(result['stderrTruncated'])
        self.assertIsNone(result['returncode'])

    def test_actual_probe_timeout_preserves_byte_and_text_partial_outputs_and_cost(self):
        for output in (b'partial\xff', 'اردو diagnostic'):
            with self.subTest(output=output):
                calls = []
                def timeout(command, **kwargs):
                    calls.append(command)
                    self.assertEqual(3, kwargs['timeout'])
                    self.assertTrue(command[-1].endswith('cat /proc/uptime\n'))
                    raise subprocess.TimeoutExpired(command, 3, output=output, stderr=b'')
                ticks = iter((0, 0, .375))
                sampler = Sampler(lambda i: read_probe('owned-adb', 'emulator-5580', i, run=timeout),
                                  clock=lambda: next(ticks))
                sampler.run()
                self.assertEqual(1, len(calls))
                row = sampler.records[0]
                expected = output if isinstance(output, bytes) else output.encode('utf-8')
                self.assertEqual(expected, base64.b64decode(row['stdoutBase64']))
                self.assertEqual('', row['stderr'])
                self.assertIsNone(row['returncode'])
                self.assertEqual(375, row['commandCostMs'])
                self.assertIn('utcStart', row)
                self.assertIn('utcEnd', row)
                self.assertEqual('PROBE_BLOCKED', sampler.ending)

    def test_phase_coverage_does_not_combine_different_processes(self):
        def row(phase, pid): return dict(stdout=f'phase={phase} elapsedMs=123 pid={pid}')
        rows = [row('instrumentation-created', 1), row('instrumentation-started', 1),
                row('before-activity-launch', 2), row('editor-mounted', 2)]
        self.assertFalse(coverage(rows, 'QUALIFICATION_EXITED')['startupToMountedObservedInOnePid'])
        rows += [row('before-activity-launch', 1), row('editor-mounted', 1)]
        self.assertTrue(coverage(rows, 'QUALIFICATION_EXITED')['startupToMountedObservedInOnePid'])
        expired = coverage([], 'BOUNDED_WINDOW_COMPLETE')
        self.assertTrue(expired['windowExpiredWithoutObservedInstrumentationStart'])
        self.assertTrue(expired['windowExpiredWithoutObservedEditorMount'])

    def test_permission_failure_is_retained_and_stops_collection(self):
        sampler = Sampler(lambda n: dict(returncode=1, stdout='', stderr='Permission denied'))
        sampler.run()
        self.assertEqual(1, len(sampler.records))
        self.assertEqual('Permission denied', sampler.records[0]['stderr'])
        self.assertEqual('PROBE_BLOCKED', sampler.ending)

    def test_output_and_sample_budgets_are_enforced(self):
        for byte_limit, samples in ((2, 10), (4096, 1)):
            sampler = Sampler(lambda n: dict(returncode=0, stdout='test-owned', stderr=''),
                              interval=0, max_bytes=byte_limit, max_samples=samples)
            sampler.run()
            self.assertLessEqual(len(sampler.records), samples)
            self.assertLessEqual(sampler.bytes, byte_limit)
            self.assertEqual('PROBE_BLOCKED' if byte_limit == 2 else 'BOUNDED_WINDOW_COMPLETE', sampler.ending)

    def test_elapsed_budget_does_not_fabricate_nominal_sampling_times(self):
        ticks = iter((0, 0, .1, .2, .5, .6, .7, 2))
        sampler = Sampler(lambda n: dict(returncode=0, stdout='', stderr=''),
                          clock=lambda: next(ticks), interval=0, duration=1)
        sampler.run()
        self.assertEqual(2, len(sampler.records))
        self.assertEqual(500, sampler.records[1]['gapMs'])
        self.assertAlmostEqual(100, sampler.records[0]['commandCostMs'])
        self.assertEqual('BOUNDED_WINDOW_COMPLETE', sampler.ending)


if __name__ == '__main__': unittest.main()
