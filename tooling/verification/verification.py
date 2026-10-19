"""Shared fresh-build/evidence contract for deterministic desktop and U0 checks."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = _REPO_ROOT
PROJECT = ROOT / 'apps/desktop-workspace'
EVIDENCE = ROOT / 'tests/local-evidence/desktop'
from doc_paths import resolve, original

def timestamp():
    return datetime.now(timezone.utc).isoformat()

def identity(path):
    path = Path(path)
    return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'sizeBytes': path.stat().st_size, 'mtimeNs': path.stat().st_mtime_ns}

def node_dependency_paths():
    paths = []
    modules = ROOT / 'tests/suites/integration/c0/node_modules'
    for package in ('yjs', 'lib0', 'isomorphic.js'):
        base = modules / package
        for pattern in ('*.js', '*.mjs', 'package.json', 'LICENSE*'):
            paths += sorted(base.rglob(pattern))
    return sorted(set(p for p in paths if p.is_file()))

def frozen_reference_paths():
    manifest = resolve('v5-freeze-manifest.json')
    frozen = json.loads(manifest.read_text(encoding='utf-8'))
    return [manifest] + [original(p) for p in frozen['hashes'] if p.startswith('validation/codex/run-20261016-20/')]

def inputs():
    paths = [ROOT / name for name in ('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat')]
    for directory in ('core', 'apps/desktop-workspace/src', 'tests/desktop-harness/src', 'build-logic', 'gradle'):
        paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and not any(part in ('build', '.gradle') for part in p.relative_to(ROOT).parts) and p.suffix in ('.kt', '.kts', '.toml', '.properties', '.jar', '.txt')]
    paths += list((ROOT / 'core').glob('*/build.gradle.kts'))
    paths += [ROOT / 'apps/desktop-workspace/build.gradle.kts', ROOT / 'tests/desktop-harness/build.gradle.kts']
    paths += sorted((ROOT / 'tooling/verification').glob('*.py'))
    paths += sorted((ROOT / 'tests/desktop/qualification').glob('*.java'))
    paths += sorted((ROOT / 'tests/desktop/qualification').glob('*.py'))
    paths += sorted(PROJECT.glob('*.cmd'))
    paths += sorted((ROOT / 'tests/suites/urdu/u0').glob('*.py'))
    paths += sorted((ROOT / 'tests/suites/pagination/gp0').glob('*.py'))
    paths += sorted((ROOT / 'tests/suites/pagination/pdf_outline').glob('*.py'))
    paths += sorted((ROOT / 'tests/suites/integration/s0').glob('*.py'))
    for pattern in ('*.java', '*.py'):
        paths += sorted((ROOT / 'tests/suites/accessibility/jab').glob(pattern))
        paths += sorted((ROOT / 'tests/suites/input/windows').glob(pattern))
    for pattern in ('*.mjs', '*.py', 'package*.json'):
        paths += sorted((ROOT / 'tests/suites/integration/c0').glob(pattern))
        paths += sorted((ROOT / 'tests/suites/web/input').glob(pattern))
    paths += node_dependency_paths()
    paths += frozen_reference_paths()
    for package in ('playwright', 'playwright-core'):
        base = ROOT / 'tests/suites/web/input/node_modules' / package
        for pattern in ('*.js', '*.mjs', '*.json', 'LICENSE*'):
            paths += sorted(p for p in base.rglob(pattern) if p.is_file())
    paths += [ROOT / 'tests/suites/urdu/u0/fixtures.json', ROOT / 'tests/suites/urdu/u0/fonts.json']
    paths += sorted((ROOT / 'tests/suites/urdu/u0/fonts').glob('*'))
    return {p.relative_to(ROOT).as_posix(): identity(p) for p in sorted(set(paths)) if p.is_file()}

class Verification:
    def __init__(self, output=None):
        self.output = Path(output) if output else EVIDENCE / ('batch-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
        self.output.mkdir(parents=True, exist_ok=False)
        self.build = self.output / 'build'
        self.report = {'status': 'RUNNING', 'startedAt': timestamp(), 'steps': [], 'inputs': inputs(),
                       'scope': 'Deterministic/synthetic desktop and Urdu checks plus bounded M1 experiment execution; PASS is not M1 gate qualification. Native OS input, screen-reader and print qualification are separate.'}
        self.report['gitCommit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        self.persist()

    def persist(self):
        path = self.output / 'results.json'
        temp = path.with_suffix('.tmp')
        temp.write_text(json.dumps(self.report, indent=2) + '\n', encoding='utf-8')
        temp.replace(path)
        latest = EVIDENCE / 'latest-verification.json'
        latest.parent.mkdir(parents=True, exist_ok=True)
        latest_tmp = latest.with_suffix('.' + str(os.getpid()) + '.tmp')
        latest_tmp.write_text(json.dumps({'status': self.report['status'], 'startedAt': self.report['startedAt'],
                              'results': str(path)}, indent=2) + '\n', encoding='utf-8')
        # Windows scanners can briefly hold the tiny current-pointer file.
        # Retry only this atomic publication; the run receipt remains authoritative.
        for attempt in range(10):
            try:
                latest_tmp.replace(latest)
                break
            except PermissionError:
                if attempt == 9:
                    raise
                time.sleep(.05)

    def run(self, name, command, timeout, expected=None, env=None):
        step = {'name': name, 'command': [str(c) for c in command], 'startedAt': timestamp(), 'status': 'RUNNING'}
        self.report['steps'].append(step)
        self.persist()
        start = time.monotonic()
        try:
            result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, env=env)
            log, code = result.stdout.decode('utf-8', 'replace'), result.returncode
        except subprocess.TimeoutExpired as error:
            log, code = (error.stdout or b'').decode('utf-8', 'replace') + '\nTIMEOUT', None
        except OSError as error:
            log, code = str(error), None
        (self.output / (name + '.log')).write_text(log, encoding='utf-8')
        passed = code == 0 and (expected is None or expected in log) and 'Exception in thread "' not in log
        step.update(status='PASS' if passed else 'FAIL', exitCode=code, finishedAt=timestamp(), seconds=round(time.monotonic() - start, 3))
        self.persist()
        print(f"{step['status']} {name}", flush=True)
        if not passed:
            print('\n'.join(log.splitlines()[-15:]), flush=True)
            raise RuntimeError(name + ' failed')
        return log

    def compile(self):
        if self.build.exists():
            raise RuntimeError('Build directory must be new: ' + str(self.build))
        launcher = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        self.run('build', [str(launcher), '--no-daemon', 'exportVerificationRuntime', '-PverificationOutput=' + str(self.build)], 900)
        if self.report['inputs'] != inputs():
            raise RuntimeError('Inputs changed during compilation')
        artifacts = {p.relative_to(self.build).as_posix(): identity(p) for p in sorted(self.build.rglob('*')) if p.is_file()}
        if not any(p.startswith('classes/') for p in artifacts) or not any(p.startswith('lib/') for p in artifacts):
            raise RuntimeError('Build is missing classes or runtime dependencies')
        manifest = {'status': 'BUILT', 'builtAt': timestamp(), 'inputs': self.report['inputs'], 'artifacts': artifacts}
        (self.build / 'build-identity.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        self.report['build'] = manifest
        self.persist()

    @property
    def classpath(self):
        return str(self.build / 'classes') + os.pathsep + str(self.build / 'lib/*')

    def finish(self, error=None):
        if error is None and self.report['inputs'] != inputs():
            error = 'Inputs changed during verification; no current PASS'
        if error is None:
            try:
                validate_build(self.build)
            except Exception as failure:
                error = str(failure)
        self.report.update(status='FAIL' if error else 'PASS', finishedAt=timestamp())
        if error:
            self.report['error'] = str(error)
        self.persist()
        print('Evidence: ' + str(self.output), flush=True)
        return 1 if error else 0

def validate_build(build):
    build = Path(build).resolve()
    manifest = json.loads((build / 'build-identity.json').read_text(encoding='utf-8'))
    if manifest['status'] != 'BUILT' or manifest['inputs'] != inputs():
        raise RuntimeError('Build does not match current sources, fixtures and fonts')
    for relative, expected in manifest['artifacts'].items():
        if identity(build / relative) != expected:
            raise RuntimeError('Build artifact changed: ' + relative)
    return build
