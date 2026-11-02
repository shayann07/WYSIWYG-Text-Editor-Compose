"""Transfer one current-workflow binary consumer build to a compilation-free test VM."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from consumer_check import source_inputs

ROLES = ('app', 'test', 'peer')
DESTINATIONS = {'app': 'android/build/outputs/apk/debug/app.apk',
                'test': 'android/build/outputs/apk/androidTest/debug/test.apk',
                'peer': 'android-peer/build/outputs/apk/debug/peer.apk'}


def context():
    value = {'schemaVersion': 1, 'repository': os.environ['GITHUB_REPOSITORY'],
             'repositoryId': int(os.environ['GITHUB_REPOSITORY_ID']), 'runId': int(os.environ['GITHUB_RUN_ID']),
             'runAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']), 'sourceHead': os.environ['CI_SOURCE_HEAD'],
             'checkoutCommit': os.environ['GITHUB_SHA']}
    if (not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', value['repository'])
            or any(value[k] <= 0 for k in ('repositoryId', 'runId', 'runAttempt'))
            or any(not re.fullmatch(r'[a-f0-9]{40}', value[k]) for k in ('sourceHead', 'checkoutCommit'))):
        raise ValueError('Invalid workflow identity')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if actual != value['checkoutCommit']:
        raise ValueError('Checkout differs from the workflow merge/source commit')
    return value


def artifact_name(ctx):
    return f"android-binaries-{ctx['runId']}-{ctx['runAttempt']}-{ctx['checkoutCommit']}"


def validate_metadata(metadata, artifact_id, ctx):
    if type(artifact_id) is not int or artifact_id <= 0:
        raise ValueError('Invalid producer artifact ID')
    run = metadata.get('workflow_run', {})
    expected = {'id': artifact_id, 'expired': False, 'name': artifact_name(ctx)}
    for key, value in expected.items():
        if type(metadata.get(key)) is not type(value) or metadata[key] != value:
            raise ValueError('Wrong, expired or foreign artifact: ' + key)
    for key, value in {'id': ctx['runId'], 'repository_id': ctx['repositoryId'], 'head_sha': ctx['sourceHead']}.items():
        if type(run.get(key)) is not type(value) or run[key] != value:
            raise ValueError('Artifact belongs to another workflow repository/run/head')


def verify_consumer(raw, identity, ctx, inputs):
    consumer = json.loads(raw)
    if (consumer.get('status') != 'PASS' or consumer.get('sourceCommit') != ctx['checkoutCommit']
            or consumer.get('sourceInputs') != inputs or set(identity) != set(ROLES)):
        raise ValueError('Consumer build/source/checkout identity mismatch')
    if consumer.get('androidPeer', {}).get('applicationId') != 'dev.shayxo.composedoc.androidsample.peerconsumer':
        raise ValueError('Unexpected independent peer application')
    paths = set()
    for role in ROLES:
        entry = identity[role]
        if not re.fullmatch(r'[a-f0-9]{64}', entry.get('sha256', '')):
            raise ValueError('Malformed APK hash')
        original = entry['original']
        actual = consumer['androidPeer']['artifacts'] if role == 'peer' else consumer['androidArtifacts']
        if original in paths or actual.get(original) != entry['sha256']:
            raise ValueError('APK does not match the original binary consumer receipt')
        paths.add(original)
    if paths != set(consumer['androidArtifacts']) | set(consumer['androidPeer']['artifacts']):
        raise ValueError('Unexpected or missing consumer APK role')
    return consumer


def pack(directory, ctx, inputs):
    directory = Path(directory)
    raw = (directory / 'results.json').read_bytes()
    identity = json.loads((directory / 'apks/identity.json').read_text(encoding='utf-8'))
    verify_consumer(raw, identity, ctx, inputs)
    hashes = {}
    for role in ROLES:
        digest = hashlib.sha256((directory / 'apks' / (role + '.apk')).read_bytes()).hexdigest()
        if digest != identity[role]['sha256']:
            raise ValueError('Producer APK bytes changed: ' + role)
        hashes[role] = digest
    record = dict(ctx, consumerReceiptSha256=hashlib.sha256(raw).hexdigest(), apkHashes=hashes)
    (directory / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    return record


def receive(archive, metadata, artifact_id, output, packages, ctx, inputs):
    validate_metadata(metadata, artifact_id, ctx)
    archive, output, packages = Path(archive), Path(output), Path(packages)
    if archive.stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Binary transfer exceeds bounded artifact size')
    output.mkdir(parents=True, exist_ok=False)
    record = {'status': 'FAIL', 'artifactId': artifact_id, 'scope': 'Same-run artifact relocation; not a new build/test result'}
    try:
        with zipfile.ZipFile(archive) as bundle:
            def read(name, cap):
                values = [e for e in bundle.infolist() if e.filename == name]
                if len(values) != 1 or values[0].file_size > cap:
                    raise ValueError('Missing, duplicate or oversized transfer entry: ' + name)
                return bundle.read(values[0])
            raw = read('results.json', 2 * 1024 * 1024)
            identity_raw = read('apks/identity.json', 65536)
            provenance_raw = read('provenance.json', 65536)
            identity, provenance = json.loads(identity_raw), json.loads(provenance_raw)
            consumer = verify_consumer(raw, identity, ctx, inputs)
            for key, value in ctx.items():
                if type(provenance.get(key)) is not type(value) or provenance[key] != value:
                    raise ValueError('Transfer provenance mismatch: ' + key)
            if provenance.get('consumerReceiptSha256') != hashlib.sha256(raw).hexdigest():
                raise ValueError('Original consumer receipt changed in transfer')
            payloads, mapping = {}, {}
            for role in ROLES:
                payloads[role] = read('apks/' + role + '.apk', 32 * 1024 * 1024)
                digest = hashlib.sha256(payloads[role]).hexdigest()
                if digest != identity[role]['sha256'] or provenance.get('apkHashes', {}).get(role) != digest:
                    raise ValueError('Transferred APK hash mismatch: ' + role)
                mapping[identity[role]['original']] = str((packages / DESTINATIONS[role]).resolve())
            # No APK is published until all metadata, source and payloads validate.
            packages.mkdir(parents=True, exist_ok=False)
            for role, data in payloads.items():
                target = packages / DESTINATIONS[role]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            relocated = copy.deepcopy(consumer)
            relocated['androidArtifacts'] = {mapping[k]: v for k, v in consumer['androidArtifacts'].items()}
            relocated['androidPeer']['artifacts'] = {mapping[k]: v for k, v in consumer['androidPeer']['artifacts'].items()}
            record.update(status='PASS_TRANSFER_ONLY', provenance=provenance, pathMapping=mapping)
            relocated['relocation'] = record
            for name, data in [('original-consumer.json', raw), ('original-identity.json', identity_raw), ('original-provenance.json', provenance_raw)]:
                (output / name).write_bytes(data)
            (output / 'consumer.json').write_text(json.dumps(relocated, indent=2) + '\n', encoding='utf-8')
    except Exception as error:
        record['error'] = str(error)
        raise
    finally:
        primary = sys.exception()
        try:
            (output / 'transfer.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
        except OSError as failure:
            if primary is None:
                raise
            primary.add_note('Cannot retain transfer failure receipt: ' + str(failure))
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['pack', 'validate-metadata', 'receive'])
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--metadata', type=Path)
    parser.add_argument('--artifact-id', type=int)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--packages-dir', type=Path)
    args = parser.parse_args()
    ctx = context()
    if args.action == 'pack':
        pack(args.directory, ctx, source_inputs())
    else:
        metadata = json.loads(args.metadata.read_text(encoding='utf-8'))
        validate_metadata(metadata, args.artifact_id, ctx)
        if args.action == 'receive':
            receive(args.archive, metadata, args.artifact_id, args.output_dir, args.packages_dir, ctx, source_inputs())


if __name__ == '__main__':
    main()
