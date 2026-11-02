"""Fail-closed identity admission for one proposed API37/16KiB environment trial."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = 'system-images;android-37.1;google_apis_ps16k;x86_64'
ARCHIVE = 'x86_64-ps16k-37.1_r09.zip'
CHECKSUM = '35c0da1cd298f3426a5b48233f59515097446a28'


def image_record(raw, installed=False):
    root = ET.fromstring(raw)
    tag = 'localPackage' if installed else 'remotePackage'
    matches = [e for e in root.iter() if e.tag.split('}')[-1] == tag and e.get('path') == PACKAGE]
    if len(matches) != 1:
        raise ValueError('Missing or duplicated pinned image identity')
    record = matches[0]
    def child(name):
        return [e for e in record if e.tag.split('}')[-1] == name]
    revisions = child('revision')
    if len(revisions) != 1 or [e.text for e in revisions[0]] != ['9']:
        raise ValueError('Image revision is not pinned revision 9')
    if not installed:
        archives = [e for e in record.iter() if e.tag.split('}')[-1] == 'complete']
        if len(archives) != 1:
            raise ValueError('Ambiguous image archive')
        values = {e.tag.split('}')[-1]: e.text for e in archives[0]}
        if values != {'size': '2178012675', 'checksum': CHECKSUM, 'url': ARCHIVE}:
            raise ValueError('Image archive differs from reviewed catalogue identity')
    return {'package': PACKAGE, 'revision': 9, 'metadataSha256': hashlib.sha256(raw).hexdigest()}


def device_record(values, properties, emulator):
    if values.get('api') != '37' or values.get('pageSize') != '16384' or values.get('abi') != 'x86_64':
        raise ValueError('Guest is not the proposed API37/x86_64/16KiB environment')
    fingerprint = values.get('fingerprint', '')
    if not re.fullmatch(r'google/sdk_gphone16k_x86_64/emu64xa16k:17/[^\s]+', fingerprint):
        raise ValueError('Unexpected device build family/fingerprint')
    if not properties.get('ro.system.build.fingerprint') or values.get('systemFingerprint') != properties['ro.system.build.fingerprint']:
        raise ValueError('Guest system fingerprint differs from installed SDK image')
    if properties.get('ro.system.build.version.sdk') != '37':
        raise ValueError('SDK image is not Android API37')
    if not re.search(r'Android emulator version 37\.2\.13\.0 \(build_id 16740729\)', emulator):
        raise ValueError('Emulator executable is not the pinned version/build')
    return dict(status='IDENTITY_VERIFIED_ONLY', **values, emulator=emulator)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path, required=True)
    parser.add_argument('--installed', action='store_true')
    parser.add_argument('--device', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = {'status': 'FAIL'}
    try:
        result['image'] = image_record(args.metadata.read_bytes(), args.installed)
        if args.device:
            if not args.installed:
                raise ValueError('Device identity requires installed image metadata')
            sdk = Path(os.environ['ANDROID_HOME'])
            adb = str(sdk / 'platform-tools/adb')
            values = {}
            for key, command in [('api', ['getprop', 'ro.build.version.sdk']), ('pageSize', ['getconf', 'PAGE_SIZE']),
                                 ('abi', ['getprop', 'ro.product.cpu.abi']), ('fingerprint', ['getprop', 'ro.build.fingerprint']),
                                 ('systemFingerprint', ['getprop', 'ro.system.build.fingerprint'])]:
                values[key] = subprocess.check_output([adb, '-s', 'emulator-5580', 'shell', *command], timeout=5, text=True).strip()
            props = dict(line.split('=', 1) for line in (args.metadata.parent / 'build.prop').read_text().splitlines()
                         if '=' in line and not line.startswith('#'))
            emulator = subprocess.check_output([str(sdk / 'emulator/emulator'), '-version'], timeout=5, text=True)
            result['device'] = device_record(values, props, emulator)
        result['status'] = 'IDENTITY_VERIFIED_ONLY'
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
