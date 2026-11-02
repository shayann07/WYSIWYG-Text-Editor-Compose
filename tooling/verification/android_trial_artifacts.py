"""Relocate exact retained hosted APKs; never manufacture a new consumer build result."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile

RUN = 40746007286
ARTIFACT = 12841796990
SOURCE_HEAD = 'c0d56ae40ef819ddf7f92b918dbe5a736f6ec8f8'
PREFIX = 'tests/local-evidence/consumer/android-ci/'
HASHES = {
    'app': '711102f4d7ead5ed1605bc4b29704033f57b17975b4c958d875088d81fcb1b9d',
    'test': 'd2e3aa0c6ae24b2df4a3c441d890e7bf61dd6d956e520d9a0401b3df0292262d',
    'peer': '376e513ee3bb36e7b4057fd3befb8f62c3cca519b01ecf88eb93229623a070e5',
}
DESTINATIONS = {
    'app': 'android/build/outputs/apk/debug/app.apk',
    'test': 'android/build/outputs/apk/androidTest/debug/test.apk',
    'peer': 'android-peer/build/outputs/apk/debug/peer.apk',
}


def verify_harness(root):
    root = Path(root)
    manifest = json.loads((root / 'source-manifest.json').read_text(encoding='utf-8'))
    names = {'tooling/verification/' + name for name in
             ['android_check.py', 'android_clipboard.py', 'android_process.py',
              'android_tap_fixture.py', 'android_memory_readiness.py']}
    if (manifest.get('sourceCommit') != '2d23d55033d0456712428ccb8202f8ae36f2941c'
            or set(manifest.get('files', {})) != names):
        raise ValueError('Trial harness source identity or helper set differs')
    for name, digest in manifest['files'].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Vendored host helper differs from reviewed source: ' + name)
    return manifest


def validate_metadata(metadata):
    run = metadata.get('workflow_run', {})
    if (metadata.get('id') != ARTIFACT or metadata.get('expired') is not False
            or run.get('id') != RUN or run.get('head_sha') != SOURCE_HEAD
            or run.get('repository_id') != 1421229803 or run.get('head_repository_id') != 1421229803):
        raise ValueError('Retained artifact provenance differs from the authorized repository/run/head')


def relocate(archive, output, metadata):
    validate_metadata(metadata)
    archive, output = Path(archive), Path(output)
    if archive.stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Retained artifact exceeds bounded transfer size')
    output.mkdir(parents=True, exist_ok=False)
    result = {'status': 'FAIL', 'sourceRun': RUN, 'sourceHead': SOURCE_HEAD,
              'artifactId': ARTIFACT, 'scope': 'Relocation only; no build or device qualification'}
    try:
        with zipfile.ZipFile(archive) as bundle:
            def read(name, limit):
                entries = [e for e in bundle.infolist() if e.filename == name]
                if len(entries) != 1 or entries[0].file_size > limit:
                    raise ValueError('Missing, duplicate or oversized retained entry: ' + name)
                return bundle.read(entries[0])

            raw = read(PREFIX + 'results.json', 2 * 1024 * 1024)
            identity_raw = read(PREFIX + 'apks/identity.json', 64 * 1024)
            original = json.loads(raw)
            identity = json.loads(identity_raw)
            if original.get('status') != 'PASS' or set(identity) != set(HASHES):
                raise ValueError('Original independent consumer did not pass or APK roles differ')
            relocated = copy.deepcopy(original)
            mappings = {}
            payloads = {}
            for role, digest in HASHES.items():
                data = read(PREFIX + 'apks/' + role + '.apk', 32 * 1024 * 1024)
                if identity[role].get('sha256') != digest or hashlib.sha256(data).hexdigest() != digest:
                    raise ValueError('Exact hosted APK hash mismatch: ' + role)
                old = identity[role]['original']
                source = original['androidPeer']['artifacts'] if role == 'peer' else original['androidArtifacts']
                if source.get(old) != digest:
                    raise ValueError('APK differs from original binary-consumer receipt: ' + role)
                mappings[old] = str((output / DESTINATIONS[role]).resolve())
                payloads[role] = data
            if set(mappings) != set(original['androidArtifacts']) | set(original['androidPeer']['artifacts']):
                raise ValueError('Unexpected consumer APK artifact')
            # Publish only after every payload and original association has been validated.
            for role, data in payloads.items():
                target = output / DESTINATIONS[role]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            relocated['androidArtifacts'] = {mappings[p]: h for p, h in original['androidArtifacts'].items()}
            relocated['androidPeer']['artifacts'] = {mappings[p]: h for p, h in original['androidPeer']['artifacts'].items()}
            result.update(status='PASS_RELOCATION_ONLY', artifactHashes=HASHES, pathMapping=mappings,
                          originalReceiptSha256=hashlib.sha256(raw).hexdigest())
            relocated['relocation'] = result
            (output / 'original-consumer.json').write_bytes(raw)
            (output / 'original-apk-identity.json').write_bytes(identity_raw)
            (output / 'consumer.json').write_text(json.dumps(relocated, indent=2) + '\n', encoding='utf-8')
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        (output / 'relocation.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--metadata', type=Path, required=True)
    args = parser.parse_args()
    relocate(args.archive, args.output_dir, json.loads(args.metadata.read_text(encoding='utf-8')))
