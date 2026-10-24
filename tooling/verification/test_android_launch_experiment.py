import copy
import json
from pathlib import Path
import subprocess
import unittest
import tempfile
import signal
from unittest.mock import patch

from android_check import prepare_instrumentation_launch, process_identity, verify_attachment, failure_root_observation, instrument_clock, instrumentation_parser_capability
from android_launch_experiment import validate_plan, execute_trials, classify, SafetyStop, run_owned, stop_owned_emulator, startup_lmk, qualification_complete

PLAN = json.loads((Path(__file__).with_name('android_launch_trial_plan.json')).read_text())


class LaunchProbe:
    def __init__(self):
        self.calls = []
        self.pid = ''
        self.birth = '1000'
        self.launch = b'Status: ok\nLaunchState: COLD\n'
        self.score = b'0'
        self.help = b'am instrument --no-restart'
        self.parser_supported = True
        self.pid_error = b''

    def __call__(self, label, *command, **kwargs):
        self.calls.append((label, command, kwargs))
        if label.endswith('-pid'):
            return subprocess.CompletedProcess(command, 0 if self.pid else 1, self.pid.encode(), self.pid_error)
        if label.endswith('-stat'):
            stat = f'{self.pid} (owned android) S ' + '0 '*18 + self.birth+'\n'
            return subprocess.CompletedProcess(command, 0, stat.encode(), b'')
        if label == 'attach-cli-help': return self.help
        if label.startswith('attach-parser-'):
            flag = command[-1]
            error = (f'Error: Argument expected after "{flag}"\n'
                     if flag == '--no-restart' and self.parser_supported else f'Error: Unknown option: {flag}\n')
            return subprocess.CompletedProcess(command, 1 if self.parser_supported else 0, b'', error.encode())
        if label == 'ordinary-cold-activity':
            self.pid = '123'
            return self.launch
        if label == 'before-attach-foreground':
            return b'topResumedActivity=ActivityRecord{dev.shayxo.composedoc.androidsample/.MainActivity}'
        if label == 'before-attach-importance': return self.score
        raise AssertionError(label)


