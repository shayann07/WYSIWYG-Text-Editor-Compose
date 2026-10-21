import unittest

from android_process import EXPECTED_TEXT, PROPOSAL, check_restore, receipt_if_ready, validated_pid


class AndroidProcessWitnessContract(unittest.TestCase):
    def test_missing_receipt_is_not_a_malformed_receipt_or_pass(self):
        self.assertIsNone(receipt_if_ready(b"cat: files/receipt.json: No such file or directory\n"))
        self.assertEqual({"stage": "READY"}, receipt_if_ready(b'{"stage":"READY"}'))
        for raw in (b"{", b"cat: denied: Permission denied", b"not JSON"):
            with self.assertRaises(ValueError):
                receipt_if_ready(raw)

    def test_never_kills_an_unidentified_replaced_or_multiple_process(self):
        self.assertEqual("42", validated_pid(b"42\n", 42))
        for response, expected in [(b"", 42), (b"43", 42), (b"42 43", 42), (b"1", 1)]:
            with self.assertRaises(ValueError):
                validated_pid(response, expected)

    def test_requires_fresh_process_exact_canonical_and_separate_proposal(self):
        result = dict(status="PASS", sourcePid=42, restoredPid=43, canonicalText=EXPECTED_TEXT,
                      separateProposalText=EXPECTED_TEXT + PROPOSAL, independentDescriptionChecked=True,
                      newUnsavedOwner=True)
        check_restore(result, 42)
        for key, value in [("restoredPid", 42), ("canonicalText", EXPECTED_TEXT + PROPOSAL),
                           ("independentDescriptionChecked", False), ("newUnsavedOwner", False)]:
            wrong = dict(result, **{key: value})
            with self.assertRaises(ValueError):
                check_restore(wrong, 42)


if __name__ == "__main__":
    unittest.main()
