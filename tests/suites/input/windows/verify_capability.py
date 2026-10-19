"""Read-only Windows input-layout capability and no-foreground activation refusal."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = _REPO_ROOT
sys.path.insert(0, str(ROOT / 'desktop-prototype'))
from verification import validate_build, identity, timestamp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True); parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args(); build = validate_build(args.build_dir)
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'RUNNING', 'startedAt': timestamp(), 'steps': [], 'buildIdentity': identity(build / 'build-identity.json'),
              'scope': 'Read-only native layout/default inspection and refusal without an owned foreground HWND; no real keyboard/IME input'}
    source = Path(__file__).resolve().parent / 'OwnedKeyboardLayout.java'
    report['source'] = identity(source)
    profile_source = source.with_name('TsfProfileProbe.java')
    report['profileSource'] = identity(profile_source)
    thread_source = source.with_name('TsfThreadScope.java'); report['threadSource'] = identity(thread_source)
    def save(): (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    def run(name, command, timeout=30):
        step = {'name': name, 'command': command, 'startedAt': timestamp()}; report['steps'].append(step); save()
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        (output / (name + '.log')).write_bytes(result.stdout)
        step.update(exitCode=result.returncode, finishedAt=timestamp()); save()
        if result.returncode != 0: raise RuntimeError(name + ' failed; see log')
        return result.stdout.decode('utf-8', 'strict')
    save()
    try:
        if sys.platform != 'win32': report.update(status='NOT_RUN', reason='Windows native layout lane'); return 0
        java = Path(shutil.which('java')).resolve(); compiler = java.parent / 'javac.exe'
        version = run('compiler-version', [str(compiler), '-version'], 10).strip()
        if not version.startswith('javac ') or int(version.split()[1].split('.')[0]) < 23:
            report.update(status='NOT_RUN', reason='Existing JDK 23+ FFM tooling required; no upgrade'); return 0
        report['java'] = identity(java); report['compiler'] = identity(compiler)
        classes = output / 'classes'; classes.mkdir()
        classpath = str(build / 'classes') + ';' + str(build / 'lib/*')
        run('compile', [str(compiler), '--release', '23', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), str(source), str(profile_source), str(thread_source)])
        raw = run('read-only-capability', [str(java), '--enable-native-access=ALL-UNNAMED', '-cp', str(classes) + ';' + classpath, 'OwnedKeyboardLayout'], 20)
        observation = json.loads(raw)
        assert observation['status'] == 'READ_ONLY_LAYOUT_CHECKS_PASS'
        assert observation['activationWithoutOwnedFocus'] == 'REFUSED_UNCHANGED'
        assert observation['state']['attempted'] is False
        report['observation'] = observation; report['class'] = identity(classes / 'OwnedKeyboardLayout.class')
        profile_raw = run('read-only-tsf', [str(java), '--enable-native-access=ALL-UNNAMED', '-cp', str(classes) + ';' + classpath, 'TsfProfileProbe'], 20)
        report['tsf'] = json.loads(profile_raw)
        after = json.loads(run('layout-after-tsf', [str(java), '--enable-native-access=ALL-UNNAMED', '-cp', str(classes) + ';' + classpath, 'OwnedKeyboardLayout'], 20))
        assert after['state'] == observation['state'], 'Read-only TSF queries changed native input state'
        validate_build(build); report['status'] = 'READ_ONLY_WINDOWS_LAYOUT_CHECKS_PASS_GATE_OPEN'
        print('PASS read-only Windows layout capability; real keyboard/IME qualification remains separate')
    except BaseException as error:
        report.update(status='FAIL', error=repr(error)); print('FAIL Windows layout probe: ' + repr(error), file=sys.stderr)
    finally: report['finishedAt'] = timestamp(); save()
    return 0 if report['status'] in ('READ_ONLY_WINDOWS_LAYOUT_CHECKS_PASS_GATE_OPEN', 'NOT_RUN') else 1


if __name__ == '__main__': sys.exit(main())