class ForegroundAttachment(unittest.TestCase):
    def test_default_cold_mode_preserves_original_launch_without_extra_actions(self):
        probe = LaunchProbe()
        self.assertEqual('cold', prepare_instrumentation_launch(probe, 'cold', 'owned')['mode'])
        self.assertEqual([], probe.calls)

    def test_ordinary_cold_launch_then_same_pid_and_birth_are_required(self):
        probe = LaunchProbe()
        result = prepare_instrumentation_launch(probe, 'foreground-attach', 'adapter-owned')
        self.assertEqual({'pid':'123', 'startTicks':'1000'}, result['before'])
        self.assertEqual('bootstrap-adapter-owned', result['bootstrapRoot'])
        verify_attachment(probe, result)
        self.assertTrue(result['sameProcessVerified'])
        probe.birth = '2000'
        with self.assertRaisesRegex(RuntimeError, 'changed owned PID/birth'):
            verify_attachment(probe, result)

    def test_preexisting_or_denied_process_is_not_treated_as_absent(self):
        for existing, error in [('456', b''), ('', b'Permission denied')]:
            probe = LaunchProbe(); probe.pid = existing; probe.pid_error = error
            with self.assertRaisesRegex(RuntimeError, 'absent target|PID query failed'):
                prepare_instrumentation_launch(probe, 'foreground-attach', 'owned')
            self.assertFalse(any(c[0]=='ordinary-cold-activity' for c in probe.calls))

    def test_unsupported_flag_or_failed_cold_launch_never_falls_back_or_retries(self):
        for flag in (False, True):
            probe = LaunchProbe()
            if flag: probe.launch = b'Error: ordinary launch failed\n'
            else: probe.parser_supported = False
            with self.assertRaisesRegex(RuntimeError, 'no fallback|cold Activity launch failed'):
                prepare_instrumentation_launch(probe, 'foreground-attach', 'owned')
            self.assertEqual(1 if flag else 0, sum(c[0]=='ordinary-cold-activity' for c in probe.calls))

    def test_misleading_captured_general_help_does_not_reject_supported_parser(self):
        # Beginning of retained c1/installed-activity-cli.stdout from run40083012189.
        probe = LaunchProbe()
        probe.help = b'Activity manager (activity) commands:\n  help\n      Print this help text.\n'
        result = prepare_instrumentation_launch(probe, 'foreground-attach', 'owned')
        self.assertEqual('RECOGNIZED_COMPONENT_REQUIRED',result['parserCapability']['status'])
        self.assertFalse(any(c[0]=='attach-cli-help' for c in probe.calls))
        calls = [c for c in probe.calls if c[0].startswith('attach-parser-')]
        self.assertEqual(2,len(calls))
        self.assertTrue(all(c[1][:3] == ('shell','am','instrument') and len(c[1]) == 4 for c in calls))
        self.assertEqual([1,1],[p['returncode'] for p in result['parserCapability']['probes']])

    def test_ambiguous_or_unavailable_parser_outputs_are_not_recognition(self):
        variants = [(0,b'',b''), (1,b'',b'Permission denied\n'),
                    (1,b'',b'Error: Argument expected after "--no-restart"\nError: transport failure\n'),
                    (2,b'',b'Error: Argument expected after "--no-restart"\n')]
        for code,stdout,stderr in variants:
            with self.subTest(code=code,stderr=stderr):
                calls=[]; record={}
                def probe(label,*command,**kwargs):
                    calls.append(command)
                    if label.endswith('recognized'):return subprocess.CompletedProcess(command,code,stdout,stderr)
                    return subprocess.CompletedProcess(command,0,b'',b'Error: Unknown option: --composedoc-parser-unknown-20261024\n')
                with self.assertRaisesRegex(RuntimeError,'unsupported or ambiguous'):
                    instrumentation_parser_capability(probe,record)
                self.assertEqual('BLOCKED',record['status'])
                self.assertEqual(2,len(calls))
                self.assertEqual(stderr.decode(),record['probes'][0]['stderr'])

    def test_unknown_control_must_reject_its_exact_option(self):
        record={}
        def probe(label,*command,**kwargs):
            return subprocess.CompletedProcess(command,1,b'',b'Error: Argument expected after "--no-restart"\n')
        with self.assertRaisesRegex(RuntimeError,'unsupported or ambiguous'):
            instrumentation_parser_capability(probe,record)
        self.assertEqual('BLOCKED',record['status'])

    def test_system_assigned_importance_is_observed_not_forced(self):
        probe = LaunchProbe(); probe.score = b'700'
        with self.assertRaisesRegex(RuntimeError, 'system-assigned foreground importance'):
            prepare_instrumentation_launch(probe, 'foreground-attach', 'owned')
        importance = [c[1] for c in probe.calls if c[0]=='before-attach-importance']
        self.assertEqual([('shell','cat','/proc/123/oom_score_adj')], importance)

    def test_missing_root_is_distinguished_from_permission_denial(self):
        def absent(*args, **kwargs): return subprocess.CompletedProcess([], 1, b'', b'')
        def denied(*args, **kwargs): return subprocess.CompletedProcess([], 1, b'', b'Permission denied')
        self.assertEqual('ABSENT', failure_root_observation(absent, 'adapter-owned')['state'])
        self.assertEqual('UNAVAILABLE', failure_root_observation(denied, 'adapter-owned')['status'])

    def test_pid_reuse_without_restart_does_not_satisfy_attachment(self):
        probe = LaunchProbe()
        result = prepare_instrumentation_launch(probe, 'foreground-attach', 'owned')
        probe.pid = '456'
        with self.assertRaisesRegex(RuntimeError, 'changed owned PID/birth'):
            verify_attachment(probe, result)

    def test_actual_clock_helper_preserves_raw_bounds_and_rejects_unsupported_format(self):
        calls = []
        def probe(label, *command, **kwargs):
            calls.append(command)
            return b'1000.123456789' if label.endswith('-epoch') else b'50.25 10.0'
        result = instrument_clock(probe, 'owned-clock')
        self.assertEqual('AVAILABLE',result['status'])
        self.assertEqual('1000.123456789',result['deviceEpoch'])
        self.assertEqual(('shell','date','+%s.%N'),calls[0])
        invalid = instrument_clock(lambda *a, **k:b'1000.%N','unsupported')
        self.assertEqual('UNAVAILABLE',invalid['status'])
        self.assertNotIn('deviceEpoch',invalid)


