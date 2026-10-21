"""Reject misleading instrumentation success, stale APKs and incomplete receipts."""

import unittest

from android_check import validate_result, wait_for_services


class AndroidEvidenceContract(unittest.TestCase):
    def valid(self):
        return {"status": "PASS", "fatal": None, "api": 37, "apkSha256": "current",
                "groups": [{"name": str(i), "status": "PASS"} for i in range(27)]}

    def test_current_complete_run(self):
        validate_result(self.valid(), "current")

    def test_missing_child_checks_cannot_pass(self):
        for size in (0, 25, 26):
            result = self.valid()
            result["groups"] = result["groups"][:size]
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
        self.assertEqual(len(seen), 8)

    def test_broken_pipe_resets_consecutive_readiness(self):
        result, _ = self.run_trace([True, RuntimeError("Broken pipe (32)"), True, True, True])
        self.assertEqual(result["attempts"], 5)
        self.assertEqual(result["consecutiveResponsiveProbes"], 3)

    def test_unavailable_service_fails_with_deadline(self):
        with self.assertRaisesRegex(TimeoutError, "services"):
            self.run_trace([RuntimeError("Broken pipe (32)")], timeout=6)


if __name__ == "__main__":
    unittest.main()
