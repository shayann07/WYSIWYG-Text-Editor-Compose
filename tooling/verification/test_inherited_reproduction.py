"""Independent corruption tests for the sealed-result comparison boundary."""
import copy
import json
from pathlib import Path
import unittest

from reproduce_inherited import compare


class ReproductionContract(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[2] / 'tests/reviews/v6-finalization-20261019/results.json'
        self.sealed = json.loads(path.read_text(encoding='utf-8'))

    def test_all_actual_sealed_groups_and_metadata_compare(self):
        self.assertEqual('PASS', compare(self.sealed, self.sealed, 16)['status'])

    def test_missing_duplicate_extra_or_renamed_group_is_rejected(self):
        for mode in ('missing', 'duplicate', 'extra', 'rename'):
            fresh = copy.deepcopy(self.sealed)
            values = fresh['results']
            if mode == 'missing': values.pop()
            if mode == 'duplicate': values[-1] = values[0]
            if mode == 'extra': values.append(dict(name='invented', status='PASS'))
            if mode == 'rename': values[-1]['name'] = 'invented'
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                compare(self.sealed, fresh, 16)

    def test_failure_and_deterministic_measurement_are_not_ignored(self):
        for key, value in [('status', 'FAIL'), ('detail', {'time': 123, 'runtime': 'fake'})]:
            fresh = copy.deepcopy(self.sealed)
            fresh['results'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                compare(self.sealed, fresh, 16)

    def test_hash_scope_and_unknown_metadata_cannot_drift(self):
        for key, value in [('sourceHashes', {}), ('limits', []), ('newIgnoredField', True)]:
            fresh = copy.deepcopy(self.sealed); fresh[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                compare(self.sealed, fresh, 16)

    def test_environment_drift_is_partial_and_does_not_hide_failure(self):
        fresh = copy.deepcopy(self.sealed); fresh['runtime'] = 'v0.0.0'
        self.assertEqual('PARTIAL', compare(self.sealed, fresh, 16)['status'])
        fresh['results'][0]['status'] = 'FAIL'
        with self.assertRaises(ValueError): compare(self.sealed, fresh, 16)

    def test_icu_observation_is_separate_from_segmentation_results(self):
        path = Path(__file__).resolve().parents[2] / 'tests/reviews/v6-finalization-20261019/inherited-results.json'
        sealed = json.loads(path.read_text(encoding='utf-8'))
        fresh = copy.deepcopy(sealed)
        corpus = next(item for item in fresh['results'] if item['name'] == 'unicodeCorpus')
        corpus['detail']['icu'] = 'different'
        self.assertEqual('PARTIAL', compare(sealed, fresh, 25)['status'])
        corpus['detail']['cases'] = -1
        with self.assertRaises(ValueError): compare(sealed, fresh, 25)


if __name__ == '__main__': unittest.main()
