"""Identity must match independent declared platform and package expectations."""
import unittest
import tempfile
from pathlib import Path
import zipfile

from android_trial_identity import device_record, image_record, verify_installed_archive


class TrialIdentity(unittest.TestCase):
    def test_installed_image_must_match_pinned_archive_bytes_not_just_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'image.zip'
            installed = root / 'installed'
            installed.mkdir()
            with zipfile.ZipFile(archive, 'w') as bundle:
                for name, data in [('system.img', b'owned-image'), ('build.prop', b'owned-build'), ('source.properties', b'revision9')]:
                    bundle.writestr('x86_64/' + name, data)
                    (installed / name).write_bytes(data)
            self.assertEqual(3, len(verify_installed_archive(archive, installed)))
            (installed / 'system.img').write_bytes(b'other-image')
            with self.assertRaisesRegex(ValueError, 'differs'):
                verify_installed_archive(archive, installed)

    def test_catalogue_archive_revision_and_installed_identity(self):
        xml = b'''<repository><remotePackage path="system-images;android-37.1;google_apis_ps16k;x86_64">
          <revision><major>9</major></revision><archives><archive><complete><size>2178012675</size>
          <checksum type="sha1">35c0da1cd298f3426a5b48233f59515097446a28</checksum>
          <url>x86_64-ps16k-37.1_r09.zip</url></complete></archive></archives></remotePackage></repository>'''
        self.assertEqual(9, image_record(xml)['revision'])
        for before, after in [(b'<major>9', b'<major>10'), (b'35c0da', b'45c0da'),
                              (b'google_apis_ps16k', b'google_apis_playstore_ps16k'),
                              (b'2178012675', b'2178012676'), (b'r09.zip', b'r10.zip')]:
            with self.subTest(before=before), self.assertRaises(ValueError):
                image_record(xml.replace(before, after))
        local = b'<repository><localPackage path="system-images;android-37.1;google_apis_ps16k;x86_64"><revision><major>9</major></revision></localPackage></repository>'
        self.assertEqual(9, image_record(local, installed=True)['revision'])
        with self.assertRaises(ValueError):
            image_record(local)

    def test_guest_system_fingerprint_page_size_abi_and_emulator_are_all_required(self):
        values = {'api': '37', 'pageSize': '16384', 'abi': 'x86_64',
                  'fingerprint': 'google/sdk_gphone16k_x86_64/emu64xa16k:17/OWNED/123:user/dev-keys',
                  'systemFingerprint': 'google/generic_system_google/generic:17/OWNED/123:user/dev-keys'}
        properties = {'ro.system.build.fingerprint': values['systemFingerprint'], 'ro.system.build.version.sdk': '37'}
        emulator = 'Android emulator version 37.2.13.0 (build_id 16740729) (CL:N/A)'
        self.assertEqual('IDENTITY_VERIFIED_ONLY', device_record(values, properties, emulator)['status'])
        for key, bad in [('api', '36'), ('pageSize', '4096'), ('abi', 'arm64-v8a'),
                         ('fingerprint', 'unidentified'), ('systemFingerprint', 'different')]:
            changed = dict(values, **{key: bad})
            with self.subTest(key=key), self.assertRaises(ValueError):
                device_record(changed, properties, emulator)
        with self.assertRaises(ValueError):
            device_record(values, properties, emulator.replace('37.2.13.0', '37.2.12.0'))
        with self.assertRaises(ValueError):
            device_record(values, properties, emulator.replace('16740729', '16428233'))