class FixedTrialExecution(unittest.TestCase):
    def configuration(self, item):
        return dict(fingerprint=PLAN['referenceFingerprint'],memoryTotalKiB=6067476, requestedRamMiB=6144, cpuCores=2,
                    device='pixel_7', dataPath='owned-data-'+item['id'])

    def test_plan_is_exactly_cold_warm_cold_warm(self):
        validate_plan(PLAN)
        for trials in (PLAN['trials'][:3], list(reversed(PLAN['trials']))):
            changed = copy.deepcopy(PLAN); changed['trials'] = trials
            with self.assertRaisesRegex(ValueError, 'C,W,C,W'): validate_plan(changed)

    def test_all_four_run_once_and_control_failures_remain_failures(self):
        calls = []
        def trial(item):
            calls.append(item['id'])
            return dict(self.configuration(item),status='FINISHED', qualification='FAIL' if item['arm']=='C' else 'PASS',
                        startupLmk=item['arm']=='C', sameProcessVerified=item['arm']=='W')
        result = execute_trials(PLAN, trial, lambda r: None)
        self.assertEqual(['c1','w1','c2','w2'], calls)
        self.assertEqual(['FAIL','PASS','FAIL','PASS'], [r['qualification'] for r in result['trials']])
        self.assertEqual('BOUNDED_LAUNCH_MECHANISM_SUPPORTED', result['finding'])
        self.assertNotEqual('PASS', result['status'])

    def test_nonreproducing_control_or_failed_treatment_is_inconclusive_without_expansion(self):
        for bad_id in ('c1','w2'):
            calls = []
            def trial(item):
                calls.append(item['id'])
                passing = item['arm']=='W'
                if item['id']==bad_id: passing = not passing
                return dict(self.configuration(item),status='FINISHED', qualification='PASS' if passing else 'FAIL',
                            startupLmk=item['arm']=='C' and not passing, sameProcessVerified=item['arm']=='W')
            result = execute_trials(PLAN, trial, lambda r: None)
            self.assertEqual(4, len(calls))
            self.assertEqual('INCONCLUSIVE', result['finding'])

    def test_safety_failure_aborts_without_touching_remaining_devices(self):
        calls = []
        def trial(item):
            calls.append(item['id'])
            raise SafetyStop('foreign emulator identity')
        result = execute_trials(PLAN, trial, lambda r: None)
        self.assertEqual(['c1'], calls)
        self.assertEqual(['BLOCKED','NOT RUN','NOT RUN','NOT RUN'], [r['status'] for r in result['trials']])
        self.assertEqual('INCONCLUSIVE', result['finding'])

    def test_late_kill_cursor_failure_and_unknown_root_are_not_startup_lmk(self):
        log = ("100.5 600 600 I ActivityManager: Start proc 123:dev.shayxo.composedoc.androidsample/u0a229 for added application\n"
               "102.0 200 200 I lowmemorykiller: Kill 'dev.shayxo.composedoc.androidsample' (123), uid 10229, oom_score_adj 700 to free 100kB")
        base = dict(status='FAIL', failureRootObservation=dict(status='AVAILABLE',state='ABSENT'),
                    instrumentationWindow=dict(start=dict(status='AVAILABLE',deviceEpoch='100.0'),end=dict(status='AVAILABLE',deviceEpoch='110.0')))
        startup = dict(records=[dict(fields=dict(target=dict(status='AVAILABLE',pids=['123']),
                       wall_start=dict(status='AVAILABLE',epoch='101.0'),wall_end=dict(status='AVAILABLE',epoch='101.2')))])
        self.assertTrue(startup_lmk(base, log, 'Process crashed',startup))
        for field in ('instrumented','failureRootObservation'):
            changed = copy.deepcopy(base)
            changed[field] = {'groups':[{'name':'tap','status':'FAIL'}]} if field=='instrumented' else {'status':'UNAVAILABLE'}
            self.assertFalse(startup_lmk(changed, log, 'Process crashed',startup))
        changed = copy.deepcopy(base); changed['failureRootObservation']['state']='PRESENT'
        self.assertFalse(startup_lmk(changed, log, 'Process crashed',startup))
        self.assertFalse(startup_lmk(base, log.replace('oom_score_adj 700','oom_score_adj 0'), 'Process crashed',startup))
        self.assertFalse(startup_lmk(base, log.replace('(123)', '(456)'), 'Process crashed',startup))
        self.assertFalse(startup_lmk(base, log.replace('102.0 ', '99.0 '), 'Process crashed',startup))
        self.assertFalse(startup_lmk(base, log.replace('100.5 ', '98.5 '), 'Process crashed',startup))
        self.assertFalse(startup_lmk(base, log.replace('102.0 ', '111.0 '), 'Process crashed',startup))
        self.assertFalse(startup_lmk(base, log, 'Process crashed',{}))
        stale = copy.deepcopy(startup); stale['records'][0]['fields']['wall_start']['epoch']='99.0'
        self.assertFalse(startup_lmk(base, log, 'Process crashed',stale))
        early = copy.deepcopy(startup); early['records'][0]['fields']['wall_start']['epoch']='100.1'; early['records'][0]['fields']['wall_end']['epoch']='100.2'
        self.assertFalse(startup_lmk(base, log, 'Process crashed',early))

    def test_written_pass_receipt_followed_by_timeout_or_failed_command_is_not_pass(self):
        receipt = dict(status='PASS',instrumented=dict(status='PASS',groups=[dict(name=str(i),status='PASS') for i in range(66)]),
                       processInterruption=dict(status='PASS'),afterCloseInterruption=dict(status='PASS'))
        clip = dict(status='PASS'); command = dict(exitCode=0,timedOut=False)
        self.assertTrue(qualification_complete(receipt,clip,command,command))
        for wrong in (dict(exitCode=1,timedOut=False),dict(exitCode=0,timedOut=True),dict(exitCode=0)):
            self.assertFalse(qualification_complete(receipt,clip,wrong,command))
            self.assertFalse(qualification_complete(receipt,clip,command,wrong))

    def test_missing_or_duplicate_arm_identity_cannot_support_finding(self):
        rows = [dict(id='w1',arm='W',status='FINISHED',qualification='PASS',sameProcessVerified=True)]*4
        self.assertEqual('INCONCLUSIVE', classify(rows))

    def test_cleanup_failure_does_not_erase_a_completed_qualification(self):
        def trial(item):
            raise SafetyStop('cleanup uncertain',dict(qualification='PASS',adapterStatus='PASS'))
        result = execute_trials(PLAN, trial, lambda r: None)
        self.assertEqual('BLOCKED',result['trials'][0]['status'])
        self.assertEqual('PASS',result['trials'][0]['qualification'])
        self.assertEqual('INCONCLUSIVE',result['finding'])

    def test_different_image_or_reused_data_makes_the_comparison_inconclusive(self):
        rows = [dict(item,**self.configuration(item),status='FINISHED',
                     qualification='FAIL' if item['arm']=='C' else 'PASS',
                     startupLmk=item['arm']=='C',sameProcessVerified=item['arm']=='W') for item in PLAN['trials']]
        self.assertEqual('BOUNDED_LAUNCH_MECHANISM_SUPPORTED', classify(rows))
        rows[1]['fingerprint']='different-image'
        self.assertEqual('INCONCLUSIVE', classify(rows))
        rows[1]['fingerprint']=PLAN['referenceFingerprint']; rows[1]['dataPath']=rows[0]['dataPath']
        self.assertEqual('INCONCLUSIVE', classify(rows))


