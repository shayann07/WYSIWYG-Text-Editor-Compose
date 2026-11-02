"""Disposable ZIPs exercise production relocation validation without network/APKs."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import android_trial_artifacts as trial


class HostedArtifactRelocation(unittest.TestCase):
    def metadata(self):
        return {'id': trial.ARTIFACT, 'expired': False,
                'workflow_run': {'id': trial.RUN, 'head_sha': trial.SOURCE_HEAD,
                                 'repository_id': 1421229803, 'head_repository_id': 1421229803}}

    def test_foreign_expired_or_wrong_head_artifact_is_rejected(self):
        for key, value in [('id', 1), ('expired', True)]:
            metadata = self.metadata()
            metadata[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                trial.validate_metadata(metadata)
        for key, value in [('id', 1), ('head_sha', 'different'), ('repository_id', 1), ('head_repository_id', 1)]:
            metadata = self.metadata()
            metadata['workflow_run'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                trial.validate_metadata(metadata)

    def exercise(self, mode=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'input.zip'
            output = root / 'output'
            payloads = {r: ('owned-' + r).encode() for r in ('app', 'test', 'peer')}
            hashes = {r: hashlib.sha256(b).hexdigest() for r, b in payloads.items()}
            original = {'status': 'PASS', 'androidArtifacts': {'/old/app': hashes['app'], '/old/test': hashes['test']},
                        'androidPeer': {'applicationId': 'dev.shayxo.composedoc.androidsample.peerconsumer',
                                        'artifacts': {'/old/peer': hashes['peer']}}}
            if mode == 'failed-consumer':
                original['status'] = 'FAIL'
            identity = {r: {'original': '/old/' + r, 'sha256': h} for r, h in hashes.items()}
            if mode == 'stale-receipt':
                original['androidArtifacts']['/old/app'] = 'bad'
            with zipfile.ZipFile(archive, 'w') as bundle:
                bundle.writestr(trial.PREFIX + 'results.json', json.dumps(original))
                bundle.writestr(trial.PREFIX + 'apks/identity.json', json.dumps(identity))
                for role, data in payloads.items():
                    if mode == 'missing' and role == 'peer':
                        continue
                    bundle.writestr(trial.PREFIX + 'apks/' + role + '.apk', b'changed' if mode == 'corrupt' and role == 'app' else data)
                bundle.writestr('../../must-not-extract.txt', 'not extracted')
            with patch.object(trial, 'HASHES', hashes):
                if mode:
                    with self.assertRaises(ValueError):
                        trial.relocate(archive, output, self.metadata())
                    self.assertFalse((output / 'consumer.json').exists())
                    self.assertEqual([], list(output.rglob('*.apk')))
                    self.assertEqual('FAIL', json.loads((output / 'relocation.json').read_text())['status'])
                else:
                    result = trial.relocate(archive, output, self.metadata())
                    self.assertEqual('PASS_RELOCATION_ONLY', result['status'])
                    relocated = json.loads((output / 'consumer.json').read_text())
                    self.assertEqual(original, json.loads((output / 'original-consumer.json').read_text()))
                    self.assertEqual(2, len(relocated['androidArtifacts']))
                    for role, data in payloads.items():
                        self.assertEqual(data, (output / trial.DESTINATIONS[role]).read_bytes())
                    self.assertFalse((root.parent / 'must-not-extract.txt').exists())

    def test_exact_roles_receipts_and_bytes_relocated_without_extracting_unrelated_paths(self):
        self.exercise()

    def test_bad_payload(self):
        self.exercise('corrupt')

    def test_missing_payload(self):
        self.exercise('missing')

    def test_failed_original_build(self):
        self.exercise('failed-consumer')

    def test_stale_original_association(self):
        self.exercise('stale-receipt')
