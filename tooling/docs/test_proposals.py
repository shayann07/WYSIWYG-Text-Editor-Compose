import copy
import json
from pathlib import Path
import unittest

from validate_proposals import ROOT, validate


class ProposalContract(unittest.TestCase):
    def setUp(self):
        self.record = json.loads((ROOT / 'docs/proposals/v6-g1-clarification-v1.json').read_text())

    def test_draft_references_and_authority(self):
        self.assertEqual('v6-g1-clarification-v1', validate(self.record))

    def test_no_self_approval_or_frozen_rewrite(self):
        for key, value in [('authoritative', True), ('ownerAcceptance', 'invented'), ('supersedesFrozenBytes', True)]:
            bad = copy.deepcopy(self.record); bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate(bad)

    def test_missing_escaping_reference_and_definition_are_rejected(self):
        for references in [['missing-file'], ['../AGENTS.md'], []]:
            bad = copy.deepcopy(self.record); bad['references'] = references
            with self.assertRaises(ValueError): validate(bad)
        bad = dict(self.record, definition='')
        with self.assertRaises(ValueError): validate(bad)

    def test_invariant_coverage_and_known_gate_mapping(self):
        mapping = json.loads((ROOT / 'docs/proposals/v6-invariant-gates-v1.json').read_text())
        self.assertEqual('v6-invariant-gates-v1', validate(mapping))
        for mode in ('missing', 'unknown', 'wrong', 'duplicate'):
            bad = copy.deepcopy(mapping)
            if mode == 'missing': del bad['invariants']['I17']
            if mode == 'unknown': bad['invariants']['I17'] = ['G99']
            if mode == 'wrong': bad['invariants']['I15'] = ['G6']
            if mode == 'duplicate': bad['invariants']['I01'] = ['G1', 'G1']
            with self.subTest(mode=mode), self.assertRaises(ValueError): validate(bad)


if __name__ == '__main__': unittest.main()