class OwnedCleanup(unittest.TestCase):
    def test_timed_out_child_runs_once_and_only_its_session_is_terminated(self):
        class Child:
            pid = 9001
            returncode = None
            calls = 0
            def poll(self): return self.returncode
            def wait(self, timeout):
                self.calls += 1
                if self.calls == 1: raise subprocess.TimeoutExpired('owned', timeout)
                self.returncode = -15
                return -15
        child = Child()
        with tempfile.TemporaryDirectory(prefix='composedoc-owned-child-') as directory, \
                patch('android_launch_experiment.subprocess.Popen', return_value=child) as spawn, \
                patch('android_launch_experiment.os.getpgid', return_value=9001, create=True), \
                patch('android_launch_experiment.os.killpg', create=True) as terminate:
            result = run_owned(['owned-child'], Path(directory), 'single', timeout=.01, env={})
            self.assertEqual(1, spawn.call_count)
            self.assertTrue(spawn.call_args.kwargs['start_new_session'])
            terminate.assert_called_once_with(9001, signal.SIGTERM)
            self.assertTrue(result['timedOut'])
            self.assertEqual(-15, result['exitCode'])

    def test_owned_emulator_is_stopped_once_and_foreign_identity_is_refused(self):
        class Child:
            pid = 9002
            def poll(self): return None
            def wait(self, timeout): return 0
        calls = []
        def runner(command, **kwargs):
            calls.append(command)
            if command[-1]=='name': return subprocess.CompletedProcess(command,0,b'owned-avd\nOK\n',b'')
            if command[-1]=='get-state': return subprocess.CompletedProcess(command,1,b'',b'no device')
            return subprocess.CompletedProcess(command,0,b'OK',b'')
        result = stop_owned_emulator(Child(),'owned-adb','emulator-5580','owned-avd',{},runner=runner)
        self.assertEqual('STOPPED', result['status'])
        self.assertEqual(1, sum(command[-1]=='kill' for command in calls))
        calls.clear()
        with self.assertRaisesRegex(SafetyStop, 'foreign emulator'):
            stop_owned_emulator(Child(),'owned-adb','emulator-5580','different-avd',{},runner=runner)
        self.assertFalse(any(command[-1]=='kill' for command in calls))


if __name__ == '__main__': unittest.main()
