"""Execute old-codec and experimental package mappings; S0 stays open pending decisions."""

from pathlib import Path as _RepoPath
import sys as _repo_sys
_REPO_ROOT = next(p for p in _RepoPath(__file__).resolve().parents if (p / 'settings.gradle.kts').is_file())
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/verification'))
_repo_sys.path.insert(0, str(_REPO_ROOT / 'tooling/docs'))
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
from experiment import decode, encode, read_astra, specimen, validate, write_astra

ROOT = _REPO_ROOT
sys.path.insert(0, str(ROOT / 'desktop-prototype'))
from verification import validate_build, timestamp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {'status': 'RUNNING', 'gate': 'S0', 'startedAt': timestamp(), 'steps': [], 'checks': []}
    def save():
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    def passed(name):
        report['checks'].append({'name': name, 'status': 'PASS'})
        save()
    def run(name, command):
        step = {'name': name, 'command': command, 'startedAt': timestamp()}
        report['steps'].append(step); save()
        p = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        log = p.stdout.decode('utf-8', 'replace')
        (output / (name + '.log')).write_text(log, encoding='utf-8')
        step.update(exitCode=p.returncode, finishedAt=timestamp()); save()
        if p.returncode != 0:
            raise RuntimeError(name + ': ' + log[-2000:])
    def rejected(name, bundle, change):
        before = encode(bundle)
        candidate = copy.deepcopy(bundle)
        change(candidate)
        try:
            encode(candidate)
        except (ValueError, KeyError, TypeError):
            assert encode(bundle) == before, 'Failed migration changed source bundle'
            passed(name)
            return
        raise AssertionError('Invalid migration accepted: ' + name)
    save()
    try:
        build = validate_build(args.build_dir)
        base = [shutil.which('java'), '-cp', str(build / 'classes') + os.pathsep + str(build / 'lib/*'), 'editor.MainKt']
        fixtures = output / 'codec-fixtures'
        run('actual-codec-export', base + ['--schema-probe', '--schema-output=' + str(fixtures)])
        golden = json.loads((fixtures / 'golden.json').read_text(encoding='utf-8'))
        for version in (1, 2, 3):
            original = (fixtures / f'original-v{version}.astra').read_bytes()
            document = read_astra(original)
            assert document == golden[str(version)], 'Independent decoder disagrees with JVM codec'
            bundle = specimen(document)
            restored = decode(encode(bundle))
            assert restored == bundle
            archive = output / f'migrated-v{version}.s0-experiment.zip'
            archive.write_bytes(encode(restored))
            (fixtures / f'roundtrip-v{version}.astra').write_bytes(write_astra(restored['flows']['body-1']['document']))
            passed(f'v{version} old-load/new-save; exact raw units, attributes, paragraph/soft breaks, identities, resources and opaque payload')
        run('actual-codec-roundtrip', base + ['--schema-probe-verify', '--schema-output=' + str(fixtures)])
        bundle = specimen(golden['3'])
        rejected('unsupported schema leaves source intact', bundle, lambda b: b.update(format='future-schema'))
        rejected('unsupported mandatory feature leaves source intact', bundle, lambda b: b.update(requiredFeatures=['nested-table']))
        rejected('overlapping merged grid rejected', bundle, lambda b: b['tables'][0]['cells'][1].update(col=0))
        rejected('missing grid slot rejected', bundle, lambda b: b['tables'][0]['cells'].pop())
        rejected('duplicate rich-cell flow rejected', bundle, lambda b: b['tables'][0]['cells'][1].update(flow='cell-1'))
        rejected('section ancestry cycle rejected', bundle, lambda b: b['sections'][0].update(parent='section-2'))
        rejected('header cannot alias unrelated body flow', bundle, lambda b: b['sections'][0]['headerVariants'].update(first='body-2'))
        rejected('duplicate cross-flow persistent identity rejected', bundle, lambda b: b['flows']['cell-1']['unitIds'].__setitem__(0, 'body-1:u:0'))
        rejected('missing footnote target rejected', bundle, lambda b: b['footnotes'][0].update(targetFlow='body-1'))
        rejected('deleted comment target cannot remain attached', bundle, lambda b: b['anchors'][0].update(units=['deleted-unit']))
        orphan = copy.deepcopy(bundle)
        orphan['anchors'][0].update(units=['deleted-unit'], state='orphaned', context='Exact deleted Urdu context')
        assert decode(encode(orphan))['anchors'][0] == orphan['anchors'][0]
        passed('explicit orphan retains deleted identity and context; no automatic retarget')
        rejected('review resource remains pinned', bundle, lambda b: b['resources'].clear())
        rejected('resource corruption rejected', bundle, lambda b: b['resources']['resource-1'].update(hex='00'))
        rejected('unknown mandatory extension rejected', bundle, lambda b: b['opaqueExtensions'][0].update(required=True))
        for entry_name, text in [('../escape', '{}'), ('manifest.json', '{"format":"a","format":"b"}')]:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as z:
                z.writestr(entry_name, text)
            try:
                decode(data.getvalue())
            except ValueError:
                passed('archive/manifest rejection: ' + entry_name)
            else:
                raise AssertionError('Invalid archive accepted')
        identity = json.loads((fixtures / 'identity-probe.json').read_text(encoding='utf-8'))
        assert identity['originalUnitIds'] != identity['engineLoadedUnitIds']
        assert identity['originalUnitIds'] != identity['codecLoadedUnitIds']
        report.update(status='EXPERIMENT_CHECKS_PASS_GATE_OPEN', schemaFrozen=False,
                      featureIds=['F14', 'F15', 'F17', 'F20', 'F22', 'F27', 'F31', 'F32'],
                      prototypeIdentityCounterexample=identity,
                      unresolved=['Durable identities through actual engine load/commands', 'C0 selective collaborative Undo and review operation decisions',
                                  'Production migration/extension policy', 'Atomic multiflow editing and resource retention lifetimes'],
                      scope='Isolated package mapping and codec experiment; no production format or user document migration')
        validate_build(build)
        print(f"PASS S0 experiment: {len(report['checks'])} checks; versions 1/2/3 verified by actual codec; S0 OPEN")
    except BaseException as error:
        report.update(status='FAIL', error=str(error))
        print('FAIL ' + str(error), file=sys.stderr)
    finally:
        report['finishedAt'] = timestamp(); save()
    return 0 if report['status'] == 'EXPERIMENT_CHECKS_PASS_GATE_OPEN' else 1


if __name__ == '__main__':
    sys.exit(main())
