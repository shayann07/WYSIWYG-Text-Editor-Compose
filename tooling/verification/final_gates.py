"""Run every required final gate on a clean committed tree and write the receipt.

The receipt (tooling/local/final-verification.json) identifies the verified
commit; tooling/github/apply-github-governance.ps1 -Apply refuses to activate
repository hardening without it.
"""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
RECEIPT = ROOT / 'tooling/local/final-verification.json'


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def git(*args):
    return subprocess.run(['git', '-C', str(ROOT), *args], check=True, stdout=subprocess.PIPE).stdout.decode().strip()


def main():
    if git('status', '--porcelain'):
        raise SystemExit('Commit all changes first; the receipt must identify one exact commit.')
    commit = git('rev-parse', 'HEAD')
    gradle = str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'))
    receipt = {'status': 'RUNNING', 'commit': commit, 'startedAt': timestamp(), 'gates': []}

    def save():
        RECEIPT.parent.mkdir(parents=True, exist_ok=True)
        RECEIPT.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')

    def gate(name, command, timeout=1800):
        print('RUN ' + name, flush=True)
        entry = {'name': name, 'command': command, 'startedAt': timestamp()}
        receipt['gates'].append(entry)
        save()
        try:
            process = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
            log, code = process.stdout.decode('utf-8', 'replace'), process.returncode
        except subprocess.TimeoutExpired:
            log, code = 'TIMEOUT', None
        entry.update(exitCode=code, finishedAt=timestamp(), tail=log.splitlines()[-5:])
        save()
        print(('PASS ' if code == 0 else 'FAIL ') + name, flush=True)
        if code != 0:
            print(log[-3000:], flush=True)
        return code, log

    save()
    gate('docs-and-frozen-hashes', [sys.executable, 'tooling/docs/validate_docs.py'])
    gate('v6-witnesses', [sys.executable, 'tooling/verification/reproduce_inherited.py'])
    gate('gradle-check-api', [gradle, '--no-daemon', '--console=plain', 'check', 'apiCheck'])
    gate('verification-contract', [sys.executable, 'tooling/verification/test_verification.py'])
    # Part of the existing evidence-contract gate; preserve the six-gate receipt shape.
    if receipt['gates'][-1]['exitCode'] == 0:
        code, log = gate('transitive-contract-tests', [sys.executable, '-m', 'unittest', 'discover',
                                                     '-s', 'tooling/docs', '-p', 'test_*.py'])
        extra = receipt['gates'].pop()
        receipt['gates'][-1]['transitiveContractTests'] = extra
        receipt['gates'][-1]['exitCode'] = code
        save()
    code, log = gate('workspace-milestone', [sys.executable, 'tooling/verification/verify.py', '--workspace-milestone'])
    evidence = re.search(r'Evidence: (.+)', log)
    if code == 0 and evidence:
        build = Path(evidence.group(1).strip()) / 'build'
        receipt['workspaceEvidence'] = str(Path(evidence.group(1).strip()).relative_to(ROOT))
        mutations = ROOT / 'tests/local-evidence/desktop' / ('mutations-' + commit[:12] + '-' + str(time.time_ns()))
        receipt['mutationEvidence'] = str(mutations.relative_to(ROOT))
        gate('implementation-mutations', [sys.executable, 'tooling/verification/mutation_checks.py',
                                          '--build-dir', str(build), '--output-dir', str(mutations)])
    else:
        receipt['gates'].append({'name': 'implementation-mutations', 'exitCode': None, 'skipped': 'workspace-milestone failed'})
    if git('rev-parse', 'HEAD') != commit or git('status', '--porcelain'):
        receipt['error'] = 'Tree changed during verification'
    passed = 'error' not in receipt and len(receipt['gates']) == 6 and all(g['exitCode'] == 0 for g in receipt['gates'])
    receipt.update(status='PASS' if passed else 'FAIL', finishedAt=timestamp())
    save()
    print(receipt['status'] + ' final gates for ' + commit, flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
