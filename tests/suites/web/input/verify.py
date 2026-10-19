"""Bounded actual-browser/JVM-owner compatibility spike with fresh-build checks."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = _REPO_ROOT
sys.path.insert(0, str(ROOT / 'desktop-prototype'))
from verification import validate_build, identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    now = lambda: datetime.now(timezone.utc).isoformat()
    report = {'status': 'RUNNING', 'gate': 'M1_WEB_INPUT_SPIKE', 'startedAt': now()}
    def save():
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    save()
    try:
        build = validate_build(args.build_dir)
        node = shutil.which('node')
        if not node:
            raise RuntimeError('Node runtime unavailable')
        command = [node, str(Path(__file__).parent / 'probe.mjs'), '--build=' + str(build), '--output=' + str(output / 'browser')]
        report.update(command=command, buildIdentity=identity(build / 'build-identity.json'))
        save()
        try:
            process = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            code, log = process.returncode, process.stdout.decode('utf-8', 'replace')
        except subprocess.TimeoutExpired as error:
            code, log = None, (error.stdout or b'').decode('utf-8', 'replace') + '\nTIMEOUT'
        (output / 'browser.log').write_text(log, encoding='utf-8')
        report['exitCode'] = code
        if code != 0:
            raise RuntimeError('Browser spike failed: ' + log[-3000:])
        browser = json.loads((output / 'browser/results.json').read_text(encoding='utf-8'))
        if browser['status'] != 'BROWSER_OWNER_COMPATIBILITY_PASS_GATE_OPEN' or not browser['browserAndOwnerClosed']:
            raise RuntimeError('Incomplete browser spike or leaked browser/owner connection')
        validate_build(build)
        report.update(status='EXPERIMENT_CHECKS_PASS_GATE_OPEN', browserVersion=browser['browserVersion'],
                      playwrightVersion=browser['playwrightVersion'], browserAndOwnerClosed=True,
                      nativeOsIme='NOT RUN', realScreenReader='NOT RUN', browserResult='browser/results.json',
                      renderPolicyCounterexample=browser['renderPolicyCounterexample'])
        print('PASS actual-browser/JVM-owner input spike; native OS/AT qualification OPEN; resources closed')
    except BaseException as error:
        report.update(status='FAIL', error=str(error))
        print('FAIL ' + str(error), file=sys.stderr)
    finally:
        report['finishedAt'] = now(); save()
    return 0 if report['status'] == 'EXPERIMENT_CHECKS_PASS_GATE_OPEN' else 1


if __name__ == '__main__':
    sys.exit(main())
