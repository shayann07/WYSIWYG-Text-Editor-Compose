"""Admit the pinned emulator's Linux loader dependencies before image downloads."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def missing_libraries(raw, code):
    if code != 0 or not raw.strip():
        raise ValueError('ldd did not complete successfully; inspect retained raw output')
    return sorted(set(re.findall(r'^\s*(\S+)\s+=>\s+not found\s*$', raw, re.MULTILINE)))


def required_packages(missing):
    known = {'libpulse.so.0': 'libpulse0', 'libpulse-simple.so.0': 'libpulse0'}
    unknown = set(missing) - set(known)
    if unknown:
        raise ValueError('Unreviewed missing emulator libraries: ' + ', '.join(sorted(unknown)))
    return sorted({known[name] for name in missing})


def prepare(sdk, output):
    sdk, output = Path(sdk), Path(output)
    output.mkdir(parents=True, exist_ok=False)
    result = {'status': 'FAIL', 'scope': 'Host emulator runtime only; no guest/editor qualification'}
    env = dict(os.environ)
    directories = sorted({str(p.parent) for p in (sdk / 'emulator').rglob('*.so*') if p.is_file()})
    env['LD_LIBRARY_PATH'] = ':'.join(directories)
    result['bundledLibraryDirectories'] = directories
    targets = [sdk / 'emulator/emulator', sdk / 'emulator/qemu/linux-x86_64/qemu-system-x86_64']

    def command(label, args, environment=None):
        completed = subprocess.run([str(arg) for arg in args], env=environment,
                                   capture_output=True, text=True, timeout=180)
        (output / (label + '.stdout')).write_text(completed.stdout, encoding='utf-8')
        (output / (label + '.stderr')).write_text(completed.stderr, encoding='utf-8')
        result.setdefault('commands', []).append({'label': label, 'command': [str(a) for a in args], 'exitCode': completed.returncode})
        return completed

    def scan(label):
        missing = set()
        for index, target in enumerate(targets):
            observed = command(label + '-' + str(index), ['ldd', target], env)
            missing.update(missing_libraries(observed.stdout, observed.returncode))
        return sorted(missing)

    try:
        missing = scan('ldd-before')
        result['missingBefore'] = missing
        packages = required_packages(missing)
        result['requiredHostPackages'] = packages
        if packages:
            for label, args in [('apt-index', ['sudo', 'apt-get', 'update', '-qq']),
                                ('apt-install', ['sudo', 'apt-get', 'install', '-y', '--no-install-recommends', *packages]),
                                ('package-versions', ['dpkg-query', '-W', '-f=${binary:Package} ${Version}\n', *packages])]:
                if command(label, args).returncode:
                    raise ValueError('Host dependency setup failed: ' + label)
        result['missingAfter'] = scan('ldd-after')
        if result['missingAfter']:
            raise ValueError('Emulator runtime still has unresolved libraries')
        # Boot uses the normal inherited environment; discovery-only library paths
        # must not make this readiness check succeed under different conditions.
        version = command('emulator-version', [targets[0], '-version'])
        if version.returncode or not re.search(r'Android emulator version 37\.2\.13\.0 \(build_id 16740729\)', version.stdout):
            raise ValueError('Pinned emulator did not execute with its expected version/build')
        result['status'] = 'HOST_RUNTIME_READY_ONLY'
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.sdk, args.output_dir)
