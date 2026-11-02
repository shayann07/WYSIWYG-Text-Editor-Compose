"""Owned ZIP fixtures prove cross-job provenance before any APK is published."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import android_ci_transfer as transfer


class SameRunBinaryTransfer(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ctx = dict(schemaVersion=1, repository='owner/repo', repositoryId=42,
                        runId=123, runAttempt=2, sourceHead='a'*40, checkoutCommit='b'*40)
        self.inputs = {'core/model/Owned.kt': 'c'*64}
        self.build = self.root / 'producer'
        (self.build / 'apks').mkdir(parents=True)
        self.payloads = {role: ('owned-' + role).encode() for role in transfer.ROLES}
        self.identity = {role: {'original': '/producer/' + role, 'sha256': hashlib.sha256(data).hexdigest()}
                         for role, data in self.payloads.items()}
        self.consumer = dict(status='PASS', sourceCommit='b'*40, sourceInputs=self.inputs,
                             androidArtifacts={self.identity[r]['original']: self.identity[r]['sha256'] for r in ['app','test']},
                             androidPeer=dict(applicationId='dev.shayxo.composedoc.androidsample.peerconsumer',
                                              artifacts={'/producer/peer': self.identity['peer']['sha256']}))
        (self.build/'results.json').write_text(json.dumps(self.consumer), encoding='utf-8')
        (self.build/'apks/identity.json').write_text(json.dumps(self.identity), encoding='utf-8')
        for role, data in self.payloads.items():
            (self.build/'apks'/f'{role}.apk').write_bytes(data)
        transfer.pack(self.build, self.ctx, self.inputs)
        self.metadata = dict(id=99, expired=False, name=transfer.artifact_name(self.ctx),
                             workflow_run=dict(id=123, repository_id=42, head_sha='a'*40))

    def bundle(self, name, changes=None, missing=None):
        archive = self.root / (name + '.zip')
        with zipfile.ZipFile(archive, 'w') as z:
            for source in self.build.rglob('*'):
                if not source.is_file():
                    continue
                key = source.relative_to(self.build).as_posix()
                if key == missing:
                    continue
                z.writestr(key, (changes or {}).get(key, source.read_bytes()))
            z.writestr('../../outside-owned.txt', b'must not extract')
        return archive

    def receive(self, name, changes=None, missing=None):
        return transfer.receive(self.bundle(name, changes, missing), self.metadata, 99,
                                self.root/name, self.root/(name+'-apks'), self.ctx, self.inputs)

    def test_current_merge_source_and_bytes_relocate_without_a_second_build(self):
        record = self.receive('valid')
        self.assertEqual('PASS_TRANSFER_ONLY', record['status'])
        for role, expected in self.payloads.items():
            self.assertEqual(expected, (self.root/'valid-apks'/transfer.DESTINATIONS[role]).read_bytes())
        self.assertEqual((self.build/'results.json').read_bytes(), (self.root/'valid/original-consumer.json').read_bytes())
        self.assertFalse((self.root.parent/'outside-owned.txt').exists())
        self.assertEqual('a'*40, record['provenance']['sourceHead'])
        self.assertEqual('b'*40, record['provenance']['checkoutCommit'])

    def test_wrong_repository_run_source_artifact_attempt_and_expiration_block(self):
        for key, value in [('id', 100), ('expired', True), ('name', transfer.artifact_name(dict(self.ctx, runAttempt=1)))]:
            bad = copy.deepcopy(self.metadata); bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                transfer.validate_metadata(bad, 99, self.ctx)
        for key, value in [('id',124),('repository_id',43),('head_sha','d'*40)]:
            bad = copy.deepcopy(self.metadata); bad['workflow_run'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                transfer.validate_metadata(bad,99,self.ctx)
        for value in (None, True, 0):
            with self.subTest(artifact=value), self.assertRaises(ValueError):
                transfer.validate_metadata(self.metadata,value,self.ctx)

    def test_provenance_context_and_hash_mismatch_never_publish_apks(self):
        original = json.loads((self.build/'provenance.json').read_text(encoding='utf-8'))
        cases = [('runAttempt',1),('runAttempt',True),('runId',124),('repositoryId',43),
                 ('sourceHead','d'*40),('checkoutCommit','d'*40),('repository','other/repo'),
                 ('schemaVersion',2),('consumerReceiptSha256','0'*64)]
        for index, (key, value) in enumerate(cases):
            bad = dict(original, **{key:value})
            name = 'bad-' + key + '-' + str(index)
            with self.subTest(key=key,value=value), self.assertRaises(ValueError):
                self.receive(name, {'provenance.json': json.dumps(bad).encode()})
            self.assertFalse((self.root/(name+'-apks')).exists())
            self.assertEqual('FAIL', json.loads((self.root/name/'transfer.json').read_text())['status'])

    def test_corrupt_missing_or_duplicate_role_rejected(self):
        with self.assertRaises(ValueError):
            self.receive('corrupt', {'apks/app.apk': b'changed'})
        with self.assertRaises(ValueError):
            self.receive('missing', missing='apks/test.apk')
        archive = self.bundle('duplicate')
        with zipfile.ZipFile(archive,'a') as z, self.assertWarns(UserWarning):
            z.writestr('apks/app.apk',self.payloads['app'])
        with self.assertRaises(ValueError):
            transfer.receive(archive,self.metadata,99,self.root/'duplicate',self.root/'duplicate-apks',self.ctx,self.inputs)
        original_write = Path.write_text
        def write(path, text, **kwargs):
            if path.name == 'transfer.json':
                raise OSError('injected receipt failure')
            return original_write(path, text, **kwargs)
        with patch.object(Path,'write_text',write), self.assertRaisesRegex(ValueError,'APK hash mismatch') as caught:
            self.receive('corrupt-with-io',{'apks/app.apk':b'changed'})
        self.assertIn('receipt failure',caught.exception.__notes__[0])

    def test_coherent_changed_receipt_cannot_launder_foreign_source_inputs(self):
        bad = copy.deepcopy(self.consumer); bad['sourceInputs']['core/model/Owned.kt'] = 'd'*64
        raw = json.dumps(bad).encode()
        provenance = json.loads((self.build/'provenance.json').read_text()); provenance['consumerReceiptSha256'] = hashlib.sha256(raw).hexdigest()
        with self.assertRaisesRegex(ValueError,'source/checkout'):
            self.receive('changed-source', {'results.json':raw,'provenance.json':json.dumps(provenance).encode()})

    def test_producer_changed_bytes_and_wrong_actual_checkout_are_rejected(self):
        (self.build/'apks/app.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'bytes changed'):
            transfer.pack(self.build,self.ctx,self.inputs)
        env={'GITHUB_REPOSITORY':'owner/repo','GITHUB_REPOSITORY_ID':'42','GITHUB_RUN_ID':'123',
             'GITHUB_RUN_ATTEMPT':'2','CI_SOURCE_HEAD':'a'*40,'GITHUB_SHA':'b'*40}
        with patch.dict('os.environ',env), patch.object(transfer.subprocess,'check_output',return_value='d'*40):
            with self.assertRaisesRegex(ValueError,'Checkout differs'):
                transfer.context()
