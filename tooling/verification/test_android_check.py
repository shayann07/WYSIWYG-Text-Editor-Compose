"""Reject misleading instrumentation success, stale APKs and incomplete receipts."""

import unittest

from android_check import validate_result


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


if __name__ == "__main__":
    unittest.main()
