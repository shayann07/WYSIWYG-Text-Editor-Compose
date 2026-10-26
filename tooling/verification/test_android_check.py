"""Reject misleading instrumentation success, stale APKs and incomplete receipts."""

import unittest
import subprocess

from android_check import EXPECTED_GROUPS, validate_result, wait_for_services, wait_for_owned_emulator, wait_for_install_broadcasts


class InstallBroadcastReadiness(unittest.TestCase):
    def probe(self, api=b"37\n", output=b"All broadcast queues are idle!\r\n"):
        calls = []
        def run(label, *args, timeout):
            calls.append((label, args, timeout))
            if label == "install-platform-api":
                return api
            if isinstance(output, Exception):
                raise output
            return output
        return run, calls

    def test_android17_waits_for_actual_completion_once(self):
        run, calls = self.probe(output=b"Waiting for queues...\nAll broadcast queues are idle!\n")
        result = wait_for_install_broadcasts(run)
        self.assertEqual(result['status'], 'READY')
        self.assertEqual(calls[-1], ('install-broadcast-idle', ('shell', 'am', 'wait-for-broadcast-idle'), 90))
        self.assertEqual(len(calls), 2)

    def test_zero_exit_empty_error_or_embedded_marker_cannot_pass(self):
        for output in (b'', b'Error: unknown command', b'Not All broadcast queues are idle!',
                       b'All broadcast queues are idle!\nError: failed'):
            run, calls = self.probe(output=output)
            with self.assertRaises(ValueError):
                wait_for_install_broadcasts(run)
            self.assertEqual(len(calls), 2)

    def test_timeout_and_transport_failure_are_not_retried(self):
        for failure in (subprocess.TimeoutExpired('adb', 90), RuntimeError('transport failed')):
            run, calls = self.probe(output=failure)
            with self.assertRaises(type(failure)):
                wait_for_install_broadcasts(run)
            self.assertEqual(len(calls), 2)

    def test_older_api_keeps_existing_behavior(self):
        run, calls = self.probe(api=b'28')
        self.assertEqual(wait_for_install_broadcasts(run), {'status': 'NOT_APPLICABLE', 'api': 28})
        self.assertEqual(len(calls), 1)

    def test_unidentified_platform_cannot_skip_barrier(self):
        for api in (b'', b'Error: 37', b'37\n0'):
            run, calls = self.probe(api=api)
            with self.assertRaises(ValueError):
                wait_for_install_broadcasts(run)
            self.assertEqual(len(calls), 1)


class AndroidEvidenceContract(unittest.TestCase):
    def valid(self):
        return {"status": "PASS", "fatal": None, "api": 37, "apkSha256": "current",
                "groups": [{"name": str(i), "status": "PASS"} for i in range(EXPECTED_GROUPS)]}

    def test_current_complete_run(self):
        validate_result(self.valid(), "current")

    def test_missing_child_checks_cannot_pass(self):
        for size in (0, 27, EXPECTED_GROUPS - 1):
            result = self.valid()
            result["groups"] = result["groups"][:size]
            with self.assertRaises(ValueError):
                validate_result(result, "current")

    def test_readiness_is_never_qualification_pass(self):
        result = self.valid()
        result["status"] = "READY"
        with self.assertRaises(ValueError):
            validate_result(result, "current")

    def test_repeated_child_is_not_a_new_check(self):
        result = self.valid()
        result["groups"][-1] = result["groups"][0]
        with self.assertRaises(ValueError):
            validate_result(result, "current")

    def test_failure_or_fatal_cannot_pass(self):
        result = self.valid()
        result["groups"][3]["status"] = "FAIL"
        with self.assertRaises(ValueError):
            validate_result(result, "current")
        result = self.valid()
        result["fatal"] = "exception"
        with self.assertRaises(ValueError):
            validate_result(result, "current")

    def test_wrong_apk_or_unidentified_platform_cannot_pass(self):
        with self.assertRaises(ValueError):
            validate_result(self.valid(), "stale")
        result = self.valid()
        result.pop("api")
        with self.assertRaises(ValueError):
            validate_result(result, "current")


class AndroidServiceReadiness(unittest.TestCase):
    def run_trace(self, states, timeout=20):
        now = [0]
        seen = []
        def probe(label, *command, timeout):
            index = int(label.split("-")[1]) - 1
            seen.append(label)
            state = states[min(index, len(states) - 1)]
            if isinstance(state, Exception):
                raise state
            unlocked = state[1] if isinstance(state, tuple) else True
            state = state[0] if isinstance(state, tuple) else state
            if command[3] == "get-started-user-state":
                return b"RUNNING_UNLOCKED" if unlocked else b"RUNNING_LOCKED"
            if command[2] == "package":
                return b"package:/system/framework/framework-res.apk" if state else b"Error: service unavailable"
            return b"0"
        def sleep(seconds):
            now[0] += seconds
        result = wait_for_services(probe, clock=lambda: now[0], sleep=sleep, timeout=timeout)
        return result, seen

    def test_boot_flag_does_not_replace_service_response(self):
        result, seen = self.run_trace([False, True, True, True])
        self.assertEqual(result["attempts"], 4)
        self.assertEqual(len(seen), 12)

    def test_broken_pipe_resets_consecutive_readiness(self):
        result, _ = self.run_trace([True, RuntimeError("Broken pipe (32)"), True, True, True])
        self.assertEqual(result["attempts"], 5)
        self.assertEqual(result["consecutiveResponsiveProbes"], 3)

    def test_responsive_services_with_locked_user_cannot_start_install(self):
        result, _ = self.run_trace([(True, False), (True, False), True, True, True])
        self.assertEqual(result["attempts"], 5)
        self.assertEqual(result["unlockedUser"], 0)

    def test_unavailable_service_fails_with_deadline(self):
        with self.assertRaisesRegex(TimeoutError, "services"):
            self.run_trace([RuntimeError("Broken pipe (32)")], timeout=6)


class OwnedEmulatorReadiness(unittest.TestCase):
    def test_device_registers_after_initial_adb_absence(self):
        now = [0]
        calls = []
        def probe(label, *command, timeout):
            calls.append(label)
            if len(calls) < 3:
                raise RuntimeError("device not found")
            return b"owned-test\nOK\n"
        def sleep(seconds):
            now[0] += seconds
        result = wait_for_owned_emulator(probe, "owned-test", clock=lambda: now[0], sleep=sleep)
        self.assertEqual(result, {"attempts": 3, "name": "owned-test"})

    def test_wrong_identity_is_not_retried_or_admitted(self):
        calls = []
        def probe(label, *command, timeout):
            calls.append(label)
            return b"someone-elses-avd\nOK\n"
        with self.assertRaisesRegex(ValueError, "identity"):
            wait_for_owned_emulator(probe, "owned-test")
        self.assertEqual(len(calls), 1)

    def test_absent_device_cannot_wait_indefinitely(self):
        now = [0]
        def probe(*command, timeout):
            raise RuntimeError("device not found")
        def sleep(seconds):
            now[0] += seconds
        with self.assertRaisesRegex(TimeoutError, "register"):
            wait_for_owned_emulator(probe, "owned-test", clock=lambda: now[0], sleep=sleep, timeout=6)


if __name__ == "__main__":
    unittest.main()
