import unittest
from pr_governance import ATTESTATIONS, SECTIONS, check


def fixture():
    # Synthetic parser input, never a submitted human attestation.
    body = "\n\n".join("## " + section + "\nFixture text" for section in SECTIONS)
    body += "\n" + "\n".join("- [x] " + text for text in ATTESTATIONS)
    return {"title": "Fixture", "body": body, "user": {"login": "fixture-contributor"}}


class GovernanceChecks(unittest.TestCase):
    def test_complete_structure(self):
        self.assertEqual([], check(fixture()))

    def test_each_missing_attestation_fails(self):
        for text in ATTESTATIONS:
            pr = fixture()
            pr["body"] = pr["body"].replace("- [x] " + text, "- [ ] " + text)
            self.assertTrue(check(pr))

    def test_comment_only_disclosure_is_empty(self):
        pr = fixture()
        pr["body"] = pr["body"].replace("## AI Assistance Disclosure\nFixture text", "## AI Assistance Disclosure\n<!-- None -->")
        self.assertTrue(check(pr))

    def test_summary_marker_in_comment_fails(self):
        pr = fixture()
        pr["body"] += "\n<!-- walkthrough_start -->"
        self.assertTrue(check(pr))

    def test_dependabot_only_exemption(self):
        self.assertEqual([], check({"user": {"login": "dependabot[bot]"}}))
        self.assertTrue(check({"user": {"login": "dependabot"}}))


if __name__ == "__main__":
    unittest.main()
