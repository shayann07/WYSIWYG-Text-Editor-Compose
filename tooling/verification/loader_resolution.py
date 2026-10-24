"""Compare static evidence policy with the actual registered production ESM loader.

The V6 root executes with a private output. Other historical roots are resolved,
never evaluated: the raw run20 verifier would overwrite its historical results.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tooling/docs'))
import evidence_integrity as integrity


def run(output, root=ROOT, policy_override=None):
    root = root.resolve()
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    record = json.loads((root/integrity.RECORD).read_text(encoding='utf-8'))
    policy = record['runtimeLoaderPolicy'] if policy_override is None else policy_override
    for name, expected in policy['boundFiles'].items():
        if integrity.digest(root/name) != expected:
            raise ValueError('Runtime loader identity changed: ' + name)
    cases = []
    for name in record['imports']:
        file = root/name
        for raw in integrity.imports(file.read_text(encoding='utf-8')):
            if raw.startswith('.'):
                target = integrity.runtime_path(root, file.parent/raw, policy)
                expected = target.as_uri()
                if 'node_modules' in target.parts:
                    key = 'npm:' + target.relative_to(root).as_posix()
                    if key not in record['externalImports']:
                        raise ValueError('Unrecorded external resolution: ' + key)
            elif raw.startswith('node:'):
                expected = raw
            else:
                raise ValueError('Bare external needs explicit conditional-export qualification: ' + raw)
            cases.append(dict(importer=name, parentURL=file.as_uri(), specifier=raw, expected=expected))
    inputs = output/'cases.json'
    inputs.write_text(json.dumps(cases, indent=2)+'\n', encoding='utf-8')
    trace = output/'actual-esm-events.jsonl'
    command = ['node', '--experimental-import-meta-resolve', '--require',
               str(root/'tooling/verification/loader_trace.cjs'),
               str(root/'tooling/verification/loader_resolution_witness.mjs'), str(root), str(output), str(inputs)]
    process = subprocess.run(command, cwd=root, capture_output=True, timeout=120,
                             env=dict(os.environ, COMPOSEDOC_LOADER_TRACE=str(trace)))
    (output/'node.stdout').write_bytes(process.stdout)
    (output/'node.stderr').write_bytes(process.stderr)
    result = dict(status='PASS' if process.returncode == 0 else 'FAIL', command=command,
                  exitCode=process.returncode, loaderPolicy=policy, requestedCases=len(cases),
                  inputsSha256=hashlib.sha256(inputs.read_bytes()).hexdigest(),
                  scope='Executed unmodified V6; actual parent-aware ESM resolution for inspection-only roots')
    if process.returncode == 0:
        witness = json.loads((output/'resolution.json').read_text(encoding='utf-8'))
        if (witness['status'] != 'PASS' or len(witness['cases']) != len(cases)
                or witness['unchangedV6Groups'] != 16 or not witness['parentAwareTinyProof']
                or not witness['conditionalImportVsRequireProof']):
            raise ValueError('Node witness did not execute its declared checks')
        if witness['runtime'] != 'v' + (root/'.nvmrc').read_text(encoding='utf-8').strip():
            raise ValueError('Loader witness runtime differs from the pinned qualification identity')
        result['witness'] = witness
        result['actualCases'] = len(witness['cases'])
        result['observerInputs'] = {name: integrity.digest(root/name) for name in
                                   ('tooling/verification/loader_trace.cjs',
                                    'tooling/verification/loader_resolution_witness.mjs',
                                    'tooling/verification/loader_resolution.py')}
        result['outputSha256'] = hashlib.sha256((output/'resolution.json').read_bytes()).hexdigest()
    (output/'result.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    result = run(args.output_dir)
    print(json.dumps({k:v for k,v in result.items() if k != 'witness'}, indent=2))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__': sys.exit(main())
