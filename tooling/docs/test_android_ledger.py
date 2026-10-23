"""Portable schema/traceability checks; ignored local receipts are not silently requalified."""
import copy
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def validate(record):
    if record.get('schemaVersion') != 1 or not re.fullmatch(r'[0-9a-f]{40}', record.get('repairSource', '')):
        raise ValueError('Identified source and schema required')
    if not re.fullmatch(r'[0-9a-f]{64}', record.get('apkSha256', '')):
        raise ValueError('Identified application artifact required')
    receipts = record.get('evidence', [])
    paths = {r.get('path') for r in receipts}
    if len(paths) != len(receipts): raise ValueError('Duplicate receipt identity')
    for receipt in receipts:
        if (not re.fullmatch(r'[0-9a-f]{64}', receipt.get('sha256', '')) or
                receipt.get('availability') != 'MACHINE_LOCAL_RECEIPT' or not receipt.get('meaning')):
            raise ValueError('Receipt hash and availability/scope required')
    values = record.get('capabilities', [])
    if len({v.get('id') for v in values}) != len(values): raise ValueError('Duplicate capability')
    for value in values:
        if value.get('status') not in {'PASS', 'PARTIAL', 'FAIL', 'BLOCKED', 'NOT RUN'}:
            raise ValueError('Explicit supported result status required')
        if not value.get('scope') or not value.get('remaining'): raise ValueError('Scope/limitations required')
        if value['status'] in {'PASS', 'PARTIAL', 'FAIL'} and not value.get('evidence'):
            raise ValueError('Measured outcome without evidence')
        if any(p not in paths for p in value.get('evidence', [])):
            raise ValueError('Unknown evidence reference')


class AndroidQualificationLedger(unittest.TestCase):
    def setUp(self):
        self.record = json.loads((ROOT / 'docs/reports/android-qualification-ledger-20261023.json').read_text())

    def test_identified_scoped_results(self): validate(self.record)

    def test_missing_receipt_unknown_status_and_unidentified_artifact_fail(self):
        for mode in ('receipt', 'status', 'artifact', 'scope'):
            bad = copy.deepcopy(self.record)
            if mode == 'receipt': bad['evidence'] = []
            if mode == 'status': bad['capabilities'][0]['status'] = 'READY'
            if mode == 'artifact': bad['apkSha256'] = ''
            if mode == 'scope': bad['capabilities'][0]['scope'] = ''
            with self.subTest(mode=mode), self.assertRaises(ValueError): validate(bad)


if __name__ == '__main__': unittest.main()
